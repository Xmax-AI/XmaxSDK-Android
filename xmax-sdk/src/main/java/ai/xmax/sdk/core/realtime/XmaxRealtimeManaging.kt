//
//  XmaxRealtimeManaging.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import android.graphics.Bitmap
import android.net.Uri

/**
 * The public API for local media, connections, and generation; each manager owns one local media source at a
 * time.
 *
 * Disconnect before creating or stopping local media. Use [replaceExternalVideoStream] for external source
 * replacement.
 * Generation requests prefer the latest call; XmaxSDK cancels old requests and waits for rollback without
 * requiring app-side serialization.
 * [disconnect] and [close] cancel affected pending operations and wait for cleanup.
 * Suspending calls report failures through exceptions and preserve CancellationException semantics.
 * Uplink and downlink adapt independently to network quality, starting at L1 (100% dimensions) when no history
 * exists.
 * Adaptation leaves original media dimensions, the model canvas, and interaction coordinates unchanged and
 * does not depend on public statistics listeners.
 */
public interface XmaxRealtimeManaging {

    /**
     * The realtime configuration supplied at creation.
     */
    public val options: RealtimeConfiguration

    /**
     * The current state snapshot; [RealtimeState.reason] reports asynchronous failures.
     */
    public val currentState: RealtimeState

    /**
     * The current camera media format, or null without a camera source; transmission quality changes do not
     * alter it.
     */
    public val currentCameraVideoFormat: RealtimeVideoFormat?

    /**
     * The preset matching the current camera dimensions, or null without a camera or matching preset.
     */
    public val currentCameraPreset: RealtimeCameraPreset?

    /**
     * Updates a ready, disconnected camera preview while retaining its track and view binding.
     *
     * @param videoFormat New dimensions, capture frame rate, and encoding settings; dimensions must match a
     * supported model format.
     * @throws XmaxError If state, format, operation conflicts, or capture configuration prevent the update.
     */
    public suspend fun updateCameraFormat(
        videoFormat: RealtimeVideoFormat,
    )

    /**
     * Changes camera resolution while preserving the current frame rate, bitrate, and encoding preference.
     *
     * @param preset The target resolution preset supported by the model.
     * @throws XmaxError If state, preset, operation conflicts, or capture configuration prevent the update.
     */
    public suspend fun updateCameraFormat(
        preset: RealtimeCameraPreset,
    )

    /**
     * The committed uplink level, or null before connection, during disconnection, or without local
     * publication.
     */
    public val uplinkQualityLevel: RealtimeQualityLevel?

    /**
     * The submitted downlink level, or null before downlink configuration exists or during disconnection.
     * The received resolution may change after this property updates.
     */
    public val downlinkQualityLevel: RealtimeQualityLevel?

    /**
     * Adjusts uplink encoding quality during generation without changing capture dimensions or the model
     * canvas.
     * Later network samples may still change the level; a manual choice does not lock adaptation.
     *
     * @param level The target uplink level.
     * @throws XmaxError If generation, local publication, operation admission, or encoding configuration does
     * not permit the update.
     */
    public suspend fun setUplinkQualityLevel(
        level: RealtimeQualityLevel,
    )

    /**
     * Adjusts downlink quality during generation while keeping automatic adaptation enabled.
     *
     * @param level The target downlink level.
     * @throws XmaxError If generation, operation admission, or signaling fails to permit the update.
     */
    public suspend fun setDownlinkQualityLevel(
        level: RealtimeQualityLevel,
    )

    /**
     * The local preview volume from 0 to 1; defaults to 0.45 when unset.
     */
    public val localAudioVolume: Float

    /**
     * The remote volume from 0 to 1, reset according to source type when creating new media.
     */
    public val remoteAudioVolume: Float

    /**
     * Replaces the state listener and asynchronously delivers the current snapshot on the main thread; null
     * unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    public suspend fun setStateListener(
        listener: RealtimeStateListener?,
    )

    /**
     * Receives final remote video frames before display.
     * Callbacks run serially on an SDK background dispatcher; slow consumers receive only the latest pending
     * frame.
     * Frame pixels may be retained for asynchronous encoding. Null unregisters the listener. Stopping,
     * disconnecting, or changing tasks discards pending old frames;
     * callbacks already executing may finish. A video view binding is not required.
     *
     * @param listener The event listener, or null to unregister it.
     */
    public suspend fun setRemoteVideoFrameListener(
        listener: RealtimeVideoFrameListener?,
    )

