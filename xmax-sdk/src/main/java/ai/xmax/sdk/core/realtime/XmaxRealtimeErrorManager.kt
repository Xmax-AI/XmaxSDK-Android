//
//  XmaxRealtimeErrorManager.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Logs realtime failures and routes current media or connection failures to scoped lifecycle cleanup.
 *
 * @param dispatcher The dispatcher used to route asynchronous runtime failures.
 */
internal class XmaxRealtimeErrorManager(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    /**
     * The lock protecting failure registrations and lifecycle revisions.
     */
    private val lock = Any()

    /**
     * The scope routing failures outside their originating RTC or media callback.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * The revision rejecting queued failures from released local media.
     */
    private var mediaRevision = 0L

    /**
     * The revision rejecting queued failures from disconnected sessions.
     */
    private var connectionRevision = 0L

    /**
     * The lifecycle failure handler and its operation validity check.
     */
    private var failureHandler: ((XmaxError, XmaxRealtimeLifecycleManager.TerminationScope, () -> Boolean) -> Unit)? = null

    /**
     * Registers the handler that terminates resources affected by a current failure.
     *
     * @param handler The failure, cleanup scope, and validity handler.
     */
    fun setFailureHandler(
        handler: (XmaxError, XmaxRealtimeLifecycleManager.TerminationScope, () -> Boolean) -> Unit,
    ) = synchronized(lock) {
        failureHandler = handler
    }

    /**
     * Invalidates queued failures belonging to resources being released.
     *
     * @param target The released scope; generation-only cleanup preserves connection failure validity.
     */
    fun invalidatePendingFailures(
        target: XmaxRealtimeLifecycleManager.TerminationScope = XmaxRealtimeLifecycleManager.TerminationScope.ALL,
    ) = synchronized(lock) {
        if (target >= XmaxRealtimeLifecycleManager.TerminationScope.CONNECTION) connectionRevision++
        if (target == XmaxRealtimeLifecycleManager.TerminationScope.ALL) mediaRevision++
    }

    /**
     * Routes a runtime failure asynchronously without retaining released resources.
     *
     * @param error The failure reported by media or transport.
     * @param target The affected resources, or null to record diagnostics only.
     * @param isCurrent The check identifying the originating runtime or session.
     */
    fun forward(
        error: XmaxError,
        target: XmaxRealtimeLifecycleManager.TerminationScope? = null,
        isCurrent: () -> Boolean = { true },
    ) {
        val (revision, handler) = synchronized(lock) {
            (if (target == XmaxRealtimeLifecycleManager.TerminationScope.ALL) mediaRevision else connectionRevision) to failureHandler
        }
        scope.launch {
            val valid = {
                isCurrent() && synchronized(lock) {
                    (if (target == XmaxRealtimeLifecycleManager.TerminationScope.ALL) mediaRevision else connectionRevision) == revision
                }
            }
            if (!valid()) return@launch

            if (target == null || handler == null || error.code == XmaxErrorCode.CANCELLED) {
                report(error)
            } else {
                handler(
                    error,
                    target,
                    valid,
                )
            }
        }
    }

    /**
     * Records a realtime failure without changing lifecycle state.
     *
     * @param error The SDK error to log.
     */
    fun report(
        error: XmaxError,
    ) {
        if (error.code == XmaxErrorCode.CANCELLED) {
            XmaxLogger.realtime.warn(message = { "Realtime diagnostic: ${ErrorMessageFormatter.format(error)}" })
        } else {
            XmaxLogger.realtime.error(message = { "Realtime service error: ${ErrorMessageFormatter.format(error)}" })
        }
    }
}
