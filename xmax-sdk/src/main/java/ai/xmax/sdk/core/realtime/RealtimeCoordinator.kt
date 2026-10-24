//
//  RealtimeCoordinator.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Admits realtime operations, merges termination requests, and owns lifecycle state changes.
 *
 * [lock] protects short state accesses without suspension; [effects] serializes operations and rollback.
 * Termination cancels affected operations and completes cleanup independently of a waiting caller's
 * cancellation.
 *
 * @param dispatcher The dispatcher for background operations; defaults to `Dispatchers.Default`.
 */
internal class RealtimeCoordinator(
    /**
     * The dispatcher for app state and video frame callbacks.
     */
    private val callbacks: RealtimeCallbacks,

    dispatcher: CoroutineDispatcher = Dispatchers.Default,

    /**
     * The function checking whether local media is still available.
     */
    private val hasLocalMedia: () -> Boolean = { false },

    /**
     * The suspending cleanup operation for a termination scope.
     */
    private val cleanup: suspend (TerminationScope) -> Unit,
) {

    /**
     * Generation and external-source replacements prefer the latest request; settings may queue, while other
     * conflicting operations are rejected.
     */
    enum class OperationKind {

        /**
         * Creates, replaces, or stops local media.
         */
        MEDIA,

        /**
         * Connects or terminates connection resources.
         */
        CONNECTION,

        /**
         * Starts or terminates a generation task.
         */
        GENERATION,

        /**
         * Replaces external video input.
         */
        REPLACEMENT,

        /**
         * Switches the active camera.
         */
        SWITCH,

        /**
         * Updates media encoding settings.
         */
        CONFIGURATION,

        /**
         * Updates volume or listener settings.
         */
        SETTING
    }

    /**
     * Resource scopes ordered from narrowest to broadest; merged termination requests may only expand the
     * scope.
     */
    enum class TerminationScope {

        /**
         * Starts or terminates a generation task.
         */
        GENERATION,

        /**
         * Connects or terminates connection resources.
         */
        CONNECTION,

        /**
         * Terminates all realtime media and connection resources.
         */
        ALL;

        /**
         * Checks whether an operation kind invalidates this operation.
         *
         * @param kind The operation or media source kind.
         * @return True if the operation invalidates the current operation.
         */
        fun affects(
            kind: OperationKind,
        ): Boolean = when (this) {
            GENERATION -> kind == OperationKind.GENERATION || kind == OperationKind.REPLACEMENT || kind == OperationKind.SWITCH ||
                kind == OperationKind.CONFIGURATION
            CONNECTION -> kind != OperationKind.MEDIA && kind != OperationKind.SETTING
            ALL -> true
        }
    }

    /**
     * The lock protecting lifecycle state and operation ownership.
     */
    private val lock = Any()

    /**
     * The mutex serializing media and connection effects.
     */
    private val effects = Mutex()

    /**
     * The scope for lifecycle operations.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * The currently admitted operation.
     */
    private var active: Operation? = null

    /**
     * The current generation request.
     */
    private var generation: GenerationRequest? = null

    /**
     * The pending configuration operations.
     */
    private val settings = mutableSetOf<Operation>()

    /**
     * The number of foreground requests waiting or executing.
     */
    private var foregroundRequests = 0

    /**
     * The pending termination request.
     */
    private var termination: Termination? = null

    /**
     * The lifecycle state exposed as a read-only snapshot.
     */
    private var state = RealtimeState(RealtimeConnectionState.IDLE)

    /**
     * Returns an immutable snapshot without allowing external state changes.
     */
    val currentState: RealtimeState get() = synchronized(lock) { state }

    /**
     * Whether foreground, background, or cleanup operations are pending.
     */
    val isBusy: Boolean get() = synchronized(lock) {
        foregroundRequests > 0 || active != null || settings.isNotEmpty() || termination != null || generation != null
    }

    /**
     * Completes media preparation once the camera frame and preview binding are ready; late callbacks do not
     * change other states.
     */
    fun localPreviewDidBecomeReady() = synchronized(lock) {
        if (state.connectionState == RealtimeConnectionState.PREPARING && termination == null) {
            setState(RealtimeState(RealtimeConnectionState.READY))
        }
    }

    /**
     * An operation ownership token that prevents stale asynchronous results from changing the current
     * lifecycle.
     */
    inner class Token internal constructor(
        /**
         * The lifecycle operation associated with this token.
         */
        private val operation: Operation,
    ) {

        /**
         * The request settings needed to resume generation after replacing input.
         *
         * Replacement inherits a pending request; committed conditions remain owned by GenerationManager.
         */
        val resumeGeneration: Boolean get() = operation.generation?.resumeGeneration == true

        /**
         * The generation context associated with this operation.
         */
        val requestedContext: RealtimeContext? get() = synchronized(lock) { operation.generation?.context }

        /**
         * The inherited face restoration setting, or null before a replacement request inherits it.
         */
        val requestedFaceRestoration: Boolean? get() = synchronized(lock) { operation.generation?.faceRestoration }

        /**
         * Retains the latest valid conditions for generation resumption.
         *
         * @param context Generation prompts and reference media; null reuses cached conditions, which must
         * exist for the first request.
         * @param faceRestoration Whether to enable face restoration through `face_replace`; defaults to
         * false.
         */
        fun inheritGenerationContext(
            context: RealtimeContext?,
            faceRestoration: Boolean = false,
        ) = synchronized(lock) {
            ensureCurrent()
            operation.generation?.let {
                if (it.context == null) it.context = context
                if (it.faceRestoration == null) it.faceRestoration = faceRestoration
            }
        }

        /**
         * Replaced or terminated operations exit through coroutine cancellation rather than reporting stale
         * results as new failures.
         */
        fun ensureCurrent() = synchronized(lock) {
            if ((active !== operation && operation !in settings) || operation.invalidated) throw CancellationException("Realtime operation was superseded")
            operation.generation?.let {
                if (generation !== it || !it.job.isActive) throw CancellationException("Realtime generation was superseded")
            }
        }

        /**
         * Commits state only while ownership is valid, checking ownership and updating state in the same
         * critical section.
         *
         * @param next The state snapshot to commit.
         */
        fun commit(
            next: RealtimeState,
        ) = synchronized(lock) {
            ensureCurrent()
            setState(next)
        }

        /**
         * Narrows subsequent failure cleanup to the generation task after the connection is established.
         *
         * @param scope The resources to terminate.
         */
        fun setFailureScope(
            scope: TerminationScope,
        ) = synchronized(lock) {
            ensureCurrent()
            operation.failureScope = scope
        }
    }

    /**
     * Replaces previous generation or source-replacement requests, rejecting other conflicts; at most 16
     * settings operations are admitted.
     * Caller cancellation waits for the operation and rollback before releasing admission, preventing reuse
     * of resources still being cleaned up.
     *
     * @param kind The operation or media source kind.
     * @param failureScope The resources to terminate on failure; null requests no additional termination.
     * @param context Generation prompts and reference media; null reuses cached conditions, which must exist
     * for the first request.
     * @param faceRestoration Whether to enable face restoration through `face_replace`; defaults to false.
     * @param action The operation to perform.
     * @return The operation's successful result.
     */
    suspend fun <T> run(
        kind: OperationKind,
        failureScope: TerminationScope? = defaultFailureScope(kind),
        context: RealtimeContext? = null,
        faceRestoration: Boolean = false,
        action: suspend (Token) -> T,
    ): T {
        val background = synchronized(lock) {
            foregroundRequests++
            active?.takeIf { it.background }?.also {
                it.invalidated = true
                it.task.cancel(CancellationException("Adaptive quality yields to a foreground operation"))
            }
        }
        try {
            background?.task?.join()
            synchronized(lock) { if (active === background) active = null }
            return if (kind == OperationKind.GENERATION || kind == OperationKind.REPLACEMENT) {
                runGeneration(
                    kind,
                    failureScope,
                    context,
                    faceRestoration,
                    action,
                )
            } else {
                runOperation(
                    kind,
                    failureScope,
                    action = action,
                )
            }
        } finally {
            synchronized(lock) { foregroundRequests-- }
        }
    }

    /**
     * Applies a low-priority quality adjustment only while idle; foreground operations and termination take
     * priority, and failure does not end generation.
     *
     * @param action The operation to perform.
     * @return True if the quality adjustment was admitted and performed.
     */
    suspend fun runAdaptiveConfiguration(
        action: suspend (Token) -> Unit,
    ): Boolean {
        return try {
            runOperation(
                OperationKind.CONFIGURATION,
                null,
                background = true,
                action = action,
            )
            true
        } catch (
            cancelled: CancellationException,
        ) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    /**
     * Runs the latest generation request after the replaced request and its rollback finish.
     *
     * @param kind The operation or media source kind.
     * @param failureScope The resources to terminate on failure; null requests no additional termination.
     * @param context Generation prompts and reference media; null reuses cached conditions, which must exist
     * for the first request.
     * @param faceRestoration Whether to enable face restoration through `face_replace`.
     * @param action The operation to perform.
     * @return The operation's successful result.
     */
    private suspend fun <T> runGeneration(
        kind: OperationKind,
        failureScope: TerminationScope?,
        context: RealtimeContext?,
        faceRestoration: Boolean,
        action: suspend (Token) -> T,
    ): T {
        val callerContext = currentCoroutineContext()
        return coroutineScope {
            lateinit var request: GenerationRequest
            val (previous, terminal) = synchronized(lock) {
                coroutineContext.ensureActive()
                if (termination?.target == TerminationScope.ALL || (active != null && active?.generation == null)) {
                    throw XmaxError(
                        XmaxErrorCode.INVALID_CONFIGURATION,
                        "Another realtime operation is in progress; wait for it to finish",
                    )
                }
                val previous = generation
                request = GenerationRequest(
                    coroutineContext.job,
                    if (kind == OperationKind.REPLACEMENT) previous?.context else context,
                    if (kind == OperationKind.REPLACEMENT) previous?.faceRestoration else faceRestoration,
                    kind == OperationKind.GENERATION || previous?.resumeGeneration == true ||
                        state.connectionState == RealtimeConnectionState.GENERATING,
                )
                generation = request
                previous?.job?.cancel(CancellationException("Realtime generation was superseded"))
                previous to termination?.task
            }
            try {
                withContext(NonCancellable) {
                    previous?.finished?.await()
                    terminal?.join()
                }
                currentCoroutineContext().ensureActive()
                runOperation(
                    kind,
                    failureScope,
                    request,
                    action = action,
                )
            } catch (
                cancelled: CancellationException,
            ) {
                callerContext.ensureActive()
                request.error?.let { throw it }
                throw cancelled
            } finally {
                synchronized(lock) {
                    if (generation === request) generation = null
                    request.finished.complete(Unit)
                }
            }
        }
    }

    /**
     * Serializes lifecycle operations and limits failure cleanup to the requested resource scope.
     *
     * @param kind The operation or media source kind.
     * @param failureScope The resources to terminate on failure; null requests no additional termination.
     * @param request The generation request, or null when no context is attached.
     * @param background Whether this is a background operation; defaults to false.
     * @param action The operation to perform.
     * @return The operation's successful result.
     */
    private suspend fun <T> runOperation(
        kind: OperationKind,
        failureScope: TerminationScope?,
        request: GenerationRequest? = null,
        background: Boolean = false,
        action: suspend (Token) -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        lateinit var operation: Operation
        val task = synchronized(lock) {
            if (background && (foregroundRequests > 0 || active != null || settings.isNotEmpty() || termination != null || generation != null)) {
                throw CancellationException("Adaptive quality requires an idle coordinator")
            }
            if (request != null && (generation !== request || !request.job.isActive)) {
                throw CancellationException("Realtime generation was superseded")
            }
            // During generation, startGeneration changes conditions rather than beginning another lifecycle.
            val operationKind = if (kind == OperationKind.GENERATION &&
                state.connectionState == RealtimeConnectionState.GENERATING
            ) OperationKind.CONFIGURATION else kind
            if (termination != null ||
                (operationKind != OperationKind.SETTING && active != null) ||
                (operationKind != OperationKind.SETTING && request == null && generation?.job?.isActive == true) ||
                (operationKind == OperationKind.SETTING && settings.size >= 16)
            ) throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Another realtime operation is in progress; wait for it to finish",
            )
            operation = Operation(
                operationKind,
                if (operationKind == OperationKind.CONFIGURATION) null else failureScope,
                request,
                background,
            )
            val task = scope.async(start = CoroutineStart.LAZY) {
                effects.withLock {
                    currentCoroutineContext().ensureActive()
                    Token(operation).let { token -> token.ensureCurrent(); action(token) }
                }
            }
            operation.task = task
            if (operationKind == OperationKind.SETTING) settings += operation else active = operation
            task
        }
        task.start()
        try {
            return task.await()
        } catch (
            cancelled: CancellationException,
        ) {
            synchronized(lock) { operation.invalidated = true }
            task.cancel(cancelled)
            // A caller may be cancelled after an underlying result is ready; release it before admitting another operation.
            withContext(NonCancellable) {
                task.join()
                val terminal = synchronized(lock) {
                    termination?.task ?: if (operation.terminated ||
                        operation.kind == OperationKind.CONFIGURATION ||
                        operation.kind == OperationKind.SETTING
                    ) null else operation.failureScope?.let { target ->
                        val replacing = operation.generation != null && generation != null && generation !== operation.generation
                        val connected = state.connectionState == RealtimeConnectionState.CONNECTED ||
                            state.connectionState == RealtimeConnectionState.GENERATING
                        // When generation starts on an existing connection, replacement releases only the task so both overloads can reuse the connection.
                        val cleanupTarget = if (replacing && connected && target == TerminationScope.CONNECTION) {
                            TerminationScope.GENERATION
                        } else target
                        requestTermination(
                            cleanupTarget,
                            origin = operation,
                        )
                    }
                }
                terminal?.await()
            }
            if (currentCoroutineContext().isActive) operation.terminationError?.let { throw it }
            throw cancelled
        } catch (
            error: Throwable,
        ) {
            val resolved = XmaxError.from(error)
            val terminal = synchronized(lock) {
                termination?.task ?: operation.failureScope?.let {
                    requestTermination(
                        it,
                        resolved,
                        origin = operation,
                    )
                }
            }
            if (terminal != null) {
                withContext(NonCancellable) { terminal.await() }
            } else if (operation.kind == OperationKind.MEDIA) {
                synchronized(lock) {
                    if (state.connectionState == RealtimeConnectionState.PREPARING) {
                        setState(
                            RealtimeState(
                                if (hasLocalMedia()) RealtimeConnectionState.READY
                                else RealtimeConnectionState.IDLE,
                            ),
                        )
                    }
                }
            }
            throw resolved
        } finally {
            synchronized(lock) {
                if (active === operation) active = null
                settings.remove(operation)
            }
        }
    }

    /**
     * Merges termination requests and waits for cleanup, recording the final state reason.
     *
     * @param target The resources to terminate.
     * @param reason The reason for the transition or failure; defaults to `RealtimeReason.Normal`.
     */
    suspend fun terminate(
        target: TerminationScope,
        reason: RealtimeReason = RealtimeReason.Normal,
    ) {
        val (request, task) = synchronized(lock) {
            generation to requestTermination(
                target,
                reason = reason,
            )
        }
        // Cancelling a waiter must not cancel the independent cleanup task.
        withContext(NonCancellable) {
            task.await()
            request?.finished?.await()
        }
    }

    /**
     * Do not repeat idle cleanup, but still cancel an operation whose connection state has not been
     * committed.
     *
     * @param reason The reason for the transition or failure; defaults to `RealtimeReason.Normal`.
     */
    suspend fun disconnect(
        reason: RealtimeReason = RealtimeReason.Normal,
    ) {
        val (request, task) = synchronized(lock) {
            val hasConnectionOperation = active?.let {
                TerminationScope.CONNECTION.affects(it.kind)
            } == true
            val task = if (generation == null && !hasConnectionOperation && termination == null &&
                state.connectionState in setOf(
                    RealtimeConnectionState.IDLE,
                    RealtimeConnectionState.PREPARING,
                    RealtimeConnectionState.READY,
                )
            ) null else requestTermination(
                TerminationScope.CONNECTION,
                reason = reason,
            )
            generation to task
        }
        withContext(NonCancellable) {
            task?.await()
            request?.finished?.await()
        }
    }

    /**
     * Registers a background failure without waiting on its originating heartbeat or media coroutine.
     *
     * @param error The operation failure or SDK error.
     * @param target The resources to terminate.
     */
    fun reportFailure(
        error: XmaxError,
        target: TerminationScope,
    ) = synchronized(lock) {
        if (state.reason is RealtimeReason.Failure && termination == null) return@synchronized
        requestTermination(
            target,
            error,
        )
        Unit
    }

    /**
     * Merges termination requests and waits until all resources in the expanded scope are released.
     *
     * @param target The resources to terminate.
     * @param error The operation failure or SDK error.
     * @param reason The reason for the state transition or failure.
     * @param origin The operation requesting termination, or null for an external request.
     * @return An awaitable result completed when cleanup finishes.
     */
    private fun requestTermination(
        target: TerminationScope,
        error: XmaxError? = null,
        reason: RealtimeReason = error?.let(RealtimeReason::Failure) ?: RealtimeReason.Normal,
        origin: Operation? = null,
    ): Deferred<Unit> {
        // Explicit termination and background failures also cancel the latest request waiting for a previous request's cleanup.
        // Rollback of an old operation must not cancel its replacement.
        if (origin == null) generation?.let {
            if (it.error == null) it.error = error
            it.job.cancel(CancellationException("Realtime generation terminated"))
        }
        val existing = termination
        val pending = existing ?: Termination(target).also { termination = it }
        if (target > pending.target) pending.target = target
        if (pending.error == null) pending.error = error
        if (reason is RealtimeReason.Failure || pending.reason == RealtimeReason.Normal) {
            pending.reason = reason
        }
        if (existing == null) {
            pending.task = scope.async(start = CoroutineStart.LAZY) {
                effects.withLock {
                    while (true) {
                        val requested = synchronized(lock) { pending.target }
                        try {
                            cleanup(requested)
                        } catch (
                            cleanupError: Throwable,
                        ) {
                            // Preserve the original failure; attach cleanup failures as suppressed diagnostics.
                            XmaxLogger.realtime.warn(
                                message = {
                                    "Realtime cleanup failed: " +
                                        ErrorMessageFormatter.format(cleanupError)
                                },
                            )
                            pending.error?.let { if (it !== cleanupError) it.addSuppressed(cleanupError) }
                        }
                        var stateNotification: RealtimeState? = null
                        val done = synchronized(lock) {
                            if (pending.target != requested) false else {
                                val finalState = RealtimeState(
                                    connectionState = when {
                                        requested == TerminationScope.ALL -> RealtimeConnectionState.IDLE
                                        requested == TerminationScope.GENERATION -> RealtimeConnectionState.CONNECTED
                                        hasLocalMedia() -> RealtimeConnectionState.READY
                                        else -> RealtimeConnectionState.IDLE
                                    },
                                    sessionId = state.sessionId,
                                    reason = pending.reason,
                                )
                                // Unregister old operations and termination before allowing the final callback to enter a new lifecycle.
                                if (active?.let { requested.affects(it.kind) } == true) active = null
                                settings.removeAll { requested.affects(it.kind) }
                                if (state != finalState) {
                                    state = finalState
                                    stateNotification = finalState
                                }
                                termination = null
                                true
                            }
                        }
                        if (done) {
                            stateNotification?.let(callbacks::state)
                            pending.error?.let { error ->
                                XmaxLogger.realtime.error(
                                    message = { "Realtime service error: ${ErrorMessageFormatter.format(error)}" },
                                )
                            }
                            break
                        }
                    }
                }
            }
        }
        (listOfNotNull(active) + settings).filter { pending.target.affects(it.kind) && it !== origin }.forEach {
            it.invalidated = true
            it.terminated = true
            it.terminationError = pending.error
            it.task.cancel(CancellationException("Realtime ${pending.target.name.lowercase()} terminated"))
        }
        if (pending.target >= TerminationScope.CONNECTION) {
            setState(
                RealtimeState(
                    connectionState = RealtimeConnectionState.DISCONNECTING,
                    sessionId = state.sessionId,
                ),
            )
        }
        pending.task.start()
        return pending.task
    }

    /**
     * Updates the state and queues listener notification only if the state changed.
     *
     * @param next The state snapshot to commit.
     */
    private fun setState(
        next: RealtimeState,
    ) {
        if (state == next) return
        state = next
        callbacks.state(next)
    }

    /**
     * Resolves the cleanup scope required after an operation fails.
     *
     * @param kind The operation or media source kind.
     * @return The cleanup scope, or null if no additional cleanup is needed.
     */
    private fun defaultFailureScope(
        kind: OperationKind,
    ): TerminationScope? = when (kind) {
        OperationKind.MEDIA -> TerminationScope.ALL
        OperationKind.CONNECTION -> TerminationScope.CONNECTION
        OperationKind.GENERATION, OperationKind.REPLACEMENT, OperationKind.SWITCH -> TerminationScope.GENERATION
        OperationKind.CONFIGURATION, OperationKind.SETTING -> null
    }

    /**
     * Validity and termination flags are protected by lock; task assignment precedes publication of the
     * operation.
     */
    internal class Operation(
        /**
         * The operation or media source kind.
         */
        val kind: OperationKind,

        /**
         * The resources to terminate on failure; null requests no additional termination.
         */
        var failureScope: TerminationScope?,

        /**
         * The identifier associating a request or event with its generation task.
         */
        val generation: GenerationRequest? = null,

        /**
         * Whether this is a background operation; defaults to false.
         */
        val background: Boolean = false,
    ) {

        /**
         * The task executing this operation.
         */
        lateinit var task: Deferred<*>

        /**
         * Whether a later request has replaced this operation.
         */
        var invalidated = false

        /**
         * Whether this operation has entered termination.
         */
        var terminated = false

        /**
         * The error causing operation termination.
         */
        var terminationError: XmaxError? = null
    }

    /**
     * Owns a pending generation or replacement request until completion; GenerationManager retains committed
     * conditions.
     */
    internal class GenerationRequest(
        /**
         * The coroutine task to await or cancel.
         */
        val job: Job,

        /**
         * Generation prompts and reference media; null reuses cached conditions after the initial request.
         */
        var context: RealtimeContext?,

        /**
         * Whether face restoration is enabled through `face_replace`.
         */
        var faceRestoration: Boolean?,

        /**
         * Whether to resume generation after replacing input.
         */
        val resumeGeneration: Boolean,
    ) {

        /**
         * The completion and failure result for this generation request.
         */
        val finished = CompletableDeferred<Unit>()

        /**
         * The failure associated with this generation request.
         */
        @Volatile
        var error: XmaxError? = null
    }

    /**
     * The merged cleanup target and first failure; cleanup repeats if a broader scope arrives.
     */
    private class Termination(
        /**
         * The resources to terminate.
         */
        var target: TerminationScope,
    ) {

        /**
         * The resource termination task.
         */
        lateinit var task: Deferred<Unit>

        /**
         * The error termination must report to your app.
         */
        var error: XmaxError? = null

        /**
         * The reason the connection ended.
         */
        var reason: RealtimeReason = RealtimeReason.Normal
    }
}