    /**
     * Sets the RTC network quality listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    public suspend fun setNetworkQualityListener(
        listener: RealtimeNetworkQualityListener?,
    )

    /**
     * Sets the main-thread uplink statistics listener; null unregisters it. The listener survives
     * disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    public suspend fun setUplinkVideoStatisticsListener(
        listener: UplinkVideoStatisticsListener?,
    )

    /**
     * Sets the main-thread downlink statistics listener; null unregisters it. The listener survives
     * disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    public suspend fun setDownlinkVideoStatisticsListener(
        listener: DownlinkVideoStatisticsListener?,
    )

    /**
     * Sets the RTC performance alarm listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    public suspend fun setPerformanceAlarmListener(
        listener: RealtimePerformanceAlarmListener?,
    )

    /**
     * Sets local preview volume from 0 to 1; a successful setting survives close and media recreation.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    public suspend fun setLocalAudioVolume(
        volume: Float,
    )

    /**
     * Sets remote playback volume from 0 to 1; a successful setting survives close.
     * Creating a camera or image source resets it to 0; creating a video source resets it to 1.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    public suspend fun setRemoteAudioVolume(
        volume: Float,
    )

    /**
     * Creates a camera stream with a resolution preset and the model's default frame rate.
     *
     * @param preset The model-supported camera resolution.
     * @param position The initial camera position.
     * @param useMicrophone Whether to use the microphone; recording permission is required, and capture
     * starts on connection.
     * @return A media stream containing the local camera track.
     * @throws XmaxError If the preset, permissions, operation conflicts, or capture startup prevent creation.
     */
    public suspend fun createLocalCameraStream(
        preset: RealtimeCameraPreset,
        position: CameraPosition = CameraPosition.FRONT,
        useMicrophone: Boolean = false,
    ): RealtimeMediaStream {
        val format = options.model.supportedVideoFormats.firstOrNull {
            it.width == preset.size.width && it.height == preset.size.height
        } ?: throw XmaxError(
            XmaxErrorCode.INVALID_CONFIGURATION,
            "Model does not support the requested camera preset",
        )
        return createLocalCameraStream(
            format,
            position,
            useMicrophone,
        )
    }

    /**
     * Creates camera input with the current model's default video format.
     * The microphone is disabled by default; your app must obtain required permissions first.
     *
     * @param position The camera position; defaults to `CameraPosition.FRONT`.
     * @param useMicrophone Whether to use the microphone; requires recording permission and defaults to
     * false.
     */
    public suspend fun createLocalCameraStream(
        position: CameraPosition = CameraPosition.FRONT,
        useMicrophone: Boolean = false,
    ): RealtimeMediaStream = createLocalCameraStream(
        videoFormat = options.model.defaultCameraVideoFormat,
        position = position,
        useMicrophone = useMicrophone,
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
    public suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean = false,
    ): RealtimeMediaStream

    /**
     * Stops and releases the current camera source; does nothing for other source types.
     */
    public suspend fun stopLocalCameraStream()

    /**
     * Decodes image bytes into a local stream that repeatedly supplies frames at the media's aspect ratio.
     * Uses orientation-corrected image dimensions, limited by model pixel area and aligned to four pixels,
     * with the model's default frame rate.
     * The model canvas is selected separately; the server fits the image and removes padding from output.
     *
     * @param imageData Encoded image file bytes, not raw pixels.
     */
    public suspend fun createLocalImageStream(
        imageData: ByteArray,
    ): RealtimeMediaStream

