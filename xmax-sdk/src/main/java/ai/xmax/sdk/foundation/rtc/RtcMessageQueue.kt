//
//  RtcMessageQueue.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.stream.room.RoomMessageCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Sends room signaling in order, retaining only the latest unsent trajectory for each key when the budget is
 * exhausted.
 *
 * @param scope The sending scope owned by the room lifecycle.
 */
internal class RtcMessageQueue(
    scope: CoroutineScope,

    /**
     * Submits one packet to RTC, throwing on failure.
     */
    private val send: (ByteArray) -> Unit,

    /**
     * The callback reporting room signal send failures.
     */
    private val onFailure: (Throwable) -> Unit,

    /**
     * The monotonic millisecond clock for send budget windows.
     */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    /**
     * One complete signal with its packet fragments, coalescing key, and result.
     */
    private class Message(
        /**
         * The packets sent in order.
         */
        var packets: List<ByteArray>,

        /**
         * The coalescing key used before sending starts.
         */
        val key: String?,

        /**
         * The result completed after the entire signal is submitted to RTC.
         */
        val completion: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    /**
     * The lock protecting queued and in-flight signal results.
     */
    private val lock = Any()

    /**
     * The unsent room signals.
     */
    private val pending = ArrayDeque<Message>()

    /**
     * The pending send results.
     */
    private val completions = mutableSetOf<CompletableDeferred<Unit>>()

    /**
     * Whether the queue has stopped accepting messages.
     */
    private var closed = false

    /**
     * The failure that terminated the sending queue.
     */
    private var failure: Throwable? = null

    /**
     * The signaling packet codec.
     */
    private val codec = RoomMessageCodec()

    /**
     * The conflated channel waking the sending loop.
     */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * The coroutine sending pending signals.
     */
    private val worker = scope.launch { drain() }

    /**
     * Queues a signal in order, replacing unsent content with the latest value for the same key.
     *
     * @param message The complete room signal.
     * @param replacingPendingMessage The coalescing key, or null to retain every signal.
     * @return The complete signal's send result, shared by coalesced callers.
     */
    fun enqueue(
        message: String,
        replacingPendingMessage: String? = null,
    ): CompletableDeferred<Unit> {
        val packets = codec.encode(message).map { it.toByteArray(Charsets.UTF_8) }
        return synchronized(lock) {
            checkAvailable()
            val existing = replacingPendingMessage?.let { key -> pending.firstOrNull { it.key == key } }
            if (existing != null) {
                existing.packets = packets
                wake.trySend(Unit)
                return@synchronized existing.completion
            }

            if (completions.size >= 64) {
                throw XmaxError(
                    XmaxErrorCode.RTC_ERROR,
                    "RTC room message queue is full",
                )
            }

            val entry = Message(
                packets,
                replacingPendingMessage,
            )
            pending.addLast(entry)
            completions.add(entry.completion)
            wake.trySend(Unit)
            entry.completion
        }
    }

    /**
     * Discards unsent coalescible signals without interrupting started fragments or ordinary control
     * messages.
     *
     * @param key The coalescing key to discard, or null for all coalescible signals.
     */
    fun discardPendingMessages(
        key: String? = null,
    ) {
        synchronized(lock) {
            val discarded = pending.filter { it.key != null && (key == null || it.key == key) }
            pending.removeAll(discarded.toSet())
            discarded.forEach {
                it.completion.complete(Unit)
                completions.remove(it.completion)
            }
            wake.trySend(Unit)
        }
    }

    /**
     * Waits for signals queued at invocation to be submitted, throwing if the queue closes or sending fails.
     */
    suspend fun flush() {
        val snapshot = synchronized(lock) {
            checkAvailable()
            completions.toList()
        }

        snapshot.forEach { it.await() }
    }

    /**
     * Closes the queue and cancels all incomplete send results.
     */
    fun close() {
        synchronized(lock) {
            if (closed) return

            closed = true
            completions.forEach { it.cancel(CancellationException("RTC room was closed")) }
            completions.clear()
            pending.clear()
        }
        worker.cancel()
        wake.close()
    }

    /**
     * Throws the closure or sending failure if the queue is unavailable.
     */
    private fun checkAvailable() {
        failure?.let { throw it }
        if (closed) throw CancellationException("RTC room was closed")
    }

    /**
     * Waits for budget, then sends the latest complete signal and records actual usage.
     */
    private suspend fun drain() {
        val sent = ArrayDeque<Pair<Long, Int>>()

        try {
            while (true) {
                coroutineContext.ensureActive()
                var waitMillis = 0L
                val entry = synchronized(lock) {
                    checkAvailable()
                    val next = pending.firstOrNull()
                    if (next != null) {
                        waitMillis = remainingWait(
                            sent,
                            next.packets.first().size,
                        )
                    }

                    if (next != null && waitMillis == 0L) pending.removeFirst() else null
                }
                if (entry == null) {
                    if (waitMillis > 0) withTimeoutOrNull(waitMillis) { wake.receive() } else wake.receive()
                    continue
                }

                for (packet in entry.packets) {
                    while (true) {
                        coroutineContext.ensureActive()
                        val remaining = remainingWait(
                            sent,
                            packet.size,
                        )
                        if (remaining == 0L) break

                        withTimeoutOrNull(remaining) { wake.receive() }
                    }

                    coroutineContext.ensureActive()
                    send(packet)
                    sent.addLast(nowMillis() to packet.size)
                }

                synchronized(lock) {
                    entry.completion.complete(Unit)
                    completions.remove(entry.completion)
                }
            }
        } catch (
            error: Throwable,
        ) {
            val report = synchronized(lock) {
                val shouldReport = !closed && error !is CancellationException
                failure = error
                completions.forEach { it.completeExceptionally(error) }
                completions.clear()
                pending.clear()
                shouldReport
            }
            if (report) onFailure(error)
        }
    }

    /**
     * Calculates the wait required by the 58-packet and 29,000-byte per-second budgets.
     *
     * @param sent Packet timestamps and byte counts submitted in the current window.
     * @param byteCount The pending packet size in bytes.
     * @return The required wait in milliseconds, or 0 if sending can proceed.
     */
    private fun remainingWait(
        sent: ArrayDeque<Pair<Long, Int>>,
        byteCount: Int,
    ): Long {
        val now = nowMillis()
        while (sent.isNotEmpty() && now - sent.first().first >= 1_000) sent.removeFirst()
        if (sent.size < 58 && sent.sumOf { it.second } + byteCount <= 29_000) return 0

        return (1_000 - (now - sent.first().first)).coerceAtLeast(1)
    }
}
