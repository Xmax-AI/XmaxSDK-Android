//
//  XmaxRealtimeFactory.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RtcManager
import ai.xmax.sdk.foundation.media.MediaFileMetadataManager
import ai.xmax.sdk.foundation.media.image.ImageManager
import ai.xmax.sdk.media.external.ExternalVideoController
import ai.xmax.sdk.media.MediaController
import ai.xmax.sdk.media.MediaControlling
import ai.xmax.sdk.media.MediaSourceController
import ai.xmax.sdk.media.camera.CameraController
import ai.xmax.sdk.media.interaction.InteractionController
import ai.xmax.sdk.media.image.ImageController
import ai.xmax.sdk.media.image.ImageSourceController
import ai.xmax.sdk.media.video.NetworkVideoController
import ai.xmax.sdk.media.video.VideoController
import ai.xmax.sdk.media.video.VideoPlayerController
import ai.xmax.sdk.render.RenderController
import ai.xmax.sdk.render.RenderControlling
import ai.xmax.sdk.service.network.ApiServicing
import ai.xmax.sdk.service.media.MediaService
import ai.xmax.sdk.service.realtime.RealtimeSessionService
import ai.xmax.sdk.stream.StreamController
import ai.xmax.sdk.stream.StreamControlling
import ai.xmax.sdk.stream.quality.QualityController
import ai.xmax.sdk.stream.quality.QualityHistory
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Creates realtime components and wires their frame, failure, and cleanup callbacks.
 */
internal object XmaxRealtimeFactory {

    /**
     * Connects realtime components to shared failure handling and resource cleanup.
     *
     * @param connectionManager The manager owning server sessions and RTC connections.
     * @param generationManager The manager owning generation requests and readiness.
     * @param errorManager The shared runtime failure manager.
     * @param mediaController The controller owning local media and interactions.
     * @param renderController The controller owning remote presentation and frame output.
     * @param streamController The controller owning media transport and quality policies.
     * @param mediaService The shared model sizing service.
     * @param timing The recorder for generation startup timings.
     * @param callbacks The dispatcher for app state notifications.
     * @param dispatcher The dispatcher for lifecycle work.
     * @return Components retained for the realtime manager's lifetime.
     */
    fun assemble(
        connectionManager: XmaxRealtimeConnectionManager,
        generationManager: XmaxRealtimeGenerationManager,
        errorManager: XmaxRealtimeErrorManager,
        mediaController: MediaControlling,
        renderController: RenderControlling,
        streamController: StreamControlling,
        mediaService: MediaServicing = MediaService(),
        timing: RealtimeTiming = RealtimeTiming(),
        callbacks: RealtimeCallbacks = RealtimeCallbacks(),
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): XmaxRealtimeComponents {
        lateinit var lifecycleManager: XmaxRealtimeLifecycleManager
        lifecycleManager = XmaxRealtimeLifecycleManager(
            callbacks,
            dispatcher,
            hasLocalMedia = { mediaController.currentTrack != null },
            errorManager = errorManager,
            cleanup = { target ->
                errorManager.invalidatePendingFailures(target)
                try {
                    cleanup(
                        mediaController,
                        streamController,
                        connectionManager,
                        generationManager,
                        target,
                        lifecycleManager.currentState.taskId.orEmpty(),
                    )
                } finally {
                    errorManager.invalidatePendingFailures(target)
                }
            },
        )
        errorManager.setFailureHandler { error, target, isCurrent ->
            lifecycleManager.reportFailure(
                error,
                target,
                isCurrent,
            )
        }
        mediaController.setCameraPreviewReadyListener(lifecycleManager::localPreviewDidBecomeReady)

        return XmaxRealtimeComponents(
            lifecycleManager,
            connectionManager,
            generationManager,
            errorManager,
            mediaController,
            renderController,
            streamController,
            mediaService,
            timing,
        )
    }

    /**
     * Releases generation, connection, or local media resources while preserving the original failure.
     *
     * @param mediaController The controller owning local media and interactions.
     * @param streamController The controller owning transport and quality settings.
     * @param connectionManager The manager releasing RTC and server connections.
     * @param generationManager The manager releasing generation tasks.
     * @param target The broadest cleanup scope requested by the lifecycle owner.
     * @param taskId The generation task being released.
     */
    private suspend fun cleanup(
        mediaController: MediaControlling,
        streamController: StreamControlling,
        connectionManager: XmaxRealtimeConnectionManager,
        generationManager: XmaxRealtimeGenerationManager,
        target: XmaxRealtimeLifecycleManager.TerminationScope,
        taskId: String,
    ) {
        val connection = target >= XmaxRealtimeLifecycleManager.TerminationScope.CONNECTION
        val all = target == XmaxRealtimeLifecycleManager.TerminationScope.ALL
        streamController.setAdaptiveQualityListener(null)
        streamController.resetQualitySamples()

        cleanupResources(
            { if (connection) generationManager.reset(taskId) else generationManager.stop(taskId) },
            { if (connection) mediaController.stopMicrophoneCapture() },
            { if (connection) connectionManager.disconnect() },
            { mediaController.setLocalAudioPreviewMuted(false) },
            { if (all) mediaController.stopLocalStream() },
        )
    }

