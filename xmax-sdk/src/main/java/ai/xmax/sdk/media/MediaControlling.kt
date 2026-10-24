//
//  MediaControlling.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeReferenceVideo
import ai.xmax.sdk.RealtimeVideoSampleMethod
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.media.interaction.InteractionControlling
import ai.xmax.sdk.RealtimeExternalVideoSource
import android.graphics.Bitmap
import android.net.Uri

/**
 * The media layer interface used by Core.
 */
internal interface MediaControlling : InteractionControlling {

    /**
     * The local preview volume retained across source changes and resource release.
     */
    val localAudioVolume: Float

    /**
     * The active media track.
     */
    val currentTrack: RealtimeVideoTrack?

    /**
     * The current local video input format.
     */
    val currentVideoFormat: RealtimeVideoFormat?

    /**
     * Whether the current source contains publishable audio.
     */
    val hasAudio: Boolean

    /**
     * The network video configuration read by the server.
     */
    val networkVideoReference: RealtimeReferenceVideo?

    /**
     * The network video generation completion callback.
     */
    val networkVideoFinishHandler: (() -> Unit)?

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
    suspend fun createNetworkVideoStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat,
        sampleMethod: RealtimeVideoSampleMethod,
        onFinish: (() -> Unit)?,
    ): RealtimeMediaStream

    /**
     * Whether the active media source supplies external frames.
     */
    val isExternalVideo: Boolean

    /**
     * Whether local video is decoded and uploaded through RTC.
     */
    val isLocalVideo: Boolean get() = false

    /**
     * Whether a local image repeatedly supplies frames to RTC.
     */
    val isLocalImage: Boolean get() = false

    /**
     * Sets the camera preview readiness listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setCameraPreviewReadyListener(
        listener: RealtimeCameraPreviewReadyListener?,
    )

    /**
     * Creates a local camera stream and starts its preview.
     *
     * Your app must obtain camera permission before creation and recording permission before
     * enabling the microphone. Microphone capture starts on connection and stops on disconnection,
     * without local playback. Input dimensions must satisfy the model constraints.
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
    ): RealtimeMediaStream

    /**
     * Updates the camera format while retaining its track and preview binding.
     *
     * @param videoFormat Model-supported dimensions, capture frame rate, and encoding settings.
     */
    suspend fun updateCameraFormat(
        videoFormat: RealtimeVideoFormat,
    )

    /**
     * Starts microphone capture for the current camera source.
     */
    fun startMicrophoneCapture()

    /**
     * Stops microphone capture for the current camera source.
     */
    fun stopMicrophoneCapture()

    /**
     * Stops the local camera stream.
     *
     * Other input sources are unaffected.
     */
    suspend fun stopLocalCameraStream()

    /**
     * Creates a continuous video frame stream from encoded image data.
     *
     * The stream retains the media aspect ratio and uses the model's default frame rate. Dimensions
     * respect model pixel-area limits and four-pixel alignment; the generation canvas is selected
     * separately.
     *
     * @param imageData Encoded image file bytes, not raw pixels.
     */
    suspend fun createLocalImageStream(
        imageData: ByteArray,
    ): RealtimeMediaStream

    /**
     * Creates a continuous video frame stream from an Android bitmap.
     *
     * The stream retains the media aspect ratio and uses the model's default frame rate. Dimensions
     * respect model pixel-area limits and four-pixel alignment; the generation canvas is selected
     * separately.
     *
     * @param bitmap The Android image to process or display.
     */
    suspend fun createLocalImageStream(
        bitmap: Bitmap,
    ): RealtimeMediaStream

    /**
     * Creates a continuous video frame stream from a local image URI.
     *
     * Your app must hold read permission. The stream retains the media aspect ratio and uses the
     * model's default frame rate. Dimensions respect model pixel-area limits and four-pixel
     * alignment; the generation canvas is selected separately.
     *
     * @param uri A media URI your app can read.
     */
    suspend fun createLocalImageStream(
        uri: Uri,
    ): RealtimeMediaStream

    /**
     * Stops the local image stream.
     *
     * Other input sources are unaffected.
     */
    suspend fun stopLocalImageStream()

    /**
     * Creates a looping audio and video stream from a local video URI.
     *
     * Your app must hold read permission. The stream retains the video's display aspect ratio and
     * uses the model's default frame rate. Dimensions respect model pixel-area limits and
     * four-pixel alignment; the generation canvas is resolved separately.
     *
     * @param uri A media URI your app can read.
     */
    suspend fun createLocalVideoStream(
        uri: Uri,
    ): RealtimeMediaStream

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
    suspend fun createExternalVideoStream(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream

    /**
     * Unbinds external video and preview without releasing your app's capture resources.
     */
    suspend fun stopExternalVideoStream()

    /**
     * Replaces external input without destroying the engine; old frame delivery is detached before source
     * preparation.
     *
     * @param videoFormat Input dimensions, frame rate, and optional uplink encoding settings; null uses
     * source or parsed media defaults.
     * @param targetSource The suspending callback preparing and returning the replacement source.
     */
    suspend fun replaceExternalVideoStream(
        videoFormat: RealtimeVideoFormat?,
        targetSource: suspend () -> RealtimeExternalVideoSource,
    ): RealtimeMediaStream

    /**
     * Stops the local file video stream.
     *
     * Other input sources are unaffected.
     */
    suspend fun stopLocalVideoStream()

    /**
     * Mutes or unmutes local preview without changing its stored volume.
     *
     * @param muted Whether audio is muted.
     */
    suspend fun setLocalAudioPreviewMuted(
        muted: Boolean,
    )

    /**
     * Sets local preview volume from 0 to 1 without changing audio submitted to RTC.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    suspend fun setLocalAudioVolume(
        volume: Float,
    )

    /**
     * Switches the active camera and updates local track metadata.
     */
    suspend fun switchCamera(): RealtimeMediaStream

    /**
     * Stops and releases the current local media source.
     */
    suspend fun stopLocalStream()

    /**
     * Checks whether this controller created and still owns the stream.
     *
     * @param stream The local input or remote result stream.
     * @return True if the controller owns the stream.
     */
    fun owns(
        stream: RealtimeMediaStream,
    ): Boolean
}
