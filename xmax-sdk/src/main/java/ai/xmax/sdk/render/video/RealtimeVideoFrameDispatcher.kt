//
//  RealtimeVideoFrameDispatcher.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.video

import ai.xmax.sdk.RealtimeVideoFrame
import ai.xmax.sdk.RealtimeVideoFrameListener
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.foundation.rtc.RtcRemoteVideoFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Dispatches remote video frames serially to the registered listener.
 *
 * Listener revisions and generation identity reject stale frames; slow consumers retain at most one
 * pending frame.
 *
 * @param dispatcher The background operation dispatcher.
 */
internal class RealtimeVideoFrameDispatcher(
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {

    /**
     * The scope delivering remote video frames in the background.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * The lock protecting frame listener and task identity.
     */
    private val lock = Any()

    /**
     * The current frame listener.
     */
    private var listener: RealtimeVideoFrameListener? = null

    /**
     * The current generation result identifier.
     */
    private var generation: Any? = null

    /**
     * The delivery revision used to discard stale tasks.
     */
    private var version = 0L

    /**
     * The latest remote frame awaiting delivery.
     */
    private var pending: Pending? = null

    /**
     * Whether a frame is being delivered to the listener.
     */
    private var draining = false

    /**
     * Replaces the remote frame listener and discards frames pending for its predecessor.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setListener(
        listener: RealtimeVideoFrameListener?,
    ) = synchronized(lock) {
        this.listener = listener
        version++
        pending = null
    }

    /**
     * Sets the generation identifier permitted to deliver frames.
     *
     * @param generation The identifier associating events with the current request.
     */
    fun setGeneration(
        generation: Any?,
    ) = synchronized(lock) {
        this.generation = generation
        version++
        pending = null
    }

    /**
     * Delivers frames only for the current generation task.
     *
     * @param generation The identifier associating events with the current request.
     * @param frame The video frame to process, display, or submit.
     */
    fun dispatch(
        generation: Any,
        frame: RtcRemoteVideoFrame,
    ) {
        val version = synchronized(lock) {
            if (this.generation !== generation || listener == null) return

            version
        }
        // Do not copy pixels without a listener; borrowed native pixels are accessible only inside the RTC callback.
        val copy = frame.copy()
        val launch = synchronized(lock) {
            if (this.version != version || this.generation !== generation || listener == null) return

            pending = Pending(
                version,
                copy,
            )
            if (draining) false else { draining = true; true }
        }
        if (launch) scope.launch { drain() }
    }

    /**
     * Delivers the latest pending frame for the active task, skipping stale tasks.
     */
    private fun drain() {
        while (true) {
            val delivery = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null) { draining = false; return }

                val callback = listener.takeIf { next.version == version }
                next.frame to callback
            }

            try {
                delivery.second?.onFrame(delivery.first)
            } catch (
                error: Throwable,
            ) {
                // App callback failures produce diagnostics without interrupting rendering or reporting fatal SDK failure.
                XmaxLogger.render.warn(
                    message = { "Remote video frame listener failed: ${error.message}" },
                )
            }
        }
    }

    /**
     * A pending video frame and its generation revision.
     */
    private data class Pending(
        /**
         * The revision identifying the owning task or callback.
         */
        val version: Long,

        /**
         * The video frame to process, display, or submit.
         */
        val frame: RealtimeVideoFrame,
    )
}
