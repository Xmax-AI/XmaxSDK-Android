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
import ai.xmax.sdk.render.video.RealtimeVideoFrameDispatcher
import ai.xmax.sdk.service.network.ApiServicing
import ai.xmax.sdk.service.media.MediaService
import androidx.compose.ui.unit.IntSize
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlin.math.roundToInt
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
 * Coordinates local media, server sessions, generation tasks, and remote presentation for the public realtime
 * API.
 * XmaxRealtimeLifecycleManager owns lifecycle state and operations; RealtimeCallbacks delivers app notifications.
 * Components are created on demand. Each runtime identifies its failure sources so callbacks from released
 * resources cannot affect a later connection.
 *
 * @param dispatcher The dispatcher for background operations; defaults to `Dispatchers.Default`.
 */
internal class XmaxRealtimeManager(
    /**
     * The model and realtime configuration.
     */
    override val options: RealtimeConfiguration,

    /**
     * The factory creating realtime components with failure callbacks and a frame dispatcher.
     */
    private val componentFactory: ((XmaxError) -> Unit, (XmaxError) -> Unit, RealtimeVideoFrameDispatcher) -> XmaxRealtimeComponents,

    /**
     * The dispatcher for app state and video frame callbacks.
     */
    private val callbacks: RealtimeCallbacks = RealtimeCallbacks(),

    dispatcher: CoroutineDispatcher = Dispatchers.Default,

    /**
     * The recorder for generation startup timing.
     */
    private val timing: RealtimeTiming = RealtimeTiming(),

    /**
     * The monotonic clock returning milliseconds for quality adjustment intervals.
     */
    private val qualityNow: () -> Long = { System.nanoTime() / 1_000_000 },

    /**
     * The manager routing current runtime failures to lifecycle termination.
     */
    private val errorManager: XmaxRealtimeErrorManager = XmaxRealtimeErrorManager(dispatcher),
) : XmaxRealtimeManaging {

    /**
     * Creates a realtime manager.
     *
     * @param options The model and realtime configuration.
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
            { onError, onMediaError, frames ->
                XmaxRealtimeFactory.make(
                    context,
                    apiService,
                    options.model,
                    onError,
                    onMediaError,
                    frames,
                )
            },
        )

    /**
     * The automatic quality adjustment job.
     */
    private val qualityScope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * The revision used to reject stale quality adjustments.
     */
    private val qualityRevision = AtomicLong()

    /**
     * The active runtime, created and released by serialized lifecycle operations.
     */
    @Volatile private var runtime: Runtime? = null

    /**
     * The persistent local preview volume.
     *
     * Volume settings survive media release; null uses the source default.
     */
    @Volatile private var configuredLocalAudioVolume: Float? = null

    /**
     * The app-configured downlink volume, or the default when unset.
     */
    @Volatile private var configuredRemoteAudioVolume: Float? = null

    /**
     * The lock protecting quality and statistics listener registrations.
     *
     * Protect only listener configuration and existing bindings; do not create components, operate media, or
     * call app code while holding this lock.
     */
    private val listenerLock = Any()

    /**
     * The network quality listener.
     */
    private var networkQualityListener: RealtimeNetworkQualityListener? = null

    /**
     * The device performance alarm listener.
     */
    private var performanceAlarmListener: RealtimePerformanceAlarmListener? = null

    /**
     * The uplink video statistics listener.
     */
    private var uplinkVideoStatisticsListener: UplinkVideoStatisticsListener? = null

    /**
     * The downlink video statistics listener.
     */
    private var downlinkVideoStatisticsListener: DownlinkVideoStatisticsListener? = null

    /**
     * The manager owning realtime operations and state.
     */
    private val lifecycleManager = XmaxRealtimeFactory.assemble(
        callbacks,
        dispatcher,
        errorManager,
        hasLocalMedia = { runtime?.components?.media?.currentTrack != null },
        cleanup = ::cleanup,
    )

    /**
     * The current media and generation state.
     */
    override val currentState: RealtimeState get() = lifecycleManager.currentState

    /**
     * The current camera media format, unaffected by transmission quality changes.
     */
    override val currentCameraVideoFormat: RealtimeVideoFormat?
        get() = runtime?.components?.media?.currentTrack?.takeIf { it.position != null }?.videoFormat

    /**
     * The preset matching the current camera dimensions.
     */
    override val currentCameraPreset: RealtimeCameraPreset?
        get() = currentCameraVideoFormat?.let { format ->
            RealtimeCameraPreset.entries.firstOrNull {
                it.size.width == format.width && it.size.height == format.height
            }
        }

    /**
     * The successfully applied uplink level for the current connection.
     */
    override val uplinkQualityLevel: RealtimeQualityLevel?
        get() = currentQualityLevel(downlink = false)

    /**
     * The successfully submitted downlink level for the current connection.
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
        val owner = runtime ?: return null
        val state = currentState.connectionState
        if (state != RealtimeConnectionState.CONNECTED && state != RealtimeConnectionState.GENERATING) return null

        if (downlink && owner.qualityTaskId == null) return null

        val configuration = owner.components.stream.qualityConfiguration ?: return null
        val level = if (downlink) configuration.downlinkLevel else configuration.uplinkLevel
        return level?.let { value -> RealtimeQualityLevel.entries.first { it.rawValue == value } }
    }

    /**
     * Adjusts uplink encoding quality during generation without disabling automatic adaptation.
     *
     * @param level The target uplink level.
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
     * Adjusts downlink output quality during generation without disabling automatic adaptation.
     *
     * @param level The target downlink level.
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
     * Serializes manual quality updates with generation changes and connection cleanup.
     *
     * @param level The target transmission quality level.
     * @param downlink Whether to adjust the downlink.
     */
    private suspend fun setQualityLevel(
        level: RealtimeQualityLevel,
        downlink: Boolean,
    ) {
        lifecycleManager.run(OperationKind.CONFIGURATION) { token ->
            token.ensureCurrent()
            val owner = runtime ?: throw invalid("Quality levels can only be changed while generating")
            val state = currentState
            val taskId = state.taskId
            if (state.connectionState != RealtimeConnectionState.GENERATING || taskId == null || owner.qualityTaskId != taskId) {
                throw invalid("Quality levels can only be changed while generating")
            }

            val configuration = owner.components.stream.qualityConfiguration
                ?: throw invalid("No quality configuration is available")
            if (!downlink && configuration.uplinkLevel == null) {
                throw invalid("No uplink quality configuration is available")
            }

            qualityRevision.incrementAndGet()
            owner.components.connection.applyQualityLevel(
                level.rawValue,
                downlink,
                taskId,
            ) {
                token.ensureCurrent()
                val current = currentState
                val ownsTask = runtime === owner && owner.qualityTaskId == taskId && current.taskId == taskId
                if (!ownsTask || current.connectionState != RealtimeConnectionState.GENERATING) {
                    throw CancellationException("Quality adjustment belongs to an inactive generation")
                }
            }
        }
    }

    /**
     * The current local preview playback volume.
     */
    override val localAudioVolume: Float get() = configuredLocalAudioVolume ?: 0.45f

    /**
     * The downlink playback volume from 0 to 1, defaulting to 1.
     */
    override val remoteAudioVolume: Float get() = configuredRemoteAudioVolume ?: 1f

    /**
     * Replaces the state listener and asynchronously delivers the current snapshot on the main thread; null
     * unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override suspend fun setStateListener(
        listener: RealtimeStateListener?,
    ) {
        callbacks.setStateListener(
            listener,
            currentState,
        )
    }

    /**
     * Sets the main-thread uplink statistics listener; null unregisters it. The listener survives
     * disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override suspend fun setUplinkVideoStatisticsListener(
        listener: UplinkVideoStatisticsListener?,
    ) {
        synchronized(listenerLock) {
            uplinkVideoStatisticsListener = listener
            runtime?.takeIf { it.listenersApplied }?.components?.stream?.setUplinkVideoStatisticsListener(listener)
        }
    }

    /**
     * Sets the main-thread downlink statistics listener; null unregisters it. The listener survives
     * disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override suspend fun setDownlinkVideoStatisticsListener(
        listener: DownlinkVideoStatisticsListener?,
    ) {
        synchronized(listenerLock) {
            downlinkVideoStatisticsListener = listener
            runtime?.takeIf { it.listenersApplied }?.components?.stream?.setDownlinkVideoStatisticsListener(listener)
        }
    }

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
    override suspend fun setRemoteVideoFrameListener(
        listener: RealtimeVideoFrameListener?,
    ) {
        callbacks.remoteVideoFrames.setListener(listener)
    }

    /**
     * Sets the RTC network quality listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override suspend fun setNetworkQualityListener(
        listener: RealtimeNetworkQualityListener?,
    ) {
        synchronized(listenerLock) {
            networkQualityListener = listener
            runtime?.takeIf { it.listenersApplied }?.components?.stream?.setNetworkQualityListener(listener)
        }
    }

    /**
     * Sets the RTC performance alarm listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override suspend fun setPerformanceAlarmListener(
        listener: RealtimePerformanceAlarmListener?,
    ) {
        synchronized(listenerLock) {
            performanceAlarmListener = listener
            runtime?.takeIf { it.listenersApplied }?.components?.stream?.setPerformanceAlarmListener(listener)
        }
    }

    /**
     * Sets local preview volume from 0 to 1; a successful setting survives close and media recreation.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    override suspend fun setLocalAudioVolume(
        volume: Float,
    ) {
        execute(OperationKind.SETTING) { _, c ->
            validateAudioVolume(volume)
            c.media.setLocalAudioVolume(volume)
            configuredLocalAudioVolume = volume
        }
    }

    /**
     * Sets remote playback volume from 0 to 1; a successful setting survives close.
     * Creating a camera or image source resets it to 0; creating a video source resets it to 1.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    override suspend fun setRemoteAudioVolume(
        volume: Float,
    ) {
        execute(OperationKind.SETTING) { _, c ->
            validateAudioVolume(volume)
            c.stream.setRemoteAudioVolume(volume)
            configuredRemoteAudioVolume = (volume * 100f).roundToInt() / 100f
        }
    }

    /**
     * Creates and starts camera input after your app obtains the required permissions. Input dimensions
     * follow the model's constraints.
     * The microphone is disabled by default; when enabled, capture starts on connection and stops on
     * disconnection without local playback.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param position The front or rear camera to use.
     * @param useMicrophone Whether to use the microphone; your app must obtain recording permission first.
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
     * Updates a ready, disconnected camera preview while retaining its track and view binding.
     *
     * @param videoFormat Model-supported dimensions, frame rate, and encoding settings.
     */
    override suspend fun updateCameraFormat(
        videoFormat: RealtimeVideoFormat,
    ) {
        updateCameraFormat { videoFormat }
    }

    /**
     * Changes camera resolution while preserving the current frame rate, bitrate, and encoding preference.
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
     * Resolves the new camera format from the current configuration within a serialized media operation.
     *
     * @param resolve Computes the requested format from the current one.
     */
    private suspend fun updateCameraFormat(
        resolve: (RealtimeVideoFormat) -> RealtimeVideoFormat,
    ) {
        execute(OperationKind.MEDIA) { token, c ->
            requireDisconnected(c)
            val current = currentCameraVideoFormat
            if (currentState.connectionState != RealtimeConnectionState.READY || current == null) {
                throw invalid("Camera preview must be ready before updating its format")
            }

            token.ensureCurrent()
            c.media.updateCameraFormat(resolve(current))
            token.ensureCurrent()
        }
    }

    /**
     * Decodes image bytes into a local stream that repeatedly supplies frames at the media's aspect ratio.
     * Uses orientation-corrected image dimensions, limited by model pixel area and aligned to four pixels,
     * with the model's default frame rate.
     * The model canvas is selected separately; the server fits the image and removes padding from output.
     *
     * @param imageData Encoded image file bytes, not raw pixels.
     */
    override suspend fun createLocalImageStream(
        imageData: ByteArray,
    ): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 0f) { it.createLocalImageStream(imageData) }

    /**
     * Creates a local image stream from a Bitmap while retaining its aspect ratio.
     * Uses Bitmap dimensions limited by model pixel area and four-pixel alignment, with the default frame
     * rate and a separately selected model canvas.
     *
     * @param bitmap The Android image to process or display.
     */
    override suspend fun createLocalImageStream(
        bitmap: Bitmap,
    ): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 0f) { it.createLocalImageStream(bitmap) }

    /**
     * Creates a local image stream from a readable URI; your app owns read permissions.
     * Uses orientation-corrected image dimensions, limited by model pixel area and aligned to four pixels,
     * with the model's default frame rate.
     * The model canvas is selected separately; the server fits the image and removes padding from output.
     *
     * @param uri A media URI your app can read.
     */
    override suspend fun createLocalImageStream(
        uri: Uri,
    ): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 0f) { it.createLocalImageStream(uri) }

    /**
     * Creates a local video stream from a URI, including its audio track when present.
     * Uses orientation-corrected media dimensions, limited by model pixel area and aligned to four pixels,
     * with the model's default frame rate.
     * The SDK selects a separate generation canvas; the server fits the media and removes padding from
     * output.
     *
     * @param uri A media URI your app can read.
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
    override suspend fun createExternalVideoStream(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 1f) { it.createExternalVideoStream(
            source,
            videoFormat,
        ) }

    /**
     * Unbinds external input after disconnection without closing your app's camera or player.
     */
    override suspend fun stopExternalVideoStream() { mediaOperation { it.stopExternalVideoStream() } }

    /**
     * One operation owns stopping, source replacement, and resumption, preventing stale frames or callbacks
     * from taking over new input.
     *
     * @param videoFormat Input dimensions, frame rate, and optional uplink encoding settings; null uses
     * source or parsed media defaults.
     * @param targetSource The suspending callback preparing and returning the replacement source.
     * @return The replacement local stream and any available remote result stream.
     */
    override suspend fun replaceExternalVideoStream(
        videoFormat: RealtimeVideoFormat?,
        targetSource: suspend () -> RealtimeExternalVideoSource,
    ): RealtimeStreamReplacement {
        // Preserve the caller's dispatcher; cancellation is owned by this SDK replacement operation.
        val preparationContext = currentCoroutineContext().minusKey(Job)

        return execute(OperationKind.REPLACEMENT) { token, c ->
            // Validate input and retain generation conditions for cancellation takeover or reconnection.
            if (!c.media.isExternalVideo) {
                throw invalid("The current local media source is not an external video")
            }

            token.inheritGenerationContext(
                c.generation.cachedContext,
                c.generation.cachedFaceRestoration,
            )
            token.setFailureScope(TerminationScope.CONNECTION)

            // Stop the old task while retaining the room; the new media format determines whether reconnection is necessary.
            c.generation.stop(currentState.taskId.orEmpty())

            token.commit(
                RealtimeState(
                    if (c.connection.currentSessionId.isNotEmpty()) {
                        RealtimeConnectionState.CONNECTED
                    } else {
                        RealtimeConnectionState.READY
                    },
                    sessionId = c.connection.currentSessionId.takeIf(String::isNotEmpty),
                ),
            )

            // Unbind old frames before asking your app to switch its player and return the new source.
            val local = c.media.replaceExternalVideoStream(videoFormat) {
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
                c.connection.currentSessionId.isNotEmpty() &&
                !c.connection.acceptsInput(
                    format,
                    c.media.hasAudio,
                )
            ) {
                c.connection.disconnect()
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
            val remote = c.connection.currentRemoteStream
                ?: connect(
                    token,
                    c,
                    local,
                    resetGeneration = false,
                )

            timing.measure {
                start(
                    token,
                    c,
                    token.requestedContext,
                    token.requestedFaceRestoration ?: c.generation.cachedFaceRestoration,
                )
            }

            RealtimeStreamReplacement(
                local,
                remote,
            )
        }
    }

    /**
     * Stops and releases the current camera source; does nothing for other source types.
     */
    override suspend fun stopLocalCameraStream() { mediaOperation { it.stopLocalCameraStream() } }

    /**
     * Stops and releases the current image source; does nothing for other source types.
     */
    override suspend fun stopLocalImageStream() { mediaOperation { it.stopLocalImageStream() } }

    /**
     * Stops the current video source and releases decoding and preview resources; does nothing for other
     * source types.
     */
    override suspend fun stopLocalVideoStream() { mediaOperation { it.stopLocalVideoStream() } }

    /**
     * Requires disconnection before local media changes; failures after preparation or resource shutdown
     * clean up all media.
     *
     * @param sourceRemoteAudioVolume The new source's default remote volume, or null to retain the current
     * setting.
     * @param waitForCameraPreview Whether to wait for camera preview readiness; defaults to false.
     * @param action The operation to perform.
     * @return The operation's successful result.
     */
    private suspend fun <T> mediaOperation(
        sourceRemoteAudioVolume: Float? = null,
        waitForCameraPreview: Boolean = false,
        action: suspend (MediaControlling) -> T,
    ): T =
        execute(OperationKind.MEDIA) { token, c ->
            requireDisconnected(c)
            if (sourceRemoteAudioVolume != null) {
                token.commit(RealtimeState(RealtimeConnectionState.PREPARING))
            } else {
                token.setFailureScope(TerminationScope.ALL)
            }

            val result = action(c.media)
            token.ensureCurrent()
            token.setFailureScope(TerminationScope.ALL)
            sourceRemoteAudioVolume?.let { volume ->
                c.stream.setRemoteAudioVolume(volume)
                configuredRemoteAudioVolume = volume
            }
            if (!waitForCameraPreview) {
                token.complete(
                    RealtimeState(
                        if (c.media.currentTrack == null) {
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
     * Switches cameras during generation by stopping the task, retaining the connection, and restarting with
     * cached conditions once the camera settles.
     */
    override suspend fun switchCamera(): RealtimeMediaStream =
        execute(OperationKind.SWITCH) { token, c ->
            val wasGenerating = currentState.connectionState == RealtimeConnectionState.GENERATING
            if (wasGenerating) {
                token.setFailureScope(TerminationScope.CONNECTION)
                c.generation.stop(currentState.taskId.orEmpty())
                token.commit(
                    currentState.copy(
                        connectionState = RealtimeConnectionState.CONNECTED,
                        taskId = null,
                    ),
                )
            }

            val stream = c.media.switchCamera()
            token.ensureCurrent()
            if (wasGenerating) {
                delay(500L)
                timing.measure { start(
                    token,
                    c,
                    null,
                    c.generation.cachedFaceRestoration,
                ) }
            }

            stream
        }

    /**
     * Connects an active local stream created by this manager and returns the remote stream for rendering.
     *
     * @param localStream The local input stream.
     */
    override suspend fun connect(
        localStream: RealtimeMediaStream,
    ): RealtimeMediaStream =
        execute(OperationKind.CONNECTION) { token, c -> connect(
            token,
            c,
            localStream,
            completesOperation = true,
        ) }

    /**
     * Validates stream ownership and commits CONNECTED only for the current operation; connection completion
     * does not start generation.
     *
     * @param token The lifecycle ownership token for this operation.
     * @param c The realtime components used by this operation.
     * @param localStream The local input stream.
     * @param resetGeneration Whether to clear cached generation conditions; defaults to true.
     * @param completesOperation Whether CONNECTED completes this operation or precedes generation startup.
     */
    private suspend fun connect(
        token: XmaxRealtimeLifecycleManager.Token,
        c: XmaxRealtimeComponents,
        localStream: RealtimeMediaStream,
        resetGeneration: Boolean = true,
        completesOperation: Boolean = false,
    ): RealtimeMediaStream {
        requireDisconnected(c)
        val videoFormat = localStream.videoTrack?.videoFormat
        if (videoFormat == null || !c.media.owns(localStream)) throw invalid("The local stream must be created and started by this realtime manager")

        token.setFailureScope(TerminationScope.CONNECTION)
        token.commit(RealtimeState(RealtimeConnectionState.CONNECTING))

        try {
            if (resetGeneration) c.generation.reset()
            c.connection.prepareQuality(
                videoFormat,
                options,
                publishLocalMedia = c.media.networkVideoReference == null,
            )
            c.media.startMicrophoneCapture()
            token.ensureCurrent()
            val owner = runtime
            val remote = c.connection.connect(
                options.model,
                videoFormat,
                c.media.hasAudio,
                publishLocalMedia = c.media.networkVideoReference == null,
                isCurrent = { try { token.ensureCurrent(); true } catch (
                    _: CancellationException,
                ) { false } },
                onHeartbeatFailure = { sessionId, error ->
                    // Check both runtime identity and session identity so an old heartbeat cannot terminate a later connection.
                    if (runtime === owner && c.connection.currentSessionId == sessionId) {
                        errorManager.forward(
                            error,
                            TerminationScope.CONNECTION,
                            isCurrent = { runtime === owner && c.connection.currentSessionId == sessionId },
                        )
                    }
                },
            )
            currentCoroutineContext().ensureActive()
            val connected = RealtimeState(
                RealtimeConnectionState.CONNECTED,
                sessionId = c.connection.currentSessionId,
            )
            if (completesOperation) token.complete(connected) else token.commit(connected)
            return remote
        } catch (
            error: Throwable,
        ) {
            cleanupAfterFailure(
                error,
                { c.connection.disconnect() },
                { c.media.stopMicrophoneCapture() },
            )
            throw error
        }
    }

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
     * @param faceRestoration Whether to enable face restoration through `face_replace`.
     */
    override suspend fun startGeneration(
        context: RealtimeContext?,
        faceRestoration: Boolean,
    ) {
        execute(
            OperationKind.GENERATION,
            context,
            faceRestoration,
        ) { token, c ->
            measureStartup { start(
                token,
                c,
                context,
                faceRestoration,
            ) }
        }
    }

    /**
     * Connects when necessary, then starts or updates generation and returns the remote stream; uses the
     * other overload's replacement semantics.
     *
     * @param localStream The local input stream.
     * @param context Generation prompts and reference media; null reuses cached conditions, which must exist
     * for the first request.
     * @param faceRestoration Whether to enable face restoration through `face_replace`.
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
        ) { token, c ->
            measureStartup {
                if (!c.media.owns(localStream)) throw invalid("The local stream must be created and started by this realtime manager")

                val remote = if (c.connection.currentSessionId.isNotEmpty()) {
                    c.connection.currentRemoteStream ?: throw XmaxError(
                        XmaxErrorCode.RTC_ERROR,
                        "Realtime connection has no remote stream",
                    )
                } else {
                    try {
                        // File video continues supplying frames, but local audio stops as soon as generation is requested.
                        c.media.setLocalAudioPreviewMuted(true)
                        connect(
                            token,
                            c,
                            localStream,
                        )
                    } catch (
                        error: Throwable,
                    ) {
                        cleanupAfterFailure(
                            error,
                            { c.media.setLocalAudioPreviewMuted(false) },
                        )
                        throw error
                    }
                }
                start(
                    token,
                    c,
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
     * Starts or updates generation and commits the generating state after a remote frame is ready to display.
     *
     * @param token The lifecycle ownership token for this operation.
     * @param c The realtime components used by this operation.
     * @param context Generation prompts and reference media; null reuses cached conditions, which must exist
     * for the first request.
     * @param faceRestoration Whether to enable face restoration through `face_replace`.
     */
    private suspend fun start(
        token: XmaxRealtimeLifecycleManager.Token,
        c: XmaxRealtimeComponents,
        context: RealtimeContext?,
        faceRestoration: Boolean,
    ) {
        val current = currentState
        val format = c.media.currentVideoFormat
        if (c.connection.currentSessionId.isEmpty() || format == null ||
            current.connectionState !in setOf(
                RealtimeConnectionState.CONNECTED,
                RealtimeConnectionState.GENERATING,
            )) {
            throw invalid("Realtime connection is not open")
        }

        val resolvedContext = (context ?: c.generation.cachedContext)?.let { supplied ->
            c.media.networkVideoReference?.let { supplied.copy(referenceVideo = it) } ?: supplied
        }
        if ((c.media.isLocalVideo || c.media.isLocalImage) && resolvedContext?.referenceVideo != null) {
            throw invalid("Local image or video frame input cannot be combined with a server video URL")
        }

        val generationSize = MediaService(options.model).resolveGenerationSize(
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
            c.generation.update(
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

        c.generation.validateContext(resolvedContext)
        val target = c.connection.prepareQuality(
            format,
            options,
            publishLocalMedia = c.media.networkVideoReference == null,
        )
        val owner = checkNotNull(runtime)
        token.setFailureScope(TerminationScope.CONNECTION)
        var taskId = ""

        try {
            c.media.setLocalAudioPreviewMuted(true)
            taskId = c.generation.start(
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

            owner.qualityTaskId = taskId
            c.connection.completeInitialQuality()
            token.complete(
                current.copy(
                    connectionState = RealtimeConnectionState.GENERATING,
                    taskId = taskId,
                ),
                onComplete = {
                    c.stream.activateNetworkVideoCompletion(
                        c.media.networkVideoFinishHandler?.let { onFinish ->
                            {
                                val snapshot = currentState
                                if (runtime?.components === c && snapshot.taskId == taskId &&
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
                { if (taskId.isNotEmpty()) c.generation.stop(taskId) },
                { c.media.setLocalAudioPreviewMuted(false) },
            )
            throw error
        }
    }

    /**
     * Ends generation and closes the session and RTC connection, retaining local media and listeners.
     */
    override suspend fun disconnect() {
        invalidateQualitySamples()
        lifecycleManager.disconnect()
    }

    /**
     * Ends generation and closes the session and RTC connection, retaining local media and listeners.
     *
     * @param reason The reason for the state transition or failure.
     */
    override suspend fun disconnect(
        reason: RealtimeReason,
    ) {
        invalidateQualitySamples()
        lifecycleManager.disconnect(reason)
    }

    /**
     * Cancels pending operations and waits for all realtime resources to be released; listeners remain
     * registered and the manager can be reused.
     */
    override suspend fun close() {
        invalidateQualitySamples()
        lifecycleManager.terminate(TerminationScope.ALL)
    }

    /**
     * Releases task, connection, or all media resources, attempting every independent cleanup step.
     *
     * @param target The resources to terminate.
     */
    private suspend fun cleanup(
        target: TerminationScope,
    ) {
        val owner = runtime ?: return
        owner.qualityTaskId = null
        owner.components.stream.resetQualitySamples()
        val c = owner.components
        cleanupResources(
            { if (target == TerminationScope.ALL) {
                synchronized(listenerLock) {
                    // Unbind old components first; registrations made during cleanup are retained for the next runtime.
                    owner.listenersApplied = false
                    c.stream.setAdaptiveQualityListener(null)
                    c.stream.setNetworkQualityListener(null)
                    c.stream.setPerformanceAlarmListener(null)
                    c.stream.setUplinkVideoStatisticsListener(null)
                    c.stream.setDownlinkVideoStatisticsListener(null)
                }
            } },
            { XmaxRealtimeFactory.cleanup(
                c,
                target,
                currentState.taskId.orEmpty(),
            ) },
            { if (target == TerminationScope.ALL) synchronized(listenerLock) { runtime = null } },
        )
    }

    /**
     * Admits operations and normalizes errors, using the operation token to determine failure cleanup.
     *
     * @param kind The operation or media source kind.
     * @param context Generation prompts and reference media; null reuses cached conditions, which must exist
     * for the first request.
     * @param faceRestoration Whether to enable face restoration through `face_replace`; defaults to false.
     * @param action The operation to perform.
     * @return The operation's successful result.
     */
    private suspend fun <T> execute(
        kind: OperationKind,
        context: RealtimeContext? = null,
        faceRestoration: Boolean = false,
        action: suspend (XmaxRealtimeLifecycleManager.Token, XmaxRealtimeComponents) -> T,
    ): T {
        invalidateQualitySamples()
        return lifecycleManager.run(
            kind,
            failureScope = null,
            context = context,
            faceRestoration = faceRestoration,
        ) { token ->
            try {
                action(
                    token,
                    components(),
                )
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
        runtime?.components?.stream?.resetQualitySamples()
    }

    /**
     * Returns the realtime components, creating them and restoring listeners and volumes when needed.
     *
     * @return Components sharing the same RTC manager.
     */
    private suspend fun components(): XmaxRealtimeComponents {
        val owner = runtime ?: Runtime().also { created ->
            created.components = componentFactory(
                { error -> errorManager.forward(
                    error,
                    TerminationScope.CONNECTION,
                    isCurrent = { runtime === created },
                ) },
                { error -> errorManager.forward(
                    error,
                    TerminationScope.ALL,
                    isCurrent = { runtime === created },
                ) },
                callbacks.remoteVideoFrames,
            )
            runtime = created
        }
        if (!owner.audioSettingsApplied) {
            // Restore settings before media creation or startup; leave restoration pending after failure or cancellation so the next operation retries.
            configuredLocalAudioVolume?.let { owner.components.media.setLocalAudioVolume(it) }
            configuredRemoteAudioVolume?.let { owner.components.stream.setRemoteAudioVolume(it) }
            owner.audioSettingsApplied = true
        }

        if (!owner.listenersApplied) {
            owner.components.stream.setAdaptiveQualityListener { handleAdaptiveQuality(
                owner,
                it,
            ) }
            owner.components.media.setCameraPreviewReadyListener {
                if (runtime === owner) {
                    lifecycleManager.localPreviewDidBecomeReady()
                }
            }
            synchronized(listenerLock) {
                owner.components.stream.setNetworkQualityListener(networkQualityListener)
                owner.components.stream.setPerformanceAlarmListener(performanceAlarmListener)
                owner.components.stream.setUplinkVideoStatisticsListener(uplinkVideoStatisticsListener)
                owner.components.stream.setDownlinkVideoStatisticsListener(downlinkVideoStatisticsListener)
                owner.listenersApplied = true
            }
        }

        return owner.components
    }

    /**
     * Rejects local media replacement unless generation and its connection are disconnected.
     *
     * @param c The realtime components used by this operation.
     */
    private fun requireDisconnected(
        c: XmaxRealtimeComponents,
    ) {
        if (c.connection.currentSessionId.isNotEmpty() || currentState.connectionState in setOf(
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
     * Evaluates connection quality only while its generation task remains current.
     *
     * @param owner The runtime owning this sample.
     * @param quality The directional network quality.
     */
    private fun handleAdaptiveQuality(
        owner: Runtime,
        quality: RealtimeNetworkQuality,
    ) {
        if (runtime !== owner) return

        val stream = owner.components.stream
        val revision = qualityRevision.get()
        val taskId = owner.qualityTaskId
        val state = currentState
        if (taskId == null || state.taskId != taskId || state.connectionState != RealtimeConnectionState.GENERATING || lifecycleManager.isBusy) {
            stream.resetQualitySamples()
            return
        }

        if (!owner.qualityPending.compareAndSet(
            false,
            true,
        )) {
            stream.resetQualitySamples()
            return
        }

        val sampleAt = qualityNow()
        qualityScope.launch {
            /**
             * Discards samples only while their original generation and foreground revision remain current.
             */
            fun resetSamplesIfCurrent() {
                if (runtime === owner && owner.qualityTaskId == taskId && qualityRevision.get() == revision) {
                    stream.resetQualitySamples()
                }
            }

            try {
                val accepted = lifecycleManager.runAdaptiveConfiguration { token ->
                    /**
                     * Rejects adjustments belonging to a replaced runtime, generation, or foreground request.
                     */
                    fun ensureCurrent() {
                        token.ensureCurrent()
                        val current = currentState
                        if (runtime !== owner || owner.qualityTaskId != taskId || current.taskId != taskId ||
                            qualityRevision.get() != revision || current.connectionState != RealtimeConnectionState.GENERATING
                        ) {
                            throw CancellationException("Adaptive quality belongs to an inactive generation")
                        }
                    }

                    ensureCurrent()
                    val change = stream.proposeQuality(
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
                            owner.components.connection.applyQualityLevel(
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
                owner.qualityPending.set(false)
            }
        }
    }

    /**
     * The media, connection, and quality state of one realtime runtime.
     */
    private class Runtime {

        /**
         * The current runtime components.
         */
        lateinit var components: XmaxRealtimeComponents

        /**
         * Whether volume settings have been applied to this runtime.
         */
        var audioSettingsApplied = false

        /**
         * Whether listeners have been bound to this runtime.
         */
        var listenersApplied = false

        /**
         * The task associated with current uplink and downlink quality state.
         */
        @Volatile var qualityTaskId: String? = null

        /**
         * Whether a quality adjustment is pending.
         */
        val qualityPending = AtomicBoolean(false)
    }

}
