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
import ai.xmax.sdk.CameraDevice
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCamera
import ai.xmax.sdk.RealtimeCameraRotation
import ai.xmax.sdk.RealtimeMicrophone
import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoFramePlane
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.resolveFrameRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.permissions.PermissionManaging
import ai.xmax.sdk.media.MediaController
import ai.xmax.sdk.media.audio.MicrophoneCapture
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.service.media.MediaService
import ai.xmax.sdk.stream.room.RtcManagingCall
import ai.xmax.sdk.stream.room.RtcManagingStub
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CameraControllerTest {

    @Test
    fun `camera preview opens without an RTC engine and prepares custom tracks on connection`() = runTest {
        val rtc = RtcManagingStub()
        val microphone = FakeMicrophoneCapture()
        val submittedAudio = mutableListOf<AudioFrame>()
        val controller = cameraController(microphone = microphone, audioListener = submittedAudio::add)
        val media = MediaController(rtc, controller)

        media.createLocalCameraStream(
            RealtimeModel.X2_1.defaultCameraVideoFormat,
            RealtimeCamera.Position(CameraPosition.FRONT),
            RealtimeMicrophone.SystemDefault,
            RealtimeCameraRotation.ROTATION_0,
        )

        assertTrue(rtc.calls.isEmpty())
        assertEquals(0, microphone.starts)

        repeat(2) {
            media.prepareCameraConnection()
            media.startMicrophoneCapture()
            val packet = AudioFrame(ByteArray(960), 10_000L)
            microphone.receiver?.invoke(packet)
            media.stopMicrophoneCapture()
            assertSame(packet, submittedAudio.last())
        }

        assertEquals(2, microphone.starts)
        assertEquals(2, microphone.stops)
        assertEquals(2, rtc.calls.count { it == RtcManagingCall.UseExternalVideoSource })
        assertEquals(2, rtc.calls.count { it == RtcManagingCall.StartExternalAudioSource })
        assertNotNull(media.currentTrack)
        media.stopLocalStream()
        assertNull(media.currentTrack)
    }

    @Test
    fun `updates format and switches lens while keeping the track and preview binding`() = runTest {
        val camera = FakeCameraCapture()
        val submittedFrames = mutableListOf<VideoFrame>()
        val controller = cameraController(camera = camera, videoListener = submittedFrames::add)
        val initial = RealtimeModel.X2_1.defaultCameraVideoFormat
        val stream = controller.createLocalCameraStream(initial, RealtimeCamera.Position(CameraPosition.FRONT))
        val track = requireNotNull(stream.videoTrack)
        val binding = VideoRenderRegistry.binding(track)
        val oldReceiver = checkNotNull(camera.receiver)
        val updated = initial.copy(width = 1920, height = 1080, fps = 24)

        controller.updateVideoFormat(updated)
        controller.updateVideoFormat(updated)
        val switched = controller.switchCamera(RealtimeCamera.Device("back"))
        oldReceiver(frame)
        camera.receiver?.invoke(frame)

        assertSame(track, switched.videoTrack)
        assertSame(binding, VideoRenderRegistry.binding(track))
        assertEquals(updated, track.videoFormat)
        assertEquals(CameraPosition.BACK, track.position)
        assertEquals(listOf(initial to CameraPosition.FRONT, updated to CameraPosition.FRONT, updated to CameraPosition.BACK), camera.starts)
        assertEquals(listOf(frame), submittedFrames)
        controller.stopLocalCameraStream()
        assertNull(VideoRenderRegistry.binding(track))
        assertNull(controller.currentTrack)
    }

    @Test
    fun `orientation correction is set in place and retained across capture restarts`() = runTest {
        val camera = FakeCameraCapture()
        val controller = cameraController(camera = camera)
        val format = RealtimeModel.X2_1.defaultCameraVideoFormat
        val stream = controller.createLocalCameraStream(
            format,
            RealtimeCamera.Position(CameraPosition.FRONT),
            rotation = RealtimeCameraRotation.ROTATION_90,
        )
        val track = requireNotNull(stream.videoTrack)
        val binding = VideoRenderRegistry.binding(track)
        assertEquals(RealtimeCameraRotation.ROTATION_90, camera.rotationAtStart.last())

        controller.setCameraRotation(RealtimeCameraRotation.ROTATION_270)
        controller.setCameraRotation(RealtimeCameraRotation.ROTATION_270)
        assertEquals(listOf(RealtimeCameraRotation.ROTATION_270), camera.rotationUpdates)
        assertEquals(1, camera.starts.size)
        assertSame(track, controller.currentTrack)
        assertSame(binding, VideoRenderRegistry.binding(track))

        controller.updateVideoFormat(format.copy(fps = 24))
        controller.switchCamera(RealtimeCamera.Device("back"))
        assertEquals(
            listOf(RealtimeCameraRotation.ROTATION_90, RealtimeCameraRotation.ROTATION_270, RealtimeCameraRotation.ROTATION_270),
            camera.rotationAtStart,
        )
        controller.stopLocalCameraStream()
        controller.createLocalCameraStream(format, RealtimeCamera.Position(CameraPosition.FRONT))
        assertEquals(RealtimeCameraRotation.ROTATION_0, camera.rotationAtStart.last())
        controller.stopLocalCameraStream()
    }

    @Test
    fun `clockwise corrections account for front camera mirroring and wrap around`() {
        val orientation = VideoRotation.ROTATION_270
        assertEquals(orientation, RealtimeCameraRotation.ROTATION_0.resolveFrameRotation(orientation, true))
        assertEquals(VideoRotation.ROTATION_0, RealtimeCameraRotation.ROTATION_90.resolveFrameRotation(orientation, false))
        assertEquals(VideoRotation.ROTATION_180, RealtimeCameraRotation.ROTATION_90.resolveFrameRotation(orientation, true))
        assertEquals(VideoRotation.ROTATION_0, RealtimeCameraRotation.ROTATION_270.resolveFrameRotation(orientation, true))
    }

    @Test
    fun `failed format replacement restores the previous camera and track`() = runTest {
        val camera = FakeCameraCapture()
        val controller = cameraController(camera = camera)
        val initial = RealtimeModel.X2_1.defaultCameraVideoFormat
        val track = requireNotNull(controller.createLocalCameraStream(initial, RealtimeCamera.Position(CameraPosition.FRONT)).videoTrack)
        val updated = initial.copy(width = 1920, height = 1080)
        val failure = XmaxError(XmaxErrorCode.MEDIA_ERROR, "Camera format failed")

        camera.startFailure = failure
        assertSame(failure, runCatching { controller.updateVideoFormat(updated) }.exceptionOrNull())
        assertEquals(initial, track.videoFormat)
        assertEquals(initial to CameraPosition.FRONT, camera.starts.last())
        assertSame(track, controller.currentTrack)
        controller.stopLocalCameraStream()
    }

    @Test
    fun `explicit camera retains its track output and rotation across failed and successful switches`() = runTest {
        val camera = FakeCameraCapture()
        val controller = cameraController(camera = camera)
        val format = RealtimeModel.X2_1.defaultCameraVideoFormat
        val track = requireNotNull(controller.createLocalCameraStream(
            videoFormat = format,
            selection = RealtimeCamera.Device("usb"),
            microphone = RealtimeMicrophone.Disabled,
            rotation = RealtimeCameraRotation.ROTATION_90,
        ).videoTrack)
        assertEquals("usb", track.cameraDevice?.id)
        assertNull(track.position)

        val updated = format.copy(fps = 24)
        controller.updateVideoFormat(updated)
        val failure = XmaxError(XmaxErrorCode.MEDIA_ERROR, "Replacement could not start")
        camera.startFailure = failure
        assertSame(failure, runCatching { controller.switchCamera(RealtimeCamera.Device("back")) }.exceptionOrNull())
        assertEquals("usb", track.cameraDevice?.id)
        assertSame(track, controller.switchCamera(RealtimeCamera.Device("back")).videoTrack)
        assertEquals("back", track.cameraDevice?.id)
        assertEquals(updated, track.videoFormat)
        assertEquals(listOf("usb", "usb", "back", "usb", "back"), camera.deviceIds)
        assertTrue(camera.rotationAtStart.all { it == RealtimeCameraRotation.ROTATION_90 })
        controller.stopLocalCameraStream()
    }

    @Test
    fun `microphone selection starts on connect and restores the prior input after a failed switch`() = runTest {
        val microphone = FakeMicrophoneCapture()
        val controller = cameraController(microphone = microphone)
        controller.createLocalCameraStream(
            videoFormat = RealtimeModel.X2_1.defaultCameraVideoFormat,
            selection = RealtimeCamera.Position(CameraPosition.FRONT),
            microphone = RealtimeMicrophone.Device("headset"),
            rotation = RealtimeCameraRotation.ROTATION_0,
        )
        controller.switchMicrophone("usb")
        assertFalse(microphone.isCapturing)
        controller.startMicrophoneCapture()
        assertEquals(listOf("usb"), microphone.deviceIds)

        val failure = XmaxError(XmaxErrorCode.MEDIA_ERROR, "Routing failed")
        microphone.startFailure = failure
        assertSame(failure, runCatching { controller.switchMicrophone("headset") }.exceptionOrNull())
        assertEquals(listOf("usb", "headset", "usb"), microphone.deviceIds)
        assertTrue(microphone.isCapturing)

        controller.switchMicrophone(null)
        controller.switchMicrophone(null)
        assertEquals(listOf("usb", "headset", "usb", null), microphone.deviceIds)
        controller.stopLocalCameraStream()
    }

    @Test
    fun `unsupported preset is rejected before requesting camera access`() = runTest {
        val camera = FakeCameraCapture()
        val permissions = object : PermissionManaging by GrantedPermissions {
            override suspend fun ensureCameraPermission() = error("Should not request camera permission")
        }
        val controller = cameraController(camera = camera, permissions = permissions)

        val error = runCatching {
            controller.createLocalCameraStream(RealtimeVideoFormat(832, 1472, 24), RealtimeCamera.Position(CameraPosition.FRONT))
        }.exceptionOrNull()

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, (error as? XmaxError)?.code)
        assertTrue(camera.starts.isEmpty())
        assertNull(controller.currentTrack)
    }

    @Test
    fun `cancelled startup stops capture and rejects callbacks from that session`() = runTest {
        val camera = FakeCameraCapture().apply { awaitStartup = true }
        val delivered = mutableListOf<VideoFrame>()
        val controller = cameraController(camera = camera, videoListener = delivered::add)
        val operation = launch {
            controller.createLocalCameraStream(
                RealtimeModel.X2_1.defaultCameraVideoFormat,
                RealtimeCamera.Position(CameraPosition.FRONT),
            )
        }
        runCurrent()
        val receiver = checkNotNull(camera.receiver)

        operation.cancelAndJoin()
        receiver(frame)

        assertTrue(operation.isCancelled)
        assertNull(camera.receiver)
        assertTrue(delivered.isEmpty())
        assertNull(controller.currentTrack)
    }

    @Test
    fun `camera cleanup still runs after a microphone stop failure`() = runTest {
        val microphone = FakeMicrophoneCapture()
        val camera = FakeCameraCapture()
        val controller = cameraController(camera = camera, microphone = microphone)
        val track = requireNotNull(controller.createLocalCameraStream(
            RealtimeModel.X2_1.defaultCameraVideoFormat,
            RealtimeCamera.Position(CameraPosition.FRONT),
            microphone = RealtimeMicrophone.SystemDefault,
        ).videoTrack)
        controller.startMicrophoneCapture()
        val failure = IllegalStateException("Microphone cleanup failed")
        microphone.stopFailure = failure

        assertSame(failure, runCatching { controller.stopLocalCameraStream() }.exceptionOrNull())
        assertNull(camera.receiver)
        assertNull(controller.currentTrack)
        assertNull(VideoRenderRegistry.binding(track))
    }

    private val frame = VideoFrame(
        VideoFormat(2, 2, VideoPixelFormat.RGBA),
        0L,
        listOf(VideoFramePlane(ByteArray(16), 8)),
    )

    private fun cameraController(
        camera: FakeCameraCapture = FakeCameraCapture(),
        microphone: FakeMicrophoneCapture = FakeMicrophoneCapture(),
        permissions: PermissionManaging = GrantedPermissions,
        videoListener: (VideoFrame) -> Unit = {},
        audioListener: (AudioFrame) -> Unit = {},
    ) = CameraController(
        camera = camera,
        microphone = microphone,
        permissionManager = permissions,
        mediaService = MediaService(RealtimeModel.X2_1),
        videoListener = videoListener,
        audioListener = audioListener,
    )

    private object GrantedPermissions : PermissionManaging {
        override suspend fun ensureCameraPermission() = Unit
        override suspend fun ensureMicrophonePermission() = Unit
    }
}

