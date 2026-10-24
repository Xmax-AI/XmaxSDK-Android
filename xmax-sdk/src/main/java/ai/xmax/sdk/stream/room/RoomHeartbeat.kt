//
//  RoomHeartbeat.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.room

import ai.xmax.sdk.ErrorMessageFormatter
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.foundation.rtc.RtcManaging
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin

/**
 * Sends periodic room heartbeats while rejecting events from stopped lifecycles.
 */
internal class RoomHeartbeat(
    /**
     * The manager for RTC capture, publication, subscription, and rendering.
     */
    private val rtcManager: RtcManaging,

    /**
     * The suspending function waiting for the next heartbeat.
     */
    private val sleeper: suspend () -> Unit = { delay(10_000L) },

    /**
     * The scope for background tasks.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /**
     * The lock protecting room heartbeat ownership.
     */
    private val taskLock = Any()

    /**
     * The current heartbeat lifecycle context.
     */
    private val cycle = AtomicLong(0L)

    /**
     * The coroutine periodically sending room heartbeats.
     */
    private var heartbeatTask: Job? = null

    /**
     * Starts a user's room heartbeat, replacing its predecessor.
     *
     * @param userId The room's business user identifier.
     */
    fun start(
        userId: String,
    ) {
        synchronized(taskLock) {
            val version = cycle.incrementAndGet()
            heartbeatTask?.cancel()
            heartbeatTask = scope.launch {
                run(
                    version,
                    userId,
                )
            }
        }
    }

    /**
     * Stops room heartbeats and waits for the running coroutine to exit.
     */
    suspend fun stop() {
        val job = synchronized(taskLock) {
            cycle.incrementAndGet()
            heartbeatTask.also { heartbeatTask = null }
        }
        job?.cancelAndJoin()
    }

    /**
     * Sends room heartbeats only while the lifecycle remains active.
     *
     * @param version The revision identifying the owning task or callback.
     * @param userId The room's business user identifier.
     */
    private suspend fun run(
        version: Long,
        userId: String,
    ) {
        while (cycle.get() == version) {
            try {
                sleeper()
                currentCoroutineContext().ensureActive()
                if (cycle.get() != version) return
                rtcManager.sendRoomMessage(RoomEvent.heartbeat(userId))
            } catch (
                _: CancellationException,
            ) {
                return
            } catch (
                error: Throwable,
            ) {
                if (cycle.get() != version) return
                XmaxLogger.room.error(
                    message = {
                        "发送 RTC 房间心跳失败 (Failed to Send RTC Room Heartbeat)\n" +
                            "└─ ${XmaxLogger.localized(
                                "原因：",
                                "Reason: ",
                            )}${ErrorMessageFormatter.format(error)}"
                    },
                )
            }
        }
    }
}
