//
//  CameraCaptureTest.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.camera

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCameraRotation
import ai.xmax.sdk.RealtimeConfiguration
import ai.xmax.sdk.RealtimeConnectionState
import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.XmaxClient
import ai.xmax.sdk.XmaxConfiguration
import ai.xmax.sdk.XmaxRealtimeVideoView
import ai.xmax.sdk.foundation.permissions.PermissionManager
import ai.xmax.sdk.media.audio.AudioRecordCapture
import ai.xmax.sdk.render.video.RealtimeVideoTestActivity
import ai.xmax.sdk.service.media.MediaService
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraCaptureTest {

    @Test
    fun localCameraManagerBecomesReadyAndCanReopenAfterClose() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val realtime = XmaxClient(context, XmaxConfiguration("local-preview-test"))
            .createRealtimeManager(RealtimeConfiguration(model = RealtimeModel.X2_1_FLASH))
        val scenario = ActivityScenario.launch<RealtimeVideoTestActivity>(
            Intent(context, RealtimeVideoTestActivity::class.java),
        )
        try {
            repeat(2) {
                val ready = CompletableDeferred<Unit>()
                realtime.setStateListener { state ->
                    if (state.connectionState == RealtimeConnectionState.READY) ready.complete(Unit)
                }
                val stream = realtime.createLocalCameraStream()
                assertEquals(RealtimeConnectionState.PREPARING, realtime.currentState.connectionState)
                scenario.onActivity { activity ->
                    activity.setContentView(XmaxRealtimeVideoView(activity).apply { track = stream.videoTrack })
                }
                withTimeout(5_000L) { ready.await() }

                RealtimeCameraRotation.entries.forEach { rotation ->
                    realtime.setCameraRotation(rotation)
                    assertEquals(RealtimeConnectionState.READY, realtime.currentState.connectionState)
                }

                realtime.disconnect()
                assertEquals(RealtimeConnectionState.READY, realtime.currentState.connectionState)
                realtime.close()
                assertEquals(RealtimeConnectionState.IDLE, realtime.currentState.connectionState)
            }
        } finally {
            realtime.close()
            scenario.close()
        }
    }

    @Test
    fun sdkCapturesPreviewAndMicrophoneWithoutAnRtcEngine() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (permission in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT))
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE))
        val scenario = ActivityScenario.launch<RealtimeVideoTestActivity>(
            Intent(context, RealtimeVideoTestActivity::class.java),
        )

        val previewReady = CompletableDeferred<Unit>()
        val video = Channel<VideoFrame>(Channel.CONFLATED)
        val audio = Channel<AudioFrame>(Channel.CONFLATED)
        val controller = CameraController(
            camera = Camera2Capture(context),
            microphone = AudioRecordCapture(),
            permissionManager = PermissionManager(context),
            mediaService = MediaService(RealtimeModel.X2_1_FLASH),
            videoListener = { video.trySend(it) },
            audioListener = { audio.trySend(it) },
            videoErrorListener = { video.close(it) },
            audioErrorListener = { audio.close(it) },
        )
        try {
            controller.setPreviewReadyListener { previewReady.complete(Unit) }
            val format = RealtimeModel.X2_1_FLASH.defaultCameraVideoFormat
            val stream = controller.createLocalCameraStream(format, CameraPosition.FRONT, useMicrophone = true)
            val firstFrame = withTimeout(5_000L) { video.receive() }
            assertEquals(VideoPixelFormat.I420, firstFrame.format.pixelFormat)
            assertEquals(format.width, firstFrame.format.width)
            assertEquals(format.height, firstFrame.format.height)
            scenario.onActivity { activity ->
                activity.setContentView(XmaxRealtimeVideoView(activity).apply { track = stream.videoTrack })
            }
            withTimeout(5_000L) { previewReady.await() }

            controller.setCameraRotation(RealtimeCameraRotation.ROTATION_180)
            video.tryReceive()
            val rotatedFrame = withTimeout(5_000L) { video.receive() }
            assertEquals(firstFrame.format, rotatedFrame.format)
            assertTrue(rotatedFrame.timestampUs > firstFrame.timestampUs)
            assertEquals(format, stream.videoTrack?.videoFormat)

            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
                val switched = controller.switchCamera()
                assertSame(stream.videoTrack, switched.videoTrack)
                assertEquals(CameraPosition.BACK, switched.videoTrack?.position)
            }

            repeat(2) {
                while (audio.tryReceive().isSuccess) Unit
                controller.startMicrophoneCapture()
                val first = withTimeout(5_000L) { audio.receive() }
                val next = withTimeout(5_000L) { audio.receive() }
                assertEquals(AudioFrame.samplesPerFrame * 2, first.data.size)
                assertTrue(next.timestampUs > first.timestampUs)
                assertEquals(0L, (next.timestampUs - first.timestampUs) % 10_000L)
                controller.stopMicrophoneCapture()
            }
        } finally {
            controller.stopLocalCameraStream()
            scenario.close()
        }
    }
}