internal class FakeCameraCapture : CameraCapture {
    val starts = mutableListOf<Pair<RealtimeVideoFormat, CameraPosition?>>()
    val deviceIds = mutableListOf<String>()
    val rotationAtStart = mutableListOf<RealtimeCameraRotation>()
    val rotationUpdates = mutableListOf<RealtimeCameraRotation>()
    var receiver: ((VideoFrame) -> Unit)? = null
    var startFailure: XmaxError? = null
    var awaitStartup = false

    override suspend fun start(
        format: RealtimeVideoFormat,
        selection: RealtimeCamera,
        rotation: RealtimeCameraRotation,
        onFrame: (VideoFrame) -> Unit,
        onError: (XmaxError) -> Unit,
    ): CameraDevice {
        val id = when (selection) {
            is RealtimeCamera.Position -> selection.position.name.lowercase()
            is RealtimeCamera.Device -> selection.id
        }
        val position = CameraPosition.entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
        starts += format to position
        deviceIds += id
        rotationAtStart += rotation
        startFailure?.let {
            startFailure = null
            throw it
        }
        receiver = onFrame
        if (awaitStartup) awaitCancellation()
        return CameraDevice(id, id, position, isExternal = position == null)
    }

    override suspend fun setRotation(
        rotation: RealtimeCameraRotation,
        onApplied: () -> Unit,
    ) {
        rotationUpdates += rotation
        onApplied()
    }

    override suspend fun stop() {
        receiver = null
    }
}

internal class FakeMicrophoneCapture : MicrophoneCapture {
    var receiver: ((AudioFrame) -> Unit)? = null
    var starts = 0
    var stops = 0
    var stopFailure: Throwable? = null
    var startFailure: XmaxError? = null
    val deviceIds = mutableListOf<String?>()

    override val isCapturing: Boolean
        get() = receiver != null

    override suspend fun validateDevice(deviceId: String) = Unit

    override suspend fun start(
        deviceId: String?,
        onFrame: (AudioFrame) -> Unit,
        onError: (XmaxError) -> Unit,
    ) {
        if (receiver != null) return
        deviceIds += deviceId
        startFailure?.let {
            startFailure = null
            throw it
        }
        receiver = onFrame
        starts++
    }

    override suspend fun stop() {
        if (receiver == null) return
        receiver = null
        stops++
        stopFailure?.let { throw it }
    }
}
