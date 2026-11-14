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

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeCameraRotation
import ai.xmax.sdk.RealtimeMediaSource
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.foundation.permissions.PermissionManaging
import ai.xmax.sdk.media.audio.MicrophoneCapture
import ai.xmax.sdk.media.video.Yuv420VideoFrameConverter
import ai.xmax.sdk.render.video.LocalVideoFramePreview
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates SDK-owned camera and microphone capture, local preview, and the active camera track.
 *
 * @property camera The Android camera capture.
 * @property microphone The Android microphone capture.
 * @property permissionManager The app's Android camera and microphone permission checker.
 * @property mediaService The service resolving media dimensions and model constraints.
 * @property videoListener The receiver of frames available for publication.
 * @property audioListener The receiver of microphone PCM packets.
 * @property videoErrorListener The receiver of camera and preview failures.
 * @property audioErrorListener The receiver of microphone failures.
 */
internal class CameraController(
    private val camera: CameraCapture,
    private val microphone: MicrophoneCapture,
    private val permissionManager: PermissionManaging,
    private val mediaService: MediaServicing,
    private val videoListener: (VideoFrame) -> Unit = {},
    private val audioListener: (AudioFrame) -> Unit = {},
    private val videoErrorListener: (XmaxError) -> Unit = {},
    private val audioErrorListener: (XmaxError) -> Unit = {},
) {

    /**
     * The track owned by the active camera source.
     */
    @Volatile var currentTrack: RealtimeVideoTrack? = null
        private set

    /**
     * Whether the current source should capture microphone audio while connected.
     */
    @Volatile var useMicrophone = false
        private set

    /**
     * The listener retained across local camera lifetimes.
     */
    @Volatile private var previewReadyListener: RealtimeCameraPreviewReadyListener? = null

    /**
     * The preview associated with the active track, retained during format and lens changes.
     */
    private var preview: LocalVideoFramePreview? = null

    /**
     * The correction retained across format changes and lens switches.
     */
    @Volatile private var cameraRotation = RealtimeCameraRotation.ROTATION_0

    /**
     * The revision used to ignore frame and failure callbacks from replaced captures.
     */
    private val captureRevision = AtomicLong()

    /**
     * Replaces the listener notified after a camera frame reaches the preview.
     *
     * @param listener The new listener, or null to clear it.
     */
    fun setPreviewReadyListener(
        listener: RealtimeCameraPreviewReadyListener?,
    ) {
        previewReadyListener = listener
    }

    /**
     * Starts the local camera. Microphone capture is deferred until connection.
     *
     * @param videoFormat The requested model-supported camera output.
     * @param position The initial front or rear camera.
     * @param useMicrophone Whether the connection will publish microphone audio.
     * @param rotation The clockwise correction relative to automatic orientation.
     */
    suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean = false,
        rotation: RealtimeCameraRotation = RealtimeCameraRotation.ROTATION_0,
    ): RealtimeMediaStream {
        check(currentTrack == null)
        val format = resolveVideoFormat(videoFormat)
        val track = RealtimeVideoTrack(LOCAL_VIDEO_TRACK_ID, format, position)
        permissionManager.ensureCameraPermission()
        if (useMicrophone) permissionManager.ensureMicrophonePermission()

        val previewReported = AtomicBoolean(false)
        var previewPixels = IntArray(0)
        val framePreview = LocalVideoFramePreview(
            makeBitmap = { frame ->
                val pixelCount = frame.format.width * frame.format.height
                if (previewPixels.size != pixelCount) {
                    previewPixels = IntArray(pixelCount)
                }
                Yuv420VideoFrameConverter.makePreviewBitmap(
                    frame = frame,
                    reusablePixels = previewPixels,
                )
            },
            onDisplayed = {
                val listener = previewReadyListener
                if (listener != null && currentTrack === track && previewReported.compareAndSet(false, true)) {
                    listener.onCameraPreviewReady()
                }
            },
            onFailure = { if (currentTrack === track) videoErrorListener(XmaxError.from(it)) },
        )
        preview = framePreview

        try {
            startCapture(format, position, framePreview, rotation)
            VideoRenderRegistry.register(
                track,
                VideoRenderBinding(
                    attachHandler = framePreview::attach,
                    detachHandler = framePreview::detach,
                ),
            )

            this.useMicrophone = useMicrophone
            cameraRotation = rotation
            currentTrack = track
            return RealtimeMediaStream(StreamID.LOCAL.value, track)
        } catch (error: Throwable) {
            cleanupAfterFailure(error, { stopLocalCameraStream() })
            throw XmaxError.from(error)
        }
    }

    /**
     * Applies a new format to the existing camera track and preview.
     *
     * @param videoFormat The requested model-supported camera output.
     */
    suspend fun updateVideoFormat(
        videoFormat: RealtimeVideoFormat,
    ) {
        val track = requireTrack()
        val format = resolveVideoFormat(videoFormat)
        if (format == track.videoFormat) return

        replaceCapture(track, format, checkNotNull(track.position))
        track.updateVideoFormat(format)
    }

    /**
     * Changes rotation on the running camera and discards queued preview work from the old angle.
     *
     * @param rotation The clockwise correction relative to automatic orientation.
     */
    suspend fun setCameraRotation(
        rotation: RealtimeCameraRotation,
    ) {
        requireTrack()
        if (rotation == cameraRotation) return

        val framePreview = checkNotNull(preview)
        camera.setRotation(rotation) {
            framePreview.discardPendingFrame()
            cameraRotation = rotation
        }
    }

    /**
     * Starts SDK microphone capture for the current connection if enabled.
     */
    suspend fun startMicrophoneCapture() {
        if (useMicrophone) microphone.start(audioListener, audioErrorListener)
    }

    /**
     * Releases the microphone while preserving camera preview and settings.
     */
    suspend fun stopMicrophoneCapture() {
        microphone.stop()
    }

    /**
     * Stops local capture and releases the track and its rendering binding.
     */
    suspend fun stopLocalCameraStream() {
        captureRevision.incrementAndGet()
        val track = currentTrack
        val framePreview = preview
        currentTrack = null
        preview = null
        useMicrophone = false
        cameraRotation = RealtimeCameraRotation.ROTATION_0

        cleanupResources(
            { microphone.stop() },
            { track?.let { VideoRenderRegistry.binding(it)?.detach() } },
            { track?.let(VideoRenderRegistry::unregister) },
            { framePreview?.close() },
            { camera.stop() },
        )
    }

    /**
     * Switches lenses while retaining the camera track and preview binding.
     */
    suspend fun switchCamera(): RealtimeMediaStream {
        val track = requireTrack()
        val position = if (track.position == CameraPosition.FRONT) CameraPosition.BACK else CameraPosition.FRONT
        replaceCapture(track, checkNotNull(track.videoFormat), position)
        track.updatePosition(position)

        return RealtimeMediaStream(StreamID.LOCAL.value, track)
    }

    /**
     * Starts a replacement and restores the previous lens and format if startup fails.
     *
     * @param track The existing track retaining its current format until replacement succeeds.
     * @param format The new output format.
     * @param position The new camera position.
     */
    private suspend fun replaceCapture(
        track: RealtimeVideoTrack,
        format: RealtimeVideoFormat,
        position: CameraPosition,
    ) {
        val framePreview = checkNotNull(preview)
        try {
            startCapture(format, position, framePreview)
        } catch (error: Throwable) {
            cleanupAfterFailure(error, {
                try {
                    startCapture(checkNotNull(track.videoFormat), checkNotNull(track.position), framePreview)
                } catch (restoreError: Exception) {
                    videoErrorListener(XmaxError.from(restoreError))
                    throw restoreError
                }
            })
            throw XmaxError.from(error)
        }
    }

    /**
     * Starts capture with callbacks scoped to its revision.
     *
     * @param format The output format.
     * @param position The camera position.
     * @param framePreview The bound preview receiving the same oriented frames as RTC.
     * @param rotation The correction to apply when capture starts.
     */
    private suspend fun startCapture(
        format: RealtimeVideoFormat,
        position: CameraPosition,
        framePreview: LocalVideoFramePreview,
        rotation: RealtimeCameraRotation = cameraRotation,
    ) {
        val revision = captureRevision.incrementAndGet()
        framePreview.discardPendingFrame()
        camera.start(
            format = format,
            position = position,
            rotation = rotation,
            onFrame = { frame ->
                if (captureRevision.get() == revision) {
                    framePreview.enqueue(frame)
                    videoListener(frame)
                }
            },
            onError = { error -> if (captureRevision.get() == revision) videoErrorListener(error) },
        )
    }

    /**
     * Returns the active track for a camera control operation.
     */
    private fun requireTrack(): RealtimeVideoTrack = currentTrack ?: throw XmaxError(
        XmaxErrorCode.INVALID_CONFIGURATION,
        "Camera preview is not started",
    )

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
