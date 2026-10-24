//
//  CameraController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.camera

import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeMediaSource
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.foundation.permissions.PermissionManaging
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import android.content.Context
import androidx.compose.ui.unit.IntSize

/**
 * Coordinates camera permissions, internal RTC capture, and local preview resources.
 */
internal class CameraController(
    /**
     * The manager for RTC capture, publication, subscription, and rendering.
     */
    private val rtcManager: RtcManaging,

    /**
     * The Android camera and recording permission checker.
     */
    private val permissionManager: PermissionManaging,

    /**
     * The service resolving media dimensions and model constraints.
     */
    private val mediaService: MediaServicing,

    /**
     * The media failure callback.
     */
    private val errorListener: (XmaxError) -> Unit = {},
) {

    /**
     * Creates a camera controller.
     *
     * @param context The Android context used for system services and media resources.
     * @param rtcManager The manager for RTC capture, publication, subscription, and rendering.
     * @param mediaService The service resolving dimensions and model input constraints.
     * @param errorListener The media failure callback.
     */
    constructor(
        context: Context,
        rtcManager: RtcManaging,
        mediaService: MediaServicing = ai.xmax.sdk.service.media.MediaService(),
        errorListener: (XmaxError) -> Unit = {},
    ) : this(
        rtcManager = rtcManager,
        permissionManager = ai.xmax.sdk.foundation.permissions.PermissionManager(context),
        mediaService = mediaService,
        errorListener = errorListener,
    )

    /**
     * The lock protecting camera input state.
     */
    private val stateLock = Any()

    /**
     * The active camera track.
     */
    private var activeTrack: RealtimeVideoTrack? = null

    /**
     * The microphone option selected when creating this camera source.
     */
    private var storedUseMicrophone = false

    /**
     * Whether microphone capture has started.
     */
    private var isMicrophoneCapturing = false

    /**
     * The current camera track snapshot.
     */
    val currentTrack: RealtimeVideoTrack?
        get() = synchronized(stateLock) { activeTrack }

    /**
     * Whether the current camera source enables microphone input.
     */
    val useMicrophone: Boolean
        get() = synchronized(stateLock) { activeTrack != null && storedUseMicrophone }

    /**
     * Replaces the camera preview readiness listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setPreviewReadyListener(
        listener: RealtimeCameraPreviewReadyListener?,
    ) {
        rtcManager.setCameraPreviewReadyListener(listener)
    }

    /**
     * Creates and starts camera input after your app obtains the required permissions. Input dimensions
     * follow the model's constraints.
     * The microphone is disabled by default; when enabled, capture starts on connection and stops on
     * disconnection without local playback.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param position The front or rear camera to use.
     * @param useMicrophone Whether to use the microphone; requires recording permission and defaults to
     * false.
     */
    suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean = false,
    ): RealtimeMediaStream {
        if (currentTrack != null) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Stop the current local camera stream before creating another one",
            )
        }

        val resolvedFormat = resolveVideoFormat(videoFormat)
        val track = RealtimeVideoTrack(
            id = LOCAL_VIDEO_TRACK_ID,
            videoFormat = resolvedFormat,
            position = position,
        )
        try {
            permissionManager.ensureCameraPermission()
            if (useMicrophone) permissionManager.ensureMicrophonePermission()
            rtcManager.switchCamera(position)
            rtcManager.startVideoCapture(
                width = resolvedFormat.width,
                height = resolvedFormat.height,
                frameRate = resolvedFormat.fps,
            )
            VideoRenderRegistry.register(
                track,
                VideoRenderBinding(
                    attachHandler = { view, contentMode ->
                        try {
                            view.prepareRtcVideoRendering()
                            rtcManager.bindLocalVideo(
                                view.rtcRenderView,
                                contentMode,
                            )
                        } catch (
                            error: Throwable,
                        ) {
                            errorListener(XmaxError.from(error))
                            throw error
                        }
                    },
                    detachHandler = { rtcManager.unbindLocalVideo() },
                ),
            )
            synchronized(stateLock) {
                activeTrack = track
                storedUseMicrophone = useMicrophone
            }
            return RealtimeMediaStream(
                StreamID.LOCAL.value,
                track,
            )
        } catch (
            error: Throwable,
        ) {
            cleanupAfterFailure(
                error,
                { VideoRenderRegistry.unregister(track) },
                { rtcManager.stopVideoCapture() },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * Updates camera output while retaining the lens, track, and preview; failure retains the previous
     * format.
     *
     * @param videoFormat Model-supported dimensions, capture frame rate, and encoding settings.
     * @throws XmaxError If the camera is not started, the format is unsupported, or capture configuration
     * fails.
     */
    fun updateVideoFormat(
        videoFormat: RealtimeVideoFormat,
    ) {
        synchronized(stateLock) {
            val track = activeTrack
                ?: throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Camera preview is not started",
                )
            val resolved = resolveVideoFormat(videoFormat)
            if (resolved == track.videoFormat) return
            rtcManager.updateVideoCaptureFormat(
                resolved.width,
                resolved.height,
                resolved.fps,
            )
            track.updateVideoFormat(resolved)
        }
    }

    /**
     * Starts microphone capture for the current camera source.
     */
    fun startMicrophoneCapture() {
        val shouldStart = synchronized(stateLock) {
            if (activeTrack == null || !storedUseMicrophone || isMicrophoneCapturing) {
                false
            } else {
                isMicrophoneCapturing = true
                true
            }
        }
        if (shouldStart) rtcManager.startAudioCapture()
    }

    /**
     * Stops microphone capture for the current camera source.
     */
    fun stopMicrophoneCapture() {
        if (!synchronized(stateLock) { isMicrophoneCapturing }) return
        rtcManager.stopAudioCapture()
        synchronized(stateLock) { isMicrophoneCapturing = false }
    }

    /**
     * Stops and releases the current camera source; does nothing for other source types.
     */
    suspend fun stopLocalCameraStream() {
        val track = synchronized(stateLock) {
            activeTrack.also {
                activeTrack = null
                storedUseMicrophone = false
            }
        }
        try {
            cleanupResources(
                { stopMicrophoneCapture() },
                { track?.let(VideoRenderRegistry::unregister) },
                { if (track != null) rtcManager.unbindLocalVideo() },
                { rtcManager.stopVideoCapture() },
            )
        } finally {
            synchronized(stateLock) { isMicrophoneCapturing = false }
        }
    }

    /**
     * Switches front and rear cameras and updates the track's position.
     */
    suspend fun switchCamera(): RealtimeMediaStream {
        val track = currentTrack
        val position = track?.position
        if (track == null || track.videoFormat == null || position == null) {
            throw XmaxError(
                code = XmaxErrorCode.RTC_ERROR,
                message = "Local camera preview is not started",
            )
        }

        val nextPosition = when (position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
        }
        return try {
            rtcManager.switchCamera(nextPosition)
            track.updatePosition(nextPosition)
            RealtimeMediaStream(
                StreamID.LOCAL.value,
                track,
            )
        } catch (
            error: Throwable,
        ) {
            throw XmaxError.from(error)
        }
    }

    /**
     * Validates or resolves camera input using the model's constraints.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @return The resolved video dimensions, frame rate, and encoding settings.
     */
    private fun resolveVideoFormat(
        videoFormat: RealtimeVideoFormat,
    ): RealtimeVideoFormat {
        videoFormat.validate()
        val size = mediaService.resolveMediaSize(
            IntSize(
                videoFormat.width,
                videoFormat.height,
            ),
            source = RealtimeMediaSource.CAMERA,
        )
        return videoFormat.copy(
            width = size.width,
            height = size.height,
        )
            .also(RealtimeVideoFormat::validate)
    }

    /**
     * The local camera track identifier.
     */
    private companion object {

        /**
         * The local camera video track identifier.
         */
        const val LOCAL_VIDEO_TRACK_ID = "video0"
    }
}
