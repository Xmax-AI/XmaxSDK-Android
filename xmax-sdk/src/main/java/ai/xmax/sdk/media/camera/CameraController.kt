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
import ai.xmax.sdk.CameraDevice
import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.RealtimeCamera
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeCameraRotation
import ai.xmax.sdk.RealtimeMediaSource
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeMicrophone
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
    val useMicrophone: Boolean
        get() = microphoneSelection != RealtimeMicrophone.Disabled

    /**
     * The microphone input requested for the current local stream.
     */
    @Volatile private var microphoneSelection: RealtimeMicrophone = RealtimeMicrophone.Disabled

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
     * Starts the selected camera and retains the microphone selection for connection.
     *
     * @param videoFormat The requested model-supported camera output.
     * @param selection The initial camera selection.
     * @param microphone The microphone selection for the connection.
     * @param rotation The clockwise correction relative to automatic orientation.
     */
    suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        selection: RealtimeCamera,
        microphone: RealtimeMicrophone = RealtimeMicrophone.Disabled,
        rotation: RealtimeCameraRotation = RealtimeCameraRotation.ROTATION_0,
    ): RealtimeMediaStream {
        check(currentTrack == null)
        val format = resolveVideoFormat(videoFormat)
        val track = RealtimeVideoTrack(LOCAL_VIDEO_TRACK_ID, format)
        permissionManager.ensureCameraPermission()
        if (microphone != RealtimeMicrophone.Disabled) permissionManager.ensureMicrophonePermission()
        if (microphone is RealtimeMicrophone.Device) this.microphone.validateDevice(microphone.id)

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
            track.updateCameraDevice(startCapture(format, selection, framePreview, rotation))
            VideoRenderRegistry.register(
                track,
                VideoRenderBinding(
                    attachHandler = framePreview::attach,
                    detachHandler = framePreview::detach,
                ),
            )

            microphoneSelection = microphone
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

        replaceCapture(track, format, RealtimeCamera.Device(checkNotNull(track.cameraDevice).id))
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
        val selection = microphoneSelection
        if (selection != RealtimeMicrophone.Disabled) startMicrophone(selection)
    }

    /**
     * Retains the requested input for connection or replaces a running recorder.
     *
     * @param deviceId The audio input ID, or null for the Android default input.
     */
    suspend fun switchMicrophone(
        deviceId: String?,
    ) {
        requireTrack()
        val previous = microphoneSelection
        if (previous == RealtimeMicrophone.Disabled) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Microphone capture is disabled for this camera stream")
        }

        val selection = deviceId?.let { RealtimeMicrophone.Device(it) } ?: RealtimeMicrophone.SystemDefault
        if (selection == previous) return
        if (deviceId != null) microphone.validateDevice(deviceId)
        if (microphone.isCapturing) {
            try {
                microphone.stop()
                startMicrophone(selection)
            } catch (error: Throwable) {
                cleanupAfterFailure(error, {
                    try {
                        microphone.stop()
                        startMicrophone(previous)
                    } catch (restoreError: Exception) {
                        audioErrorListener(XmaxError.from(restoreError))
                        throw restoreError
                    }
                })
                throw XmaxError.from(error)
            }
        }
        microphoneSelection = selection
    }

    /**
     * Opens the recorder using the selected input.
     *
     * @param selection The enabled microphone selection.
     */
    private suspend fun startMicrophone(
        selection: RealtimeMicrophone,
    ) {
        microphone.start((selection as? RealtimeMicrophone.Device)?.id, audioListener, audioErrorListener)
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
        microphoneSelection = RealtimeMicrophone.Disabled
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
     * Switches cameras while retaining the camera track and preview binding.
     *
     * @param selection The camera position or device ID to switch to.
     */
    suspend fun switchCamera(
        selection: RealtimeCamera,
    ): RealtimeMediaStream {
        val track = requireTrack()
        if (selection !is RealtimeCamera.Device || selection.id != track.cameraDevice?.id) {
            replaceCapture(
                track,
                checkNotNull(track.videoFormat),
                selection,
            )
        }
        return RealtimeMediaStream(StreamID.LOCAL.value, track)
    }

    /**
     * Starts a replacement and restores the previous device and format if startup fails.
     *
     * @param track The existing track retaining its current format until replacement succeeds.
     * @param format The new output format.
     * @param selection The new camera selection.
     */
    private suspend fun replaceCapture(
        track: RealtimeVideoTrack,
        format: RealtimeVideoFormat,
        selection: RealtimeCamera,
    ) {
        val previousDevice = checkNotNull(track.cameraDevice)
        val framePreview = checkNotNull(preview)
        try {
            track.updateCameraDevice(startCapture(format, selection, framePreview))
        } catch (error: Throwable) {
            cleanupAfterFailure(error, {
                try {
                    startCapture(
                        checkNotNull(track.videoFormat),
                        RealtimeCamera.Device(previousDevice.id),
                        framePreview,
                    )
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
     * @param selection The camera selection.
     * @param framePreview The bound preview receiving the same oriented frames as RTC.
     * @param rotation The correction to apply when capture starts.
     * @return The camera opened for the selection.
     */
    private suspend fun startCapture(
        format: RealtimeVideoFormat,
        selection: RealtimeCamera,
        framePreview: LocalVideoFramePreview,
        rotation: RealtimeCameraRotation = cameraRotation,
    ): CameraDevice {
        val revision = captureRevision.incrementAndGet()
        framePreview.discardPendingFrame()
        return camera.start(
            format = format,
            selection = selection,
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
