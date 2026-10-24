//
//  CameraControllerTest.kt
//  XmaxSDK
//  Repo: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.camera

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.RealtimeVideoEncoderPreference
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.permissions.PermissionManaging
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.foundation.rtc.RoomJoinConfiguration
import ai.xmax.sdk.foundation.rtc.RtcEventListener
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.foundation.rtc.RtcQualityListener
import ai.xmax.sdk.foundation.rtc.VideoEncodingConfiguration
import ai.xmax.sdk.media.MediaController
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.service.media.MediaService
import ai.xmax.sdk.RealtimeMediaSource
import android.view.View
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControllerTest {
    @Test
    fun `shared media components retain volume and rebind preview readiness after engine release`() = runTest {
        val rtc = CameraRtcStub()
        var permissionChecks = 0
        val permissions = object : PermissionManaging {
            override suspend fun ensureCameraPermission() { permissionChecks++ }
            override suspend fun ensureMicrophonePermission() = Unit
        }
        val camera = CameraController(rtc, permissions, IdentityMediaService)
        val media = MediaController(rtc, camera)
        var readyCount = 0
        val listener = RealtimeCameraPreviewReadyListener { readyCount++ }
        media.setCameraPreviewReadyListener(listener)
        media.setLocalAudioVolume(0.2f)

        assertEquals(0, rtc.initializeCount)
        assertEquals(0, permissionChecks)
        assertTrue(rtc.captureFormats.isEmpty())

        repeat(2) {
            media.createLocalCameraStream(RealtimeVideoFormat(704, 1280, 24), CameraPosition.FRONT)
            assertSame(listener, rtc.previewListener)
            rtc.previewListener!!.onCameraPreviewReady()
            assertEquals(0.2f, media.localAudioVolume)
            media.stopLocalStream()
            assertNull(rtc.previewListener)
            assertNull(media.currentTrack)
            assertEquals(0.2f, media.localAudioVolume)
        }

        assertEquals(2, rtc.initializeCount)
        assertEquals(2, rtc.destroyCount)
        assertEquals(2, permissionChecks)
        assertEquals(2, readyCount)
    }

    @Test
    fun `format update preserves track preview binding and camera position`() = runTest {
        val rtc = CameraRtcStub()
        val controller = CameraController(rtc, GrantedPermissionManager, MediaService(RealtimeModel.X2_1_1_PREVIEW_CN))
        val initial = RealtimeVideoFormat(1024, 1920, 24, 1500, 3000)
        val stream = controller.createLocalCameraStream(initial, CameraPosition.BACK)
        val track = stream.videoTrack!!
        val binding = VideoRenderRegistry.binding(track)
        val updated = initial.copy(width = 1920, height = 1024, fps = 30)

        controller.updateVideoFormat(updated)

        assertSame(track, controller.currentTrack)
        assertSame(binding, VideoRenderRegistry.binding(track))
        assertEquals(updated, track.videoFormat)
        assertEquals(CameraPosition.BACK, track.position)
        assertEquals(Triple(1920, 1024, 30), rtc.captureFormats.last())
        assertEquals(0, rtc.stopCount)
        assertEquals(0, rtc.unbindCount)
        controller.updateVideoFormat(updated)
        assertEquals(2, rtc.captureFormats.size)
        controller.stopLocalCameraStream()
    }

    @Test
    fun `invalid or failed format update leaves camera preview intact`() = runTest {
        val rtc = CameraRtcStub()
        val controller = CameraController(rtc, GrantedPermissionManager, MediaService(RealtimeModel.X2_1_1_PREVIEW_CN))
        val initial = RealtimeModel.X2_1_1_PREVIEW_CN.defaultCameraVideoFormat
        val track = controller.createLocalCameraStream(initial, CameraPosition.FRONT).videoTrack!!
        val binding = VideoRenderRegistry.binding(track)

        val invalid = runCatching { controller.updateVideoFormat(initial.copy(width = 720, height = 1280)) }.exceptionOrNull()
        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, (invalid as XmaxError).code)
        assertEquals(1, rtc.captureFormats.size)

        val failure = XmaxError(XmaxErrorCode.RTC_ERROR, "capture configuration failed")
        rtc.startFailure = failure
        assertSame(failure, runCatching { controller.updateVideoFormat(initial.copy(width = 1920, height = 1024)) }.exceptionOrNull())
        assertEquals(initial, track.videoFormat)
        assertSame(binding, VideoRenderRegistry.binding(track))
        assertEquals(0, rtc.stopCount)
        assertEquals(0, rtc.unbindCount)
        controller.stopLocalCameraStream()
    }

    @Test
    fun `bucket camera preserves resolution and encoding options`() = runTest {
        RealtimeModel.X2_1_1_PREVIEW_CN.supportedVideoFormats.map { IntSize(it.width, it.height) }.forEach { (width, height) ->
            val rtc = CameraRtcStub()
            val controller = CameraController(
                rtcManager = rtc,
                permissionManager = GrantedPermissionManager,
                mediaService = MediaService(RealtimeModel.X2_1_1_PREVIEW_CN),
            )
            val requested = RealtimeVideoFormat(
                width = width,
                height = height,
                fps = 30,
                minimumBitrate = 1_500,
                maximumBitrate = 3_000,
                encoderPreference = RealtimeVideoEncoderPreference.MAINTAIN_FRAMERATE,
            )

            val stream = controller.createLocalCameraStream(requested, CameraPosition.FRONT)

            assertEquals(requested, stream.videoTrack?.videoFormat)
            assertEquals(listOf(Triple(width, height, 30)), rtc.captureFormats)
            controller.stopLocalCameraStream()
        }
    }

    @Test
    fun `unsupported bucket camera fails before permission and capture`() = runTest {
        var cameraPermissionChecks = 0
        val permissions = object : PermissionManaging {
            override suspend fun ensureCameraPermission() {
                cameraPermissionChecks += 1
            }

            override suspend fun ensureMicrophonePermission() = Unit
        }
        val rtc = CameraRtcStub()
        val controller = CameraController(
            rtcManager = rtc,
            permissionManager = permissions,
            mediaService = MediaService(RealtimeModel.X2_1_1_PREVIEW_CN),
        )

        val error = runCatching {
            controller.createLocalCameraStream(
                RealtimeVideoFormat(width = 832, height = 1_472, fps = 24),
                CameraPosition.FRONT,
            )
        }.exceptionOrNull()

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, (error as? XmaxError)?.code)
        assertEquals(0, cameraPermissionChecks)
        assertTrue(rtc.cameraPositions.isEmpty())
        assertTrue(rtc.captureFormats.isEmpty())
        assertNull(controller.currentTrack)
    }

    @Test
    fun `microphone is optional and follows camera stream lifetime`() = runTest {
        var microphonePermissionChecks = 0
        val permissions = object : PermissionManaging {
            override suspend fun ensureCameraPermission() = Unit
            override suspend fun ensureMicrophonePermission() {
                microphonePermissionChecks += 1
            }
        }
        val rtc = CameraRtcStub()
        val controller = CameraController(rtc, permissions, IdentityMediaService)

        controller.createLocalCameraStream(
            RealtimeVideoFormat(704, 1280, 24),
            CameraPosition.FRONT,
            useMicrophone = true,
        )

        assertEquals(1, microphonePermissionChecks)
        assertTrue(controller.useMicrophone)
        assertEquals(0, rtc.audioStartCount)
        controller.startMicrophoneCapture()
        controller.startMicrophoneCapture()
        assertEquals(1, rtc.audioStartCount)

        controller.stopMicrophoneCapture()
        assertEquals(1, rtc.audioStopCount)
        controller.startMicrophoneCapture()
        controller.stopLocalCameraStream()
        assertEquals(2, rtc.audioStartCount)
        assertEquals(2, rtc.audioStopCount)
        assertFalse(controller.useMicrophone)
    }

    @Test
    fun `creates switches and stops one local camera stream`() = runTest {
        val rtc = CameraRtcStub()
        val controller = CameraController(
            rtcManager = rtc,
            permissionManager = GrantedPermissionManager,
            mediaService = IdentityMediaService,
        )

        val stream = controller.createLocalCameraStream(
            videoFormat = RealtimeVideoFormat(704, 1280, 24),
            position = CameraPosition.FRONT,
        )

        assertEquals("stream-local", stream.id)
        assertEquals("video0", stream.videoTrack?.id)
        assertEquals(listOf(CameraPosition.FRONT), rtc.cameraPositions)
        assertEquals(listOf(Triple(704, 1280, 24)), rtc.captureFormats)
        assertSame(stream.videoTrack, controller.currentTrack)

        val switched = controller.switchCamera()

        assertSame(stream.videoTrack, switched.videoTrack)
        assertEquals(CameraPosition.BACK, switched.videoTrack?.position)
        assertEquals(listOf(CameraPosition.FRONT, CameraPosition.BACK), rtc.cameraPositions)

        controller.stopLocalCameraStream()

        assertNull(controller.currentTrack)
        assertEquals(1, rtc.unbindCount)
        assertEquals(1, rtc.stopCount)
    }

    @Test
    fun `camera cleanup failures are preserved while remaining resources are released`() = runTest {
        val unbindError = IllegalStateException("unbind failed")
        val stopError = IllegalStateException("capture stop failed")
        val rtc = CameraRtcStub().apply {
            unbindFailure = unbindError
            stopFailure = stopError
        }
        val camera = CameraController(rtc, GrantedPermissionManager, IdentityMediaService)
        val media = MediaController(rtc, camera)
        val stream = media.createLocalCameraStream(
            RealtimeVideoFormat(704, 1280, 24),
            CameraPosition.FRONT,
        )
        val track = requireNotNull(stream.videoTrack)
        assertNotNull(VideoRenderRegistry.binding(track))

        val error = runCatching { media.stopLocalCameraStream() }.exceptionOrNull()

        assertSame(unbindError, error)
        assertSame(stopError, error?.suppressed?.single())
        assertEquals(1, rtc.unbindCount)
        assertEquals(1, rtc.stopCount)
        assertEquals(1, rtc.destroyCount)
        assertNull(camera.currentTrack)
        assertNull(media.currentTrack)
        assertNull(VideoRenderRegistry.binding(track))

        media.stopLocalCameraStream()
        assertEquals(1, rtc.destroyCount)
        assertEquals(1, rtc.stopCount)
    }

    @Test
    fun `capture start failure retains rollback failure as suppressed`() = runTest {
        val startError = XmaxError(XmaxErrorCode.RTC_ERROR, "capture start failed")
        val stopError = IllegalStateException("capture rollback failed")
        val rtc = CameraRtcStub().apply {
            startFailure = startError
            stopFailure = stopError
        }
        val controller = CameraController(rtc, GrantedPermissionManager, IdentityMediaService)

        val error = runCatching {
            controller.createLocalCameraStream(
                RealtimeVideoFormat(704, 1280, 24),
                CameraPosition.FRONT,
            )
        }.exceptionOrNull()

        assertSame(startError, error)
        assertSame(stopError, error?.suppressed?.single())
        assertEquals(1, rtc.stopCount)
        assertNull(controller.currentTrack)
    }

    @Test
    fun `cancelled creation retains cancellation and rollback failure`() = runTest {
        val stopError = IllegalStateException("capture rollback failed")
        val rtc = CameraRtcStub().apply { stopFailure = stopError }
        var cancellation: CancellationException? = null
        val permissionManager = object : PermissionManaging by GrantedPermissionManager {
            override suspend fun ensureCameraPermission() {
                try {
                    awaitCancellation()
                } catch (error: CancellationException) {
                    cancellation = error
                    throw error
                }
            }
        }
        val controller = CameraController(rtc, permissionManager, IdentityMediaService)
        var failure: Throwable? = null
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                controller.createLocalCameraStream(
                    RealtimeVideoFormat(704, 1280, 24),
                    CameraPosition.FRONT,
                )
            } catch (error: Throwable) {
                failure = error
                throw error
            }
        }

        worker.cancelAndJoin()

        assertTrue(worker.isCancelled)
        assertNotNull(cancellation)
        assertSame(cancellation, failure)
        assertSame(stopError, failure?.suppressed?.single())
        assertEquals(1, rtc.stopCount)
        assertTrue(rtc.captureFormats.isEmpty())
        assertNull(controller.currentTrack)
    }
}

