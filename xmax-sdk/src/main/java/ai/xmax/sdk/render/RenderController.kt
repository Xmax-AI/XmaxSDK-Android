//
//  RenderController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render

import ai.xmax.sdk.foundation.rtc.RtcRemoteVideoSink
import ai.xmax.sdk.foundation.rtc.RtcRemoteVideoFrame
import ai.xmax.sdk.render.video.RealtimeMediaFrameDispatcher
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.RealtimeMediaFrameListener
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxVideoView
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.media.interaction.InteractionFrame
import ai.xmax.sdk.render.trajectory.TrajectoryBinding
import ai.xmax.sdk.render.trajectory.TrajectoryRegistry
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import androidx.annotation.MainThread
import androidx.compose.ui.unit.IntSize
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

/**
 * Coordinates video tracks, remote frame processing, and trajectory rendering.
 *
 * Each generation shares one remote frame path for first-frame readiness, display, and app frame
 * callbacks.
 */
internal class RenderController(
    /**
     * The manager for RTC capture, publication, subscription, and rendering.
     */
    private val rtcManager: RtcManaging,

    /**
     * The remote frame readiness timeout in milliseconds; defaults to `REMOTE_FRAME_READY_TIMEOUT_MILLIS`.
     */
    private val remoteFrameReadyTimeoutMillis: Long = REMOTE_FRAME_READY_TIMEOUT_MILLIS,

    /**
     * The dispatcher for view binding and display updates; defaults to `Dispatchers.Main.immediate`.
     */
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,

    /**
     * The bounded, serialized remote audio/video output queue.
     */
    private val frameDispatcher: RealtimeMediaFrameDispatcher = RealtimeMediaFrameDispatcher(),

    /**
     * The media failure callback.
     */
    private val errorListener: (XmaxError) -> Unit = {},
) : RenderControlling {

    /**
     * The mutex protecting remote rendering state.
     *
     * Serializes track registration, canvas binding, and release without holding the lock while awaiting a
     * frame.
     */
    private val operationLock = ReentrantLock()

    /**
     * The active result stream and its rendering state.
     */
    private var generation: RemoteGeneration? = null

    /**
     * A weak reference to the current downlink video view.
     */
    private var remoteView: WeakReference<XmaxVideoView>? = null

    /**
     * The downlink video's aspect-fit or aspect-fill mode.
     */
    private var remoteContentMode = VideoContentMode.FILL

    /**
     * The active downlink video track.
     */
    private var remoteTrack: RealtimeVideoTrack? = null

    /**
     * Sets serialized remote audio/video output without changing playback.
     *
     * @param listener The output listener, or null to unregister it.
     */
    override fun setRemoteMediaFrameListener(
        listener: RealtimeMediaFrameListener?,
    ) = operationLock.withLock {
        val current = generation
        if (listener != null && current != null) {
            rtcManager.setRemoteAudioFrameListener(current.stream) { frame ->
                frameDispatcher.dispatchAudio(
                    current,
                    frame,
                )
            }
        }
        frameDispatcher.setListener(listener)
        if (listener == null && current != null) rtcManager.setRemoteAudioFrameListener(
            current.stream,
            null,
        )
    }

    /**
     * Sets the subscribed remote stream; null unbinds it.
     *
     * @param stream The remote stream identified by room and user.
     */
    @MainThread
    override fun setRemoteStream(
        stream: RemoteStream?,
    ) = operationLock.withLock {
        val previous = generation
        val next = stream?.let(::RemoteGeneration)
        generation = next
        frameDispatcher.setGeneration(next)
        previous?.ready?.cancel(CancellationException("Remote generation was replaced"))
        remoteView?.get()?.invalidateVideoPresentation()

        try {
            if (previous != null) {
                var failure = runCatching {
                    rtcManager.setRemoteVideoFrameListener(
                        previous.stream,
                        null,
                    )
                }.exceptionOrNull()

                runCatching { rtcManager.setRemoteAudioFrameListener(
                    previous.stream,
                    null,
                ) }
                    .onFailure { error ->
                        if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
                    }

                try {
                    rtcManager.unbindRemoteVideo(previous.stream)
                } catch (
                    error: Throwable,
                ) {
                    if (failure == null) throw error

                    if (failure !== error) failure.addSuppressed(error)
                }

                failure?.let { throw it }
            }

            if (next != null) {
                // Register frames after the target publisher is confirmed; never reuse the previous task's first-frame flag, even for the same stream.
                rtcManager.setRemoteVideoFrameListener(
                    next.stream,
                    object : RtcRemoteVideoSink {

                        /**
                         * Reports the first subscribed frame and its dimensions.
                         *
                         * @param width The frame width, in pixels.
                         * @param height The frame height, in pixels.
                         */
                        override fun onFirstFrame(
                            width: Int,
                            height: Int,
                        ) = handleRemoteVideoFrame(
                            next,
                            width,
                            height,
                        )

                        /**
                         * Receives a frame from the subscribed video stream.
                         *
                         * @param frame The video frame to process, display, or submit.
                         */
                        override fun onFrame(
                            frame: RtcRemoteVideoFrame,
                        ) {
                            operationLock.withLock {
                                if (generation === next) updateDisplayedFormat(
                                    frame.displayWidth,
                                    frame.displayHeight,
                                )
                            }
                            frameDispatcher.dispatch(
                                next,
                                frame,
                            )
                        }

                        /**
                         * Reports a failure in the current media or RTC operation.
                         *
                         * @param error The operation failure or SDK error.
                         */
                        override fun onError(
                            error: XmaxError,
                        ): Unit = operationLock.withLock {
                            if (generation !== next) return

                            frameDispatcher.setGeneration(null)
                            if (!next.ready.completeExceptionally(error)) errorListener(error)
                        }
                    },
                )
                if (frameDispatcher.hasListener) {
                    rtcManager.setRemoteAudioFrameListener(next.stream) { frame ->
                        frameDispatcher.dispatchAudio(
                            next,
                            frame,
                        )
                    }
                }
            }
        } catch (
            error: Throwable,
        ) {
            frameDispatcher.setGeneration(null)
            next?.ready?.completeExceptionally(error)
            throw error
        }
    }

    /**
     * Registers remote rendering and trajectory interactions.
     *
     * @param track The video track to display or bind.
     * @param resolvedMediaSize The fixed, resolved local media dimensions used for interaction coordinates.
     * @param interactionListener The interaction frame callback.
     */
    override fun registerRemoteTrack(
        track: RealtimeVideoTrack,
        resolvedMediaSize: IntSize,
        interactionListener: (InteractionFrame) -> Unit,
    ) {
        operationLock.withLock { remoteTrack = track }
        VideoRenderRegistry.register(
            track,
            VideoRenderBinding(
                attachHandler = ::attachRemoteVideo,
                detachHandler = ::detachRemoteVideo,
            ),
        )
        TrajectoryRegistry.register(
            track,
            TrajectoryBinding(
                interactionListener,
                resolvedMediaSize,
            ),
        )
    }

    /**
     * Waits for the first remote frame ready for display.
     *
     * An expired stream or timeout ends the wait with an error.
     */
    override suspend fun waitUntilRemoteFrameReady() {
        val current = operationLock.withLock { generation } ?: throw XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = "Remote video stream is unavailable",
        )

        try {
            withTimeout(remoteFrameReadyTimeoutMillis) { current.ready.await() }
        } catch (
            error: TimeoutCancellationException,
        ) {
            currentCoroutineContext().ensureActive()
            throw XmaxError(
                code = XmaxErrorCode.TIMEOUT,
                message = "Remote video first frame timed out",
                cause = error,
            )
        }
    }

    /**
     * Unregisters a remote track and releases its display resources.
     *
     * @param track The video track to display or bind.
     */
    override suspend fun resetRemoteTrack(
        track: RealtimeVideoTrack?,
    ) = withContext(NonCancellable + renderDispatcher) {
        // Release on the rendering dispatcher to avoid mutual waits with UI binding.
        operationLock.withLock {
            track?.let(VideoRenderRegistry::unregister)
            track?.let(TrajectoryRegistry::unregister)

            try {
                setRemoteStream(null)
            } finally {
                remoteView = null
                remoteContentMode = VideoContentMode.FILL
                remoteTrack = null
            }
        }
    }

    /**
     * Binds remote video to a preview view.
     *
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    private fun attachRemoteVideo(
        view: XmaxVideoView,
        contentMode: VideoContentMode,
    ): Unit = operationLock.withLock {
        remoteView = WeakReference(view)
        remoteContentMode = contentMode
        view.invalidateVideoPresentation()
        generation?.takeIf { it.isReady }?.let { current ->
            bindRemoteView(
                current,
                view,
            )
        }
    }

    /**
     * Unbinds remote video from a view.
     *
     * @param view The view to bind or unbind.
     */
    private fun detachRemoteVideo(
        view: XmaxVideoView,
    ): Unit = operationLock.withLock {
        if (remoteView?.get() !== view) return

        view.invalidateVideoPresentation()
        remoteView = null
        generation?.takeIf { it.isReady }?.let { rtcManager.unbindRemoteVideo(it.stream) }
    }

    /**
     * Reports first-frame dimensions only for a valid current subscription.
     *
     * @param current The active remote generation display state.
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     */
    private fun handleRemoteVideoFrame(
        current: RemoteGeneration,
        width: Int,
        height: Int,
    ): Unit = operationLock.withLock {
        if (generation !== current || current.ready.isCompleted || width <= 0 || height <= 0) return

        try {
            updateDisplayedFormat(
                width,
                height,
            )
            remoteView?.get()?.let { view ->
                bindRemoteView(
                    current,
                    view,
                )
            }

            current.isReady = true
            current.ready.complete(Unit)
        } catch (
            error: Throwable,
        ) {
            // Returns native renderer configuration failures through startup and cleans up connection resources.
            current.ready.completeExceptionally(error)
        }

        Unit
    }

    /**
     * Binds the current generation result to the remote video view.
     *
     * @param current The active remote generation display state.
     * @param view The view to bind or unbind.
     */
    private fun bindRemoteView(
        current: RemoteGeneration,
        view: XmaxVideoView,
    ) {
        // Rebind display resources and await a fresh first frame when a new task reuses the track.
        view.prepareRtcVideoRendering()
        rtcManager.bindRemoteVideo(
            current.stream,
            view.rtcRenderView,
            remoteContentMode,
        )
    }

    /**
     * Updates remote track metadata from actual received dimensions.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     */
    private fun updateDisplayedFormat(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return

        val track = remoteTrack ?: return
        val previous = track.videoFormat ?: return
        if (previous.width == width && previous.height == height) return

        val format = previous.copy(
            width = width,
            height = height,
        )
        track.updateVideoFormat(format)
    }

    /**
     * The first-frame waiter and view binding for the current remote generation.
     */
    private class RemoteGeneration(
        /**
         * The remote stream identified by room and user.
         */
        val stream: RemoteStream,
    ) {

        /**
         * The current result stream's first-frame waiter.
         */
        val ready = CompletableDeferred<Unit>()

        /**
         * Whether the current generated image is ready.
         */
        var isReady = false
    }

    /**
     * The first-frame display timeout configuration.
     */
    private companion object {

        /**
         * The generated-frame readiness timeout, in milliseconds.
         */
        const val REMOTE_FRAME_READY_TIMEOUT_MILLIS = 10_000L
    }
}
