//
//  RoomCommandController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.room

import ai.xmax.sdk.XmaxLogger
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Retries logical commands independently of transport completion and generation readiness.
 */
internal class RoomCommandController(
    /**
     * Submits one complete, independently cancellable transport attempt.
     */
    private val sendMessage: (String) -> Deferred<Unit>,

    /**
     * The scope scheduling command sends and receipt deadlines.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /**
     * Commands with service receipts and a bounded retry schedule.
     */
    enum class Kind(
        /**
         * The room event name.
         */
        val value: String,
    ) {

        /**
         * Starts a generation task.
         */
        START("start"),

        /**
         * Updates generation conditions.
         */
        CHANGE_CONDITION("change_condition"),

        /**
         * Updates returned video dimensions.
         */
        CHANGE_TARGET_SIZE("change_target_size"),
    }

    /**
     * One immutable command and its pending transport attempts.
     */
    private class Command(
        /**
         * The generation operation.
         */
        val kind: Kind,

        /**
         * The generation task owning this command.
         */
        val taskId: String,

        /**
         * The identifier shared by the original and every redundant attempt.
         */
        val xid: String,

        /**
         * The serialized payload retained across attempts.
         */
        val message: String,

        /**
         * Rejects obsolete configuration until the first complete send succeeds.
         */
        val ensureCurrent: () -> Unit,
    ) {

        /**
         * Completes on the first successful send, initial failure, or cancellation.
         */
        val result = CompletableDeferred<Unit>()

        /**
         * The scheduled redundant sends and receipt deadline.
         */
        val scheduled = mutableListOf<Job>()

        /**
         * The active attempts, paired with whether they are the original send.
         */
        val transports = mutableMapOf<UUID, Pair<Boolean, Deferred<Unit>>>()

        /**
         * The number of attempts submitted to the transport.
         */
        var attempts = 0

        /**
         * Whether redundant attempts may still be submitted.
         */
        var retrying = true

        /**
         * Whether any complete attempt has succeeded.
         */
        var sent = false

        /**
         * Whether cancellation invalidated this command.
         */
        var cancelled = false
    }

    /**
     * The lock protecting command submission, receipts, and transport completions.
     */
    private val lock = Any()

    /**
     * Commands indexed by the identifier retained across retries.
     */
    private val pending = mutableMapOf<String, Command>()

    /**
     * Orders initial sends without waiting for receipts or retry deadlines.
     */
    private var sendTail: Job? = null

    /**
     * Whether the room has permanently stopped accepting commands.
     */
    private var closed = false

    /**
     * Freezes a command and cancels superseded work.
     *
     * @param kind The generation operation.
     * @param taskId The generation task owning the command.
     * @param createMessage Encodes the command with its assigned identifier.
     * @param ensureCurrent Checks configuration validity until the first complete send succeeds.
     * @return The first successful send, independent of receipt arrival.
     */
    fun send(
        kind: Kind,
        taskId: String,
        createMessage: (String) -> String,
        ensureCurrent: () -> Unit = {},
    ): Deferred<Unit> = synchronized(lock) {
        if (closed) throw CancellationException("RTC command room is closed")
        ensureCurrent()
        val xid = UUID.randomUUID().toString()
        val message = createMessage(xid)
        cancelMatching { kind == Kind.START || (it.taskId == taskId && it.kind == kind) }

        val command = Command(
            kind,
            taskId,
            xid,
            message,
            ensureCurrent,
        )
        pending[xid] = command
        command.result.invokeOnCompletion { error ->
            if (error is CancellationException) synchronized(lock) {
                if (!command.cancelled) cancelCommand(command)
            }
        }

        val previous = sendTail
        val ordering = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            previous?.join()
            synchronized(lock) {
                if (!command.cancelled) begin(command)
            }
            command.result.join()
        }
        sendTail = ordering
        command.result
    }

    /**
     * Stops redundant sends for a matching service receipt.
     *
     * @param xid The acknowledged command identifier.
     * @param count The service receipt count; only positive, safe integers are accepted.
     */
    fun acknowledge(
        xid: String,
        count: Long,
    ) {
        synchronized(lock) {
            if (count !in 1..9_007_199_254_740_991L) return
            val command = pending[xid]?.takeIf { it.retrying && it.attempts > 0 } ?: return

            try {
                if (!command.sent) command.ensureCurrent()
                XmaxLogger.room.info(
                    message = {
                        "接收房间信令 (Inbound Room Signaling)\n" +
                            "├─ ${XmaxLogger.localized(
                                "类型：",
                                "Type: ",
                            )}xid_ack\n" +
                            "├─ ${XmaxLogger.localized(
                                "命令：",
                                "Command: ",
                            )}${command.kind.value}\n" +
                            "├─ xid: ${command.xid}\n" +
                            "├─ count: $count\n" +
                            "└─ ${XmaxLogger.localized(
                                "发送次数：",
                                "Attempts: ",
                            )}${command.attempts}"
                    },
                )
                stopRetries(command)
            } catch (error: Throwable) {
                cancelCommand(command, error)
            }
        }
    }

    /**
     * Cancels commands belonging to an ended task.
     *
     * @param taskId The ended task, or null to cancel every room command.
     */
    fun cancel(
        taskId: String? = null,
    ) {
        synchronized(lock) {
            cancelMatching { taskId == null || it.taskId == taskId }
            if (taskId == null) sendTail = null
        }
    }

    /**
     * Cancels room commands and rejects further submissions.
     */
    fun close() {
        synchronized(lock) {
            closed = true
            cancel()
        }
    }

    /**
     * Schedules two send rounds and the six-second receipt deadline.
     *
     * @param command The current logical command.
     */
    private fun begin(
        command: Command,
    ) {
        for (offset in listOf(300L, 1_000L, 3_000L, 3_300L, 4_000L, 6_000L)) {
            val timer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                delay(offset)
                synchronized(lock) {
                    if (command.cancelled || !command.retrying) return@launch
                    if (offset == 6_000L) {
                        XmaxLogger.room.warn(message = {
                            "RTC command ACK not received after 6000 ms; retries stopped: " +
                                "${command.kind.value}, xid: ${command.xid}"
                        })
                        stopRetries(command)
                    } else {
                        attempt(command, initial = false)
                    }
                }
            }
            command.scheduled += timer
        }
        attempt(command, initial = true)
    }

    /**
     * Submits a valid command while retaining its transport cancellation handle.
     *
     * @param command The command to send.
     * @param initial Whether this is the original attempt.
     */
    private fun attempt(
        command: Command,
        initial: Boolean,
    ) {
        if (command.cancelled || !command.retrying) return
        try {
            if (!command.sent) command.ensureCurrent()
        } catch (error: Throwable) {
            cancelCommand(command, error)
            return
        }

        val id = UUID.randomUUID()
        try {
            command.attempts += 1
            val transport = sendMessage(command.message)
            command.transports[id] = initial to transport
            transport.invokeOnCompletion { error ->
                if (Thread.holdsLock(lock)) {
                    finishAttempt(
                        command,
                        id,
                        initial,
                        error,
                    )
                } else {
                    // Native completion can hold the RTC lifecycle gate; acquire command state after dispatch.
                    scope.launch {
                        synchronized(lock) {
                            finishAttempt(
                                command,
                                id,
                                initial,
                                error,
                            )
                        }
                    }
                }
            }
            if (!command.retrying && (!initial || command.sent)) transport.cancel()
        } catch (error: Throwable) {
            finishAttempt(
                command,
                id,
                initial,
                error,
            )
        }
    }

    /**
     * Resolves the caller on send success and preserves initial-send failures.
     *
     * @param command The command owning the completed attempt.
     * @param id The attempt identifier.
     * @param initial Whether this was the original attempt.
     * @param error The transport failure, or null on complete submission.
     */
    private fun finishAttempt(
        command: Command,
        id: UUID,
        initial: Boolean,
        error: Throwable?,
    ) {
        command.transports.remove(id)
        if (command.cancelled) return
        if (error == null) {
            command.sent = true
            command.result.complete(Unit)
            if (!command.retrying) pending.remove(command.xid)
        } else if (initial && !command.sent) {
            if (command.kind == Kind.START) cancelMatching { it !== command && it.taskId == command.taskId }
            cancelCommand(command, error)
        } else if (command.retrying && error !is CancellationException) {
            XmaxLogger.room.warn(message = {
                "RTC command retry send failed: ${command.kind.value}, xid: ${command.xid}"
            })
        }
    }

    /**
     * Stops redundant work without cancelling an unresolved original send.
     *
     * @param command The acknowledged or expired command.
     */
    private fun stopRetries(
        command: Command,
    ) {
        command.retrying = false
        command.scheduled.forEach { it.cancel() }
        command.transports.values.toList().forEach { (initial, transport) ->
            if (!initial || command.sent) transport.cancel()
        }
        if (command.sent) pending.remove(command.xid)
    }

    /**
     * Cancels every attempt and completes an unresolved caller with the operation's failure.
     *
     * @param command The command invalidated by its lifecycle or a newer request.
     * @param error The cancellation or initial transport failure.
     */
    private fun cancelCommand(
        command: Command,
        error: Throwable = CancellationException("RTC command was cancelled"),
    ) {
        command.cancelled = true
        stopRetries(command)
        command.transports.values.toList().forEach { it.second.cancel() }
        command.result.completeExceptionally(error)
        pending.remove(command.xid)
    }

    /**
     * Cancels a snapshot of commands matching the supplied lifecycle boundary.
     *
     * @param matches Selects commands belonging to the superseded operation.
     */
    private fun cancelMatching(
        matches: (Command) -> Boolean,
    ) {
        pending.values.filter(matches).forEach { cancelCommand(it) }
    }
}
