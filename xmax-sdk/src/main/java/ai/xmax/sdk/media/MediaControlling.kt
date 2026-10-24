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
 * The local media capabilities exposed to Core.
 */
internal interface MediaControlling : InteractionControlling {

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
     * Stops and releases the current camera source; does nothing for other source types.
     */
    suspend fun stopLocalCameraStream()

    /**
     * Decodes image bytes into a local stream that repeatedly supplies frames at the media's aspect ratio.
     * Uses orientation-corrected image dimensions, limited by model pixel area and aligned to four pixels,
     * with the model's default frame rate.
     * The model canvas is selected separately; the server fits the image and removes padding from output.
     *
     * @param imageData Encoded image file bytes, not raw pixels.
     */
    suspend fun createLocalImageStream(
        imageData: ByteArray,
    ): RealtimeMediaStream

    /**
     * Creates a local image stream from a Bitmap while retaining its aspect ratio.
     * Uses Bitmap dimensions limited by model pixel area and four-pixel alignment, with the default frame
     * rate and a separately selected model canvas.
     *
     * @param bitmap The Android image to process or display.
     */
    suspend fun createLocalImageStream(
        bitmap: Bitmap,
    ): RealtimeMediaStream

    /**
     * Creates a local image stream from a readable URI; your app owns read permissions.
     * Uses orientation-corrected image dimensions, limited by model pixel area and aligned to four pixels,
     * with the model's default frame rate.
     * The model canvas is selected separately; the server fits the image and removes padding from output.
     *
     * @param uri A media URI your app can read.
     */
    suspend fun createLocalImageStream(
        uri: Uri,
    ): RealtimeMediaStream

    /**
     * Stops and releases the current image source; does nothing for other source types.
     */
    suspend fun stopLocalImageStream()

    /**
     * Creates a local video stream from a URI, including its audio track when present.
     * Uses orientation-corrected media dimensions, limited by model pixel area and aligned to four pixels,
     * with the model's default frame rate.
     * The SDK selects a separate generation canvas; the server fits the media and removes padding from
     * output.
     *
     * @param uri A media URI your app can read.
     */
    suspend fun createLocalVideoStream(
        uri: Uri,
    ): RealtimeMediaStream

    /**
     * Binds video collected, decoded, or processed by your app; your app owns camera or player startup,
     * shutdown, and release.
     * Video is provided by default; the source may optionally supply audio and a preview inside the SDK
     * container.
     * When videoFormat is absent, uses source dimensions and the model's default frame rate, subject to model
     * validation and alignment.
     * The external source must deliver frames matching sink.videoFormat.
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
     * Unbinds external input after disconnection without closing your app's camera or player.
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
     * Stops the current video source and releases decoding and preview resources; does nothing for other
     * source types.
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
