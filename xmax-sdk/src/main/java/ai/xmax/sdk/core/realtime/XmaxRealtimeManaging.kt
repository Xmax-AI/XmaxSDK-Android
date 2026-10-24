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
 * An interface for local media, realtime connections, and video generation.
 *
 * Each manager owns one local media source at a time. Disconnect before creating or stopping local media.
 * Use [replaceExternalVideoStream] for external source replacement.
 * Generation requests prefer the latest call; XmaxSDK cancels old requests and waits for rollback without
 * requiring app-side serialization.
 * [disconnect] and [close] cancel affected pending operations and wait for cleanup.
 * Suspending calls report failures through exceptions and preserve CancellationException semantics.
 * When enabled in [RealtimeConfiguration], uplink and downlink adapt independently to network quality,
 * starting at L1 (100% dimensions) when no history exists. Disabling adaptation starts each connection at L1
 * without reading or updating history; manual quality changes remain available.
 * Adaptation leaves original media dimensions, the model canvas, and interaction coordinates unchanged and
 * does not depend on public statistics listeners.
 */
public interface XmaxRealtimeManaging {

    /**
     * The realtime configuration supplied at creation.
     */
    public val options: RealtimeConfiguration

    /**
     * The current connection and generation state; [RealtimeState.reason] reports asynchronous failures.
     */
    public val currentState: RealtimeState

    /**
     * The camera's resolved media format, or null for other input sources.
     *
     * Transmission quality changes do not alter the media format.
     */
    public val currentCameraVideoFormat: RealtimeVideoFormat?

    /**
     * The preset matching the current camera dimensions, or null without a camera or matching preset.
     */
    public val currentCameraPreset: RealtimeCameraPreset?

    /**
     * Updates a ready, disconnected camera format while preserving stream and view bindings.
     *
     * @param videoFormat New dimensions, capture frame rate, and encoding settings; dimensions must match a
     * supported model format.
     * @throws XmaxError If state, format, operation conflicts, or capture configuration prevent the update.
     */
    public suspend fun updateCameraFormat(
        videoFormat: RealtimeVideoFormat,
    )

    /**
     * Updates camera dimensions while preserving frame rate and encoding settings.
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
     * Changes the uplink encoding level during generation without changing capture or generation
     * dimensions.
     *
     * Subsequent network samples may change the level; a manual choice does not disable automatic
     * adaptation.
     *
     * @param level The target uplink level.
     * @throws XmaxError If generation, local publication, operation admission, or encoding configuration does
     * not permit the update.
     */
    public suspend fun setUplinkQualityLevel(
        level: RealtimeQualityLevel,
    )

    /**
     * Changes the downlink output level while allowing subsequent automatic adaptation.
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
     * Sets the realtime state listener.
     *
     * The current snapshot and subsequent changes are delivered asynchronously on the main thread.
     *
     * @param listener The state change handler, or null to remove the listener.
     */
    public suspend fun setStateListener(
        listener: RealtimeStateListener?,
    )

    /**
     * Sets serialized remote video output without changing playback.
     *
     * Callbacks run serially on an SDK background dispatcher; slow consumers receive only the
     * latest pending frame. Frame pixels may be retained for asynchronous encoding. Stopping,
     * disconnecting, or changing tasks discards pending old frames; callbacks already executing may
     * finish. A video view binding is not required.
     *
     * @param listener The video frame handler, or null to stop output.
     */
    public suspend fun setRemoteVideoFrameListener(
        listener: RealtimeVideoFrameListener?,
    )

    /**
     * Sets the network quality listener.
     *
     * @param listener The uplink and downlink quality handler, or null to remove it.
     */
    public suspend fun setNetworkQualityListener(
        listener: RealtimeNetworkQualityListener?,
    )

    /**
     * Sets the uplink video statistics listener.
     *
     * Callbacks run on the main thread. The listener remains registered after disconnection.
     *
     * @param listener The main-thread uplink statistics handler, or null to remove it.
     */
    public suspend fun setUplinkVideoStatisticsListener(
        listener: UplinkVideoStatisticsListener?,
    )

