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
import ai.xmax.sdk.render.video.RealtimeVideoFrameDispatcher
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

/**
 * Creates realtime dependencies and wires media, transport, rendering, and cleanup workflows.
 */
internal object XmaxRealtimeFactory {

    /**
     * Connects lifecycle admission to scoped cleanup and asynchronous failure routing.
     *
     * @param callbacks The dispatcher for app state and frame callbacks.
     * @param dispatcher The dispatcher for lifecycle work.
     * @param errorManager The shared runtime failure manager.
     * @param hasLocalMedia The check for retained local preview resources.
     * @param cleanup The runtime cleanup operation.
     * @return The lifecycle owner shared by public realtime operations.
     */
    fun assemble(
        callbacks: RealtimeCallbacks,
        dispatcher: CoroutineDispatcher,
        errorManager: XmaxRealtimeErrorManager,
        hasLocalMedia: () -> Boolean,
        cleanup: suspend (XmaxRealtimeLifecycleManager.TerminationScope) -> Unit,
    ): XmaxRealtimeLifecycleManager {
        val lifecycleManager = XmaxRealtimeLifecycleManager(
            callbacks,
            dispatcher,
            hasLocalMedia = hasLocalMedia,
            errorManager = errorManager,
            cleanup = { target ->
                errorManager.invalidatePendingFailures(target)
                cleanup(target)
            },
        )
        errorManager.setFailureHandler { error, target, isCurrent ->
            lifecycleManager.reportFailure(
                error,
                target,
                isCurrent,
            )
        }

        return lifecycleManager
    }

    /**
     * Releases generation, connection, or local media resources while preserving the original failure.
     *
     * @param components The runtime components owning the resources.
     * @param target The broadest cleanup scope requested by the lifecycle owner.
     * @param taskId The generation task being released.
     */
    suspend fun cleanup(
        components: XmaxRealtimeComponents,
        target: XmaxRealtimeLifecycleManager.TerminationScope,
        taskId: String,
    ) {
        val connection = target >= XmaxRealtimeLifecycleManager.TerminationScope.CONNECTION
        val all = target == XmaxRealtimeLifecycleManager.TerminationScope.ALL
        cleanupResources(
            { if (connection) components.generation.reset(taskId) else components.generation.stop(taskId) },
            { if (connection) components.media.stopMicrophoneCapture() },
            { if (connection) components.connection.disconnect() },
            { components.media.setLocalAudioPreviewMuted(false) },
            { if (all) cleanupResources(
                { components.media.stopLocalStream() },
                { components.media.setCameraPreviewReadyListener(null) },
            ) },
        )
    }

    /**
     * Creates the media, transport, rendering, and service components for one realtime manager, sharing one RTC
     * manager.
     * Media frames and interaction signals go through Stream; remote tracks go through Render.
     * Failures are reported to Core, where lifecycle management controls cleanup and notifications. Each component set
     * owns its media and connections.
     *
     * @param context The Android context used for system services and media resources.
     * @param apiService The client for server API requests.
     * @param model The model defining server capabilities and input constraints.
     * @param onError The callback for connection or runtime failures.
     * @param onMediaError The callback for local media failures.
     * @param frameDispatcher The remote video frame dispatcher.
     * @return Components sharing the same RTC manager.
     */
    fun make(
        context: Context,
        apiService: ApiServicing,
        model: RealtimeModel,
        onError: (XmaxError) -> Unit,
        onMediaError: (XmaxError) -> Unit,
        frameDispatcher: RealtimeVideoFrameDispatcher,
    ): XmaxRealtimeComponents {
        val rtcManager = RtcManager(context)
        val mediaService = MediaService(model)
        val renderController = RenderController(
            rtcManager,
            frameDispatcher = frameDispatcher,
            errorListener = onError,
        )
        val streamController = StreamController(
            rtcManager = rtcManager,
            qualityController = QualityController(
                rtcManager,
                history = QualityHistory.create(
                    context,
                    model.id,
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
                requestingFrames = { streamController.isRequestingExternalFrames },
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

        return XmaxRealtimeComponents(
            mediaController,
            streamController,
            renderController,
            connectionManager,
            generationManager,
        )
    }
}

/**
 * The components of one runtime; after close, the next operation creates a new set.
 */
internal data class XmaxRealtimeComponents(
    /**
     * The controller owning local media lifecycles.
     */
    val media: MediaControlling,

    /**
     * The controller for media publication, subscriptions, and signaling.
     */
    val stream: StreamControlling,

    /**
     * The controller for video rendering and trajectory bindings.
     */
    val render: RenderControlling,

    /**
     * The manager owning server sessions and RTC connections.
     */
    val connection: XmaxRealtimeConnectionManager,

    /**
     * The manager owning generation contexts and tasks.
     */
    val generation: XmaxRealtimeGenerationManager,
)
