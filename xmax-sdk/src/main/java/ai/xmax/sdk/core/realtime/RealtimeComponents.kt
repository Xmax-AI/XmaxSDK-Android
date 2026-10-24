//
//  RealtimeComponents.kt
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
import ai.xmax.sdk.service.network.ApiServicing
import ai.xmax.sdk.service.media.MediaService
import ai.xmax.sdk.service.realtime.RealtimeSessionService
import ai.xmax.sdk.stream.StreamController
import ai.xmax.sdk.stream.StreamControlling
import android.content.Context

import ai.xmax.sdk.render.RenderControlling

/**
 * Creates the media, transport, rendering, and service components for one realtime manager, sharing one RTC
 * manager.
 * Media frames and interaction signals go through Stream; remote tracks go through Render.
 * Failures are reported to Core, where the coordinator controls cleanup and notifications. Each component set
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
internal fun createRealtimeComponents(
    context: Context,
    apiService: ApiServicing,
    model: RealtimeModel,
    onError: (XmaxError) -> Unit,
    onMediaError: (XmaxError) -> Unit,
    frameDispatcher: RealtimeVideoFrameDispatcher,
): RealtimeComponents {
    val rtcManager = RtcManager(context)
    val mediaService = MediaService(model)
    val renderController = RenderController(
        rtcManager,
        frameDispatcher = frameDispatcher,
        errorListener = onError,
    )
    val streamController = StreamController(
        rtcManager = rtcManager,
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
    )
    val generationManager = XmaxRealtimeGenerationManager(
        interactionController = mediaController,
        streamController = streamController,
    )

    return RealtimeComponents(
        mediaController,
        streamController,
        renderController,
        connectionManager,
        generationManager,
    )
}

/**
 * The components of one runtime; after close, the next operation creates a new set.
 */
internal data class RealtimeComponents(
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
