//
//  RealtimeCallbacks.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.render.video.RealtimeVideoFrameDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Dispatches state notifications asynchronously on the main thread and remote frames on a separate serial
 * background queue.
 * Queued notifications are discarded if their listener registration has changed before delivery. App callback
 * failures are isolated.
 *
 * @param dispatcher The dispatcher for main-thread notifications; defaults to `Dispatchers.Main`.
 */
internal class RealtimeCallbacks(
    dispatcher: CoroutineDispatcher = Dispatchers.Main,

    /**
     * The serial queue shared across runtime recreation within this manager's lifetime.
     */
    val remoteVideoFrames: RealtimeVideoFrameDispatcher = RealtimeVideoFrameDispatcher(),
) {

    /**
     * The lock protecting callback registrations.
     */
    private val lock = Any()

    /**
     * The scope for state notifications.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * The state listener and its registration revision.
     */
    private var stateListener: RealtimeStateListener? = null

    /**
     * The revision used to discard outdated state notifications.
     */
    private var stateVersion = 0L

    /**
     * Replaces the listener and queues the current snapshot; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     * @param state The current state snapshot.
     */
    fun setStateListener(
        listener: RealtimeStateListener?,
        state: RealtimeState,
    ) = synchronized(lock) {
        stateVersion++
        stateListener = listener
        state(state)
    }

    /**
     * Delivers a snapshot only if its captured listener registration is still current.
     *
     * @param state The current state snapshot.
     */
    fun state(
        state: RealtimeState,
    ) = synchronized(lock) {
        val version = stateVersion
        val listener = stateListener ?: return@synchronized
        scope.launch {
            if (synchronized(lock) { version == stateVersion }) protect { listener.onStateChanged(state) }
        }
        Unit
    }

    /**
     * Logs and isolates app callback failures so later notifications remain deliverable.
     *
     * @param action The operation to perform.
     */
    private inline fun protect(
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (
            error: Throwable,
        ) {
            XmaxLogger.realtime.warn(
                message = { "Realtime listener failed: ${ErrorMessageFormatter.format(error)}" },
            )
        }
    }
}