private data object GrantedPermissionManager : PermissionManaging {
    override suspend fun ensureCameraPermission() = Unit
    override suspend fun ensureMicrophonePermission() = Unit
}

private data object IdentityMediaService : MediaServicing by MediaService() {
    override val model: RealtimeModel = RealtimeModel.X2_1_1_PREVIEW_CN

    override fun resolveMediaSize(size: IntSize, source: RealtimeMediaSource): IntSize = size
}

private class CameraRtcStub : RtcManaging {
    val cameraPositions = mutableListOf<CameraPosition>()
    val captureFormats = mutableListOf<Triple<Int, Int, Int>>()
    var stopCount = 0
    var audioStartCount = 0
    var audioStopCount = 0
    var unbindCount = 0
    var destroyCount = 0
    var initializeCount = 0
    var previewListener: RealtimeCameraPreviewReadyListener? = null
    var startFailure: Throwable? = null
    var stopFailure: Throwable? = null
    var unbindFailure: Throwable? = null

    override suspend fun initialize() { initializeCount++ }
    override suspend fun destroy() {
        destroyCount += 1
        previewListener = null
    }
    override fun configureVideoEncoding(configuration: VideoEncodingConfiguration) = Unit
    override fun pushExternalVideoFrame(frame: VideoFrame, seiData: ByteArray?) = Unit
    override fun pushExternalAudioFrame(frame: AudioFrame) = Unit
    override fun useExternalVideoSource() = Unit
    override fun startExternalAudioSource() = Unit
    override fun stopExternalAudioSource() = Unit
    override fun startAudioCapture() {
        audioStartCount += 1
    }
    override fun stopAudioCapture() {
        audioStopCount += 1
    }
    override fun startVideoCapture(width: Int, height: Int, frameRate: Int) {
        captureFormats += Triple(width, height, frameRate)
        startFailure?.let { throw it }
    }
    override fun updateVideoCaptureFormat(width: Int, height: Int, frameRate: Int) {
        startFailure?.let { throw it }
        captureFormats += Triple(width, height, frameRate)
    }
    override fun stopVideoCapture() {
        stopCount += 1
        stopFailure?.let { throw it }
    }
    override fun switchCamera(position: CameraPosition) {
        cameraPositions += position
    }
    override fun bindLocalVideo(view: View, contentMode: VideoContentMode) = Unit
    override fun unbindLocalVideo() {
        unbindCount += 1
        unbindFailure?.let { throw it }
    }
    override fun bindRemoteVideo(
        stream: RemoteStream,
        view: View,
        contentMode: VideoContentMode,
    ) = Unit
    override fun unbindRemoteVideo(stream: RemoteStream) = Unit
    override suspend fun joinRoom(configuration: RoomJoinConfiguration) = Unit
    override suspend fun leaveRoom() = Unit
    override fun publishLocalVideo() = Unit
    override fun unpublishLocalVideo() = Unit
    override fun publishLocalAudio() = Unit
    override fun unpublishLocalAudio() = Unit
    override fun subscribeRemoteVideo(userId: String, subscribe: Boolean) = Unit
    override fun subscribeRemoteAudio(userId: String, subscribe: Boolean) = Unit
    override fun setRemoteAudioVolume(volume: Int, userId: String) = Unit
    override fun sendRoomMessage(message: String, replacingPendingMessage: String?) = Unit

    override fun discardPendingMessages(key: String?) = Unit
    override fun setEventListener(listener: RtcEventListener?) = Unit
    override fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?) { previewListener = listener }
    override fun setRemoteAudioFrameListener(
        stream: ai.xmax.sdk.foundation.rtc.RemoteStream,
        listener: ai.xmax.sdk.foundation.rtc.RtcRemoteAudioFrameListener?,
    ): Unit = Unit

    override fun setRemoteVideoFrameListener(
        stream: RemoteStream,
        listener: ai.xmax.sdk.foundation.rtc.RtcRemoteVideoSink?,
    ) = Unit
    override fun setQualityListener(listener: RtcQualityListener?) = Unit
}
