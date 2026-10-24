//
//  XmaxRealtimeManager.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.XmaxRealtimeLifecycleManager.OperationKind
import ai.xmax.sdk.XmaxRealtimeLifecycleManager.TerminationScope
import ai.xmax.sdk.media.MediaControlling
import ai.xmax.sdk.service.network.ApiServicing
import androidx.compose.ui.unit.IntSize
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext

/**
 * Implements the public realtime API and sequences media, connection, and generation workflows.
 *
 * @param components The assembled managers, controllers, services, and timing recorder.
 * @param dispatcher The dispatcher for automatic quality adjustments; defaults to `Dispatchers.Default`.
 */
internal class XmaxRealtimeManager(
    /**
     * The realtime configuration supplied at creation.
     */
    override val options: RealtimeConfiguration,

    components: XmaxRealtimeComponents,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,

    /**
     * The monotonic clock returning milliseconds for quality adjustment intervals.
     */
    private val qualityNow: () -> Long = { System.nanoTime() / 1_000_000 },
) : XmaxRealtimeManaging {

    // Dependencies

    /**
     * The lifecycle owner that admits operations and merges termination requests.
     */
    private val lifecycleManager = components.lifecycleManager

    /**
     * The manager that owns the server session and RTC connection.
     */
    private val connectionManager = components.connectionManager

    /**
     * The manager that sends generation requests and confirms result streams.
     */
    private val generationManager = components.generationManager

    /**
     * The manager that logs and forwards media and connection failures.
     */
    private val errorManager = components.errorManager

    /**
     * The controller that manages local media, preview, and interactions.
     */
    private val mediaController = components.mediaController

    /**
     * The controller that manages remote tracks, first-frame readiness, and rendering.
     */
    private val renderController = components.renderController

    /**
     * The controller that manages RTC connections, signaling, and transport quality.
     */
    private val streamController = components.streamController

    /**
     * The service that resolves media dimensions and model input constraints.
     */
    private val mediaService = components.mediaService

    /**
     * The recorder for generation startup stage durations.
     */
    private val timing = components.timing

    /**
     * Creates a realtime manager using the default SDK components.
     *
     * @param options The realtime configuration supplied at creation.
     * @param context The Android context used for system services and media resources.
     * @param apiService The client for server API requests.
     */
    constructor(
        options: RealtimeConfiguration,
        context: Context,
        apiService: ApiServicing,
    ) :
        this(
            options,
            XmaxRealtimeFactory.make(
                options,
                context,
                apiService,
            ),
        )

    // State

    /**
     * The coroutine scope for automatic quality adjustments.
     */
    private val qualityScope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * The revision used to reject stale quality adjustments.
     */
    private val qualityRevision = AtomicLong()

    /**
     * Whether an adaptive quality request is already pending.
     */
    private val qualityPending = AtomicBoolean(false)

    /**
     * The current connection and generation state.
     */
    override val currentState: RealtimeState get() = lifecycleManager.currentState

    /**
     * The camera's resolved media format, or null for other input sources.
     */
    override val currentCameraVideoFormat: RealtimeVideoFormat?
        get() = mediaController.currentTrack?.takeIf { it.position != null }?.videoFormat

    /**
     * The preset matching current camera dimensions, independent of frame rate or encoding level.
     */
    override val currentCameraPreset: RealtimeCameraPreset?
        get() = currentCameraVideoFormat?.let { format ->
            RealtimeCameraPreset.entries.firstOrNull {
                it.size.width == format.width && it.size.height == format.height
            }
        }

    /**
     * The uplink level successfully applied to the current connection.
     */
    override val uplinkQualityLevel: RealtimeQualityLevel?
        get() = currentQualityLevel(downlink = false)

    /**
     * The downlink level successfully submitted for the current connection.
     */
    override val downlinkQualityLevel: RealtimeQualityLevel?
        get() = currentQualityLevel(downlink = true)

    /**
     * Reads the connected session's quality level without exposing stale configuration during disconnection.
     *
     * @param downlink Whether to read the downlink level.
     * @return The committed level, or null if this direction has no active configuration.
     */
    private fun currentQualityLevel(
        downlink: Boolean,
    ): RealtimeQualityLevel? {
        val state = currentState.connectionState
        if (state != RealtimeConnectionState.CONNECTED && state != RealtimeConnectionState.GENERATING) return null

        if (downlink && currentState.taskId == null) return null

        val configuration = streamController.qualityConfiguration ?: return null
        val level = if (downlink) configuration.downlinkLevel else configuration.uplinkLevel
        return level?.let { value -> RealtimeQualityLevel.entries.first { it.rawValue == value } }
    }

    /**
     * Changes the uplink encoding level while allowing subsequent automatic adaptation.
     *
     * @param level The requested uplink quality level.
     */
    override suspend fun setUplinkQualityLevel(
        level: RealtimeQualityLevel,
    ) {
        setQualityLevel(
            level,
            downlink = false,
        )
    }

    /**
     * Changes the downlink output level while allowing subsequent automatic adaptation.
     *
     * @param level The requested downlink quality level.
     */
    override suspend fun setDownlinkQualityLevel(
        level: RealtimeQualityLevel,
    ) {
        setQualityLevel(
            level,
            downlink = true,
        )
    }

    /**
     * Changes the requested uplink or downlink quality level.
     *
     * @param level The requested transport quality level.
     * @param downlink Whether to change downlink output rather than uplink encoding.
     */
    private suspend fun setQualityLevel(
        level: RealtimeQualityLevel,
        downlink: Boolean,
    ) {
        lifecycleManager.run(OperationKind.CONFIGURATION) { token ->
            token.ensureCurrent()
            val state = currentState
            val taskId = state.taskId
            if (state.connectionState != RealtimeConnectionState.GENERATING || taskId == null) {
                throw invalid("Quality levels can only be changed while generating")
            }

            val configuration = streamController.qualityConfiguration
                ?: throw invalid("No quality configuration is available")
            if (!downlink && configuration.uplinkLevel == null) {
                throw invalid("No uplink quality configuration is available")
            }

            qualityRevision.incrementAndGet()
            connectionManager.applyQualityLevel(
                level.rawValue,
                downlink,
                taskId,
            ) {
                token.ensureCurrent()
                val current = currentState
                val ownsTask = current.taskId == taskId
                if (!ownsTask || current.connectionState != RealtimeConnectionState.GENERATING) {
                    throw CancellationException("Quality adjustment belongs to an inactive generation")
                }
            }
        }
    }

    /**
     * The local preview volume, in the range `0...1`.
     */
    override val localAudioVolume: Float get() = mediaController.localAudioVolume

    /**
     * The remote generated audio volume, in the range `0...1`.
     */
    override val remoteAudioVolume: Float get() = streamController.remoteAudioVolume

    /**
     * Sets the realtime state listener.
     *
     * The current snapshot and subsequent changes are delivered asynchronously on the main thread.
     *
     * @param listener The state change handler, or null to remove the listener.
     */
    override suspend fun setStateListener(
        listener: RealtimeStateListener?,
    ) {
        lifecycleManager.setStateListener(listener)
    }

    /**
     * Sets the uplink video statistics listener.
     *
     * The listener remains registered after disconnection.
     *
     * @param listener The main-thread statistics handler, or null to remove it.
     */
    override suspend fun setUplinkVideoStatisticsListener(
        listener: UplinkVideoStatisticsListener?,
    ) {
        streamController.setUplinkVideoStatisticsListener(listener)
    }

    /**
     * Sets the downlink video statistics listener.
     *
     * The listener remains registered after disconnection.
     *
     * @param listener The main-thread statistics handler, or null to remove it.
     */
    override suspend fun setDownlinkVideoStatisticsListener(
        listener: DownlinkVideoStatisticsListener?,
    ) {
        streamController.setDownlinkVideoStatisticsListener(listener)
    }

    /**
     * Sets serialized remote audio/video output without changing playback or volume.
     *
     * Owned PCM16 and I420 packets share a stream identity and presentation timeline. Removing the
     * listener invalidates queued events; an executing callback may finish. Registration survives close.
     *
     * @param listener The audio/video packet handler, or null to stop output.
     */
    override suspend fun setRemoteMediaFrameListener(
        listener: RealtimeMediaFrameListener?,
    ) {
        renderController.setRemoteMediaFrameListener(listener)
    }

    /**
     * Sets the network quality listener.
     *
     * @param listener The uplink and downlink quality handler, or null to remove it.
     */
    override suspend fun setNetworkQualityListener(
        listener: RealtimeNetworkQualityListener?,
    ) {
        streamController.setNetworkQualityListener(listener)
    }

    /**
     * Sets the device performance alarm listener.
     *
     * @param listener The device performance alarm handler, or null to remove it.
     */
    override suspend fun setPerformanceAlarmListener(
        listener: RealtimePerformanceAlarmListener?,
    ) {
        streamController.setPerformanceAlarmListener(listener)
    }

    /**
     * Sets the local media preview volume.
     *
     * The setting is retained after closing and creating local media again.
     *
     * @param volume The preview volume, in the range `0...1`.
     */
    override suspend fun setLocalAudioVolume(
        volume: Float,
    ) {
        execute(OperationKind.SETTING) { _ ->
            validateAudioVolume(volume)
            mediaController.setLocalAudioVolume(volume)
        }
    }

    /**
     * Sets the remote audio playback volume.
     *
     * The setting is retained before connection or subscription and applied before remote audio playback.
     * Call this method after creating the local stream to override its initial volume.
     *
     * @param volume The remote playback volume, in the range `0...1`.
     */
    override suspend fun setRemoteAudioVolume(
        volume: Float,
    ) {
        execute(OperationKind.SETTING) { _ ->
            validateAudioVolume(volume)
            streamController.setRemoteAudioVolume(volume)
        }
    }

    /**
     * Creates a local camera stream and starts its preview.
     *
     * Bind the returned track to a preview view. The state becomes [RealtimeConnectionState.READY] after
     * the preview receives a valid frame. Your app must obtain camera permission before calling this method.
     *
     * @param videoFormat The camera capture and encoding format, subject to the model's constraints.
     * @param position The camera position to use initially.
     * @param useMicrophone Whether to capture microphone audio while connected, without local playback.
     * Your app must obtain recording permission before enabling it.
     * @return A media stream containing the local camera video track.
     */
    override suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean,
    ): RealtimeMediaStream = mediaOperation(
        sourceRemoteAudioVolume = 0f,
        waitForCameraPreview = true,
    ) {
        it.createLocalCameraStream(
            videoFormat,
            position,
            useMicrophone,
        )
    }

    /**
     * Updates a ready, disconnected camera format while preserving stream and view bindings.
     *
     * @param videoFormat The supported camera dimensions, frame rate, and encoding settings.
     */
    override suspend fun updateCameraFormat(
        videoFormat: RealtimeVideoFormat,
    ) {
        updateCameraFormat { videoFormat }
    }

    /**
     * Updates camera dimensions while preserving frame rate and encoding settings.
     *
     * @param preset The target resolution preset supported by the model.
     */
    override suspend fun updateCameraFormat(
        preset: RealtimeCameraPreset,
    ) {
        updateCameraFormat { current ->
            current.copy(
                width = preset.size.width,
                height = preset.size.height,
            )
        }
    }

    /**
     * Updates the camera format using its current settings.
     *
     * @param resolve A function deriving the requested format from the current camera format.
     */
    private suspend fun updateCameraFormat(
        resolve: (RealtimeVideoFormat) -> RealtimeVideoFormat,
    ) {
        execute(OperationKind.MEDIA) { token ->
            ensureLocalMediaCanChange()
            val current = currentCameraVideoFormat
            if (currentState.connectionState != RealtimeConnectionState.READY || current == null) {
                throw invalid("Camera preview must be ready before updating its format")
            }

            token.ensureCurrent()
            mediaController.updateCameraFormat(resolve(current))
            token.ensureCurrent()
        }
    }

    /**
     * Creates a continuous video frame stream from encoded image data.
     *
     * The stream retains the media aspect ratio; the model selects the generation canvas separately.
     *
     * @param imageData Encoded image data in a supported format, such as JPEG or PNG.
     * @return A media stream containing the local image video track.
     */
    override suspend fun createLocalImageStream(
        imageData: ByteArray,
    ): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 0f) { it.createLocalImageStream(imageData) }

    /**
     * Creates a continuous video frame stream from an Android bitmap.
     *
     * The stream retains the media aspect ratio; the model selects the generation canvas separately.
     *
     * @param bitmap The Android bitmap to use as local input.
     * @return A media stream containing the local image video track.
     */
    override suspend fun createLocalImageStream(
        bitmap: Bitmap,
    ): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 0f) { it.createLocalImageStream(bitmap) }

    /**
     * Creates a continuous video frame stream from a local image URI.
     *
     * The stream retains the media aspect ratio; the model selects the generation canvas separately.
     *
     * @param uri The local image URI for which your app holds read permission.
     * @return A media stream containing the local image video track.
     */
    override suspend fun createLocalImageStream(
        uri: Uri,
    ): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 0f) { it.createLocalImageStream(uri) }

    /**
     * Creates a looping audio and video stream from a local video URI.
     *
     * The stream retains the video's display aspect ratio and uses the model's default frame rate.
     * The generation canvas is resolved separately.
     *
     * @param uri The local video URI for which your app holds read permission.
     * @return A media stream containing the local file video track.
     */
    override suspend fun createLocalVideoStream(
        uri: Uri,
    ): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 1f) { it.createLocalVideoStream(uri) }

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
     * @return A media stream containing the local preview video track.
     */
    override suspend fun createNetworkVideoStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat,
        sampleMethod: RealtimeVideoSampleMethod,
        onFinish: (() -> Unit)?,
    ): RealtimeMediaStream = mediaOperation(sourceRemoteAudioVolume = 1f) {
        it.createNetworkVideoStream(
            uri,
            videoFormat,
            sampleMethod,
            onFinish,
        )
    }

    /**
     * Binds an external video source and resolves its media format.
     *
     * Your app owns camera or player startup, shutdown, and release. Submit orientation-corrected
     * RGBA matching source.videoFormat; the SDK copies and resizes pixels for preview and generation.
     * The source can also supply PCM audio and its own native preview.
     *
     * @param source The external media source whose lifecycle your app owns.
     * @param videoFormat Desired processed dimensions, frame rate, and uplink encoding settings;
     * null uses the source format. Submitted pixels retain original source dimensions.
     * @return A local stream for SDK preview and generation.
     */
    override suspend fun createExternalVideoStream(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 1f) { it.createExternalVideoStream(
            source,
            videoFormat,
        ) }

    /**
     * Unbinds the external video source.
     *
     * Disconnect realtime first. Your app remains responsible for stopping its camera or player.
     */
    override suspend fun stopExternalVideoStream() { mediaOperation { it.stopExternalVideoStream() } }

    /**
     * Replaces the external video source and resumes any active or pending generation request.
     *
     * Compatible media formats reuse the RTC connection; changed formats reconnect with the retained
     * generation conditions. Replacing a preview-only source does not start generation.
     *
     * @param videoFormat Desired processed dimensions, frame rate, and uplink encoding settings;
     * null uses the replacement source format. Submitted pixels retain original source dimensions.
     * @param targetSource The suspending callback preparing and returning the replacement source.
     * @return The replacement local stream and any available remote result stream.
     */
    override suspend fun replaceExternalVideoStream(
        videoFormat: RealtimeVideoFormat?,
        targetSource: suspend () -> RealtimeExternalVideoSource,
    ): RealtimeStreamReplacement {
        // Preserve the caller's dispatcher; cancellation is owned by this SDK replacement operation.
        val preparationContext = currentCoroutineContext().minusKey(Job)

        return execute(OperationKind.REPLACEMENT) { token ->
            // Validate input and retain generation conditions for cancellation takeover or reconnection.
            if (!mediaController.isExternalVideo) {
                throw invalid("The current local media source is not an external video")
            }

            token.inheritGenerationContext(
                generationManager.cachedContext,
                generationManager.cachedFaceRestoration,
            )
            token.setFailureScope(TerminationScope.CONNECTION)

            // Stop the old task while retaining the room; the new media format determines whether reconnection is necessary.
            generationManager.stop(currentState.taskId.orEmpty())

            token.commit(
                RealtimeState(
                    if (connectionManager.currentSessionId.isNotEmpty()) {
                        RealtimeConnectionState.CONNECTED
                    } else {
                        RealtimeConnectionState.READY
                    },
                    sessionId = connectionManager.currentSessionId.takeIf(String::isNotEmpty),
                ),
            )

            // Unbind old frames before asking your app to switch its player and return the new source.
            val local = mediaController.replaceExternalVideoStream(videoFormat) {
                withContext(preparationContext) {
                    targetSource()
                }.also {
                    token.ensureCurrent()
                }
            }

            token.ensureCurrent()

            // Compatible media formats reuse the room; changed formats reconnect while retaining the engine and generation conditions.
            val format = checkNotNull(local.videoTrack?.videoFormat)

            if (
                connectionManager.currentSessionId.isNotEmpty() &&
                !connectionManager.acceptsInput(
                    format,
                    mediaController.hasAudio,
                )
            ) {
                connectionManager.disconnect()
                token.ensureCurrent()
                token.commit(RealtimeState(RealtimeConnectionState.READY))
            }

            // A preview-only replacement returns a local stream without starting generation.
            if (!token.resumeGeneration) {
                return@execute RealtimeStreamReplacement(
                    local,
                    null,
                )
            }

            // Resume an existing or pending generation request with its retained prompt and references.
            val remote = connectionManager.currentRemoteStream
                ?: performConnect(
                    token,
                    local,
                    resetGeneration = false,
                )

            timing.measure {
                performStartGeneration(
                    token,
                    token.requestedContext,
                    token.requestedFaceRestoration ?: generationManager.cachedFaceRestoration,
                )
            }

            RealtimeStreamReplacement(
                local,
                remote,
            )
        }
    }

    /**
     * Stops the local camera stream.
     *
     * Disconnect realtime first. Other source types are left unchanged.
     */
    override suspend fun stopLocalCameraStream() { mediaOperation { it.stopLocalCameraStream() } }

    /**
     * Stops the local image stream.
     *
     * Disconnect realtime first. Other source types are left unchanged.
     */
    override suspend fun stopLocalImageStream() { mediaOperation { it.stopLocalImageStream() } }

    /**
     * Stops the local file video stream.
     *
     * Disconnect realtime first. Other source types are left unchanged.
     */
    override suspend fun stopLocalVideoStream() { mediaOperation { it.stopLocalVideoStream() } }

    /**
     * Creates or stops local media and publishes the resulting preview state.
     *
     * Local media changes require disconnection. Camera readiness is updated after the first valid preview
     * frame; failures after media changes release the affected realtime resources.
     *
     * @param sourceRemoteAudioVolume The new source's default remote volume, or null to retain the current
     * setting.
     * @param waitForCameraPreview Whether preview readiness determines when the camera becomes ready.
     * @param action The media creation or stop operation.
     * @return The operation's successful result.
     */
    private suspend fun <T> mediaOperation(
        sourceRemoteAudioVolume: Float? = null,
        waitForCameraPreview: Boolean = false,
        action: suspend (MediaControlling) -> T,
    ): T =
        execute(OperationKind.MEDIA) { token ->
            ensureLocalMediaCanChange()
            if (sourceRemoteAudioVolume != null) {
                token.commit(RealtimeState(RealtimeConnectionState.PREPARING))
            } else {
                token.setFailureScope(TerminationScope.ALL)
            }

            val result = action(mediaController)
            token.ensureCurrent()
            token.setFailureScope(TerminationScope.ALL)
            sourceRemoteAudioVolume?.let { volume ->
                streamController.setRemoteAudioVolume(volume)
            }
            if (!waitForCameraPreview) {
                token.complete(
                    RealtimeState(
                        if (mediaController.currentTrack == null) {
                            RealtimeConnectionState.IDLE
                        } else {
                            RealtimeConnectionState.READY
                        },
                    ),
                )
            }

            result
        }

    /**
     * Switches between the front and rear cameras.
     *
     * During generation, XmaxSDK stops the current task, switches cameras, and resumes with cached
     * conditions. The RTC connection remains open. Switching is unavailable while connection or generation
     * startup is in progress.
     *
     * @return The local stream with its existing video track and updated camera position.
     */
    override suspend fun switchCamera(): RealtimeMediaStream =
        execute(OperationKind.SWITCH) { token ->
            val wasGenerating = currentState.connectionState == RealtimeConnectionState.GENERATING
            if (wasGenerating) {
                token.setFailureScope(TerminationScope.CONNECTION)
                generationManager.stop(currentState.taskId.orEmpty())
                token.commit(
                    currentState.copy(
                        connectionState = RealtimeConnectionState.CONNECTED,
                        taskId = null,
                    ),
                )
            }

            val stream = mediaController.switchCamera()
            token.ensureCurrent()
            if (wasGenerating) {
                delay(500L)
                timing.measure { performStartGeneration(
                    token,
                    null,
                    generationManager.cachedFaceRestoration,
                ) }
            }

            stream
        }

    /**
     * Establishes a realtime connection using a local stream created by this manager.
     *
     * @param localStream The active local media stream owned by this manager.
     * @return The connection's remote stream, which can be bound to a result view.
     */
    override suspend fun connect(
        localStream: RealtimeMediaStream,
    ): RealtimeMediaStream =
        execute(OperationKind.CONNECTION) { token -> performConnect(
            token,
            localStream,
            completesOperation = true,
        ) }

    /**
     * Establishes a realtime connection for the local stream.
     *
     * @param token The validity token for the connection operation.
     * @param localStream The active local stream created by this manager.
     * @param resetGeneration Whether to clear cached generation conditions; defaults to true.
     * @param completesOperation Whether to complete the operation when connected or leave it active for
     * generation startup.
     * @return The remote media stream for the session.
     */
    private suspend fun performConnect(
        token: XmaxRealtimeLifecycleManager.Token,
        localStream: RealtimeMediaStream,
        resetGeneration: Boolean = true,
        completesOperation: Boolean = false,
    ): RealtimeMediaStream {
        ensureLocalMediaCanChange()
        val videoFormat = localStream.videoTrack?.videoFormat
        if (videoFormat == null || !mediaController.owns(localStream)) throw invalid("The local stream must be created and started by this realtime manager")

        token.setFailureScope(TerminationScope.CONNECTION)
        token.commit(RealtimeState(RealtimeConnectionState.CONNECTING))

        try {
            if (resetGeneration) generationManager.reset()
            connectionManager.prepareQuality(
                videoFormat,
                options,
                publishLocalMedia = mediaController.networkVideoReference == null,
            )
            mediaController.startMicrophoneCapture()
            token.ensureCurrent()
            val remote = connectionManager.connect(
                options.model,
                videoFormat,
                mediaController.hasAudio,
                publishLocalMedia = mediaController.networkVideoReference == null,
                isCurrent = { try { token.ensureCurrent(); true } catch (
                    _: CancellationException,
                ) { false } },
                onHeartbeatFailure = { sessionId, error ->
                    // An expired heartbeat must not terminate a later connection using these shared components.
                    if (connectionManager.currentSessionId == sessionId) {
                        errorManager.forward(
                            error,
                            TerminationScope.CONNECTION,
                            isCurrent = { connectionManager.currentSessionId == sessionId },
                        )
                    }
                },
            )
            currentCoroutineContext().ensureActive()
            val connected = RealtimeState(
                RealtimeConnectionState.CONNECTED,
                sessionId = connectionManager.currentSessionId,
            )
            if (completesOperation) token.complete(connected) else token.commit(connected)
            return remote
        } catch (
            error: Throwable,
        ) {
            cleanupAfterFailure(
                error,
                { connectionManager.disconnect() },
                { mediaController.stopMicrophoneCapture() },
            )
            throw error
        }
    }

    /**
     * Starts generation or updates conditions for the active task.
     *
     * Requires an open connection. New tasks become [RealtimeConnectionState.GENERATING] after result-stream
     * confirmation and first-frame readiness. Both overloads prefer the latest request: a new call cancels
     * the pending request with [CancellationException] without reporting a business failure.
     * [disconnect] and [close] cancel pending generation without resuming it after cleanup.
     *
     * @param context The generation conditions. Required for the first request; null reuses cached
     * conditions thereafter.
     * @param faceRestoration Whether to restore the original face.
     */
    override suspend fun startGeneration(
        context: RealtimeContext?,
        faceRestoration: Boolean,
    ) {
        execute(
            OperationKind.GENERATION,
            context,
            faceRestoration,
        ) { token ->
            measureStartup { performStartGeneration(
                token,
                context,
                faceRestoration,
            ) }
        }
    }

    /**
     * Connects the current local stream if needed and starts generation.
     *
     * Local video file playback continues during connection and generation. An existing connection is
     * reused. Bind the returned remote stream to a realtime video view to display results. Pending requests
     * follow the same latest-request cancellation rules as the conditions-only overload.
     *
     * @param localStream The active local media stream owned by this manager.
     * @param context The generation conditions. Required for the first request; null reuses cached
     * conditions thereafter.
     * @param faceRestoration Whether to restore the original face.
     * @return The remote media stream for the current RTC connection.
     */
    override suspend fun startGeneration(
        localStream: RealtimeMediaStream,
        context: RealtimeContext?,
        faceRestoration: Boolean,
    ): RealtimeMediaStream =
        execute(
            OperationKind.GENERATION,
            context,
            faceRestoration,
        ) { token ->
            measureStartup {
                if (!mediaController.owns(localStream)) throw invalid("The local stream must be created and started by this realtime manager")

                val remote = if (connectionManager.currentSessionId.isNotEmpty()) {
                    connectionManager.currentRemoteStream ?: throw XmaxError(
                        XmaxErrorCode.RTC_ERROR,
                        "Realtime connection has no remote stream",
                    )
                } else {
                    try {
                        // File video continues supplying frames, but local audio stops as soon as generation is requested.
                        mediaController.setLocalAudioPreviewMuted(true)
                        performConnect(
                            token,
                            localStream,
                        )
                    } catch (
                        error: Throwable,
                    ) {
                        cleanupAfterFailure(
                            error,
                            { mediaController.setLocalAudioPreviewMuted(false) },
                        )
                        throw error
                    }
                }
                performStartGeneration(
                    token,
                    context,
                    faceRestoration,
                )
                remote
            }
        }

    /**
     * Records startup time from the API call to the first generated frame.
     *
     * @param action The operation to perform.
     * @return The operation's successful result.
     */
    private suspend fun <T> measureStartup(
        action: suspend () -> T,
    ): T =
        if (currentState.connectionState == RealtimeConnectionState.GENERATING) action() else timing.measure(action)

    /**
     * Starts generation or updates the active task's conditions.
     *
     * @param token The token used to verify that the current operation remains valid.
     * @param context The requested conditions, or null to reuse the most recent successful conditions.
     * @param faceRestoration Whether to restore the original face.
     */
    private suspend fun performStartGeneration(
        token: XmaxRealtimeLifecycleManager.Token,
        context: RealtimeContext?,
        faceRestoration: Boolean,
    ) {
        val current = currentState
        val format = mediaController.currentVideoFormat
        if (connectionManager.currentSessionId.isEmpty() || format == null ||
            current.connectionState !in setOf(
                RealtimeConnectionState.CONNECTED,
                RealtimeConnectionState.GENERATING,
            )) {
            throw invalid("Realtime connection is not open")
        }

        val resolvedContext = (context ?: generationManager.cachedContext)?.let { supplied ->
            mediaController.networkVideoReference?.let { supplied.copy(referenceVideo = it) } ?: supplied
        }
        if ((mediaController.isLocalVideo || mediaController.isLocalImage) && resolvedContext?.referenceVideo != null) {
            throw invalid("Local image or video frame input cannot be combined with a server video URL")
        }

        val generationSize = mediaService.resolveGenerationSize(
            IntSize(
                format.width,
                format.height,
            ),
        )
        val generationFormat = format.copy(
            width = generationSize.width,
            height = generationSize.height,
        )
        val paddingContain = resolvedContext?.referenceVideo == null &&
            format.width.toLong() * generationFormat.height != format.height.toLong() * generationFormat.width
        token.commit(current)
        if (current.connectionState == RealtimeConnectionState.GENERATING && current.taskId != null) {
            generationManager.update(
                taskId = current.taskId,
                videoFormat = format,
                context = resolvedContext,
                faceRestoration = faceRestoration,
                generationVideoFormat = generationFormat,
                paddingContain = paddingContain,
                ensureCurrent = token::ensureCurrent,
            )
            return
        }

        generationManager.validateContext(resolvedContext)
        val target = connectionManager.prepareQuality(
            format,
            options,
            publishLocalMedia = mediaController.networkVideoReference == null,
        )
        token.setFailureScope(TerminationScope.CONNECTION)
        var taskId = ""

        try {
            mediaController.setLocalAudioPreviewMuted(true)
            taskId = generationManager.start(
                videoFormat = format,
                context = resolvedContext,
                faceRestoration = faceRestoration,
                targetVideoFormat = target,
                generationVideoFormat = generationFormat,
                paddingContain = paddingContain,
                ensureCurrent = token::ensureCurrent,
            )
            currentCoroutineContext().ensureActive()
            token.ensureCurrent()

            connectionManager.completeInitialQuality()
            token.complete(
                current.copy(
                    connectionState = RealtimeConnectionState.GENERATING,
                    taskId = taskId,
                ),
                onComplete = {
                    if (options.isAdaptiveQualityEnabled) {
                        streamController.setAdaptiveQualityListener { quality ->
                            adaptQuality(
                                quality,
                                taskId,
                            )
                        }
                    } else {
                        streamController.setAdaptiveQualityListener(null)
                    }
                    streamController.activateNetworkVideoCompletion(
                        mediaController.networkVideoFinishHandler?.let { onFinish ->
                            {
                                val snapshot = currentState
                                if (snapshot.taskId == taskId &&
                                    snapshot.connectionState == RealtimeConnectionState.GENERATING
                                ) onFinish()
                            }
                        },
                    )
                },
            )

            currentCoroutineContext()[RealtimeTiming.Attempt]?.finish(taskId)
        } catch (
            error: Throwable,
        ) {
            cleanupAfterFailure(
                error,
                { if (taskId.isNotEmpty()) generationManager.stop(taskId) },
                { mediaController.setLocalAudioPreviewMuted(false) },
            )
            throw error
        }
    }

    /**
     * Disconnects realtime while preserving the local media preview.
     *
     * Listeners remain registered after disconnection.
     */
    override suspend fun disconnect() {
        invalidateQualitySamples()
        lifecycleManager.disconnect()
    }

    /**
     * Disconnects for the specified reason while preserving available local media.
     *
     * Listeners remain registered after disconnection.
     *
     * @param reason The reason for disconnection.
     */
    override suspend fun disconnect(
        reason: RealtimeReason,
    ) {
        invalidateQualitySamples()
        lifecycleManager.disconnect(reason)
    }

    /**
     * Closes the realtime lifecycle and releases the connection, local media, and RTC engine.
     *
     * Concurrent calls await the same cleanup task. Listeners remain registered, and local streams can be
     * created again after closing completes.
     */
    override suspend fun close() {
        invalidateQualitySamples()
        lifecycleManager.terminate(TerminationScope.ALL)
    }

    /**
     * Runs a realtime operation with lifecycle admission, failure cleanup, and SDK error reporting.
     *
     * @param kind The kind of realtime operation.
     * @param context The conditions retained for generation or external-source replacement, if applicable.
     * @param faceRestoration Whether to restore the original face for a generation request.
     * @param action The operation receiving the current validity token.
     * @return The operation's successful result.
     */
    private suspend fun <T> execute(
        kind: OperationKind,
        context: RealtimeContext? = null,
        faceRestoration: Boolean = false,
        action: suspend (XmaxRealtimeLifecycleManager.Token) -> T,
    ): T {
        invalidateQualitySamples()
        return lifecycleManager.run(
            kind,
            failureScope = null,
            context = context,
            faceRestoration = faceRestoration,
        ) { token ->
            try {
                action(token)
            } catch (
                error: Throwable,
            ) {
                currentCoroutineContext().ensureActive()
                token.ensureCurrent()
                val resolved = XmaxError.from(error)
                XmaxLogger.realtime.warn(
                    message = {
                        "Realtime ${kind.name.lowercase()} failed: " +
                            ErrorMessageFormatter.format(resolved)
                    },
                )
                throw resolved
            }
        }
    }

    /**
     * Clears quality samples before foreground operations so stale samples cannot trigger later adaptation.
     */
    private fun invalidateQualitySamples() {
        qualityRevision.incrementAndGet()
        streamController.resetQualitySamples()
    }

    /**
     * Verifies that no realtime connection prevents local media changes.
     */
    private fun ensureLocalMediaCanChange() {
        if (connectionManager.currentSessionId.isNotEmpty() || currentState.connectionState in setOf(
            RealtimeConnectionState.CONNECTING,
            RealtimeConnectionState.CONNECTED,
            RealtimeConnectionState.GENERATING,
            RealtimeConnectionState.DISCONNECTING,
        )) throw invalid("Disconnect realtime before changing the local stream or opening another connection")
    }

    /**
     * Validates that playback volume is within 0 to 1.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    private fun validateAudioVolume(
        volume: Float,
    ) {
        if (!volume.isFinite() || volume !in 0f..1f) throw invalid("Audio volume must be between 0 and 1")
    }

    /**
     * Creates an invalid-configuration error.
     *
     * @param message The message to send or log.
     * @return An SDK invalid-configuration error.
     */
    private fun invalid(
        message: String,
    ) = XmaxError(
        XmaxErrorCode.INVALID_CONFIGURATION,
        message,
    )

    /**
     * Attempts automatic adaptation for the current task, skipping samples while busy.
     *
     * @param quality The sampled uplink and downlink network quality.
     * @param taskId The identifier of the task associated with this sample.
     */
    private fun adaptQuality(
        quality: RealtimeNetworkQuality,
        taskId: String,
    ) {
        val revision = qualityRevision.get()
        val state = currentState
        if (state.taskId != taskId || state.connectionState != RealtimeConnectionState.GENERATING || lifecycleManager.isBusy) {
            streamController.resetQualitySamples()
            return
        }

        if (!qualityPending.compareAndSet(
            false,
            true,
        )) {
            streamController.resetQualitySamples()
            return
        }

        val sampleAt = qualityNow()
        qualityScope.launch {
            /**
             * Discards samples only while their original generation and foreground revision remain current.
             */
            fun resetSamplesIfCurrent() {
                if (currentState.taskId == taskId && qualityRevision.get() == revision) {
                    streamController.resetQualitySamples()
                }
            }

            try {
                val accepted = lifecycleManager.runAdaptiveConfiguration { token ->
                    /**
                     * Rejects adjustments belonging to a replaced generation or foreground request.
                     */
                    fun ensureCurrent() {
                        token.ensureCurrent()
                        val current = currentState
                        if (current.taskId != taskId ||
                            qualityRevision.get() != revision || current.connectionState != RealtimeConnectionState.GENERATING
                        ) {
                            throw CancellationException("Adaptive quality belongs to an inactive generation")
                        }
                    }

                    ensureCurrent()
                    val change = streamController.proposeQuality(
                        quality,
                        sampleAt,
                    )
                    for ((candidate, downlink) in listOf(
                        change.uplink to false,
                        change.downlink to true,
                    )) {
                        ensureCurrent()
                        if (candidate == null) continue

                        try {
                            connectionManager.applyQualityLevel(
                                candidate.to,
                                downlink,
                                taskId,
                                reason = candidate.reason,
                                ensureCurrent = ::ensureCurrent,
                            )
                        } catch (
                            cancelled: CancellationException,
                        ) {
                            resetSamplesIfCurrent()
                            throw cancelled
                        } catch (
                            error: Throwable,
                        ) {
                            ensureCurrent()
                            XmaxLogger.realtime.warn(message = { "Quality adjustment failed: ${ErrorMessageFormatter.format(error)}" })
                        }
                    }
                }
                if (!accepted) resetSamplesIfCurrent()
            } catch (
                cancelled: CancellationException,
            ) {
                resetSamplesIfCurrent()
            } catch (
                error: Throwable,
            ) {
                resetSamplesIfCurrent()
                XmaxLogger.realtime.warn(message = { "Adaptive quality failed: ${ErrorMessageFormatter.format(error)}" })
            } finally {
                qualityPending.set(false)
            }
        }
    }

}
