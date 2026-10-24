//
//  MediaController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeReferenceVideo
import ai.xmax.sdk.RealtimeVideoSampleMethod
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.media.camera.CameraController
import ai.xmax.sdk.media.image.ImageController
import ai.xmax.sdk.media.interaction.InteractionController
import ai.xmax.sdk.media.interaction.InteractionFrame
import ai.xmax.sdk.media.video.NetworkVideoController
import ai.xmax.sdk.media.video.VideoController
import ai.xmax.sdk.RealtimeExternalVideoSource
import ai.xmax.sdk.media.external.ExternalVideoController
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates local media ownership and the RTC engine lifecycle.
 */
internal class MediaController(
    /**
     * The manager for RTC capture, publication, subscription, and rendering.
     */
    private val rtcManager: RtcManaging,

    /**
     * The local camera controller.
     */
    private val cameraController: CameraController,

    /**
     * The local image controller.
     */
    private val imageController: ImageController? = null,

    /**
     * The local video controller.
     */
    private val videoController: VideoController? = null,

    /**
     * The app-provided external video controller.
     */
    private val externalController: ExternalVideoController? = null,

    /**
     * The controller for video read directly by the server.
     */
    private val networkVideoController: NetworkVideoController? = null,

    /**
     * The controller submitting interaction trajectories.
     */
    private val interactionController: InteractionController = InteractionController(),
) : MediaControlling {

    /**
     * The mutex serializing local media operations.
     */
    private val operationMutex = Mutex()

    /**
     * The lock protecting active source state.
     */
    private val stateLock = Any()

    /**
     * The active local media source kind.
     */
    private var activeSource: LocalMediaKind? = null

    /**
     * The successfully configured local preview volume, from 0 to 1.
     */
    @Volatile override var localAudioVolume: Float = 0.45f
        private set

    /**
     * The track supplied by the current media source.
     */
    override val currentTrack: RealtimeVideoTrack?
        get() = when (synchronized(stateLock) { activeSource }) {
            LocalMediaKind.CAMERA -> cameraController.currentTrack
            LocalMediaKind.IMAGE -> imageController?.currentTrack
            LocalMediaKind.EXTERNAL -> externalController?.currentTrack
            LocalMediaKind.VIDEO -> videoController?.currentTrack
            LocalMediaKind.NETWORK_VIDEO -> networkVideoController?.currentTrack
            null -> null
        }

    /**
     * The current local track's input format.
     */
    override val currentVideoFormat: RealtimeVideoFormat?
        get() = currentTrack?.videoFormat

    /**
     * Whether the current source contains publishable audio.
     */
    override val hasAudio: Boolean
        get() = when (synchronized(stateLock) { activeSource }) {
            LocalMediaKind.CAMERA -> cameraController.useMicrophone
            LocalMediaKind.EXTERNAL -> externalController?.hasAudio == true
            LocalMediaKind.VIDEO -> videoController?.hasAudio == true
            else -> false
        }

    /**
     * The server reference for the current network video.
     */
    override val networkVideoReference: RealtimeReferenceVideo?
        get() = if (synchronized(stateLock) { activeSource == LocalMediaKind.NETWORK_VIDEO }) {
            networkVideoController?.reference
        } else null

    /**
     * The network video generation completion callback.
     */
    override val networkVideoFinishHandler: (() -> Unit)?
        get() = networkVideoController?.onFinish

    /**
     * Creates a video source read directly by the server from an HTTP or HTTPS URL.
     * The local preview plays once without sound or local publication; remote audio begins after the first
     * generated frame is ready.
     * [onFinish] runs once on the main thread after the current task's server completion event and
     * first-frame readiness;
     * it does not confirm that the last remote frame has been displayed. Creation does not start generation;
     * [close] releases the source.
     *
     * @param uri A media URI your app can read.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param sampleMethod The sampling method used when the server reads the network video.
     * @param onFinish The completion callback for the network video task, or null to omit it.
     */
    override suspend fun createNetworkVideoStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat,
        sampleMethod: RealtimeVideoSampleMethod,
        onFinish: (() -> Unit)?,
    ): RealtimeMediaStream = createSource(LocalMediaKind.NETWORK_VIDEO) {
        checkNotNull(networkVideoController).create(
            uri,
            videoFormat,
            sampleMethod,
            onFinish,
        )
    }

    /**
     * Whether the active media source supplies external frames.
     */
    override val isExternalVideo: Boolean
        get() = synchronized(stateLock) { activeSource == LocalMediaKind.EXTERNAL }

    /**
     * Sets the camera preview readiness listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setCameraPreviewReadyListener(
        listener: RealtimeCameraPreviewReadyListener?,
    ) {
        cameraController.setPreviewReadyListener(listener)
    }

    /**
     * Starts accepting interactions for a generation task.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     */
    override suspend fun startInteraction(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
    ) {
        interactionController.startInteraction(
            taskId,
            videoFormat,
        )
    }

    /**
     * Stops accepting interactions and clears pending trajectories.
     */
    override suspend fun stopInteraction() {
        interactionController.stopInteraction()
    }

    /**
     * Submits a touch trajectory.
     *
     * @param frame The current trajectory and displayed image dimensions.
     */
    override fun submitInteraction(
        frame: InteractionFrame,
    ) {
        interactionController.submitInteraction(frame)
    }

    /**
     * Creates a local camera stream and starts its preview.
     *
     * Your app must obtain camera permission before creation and recording permission before
     * enabling the microphone. Microphone capture starts on connection and stops on disconnection,
     * without local playback. Input dimensions must satisfy the model constraints.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param position The front or rear camera to use.
     * @param useMicrophone Whether to use the microphone; your app must obtain recording permission first.
     */
    override suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean,
    ): RealtimeMediaStream = createSource(LocalMediaKind.CAMERA) {
        cameraController.createLocalCameraStream(
            videoFormat,
            position,
            useMicrophone,
        )
    }

    /**
     * Updates camera settings while retaining source ownership and the active track.
     *
     * @param videoFormat Model-supported dimensions, capture frame rate, and encoding settings.
     */
    override suspend fun updateCameraFormat(
        videoFormat: RealtimeVideoFormat,
    ) {
        operationMutex.withLock {
            if (synchronized(stateLock) { activeSource } != LocalMediaKind.CAMERA) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Camera preview is not started",
                )
            }

            cameraController.updateVideoFormat(videoFormat)
        }
    }

    /**
     * Starts microphone capture for the current camera source.
     */
    override fun startMicrophoneCapture() {
        if (synchronized(stateLock) { activeSource } == LocalMediaKind.CAMERA) {
            cameraController.startMicrophoneCapture()
        }
    }

    /**
     * Stops microphone capture for the current camera source.
     */
    override fun stopMicrophoneCapture() {
        if (synchronized(stateLock) { activeSource } == LocalMediaKind.CAMERA) {
            cameraController.stopMicrophoneCapture()
        }
    }

    /**
     * Creates a continuous video frame stream from encoded image data.
     *
     * The stream retains the media aspect ratio and uses the model's default frame rate. Dimensions
     * respect model pixel-area limits and four-pixel alignment; the generation canvas is selected
     * separately.
     *
     * @param imageData Encoded image file bytes, not raw pixels.
     */
    override suspend fun createLocalImageStream(
        imageData: ByteArray,
    ): RealtimeMediaStream = createSource(LocalMediaKind.IMAGE) {
        requiredImageController().createLocalImageStream(imageData)
    }

    /**
     * Creates a continuous video frame stream from an Android bitmap.
     *
     * The stream retains the media aspect ratio and uses the model's default frame rate. Dimensions
     * respect model pixel-area limits and four-pixel alignment; the generation canvas is selected
     * separately.
     *
     * @param bitmap The Android image to process or display.
     */
    override suspend fun createLocalImageStream(
        bitmap: Bitmap,
    ): RealtimeMediaStream = createSource(LocalMediaKind.IMAGE) {
        requiredImageController().createLocalImageStream(bitmap)
    }

    /**
     * Creates a continuous video frame stream from a local image URI.
     *
     * Your app must hold read permission. The stream retains the media aspect ratio and uses the
     * model's default frame rate. Dimensions respect model pixel-area limits and four-pixel
     * alignment; the generation canvas is selected separately.
     *
     * @param uri A media URI your app can read.
     */
    override suspend fun createLocalImageStream(
        uri: Uri,
    ): RealtimeMediaStream = createSource(LocalMediaKind.IMAGE) {
        requiredImageController().createLocalImageStream(uri)
    }

    /**
     * Stops the local camera stream.
     *
     * Other input sources are unaffected.
     */
    override suspend fun stopLocalCameraStream() {
        stopSource(LocalMediaKind.CAMERA)
    }

    /**
     * Stops the local image stream.
     *
     * Other input sources are unaffected.
     */
    override suspend fun stopLocalImageStream() {
        stopSource(LocalMediaKind.IMAGE)
    }

    /**
     * The active local media source kind.
     */
    override val isLocalVideo: Boolean
        get() = synchronized(stateLock) { activeSource == LocalMediaKind.VIDEO }

    /**
     * Whether the active source is a local image.
     */
    override val isLocalImage: Boolean
        get() = synchronized(stateLock) { activeSource == LocalMediaKind.IMAGE }

    /**
     * Creates a looping audio and video stream from a local video URI.
     *
     * Your app must hold read permission. The stream retains the video's display aspect ratio and
     * uses the model's default frame rate. Dimensions respect model pixel-area limits and
     * four-pixel alignment; the generation canvas is resolved separately.
     *
     * @param uri A media URI your app can read.
     */
    override suspend fun createLocalVideoStream(
        uri: Uri,
    ): RealtimeMediaStream = createSource(LocalMediaKind.VIDEO) {
        requiredVideoController().createLocalVideoStream(uri)
    }

    /**
     * Binds an external video source with optional audio for realtime generation.
     *
     * Your app owns camera or player startup, shutdown, and release. The source may optionally
     * supply a preview inside the SDK container. When videoFormat is absent, source dimensions and
     * the model's default frame rate apply, subject to model validation and alignment. Frames must
     * match the sink's video format.
     *
     * @param source The external media source whose lifecycle your app owns.
     * @param videoFormat Input dimensions, frame rate, and optional uplink encoding settings; null uses
     * source or parsed media defaults.
     */
    override suspend fun createExternalVideoStream(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream =
        createSource(LocalMediaKind.EXTERNAL) { checkNotNull(externalController).create(
            source,
            videoFormat,
        ) }

    /**
     * Unbinds external video and preview without releasing your app's capture resources.
     */
    override suspend fun stopExternalVideoStream() { stopSource(LocalMediaKind.EXTERNAL) }

    /**
     * Replacement retains the engine and source kind on failure so retries or final cleanup can still release
     * the correct resources.
     *
     * @param videoFormat Input dimensions, frame rate, and optional uplink encoding settings; null uses
     * source or parsed media defaults.
     * @param targetSource The suspending callback preparing and returning the replacement source.
     */
    override suspend fun replaceExternalVideoStream(
        videoFormat: RealtimeVideoFormat?,
        targetSource: suspend () -> RealtimeExternalVideoSource,
    ): RealtimeMediaStream = operationMutex.withLock {
        check(isExternalVideo) { "The current local media source is not an external video" }
        val controller = checkNotNull(externalController)
        controller.stop()
        val source = targetSource()
        currentCoroutineContext().ensureActive()
        controller.create(
            source,
            videoFormat,
        )
    }

    /**
     * Stops the local file video stream.
     *
     * Other input sources are unaffected.
     */
    override suspend fun stopLocalVideoStream() {
        stopSource(LocalMediaKind.VIDEO)
    }

    /**
     * Mutes or unmutes local preview without changing its stored volume.
     *
     * @param muted Whether audio is muted.
     */
    override suspend fun setLocalAudioPreviewMuted(
        muted: Boolean,
    ) {
        if (synchronized(stateLock) { activeSource } == LocalMediaKind.EXTERNAL) {
            externalController?.setAudio(muted = muted)
        }

        if (synchronized(stateLock) { activeSource } == LocalMediaKind.VIDEO) {
            videoController?.setLocalAudioPreviewMuted(muted)
        }
    }

    /**
     * Sets local preview volume from 0 to 1 without changing audio submitted to RTC.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    override suspend fun setLocalAudioVolume(
        volume: Float,
    ) {
        videoController?.setLocalAudioVolume(volume)
        externalController?.setAudio(volume = volume)
        localAudioVolume = volume
    }

    /**
     * Switches the active camera and updates local track metadata.
     */
    override suspend fun switchCamera(): RealtimeMediaStream = operationMutex.withLock {
        if (synchronized(stateLock) { activeSource } != LocalMediaKind.CAMERA) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "The current local media source is not a camera",
            )
        }

        cameraController.switchCamera()
    }

    /**
     * Stops and releases the current local media source.
     */
    override suspend fun stopLocalStream() = operationMutex.withLock {
        synchronized(stateLock) { activeSource }?.let { stopSourceLocked(it) }
        Unit
    }

    /**
     * Checks whether this controller created and still owns the stream.
     *
     * @param stream The local input or remote result stream.
     * @return True if the controller owns the stream.
     */
    override fun owns(
        stream: RealtimeMediaStream,
    ): Boolean =
        stream.videoTrack != null && stream.videoTrack === currentTrack

    /**
     * Creates a local source of the requested kind and records its ownership.
     *
     * @param kind The operation or media source kind.
     * @param create The suspending function creating the local stream.
     */
    private suspend fun createSource(
        kind: LocalMediaKind,
        create: suspend () -> RealtimeMediaStream,
    ): RealtimeMediaStream = operationMutex.withLock {
        if (currentTrack != null) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Stop the current local media stream before creating another one",
            )
        }

        try {
            rtcManager.initialize()
            create().also {
                synchronized(stateLock) { activeSource = kind }
            }
        } catch (
            error: Throwable,
        ) {
            cleanupAfterFailure(
                error,
                { stopController(kind) },
                { rtcManager.destroy() },
                { synchronized(stateLock) { activeSource = null } },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * Stops a source only if its kind matches the current input.
     *
     * @param kind The operation or media source kind.
     */
    private suspend fun stopSource(
        kind: LocalMediaKind,
    ) = operationMutex.withLock {
        if (synchronized(stateLock) { activeSource } == kind) stopSourceLocked(kind)
    }

    /**
     * Releases the active source and tracks of the requested kind.
     *
     * @param kind The operation or media source kind.
     */
    private suspend fun stopSourceLocked(
        kind: LocalMediaKind,
    ) {
        cleanupResources(
            { stopController(kind) },
            { rtcManager.destroy() },
            { synchronized(stateLock) { activeSource = null } },
        )
    }

    /**
     * Releases the controller for a media source kind.
     *
     * @param kind The operation or media source kind.
     */
    private suspend fun stopController(
        kind: LocalMediaKind,
    ) {
        when (kind) {
            LocalMediaKind.CAMERA -> cameraController.stopLocalCameraStream()
            LocalMediaKind.IMAGE -> imageController?.stopLocalImageStream()
            LocalMediaKind.VIDEO -> videoController?.stopLocalVideoStream()
            LocalMediaKind.EXTERNAL -> externalController?.stop()
            LocalMediaKind.NETWORK_VIDEO -> networkVideoController?.stop()
        }
    }

    /**
     * Returns the image controller or reports unsupported input if absent.
     *
     * @return The configured image controller.
     */
    private fun requiredImageController(): ImageController = imageController ?: throw XmaxError(
        XmaxErrorCode.INTERNAL_ERROR,
        "Local image media is unavailable",
    )

    /**
     * Returns the video controller or reports unsupported input if absent.
     *
     * @return The configured video controller.
     */
    private fun requiredVideoController(): VideoController = videoController ?: throw XmaxError(
        XmaxErrorCode.INTERNAL_ERROR,
        "Local video media is unavailable",
    )

    /**
     * The active local input source.
     */
    private enum class LocalMediaKind {

        /**
         * A network video read directly by the server.
         */
        NETWORK_VIDEO,

        /**
         * External frames supplied by your app.
         */
        EXTERNAL,

        /**
         * The device camera.
         */
        CAMERA,

        /**
         * A still image.
         */
        IMAGE,

        /**
         * A local video.
         */
        VIDEO,
    }
}