    /**
     * Creates the components for a realtime manager.
     *
     * The components share one RTC manager. Media frames and interactions pass through the stream
     * controller; remote tracks pass through the render controller. Lifecycle management
     * coordinates failures and cleanup.
     *
     * @param options The model and realtime configuration.
     * @param context The Android context used for system services and media resources.
     * @param apiService The client for server API requests.
     * @param dispatcher The dispatcher for lifecycle work and asynchronous failures.
     * @return Shared components whose camera and RTC resources initialize only when media starts.
     */
    fun make(
        options: RealtimeConfiguration,
        context: Context,
        apiService: ApiServicing,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): XmaxRealtimeComponents {
        val errorManager = XmaxRealtimeErrorManager(dispatcher)
        val callbacks = RealtimeCallbacks()
        val onError: (XmaxError) -> Unit = {
            errorManager.forward(
                it,
                XmaxRealtimeLifecycleManager.TerminationScope.CONNECTION,
            )
        }
        val onMediaError: (XmaxError) -> Unit = {
            errorManager.forward(
                it,
                XmaxRealtimeLifecycleManager.TerminationScope.ALL,
            )
        }
        val rtcManager = RtcManager(context)
        val mediaService = MediaService(options.model)
        val renderController = RenderController(
            rtcManager,
            frameDispatcher = callbacks.remoteVideoFrames,
            errorListener = onError,
        )
        val streamController = StreamController(
            rtcManager = rtcManager,
            qualityController = QualityController(
                rtcManager,
                history = QualityHistory.create(
                    context,
                    options.model.id,
                ),
            ),
            errorListener = onError,
            remoteStreamListener = renderController::setRemoteStream,
        )
        val mediaController: MediaControlling = MediaController(
            rtcManager = rtcManager,
            cameraController = CameraController(
                context = context,
                rtcManager = rtcManager,
                mediaService = mediaService,
                errorListener = onMediaError,
            ),
            imageController = ImageController(
                rtcManager = rtcManager,
                imageSourceController = ImageSourceController(
                    context = context.applicationContext,
                    imageManager = ImageManager(),
                    mediaService = mediaService,
                    frameListener = streamController::pushLocalVideoFrame,
                    errorListener = onMediaError,
                ),
            ),
            videoController = VideoController(
                rtcManager = rtcManager,
                mediaSourceController = MediaSourceController(
                    metadataManager = MediaFileMetadataManager(context),
                    mediaService = mediaService,
                    playerController = VideoPlayerController(
                        context = context,
                        videoFrameListener = streamController::pushLocalVideoFrame,
                        audioFrameListener = streamController::pushLocalAudioFrame,
                        errorListener = onMediaError,
                    ),
                ),
            ),
            externalController = ExternalVideoController(
                rtc = rtcManager,
                mediaService = mediaService,
                videoListener = streamController::pushLocalVideoFrame,
                audioListener = streamController::pushLocalAudioFrame,
                errorListener = onMediaError,
            ),
            networkVideoController = NetworkVideoController(
                context.applicationContext,
                mediaService,
            ),
            interactionController = InteractionController(
                listener = { taskId, points ->
                    streamController.sendTracks(
                        taskId,
                        points,
                    )
                },
            ),
        )
        val connectionManager = XmaxRealtimeConnectionManager(
            sessionService = RealtimeSessionService(apiService),
            interactionController = mediaController,
            renderController = renderController,
            streamController = streamController,
            mediaService = mediaService,
        )
        val generationManager = XmaxRealtimeGenerationManager(
            interactionController = mediaController,
            streamController = streamController,
            renderController = renderController,
        )

        return assemble(
            connectionManager = connectionManager,
            generationManager = generationManager,
            errorManager = errorManager,
            mediaController = mediaController,
            renderController = renderController,
            streamController = streamController,
            mediaService = mediaService,
            callbacks = callbacks,
            dispatcher = dispatcher,
        )
    }
}

/**
 * The shared components used by the public facade and its lifecycle manager.
 */
internal data class XmaxRealtimeComponents(
    /**
     * The lifecycle owner that admits operations and merges termination requests.
     */
    val lifecycleManager: XmaxRealtimeLifecycleManager,

    /**
     * The manager that owns the server session and RTC connection.
     */
    val connectionManager: XmaxRealtimeConnectionManager,

    /**
     * The manager that sends generation requests and confirms result streams.
     */
    val generationManager: XmaxRealtimeGenerationManager,

    /**
     * The manager that logs and forwards media and connection failures.
     */
    val errorManager: XmaxRealtimeErrorManager,

    /**
     * The controller that manages local media, preview, and interactions.
     */
    val mediaController: MediaControlling,

    /**
     * The controller that manages remote tracks, first-frame readiness, and rendering.
     */
    val renderController: RenderControlling,

    /**
     * The controller that manages RTC connections, signaling, and transport quality.
     */
    val streamController: StreamControlling,

    /**
     * The service that resolves media dimensions and model input constraints.
     */
    val mediaService: MediaServicing,

    /**
     * The recorder for generation startup stage durations.
     */
    val timing: RealtimeTiming,
)