    /**
     * Sets the downlink video statistics listener.
     *
     * Callbacks run on the main thread. The listener remains registered after disconnection.
     *
     * @param listener The main-thread downlink statistics handler, or null to remove it.
     */
    public suspend fun setDownlinkVideoStatisticsListener(
        listener: DownlinkVideoStatisticsListener?,
    )

    /**
     * Sets the device performance alarm listener.
     *
     * @param listener The device performance alarm handler, or null to remove it.
     */
    public suspend fun setPerformanceAlarmListener(
        listener: RealtimePerformanceAlarmListener?,
    )

    /**
     * Sets the local media preview volume.
     *
     * The setting is retained after closing and creating local media again.
     *
     * @param volume The preview volume, in the range `0...1`.
     */
    public suspend fun setLocalAudioVolume(
        volume: Float,
    )

    /**
     * Sets the remote audio playback volume.
     *
     * The setting is retained after closing. Creating a camera or image source resets it to 0;
     * creating a video source resets it to 1. Call this method after creating the local stream to
     * override its initial volume.
     *
     * @param volume The remote playback volume, in the range `0...1`.
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
     * Creates a local camera stream using the model's default video format.
     *
     * The microphone is disabled by default; your app must obtain required permissions first.
     *
     * @param position The camera position; defaults to `CameraPosition.FRONT`.
     * @param useMicrophone Whether to use the microphone; requires recording permission and defaults to
     * false.
     * @return A media stream containing the local camera video track.
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
     * @return A media stream containing the local camera video track.
     */
    public suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean = false,
    ): RealtimeMediaStream

    /**
     * Stops the local camera stream.
     *
     * Other input sources are unaffected.
     */
    public suspend fun stopLocalCameraStream()

    /**
     * Creates a continuous video frame stream from encoded image data.
     *
     * The stream retains the media aspect ratio and uses the model's default frame rate. Dimensions
     * respect model pixel-area limits and four-pixel alignment; the generation canvas is selected
     * separately.
     *
     * @param imageData Encoded image file bytes, not raw pixels.
     * @return A media stream containing the local image video track.
     */
    public suspend fun createLocalImageStream(
        imageData: ByteArray,
    ): RealtimeMediaStream

    /**
     * Creates a continuous video frame stream from an Android bitmap.
     *
     * The stream retains the media aspect ratio and uses the model's default frame rate. Dimensions
     * respect model pixel-area limits and four-pixel alignment; the generation canvas is selected
     * separately.
     *
     * @param bitmap The Android bitmap to use as local input.
     * @return A media stream containing the local image video track.
     */
    public suspend fun createLocalImageStream(
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
     * @return A media stream containing the local image video track.
     */
    public suspend fun createLocalImageStream(
        uri: Uri,
    ): RealtimeMediaStream

    /**
     * Stops the local image stream.
     *
     * Other input sources are unaffected.
     */
    public suspend fun stopLocalImageStream()

    /**
     * Creates a looping audio and video stream from a local video URI.
     *
     * Your app must hold read permission. The stream retains the video's display aspect ratio and
     * uses the model's default frame rate. Dimensions respect model pixel-area limits and
     * four-pixel alignment; the generation canvas is resolved separately.
     *
     * @param uri A media URI your app can read.
     * @return A media stream containing the local file video track.
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
     * @return A media stream containing the local preview video track.
     */
    public suspend fun createNetworkVideoStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat,
        sampleMethod: RealtimeVideoSampleMethod = RealtimeVideoSampleMethod.TIME,
        onFinish: (() -> Unit)? = null,
    ): RealtimeMediaStream

    /**
     * Binds an external video source with optional audio for realtime generation.
     *
     * Your app owns camera or player startup, shutdown, and release. Submit orientation-corrected
     * RGBA matching source.videoFormat; the SDK copies and resizes pixels for preview and generation.
     * Without videoFormat, source dimensions and frame rate apply, subject to media-size alignment.
     * The sink continues accepting preview frames while disconnected. Optional PCM is converted to
     * 48 kHz mono, 10 ms packets. Stop and replacement invalidate old sinks before releasing bindings.
     *
     * @param source The external media source whose lifecycle your app owns.
     * @param videoFormat Desired processed dimensions, frame rate, and uplink encoding settings;
     * null uses the source format. Submitted pixels always match the original source dimensions.
     * @return A local stream for SDK preview and generation.
     */
    public suspend fun createExternalVideoStream(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream

    /**
     * Unbinds external video and preview without releasing your app's capture resources.
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
     * @param videoFormat Desired processed dimensions, frame rate, and uplink encoding settings;
     * null uses the replacement source format. Submitted pixels retain original source dimensions.
     * @param targetSource The suspending callback preparing and returning the replacement source.
     * @return The replacement local stream and any available remote result stream.
     */
    public suspend fun replaceExternalVideoStream(
        videoFormat: RealtimeVideoFormat? = null,
        targetSource: suspend () -> RealtimeExternalVideoSource,
    ): RealtimeStreamReplacement

    /**
     * Stops the local file video stream.
     *
     * Other input sources are unaffected.
     */
    public suspend fun stopLocalVideoStream()

    /**
     * Switches between the front and rear cameras.
     *
     * During generation, XmaxSDK stops the current task and resumes with cached conditions after
     * switching.
     *
     * @return The local stream with its existing video track and updated camera position.
     */
    public suspend fun switchCamera(): RealtimeMediaStream

    /**
     * Establishes a realtime connection using a local stream created by this manager.
     *
     * @param localStream The active local media stream owned by this manager.
     * @return The connection's remote stream, which can be bound to a result view.
     */
    public suspend fun connect(
        localStream: RealtimeMediaStream,
    ): RealtimeMediaStream

    /**
     * Disconnects realtime while preserving the local media preview.
     *
     * Generation ends and the connection is released. The final state is READY when local media
     * remains, or IDLE otherwise.
     */
    public suspend fun disconnect()

    /**
     * Disconnects for the specified reason while preserving available local media.
     *
     * @param reason The reason for disconnection.
     */
    public suspend fun disconnect(
        reason: RealtimeReason,
    )

    /**
     * Starts generation or updates conditions for the active task.
     *
     * Requires an open connection. New tasks become GENERATING after result-stream confirmation and
     * first-frame readiness. Both overloads prefer the latest request: a new call cancels the
     * pending request with CancellationException without reporting a business failure. Disconnect
     * and close cancel pending generation without resuming it after cleanup.
     *
     * @param context The generation conditions. Required for the first request; null reuses cached
     * conditions thereafter.
     * @param faceRestoration Whether to restore the original face; defaults to false and is sent on every
     * call.
     */
    public suspend fun startGeneration(
        context: RealtimeContext?,
        faceRestoration: Boolean = false,
    )

    /**
     * Connects the current local stream if needed and starts generation.
     *
     * Local video file playback continues during connection and generation. An existing connection
     * is reused. Bind the returned remote stream to a realtime video view to display results.
     * Pending requests follow the same latest-request cancellation rules as the conditions-only
     * overload.
     *
     * @param localStream The active local media stream owned by this manager.
     * @param context The generation conditions. Required for the first request; null reuses cached
     * conditions thereafter.
     * @param faceRestoration Whether to restore the original face; defaults to false and is sent on every
     * call.
     * @return The remote media stream for the current RTC connection.
     */
    public suspend fun startGeneration(
        localStream: RealtimeMediaStream,
        context: RealtimeContext?,
        faceRestoration: Boolean = false,
    ): RealtimeMediaStream

    /**
     * Closes the realtime lifecycle and releases the connection, local media, and RTC engine.
     *
     * Concurrent calls await the same cleanup task. Listeners remain registered, and local streams
     * can be created again after closing completes.
     */
    public suspend fun close()
}