    /**
     * Creates a local image stream from a Bitmap while retaining its aspect ratio.
     * Uses Bitmap dimensions limited by model pixel area and four-pixel alignment, with the default frame
     * rate and a separately selected model canvas.
     *
     * @param bitmap The Android image to process or display.
     */
    public suspend fun createLocalImageStream(
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
    public suspend fun createLocalImageStream(
        uri: Uri,
    ): RealtimeMediaStream

    /**
     * Stops and releases the current image source; does nothing for other source types.
     */
    public suspend fun stopLocalImageStream()

    /**
     * Creates a local video stream from a URI, including its audio track when present.
     * Uses orientation-corrected media dimensions, limited by model pixel area and aligned to four pixels,
     * with the model's default frame rate.
     * The SDK selects a separate generation canvas; the server fits the media and removes padding from
     * output.
     *
     * @param uri A media URI your app can read.
     */
    public suspend fun createLocalVideoStream(
        uri: Uri,
    ): RealtimeMediaStream

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
     * @param sampleMethod The server video sampling method; defaults to `RealtimeVideoSampleMethod.TIME`.
     * @param onFinish The completion callback for the network video task, or null to omit it.
     */
    public suspend fun createNetworkVideoStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat,
        sampleMethod: RealtimeVideoSampleMethod = RealtimeVideoSampleMethod.TIME,
        onFinish: (() -> Unit)? = null,
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
    public suspend fun createExternalVideoStream(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream

    /**
     * Unbinds external input after disconnection without closing your app's camera or player.
     */
    public suspend fun stopExternalVideoStream()

    /**
     * Replaces external video input, stopping the old task and frame delivery before [targetSource] prepares
     * the new source.
     * The callback runs on the caller's dispatcher with cancellation owned by the SDK operation; your app
     * still owns the capture device or player.
     * An active or pending generation restarts with retained conditions; preview-only replacement does not
     * start generation.
     * Compatible audio/video formats retain the room; changed formats reconnect while reusing the manager and
     * RTC engine.
     * The latest replacement wins; old requests end with CancellationException. Failure does not restore the
     * old task.
     *
     * @param videoFormat Input dimensions, frame rate, and optional uplink encoding settings; null uses
     * source or parsed media defaults.
     * @param targetSource The suspending callback preparing and returning the replacement source.
     * @return The replacement local stream and any available remote result stream.
     */
    public suspend fun replaceExternalVideoStream(
        videoFormat: RealtimeVideoFormat? = null,
        targetSource: suspend () -> RealtimeExternalVideoSource,
    ): RealtimeStreamReplacement

    /**
     * Stops the current video source and releases decoding and preview resources; does nothing for other
     * source types.
     */
    public suspend fun stopLocalVideoStream()

    /**
     * Switches cameras; during generation, stops the task and restarts with cached conditions after
     * switching.
     */
    public suspend fun switchCamera(): RealtimeMediaStream

    /**
     * Connects an active local stream created by this manager and returns the remote stream for rendering.
     *
     * @param localStream The local input stream.
     */
    public suspend fun connect(
        localStream: RealtimeMediaStream,
    ): RealtimeMediaStream

    /**
     * Ends generation and closes the session and RTC connection, retaining local media; the final state is
     * READY or IDLE.
     */
    public suspend fun disconnect()

    /**
     * Disconnects with a specific reason, such as [RealtimeReason.OrientationChanged] after a display
     * orientation change.
     *
     * @param reason The reason for the state transition or failure.
     */
    public suspend fun disconnect(
        reason: RealtimeReason,
    )

    /**
     * Starts generation on an existing connection; the first call requires conditions, and later null values
     * reuse the cache.
     * An active task updates its conditions; a new task enters GENERATING only after remote confirmation and
     * first-frame readiness.
     * Both overloads share replacement ordering: a new call cancels the pending old call with
     * CancellationException,
     * without reporting a business failure. Disconnect and close cancel all pending generation without
     * automatically resuming it after cleanup.
     *
     * @param context Generation prompts and reference media; null reuses cached conditions, which must exist
     * for the first request.
     * @param faceRestoration Whether to restore the original face; defaults to false and is sent on every
     * call.
     */
    public suspend fun startGeneration(
        context: RealtimeContext?,
        faceRestoration: Boolean = false,
    )

    /**
     * Connects when necessary, then starts or updates generation and returns the remote stream; uses the
     * other overload's replacement semantics.
     *
     * @param localStream The local input stream.
     * @param context Generation prompts and reference media; null reuses cached conditions, which must exist
     * for the first request.
     * @param faceRestoration Whether to restore the original face; defaults to false and is sent on every
     * call.
     */
    public suspend fun startGeneration(
        localStream: RealtimeMediaStream,
        context: RealtimeContext?,
        faceRestoration: Boolean = false,
    ): RealtimeMediaStream

    /**
     * Cancels pending operations and waits for all realtime resources to be released; listeners remain
     * registered and the manager can be reused.
     */
    public suspend fun close()
}
