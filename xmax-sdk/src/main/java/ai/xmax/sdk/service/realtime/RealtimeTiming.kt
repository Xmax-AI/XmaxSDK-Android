//
//  RealtimeTiming.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Records the durations of realtime generation startup stages.
 */
internal class RealtimeTiming(
    /**
     * The monotonic clock returning nanoseconds.
     */
    private val clockNanos: () -> Long = System::nanoTime,

    /**
     * The output function receiving formatted log text.
     */
    private val log: (String) -> Unit = {
        XmaxLogger.timing.info(
            message = { it },
            option = XmaxLoggerOption.performance,
        )
    },
) {

    /**
     * Measures startup stages and logs success or non-cancellation failure.
     *
     * @param action The operation to perform.
     * @return The operation's successful result.
     */
    suspend fun <T> measure(
        action: suspend () -> T,
    ): T {
        val attempt = Attempt(
            clockNanos,
            log,
        )
        return try {
            withContext(attempt) { action() }
        } catch (
            error: Throwable,
        ) {
            attempt.fail(error)
            throw error
        } finally {
            attempt.invalidate()
        }
    }

    /**
     * The startup stages and outcome for one generation task, excluding other tasks' events.
     */
    internal class Attempt(
        /**
         * The monotonic clock returning nanoseconds.
         */
        private val clock: () -> Long,

        /**
         * The output function receiving formatted log text.
         */
        private val log: (String) -> Unit,
    ) : AbstractCoroutineContextElement(Key) {

        /**
         * The lock protecting startup measurements.
         */
        private val lock = Any()

        /**
         * The startup origin and recorded stage timestamps.
         */
        private val startedAt = clock()

        /**
         * Monotonic stage timestamps, in nanoseconds.
         */
        private val times = mutableMapOf<Stage, Long>()

        /**
         * The generation task associated with these measurements.
         */
        private var taskId: String? = null

        /**
         * Whether startup measurements have been finalized.
         */
        private var completed = false

        /**
         * Records the first occurrence of a stage unless measurement has ended.
         *
         * @param stage The startup stage to record.
         */
        fun mark(
            stage: Stage,
        ) = synchronized(lock) {
            if (!completed) times.putIfAbsent(
                stage,
                clock(),
            )

            Unit
        }

        /**
         * Associates the generation task and records the start of generation signaling.
         *
         * @param taskId The identifier associating the generation request with its result stream.
         */
        fun beginSignal(
            taskId: String,
        ) = synchronized(lock) {
            if (!completed && this.taskId == null) {
                this.taskId = taskId
                times[Stage.SIGNAL_START] = clock()
            }
        }

        /**
         * Confirms result-stream readiness for the current task, ignoring other tasks.
         *
         * @param taskId The identifier associating the generation request with its result stream.
         */
        fun confirmRemoteStream(
            taskId: String,
        ) = markTask(
            taskId,
            Stage.STREAM_CONFIRMED,
        )

        /**
         * Records a task's stage timestamp, ignoring duplicate and unrelated events.
         *
         * @param taskId The identifier associating the generation request with its result stream.
         * @param stage The startup stage to record.
         */
        private fun markTask(
            taskId: String,
            stage: Stage,
        ) = synchronized(lock) {
            if (!completed && this.taskId == taskId) times.putIfAbsent(
                stage,
                clock(),
            )

            Unit
        }

        /**
         * Outputs startup and stage durations once per startup.
         *
         * @param taskId The identifier associating the generation request with its result stream.
         */
        fun finish(
            taskId: String,
        ) {
            val message = synchronized(lock) {
                if (completed || this.taskId != taskId) return

                completed = true
                successMessage(clock())
            }

            log(message)
        }

        /**
         * Logs completed stages and the pending stage after startup failure; cancellation is not logged as a
         * failure.
         *
         * @param error The operation failure or SDK error.
         */
        fun fail(
            error: Throwable,
        ) {
            val message = synchronized(lock) {
                if (completed) return

                completed = true
                val isCancellation = error is CancellationException ||
                    (error is XmaxError && error.code == XmaxErrorCode.CANCELLED)
                if (isCancellation) return

                failureMessage(
                    clock(),
                    error,
                )
            }

            log(message)
        }

        /**
         * Ends measurement and ignores later stage events.
         */
        fun invalidate() = synchronized(lock) { completed = true }

        /**
         * Calculates a nonnegative duration between two timestamps, in milliseconds.
         *
         * @param start The monotonic start timestamp in nanoseconds, or null if unrecorded.
         * @param end The monotonic end timestamp in nanoseconds, or null if unrecorded.
         * @return The nonnegative duration in milliseconds, or null if either timestamp is missing.
         */
        private fun elapsed(
            start: Long?,
            end: Long?,
        ): Double? =
            if (start == null || end == null) null else (end - start).coerceAtLeast(0L) / 1_000_000.0

        /**
         * Formats milliseconds with one decimal place.
         *
         * @param value The duration in milliseconds.
         * @return Text with one decimal place and the ms unit.
         */
        private fun ms(
            value: Double,
        ): String = String.format(
            Locale.ROOT,
            "%.1f ms",
            value,
        )

        /**
         * Selects Chinese or English text from the logging environment.
         *
         * @param chinese The text used in the China environment.
         * @param english The text used in the global environment.
         * @return Text in the configured log language.
         */
        private fun label(
            chinese: String,
            english: String,
        ): String = XmaxLogger.localized(
            chinese,
            english,
        )

        /**
         * Appends a measured stage only when its timestamps exist and its duration meets the threshold.
         *
         * @param title The log entry title.
         * @param start The monotonic start timestamp in nanoseconds, or null if unrecorded.
         * @param end The monotonic end timestamp in nanoseconds, or null if unrecorded.
         * @param minimum The minimum logged stage duration, in milliseconds.
         */
        private fun MutableList<String>.addDuration(
            title: String,
            start: Long?,
            end: Long?,
            minimum: Double = 0.0,
        ) {
            elapsed(
                start,
                end,
            )?.takeIf { it >= minimum }?.let {
                add(
                    "$title${label(
                        "：",
                        ": ",
                    )}${ms(it)}",
                )
            }
        }

        /**
         * Formats startup totals, connection stages, and first-frame wait time.
         *
         * @param readyAt The monotonic first-frame-ready timestamp, in nanoseconds.
         * @return The successful startup timing lines.
         */
        private fun successMessage(
            readyAt: Long,
        ): String {
            val lines = mutableListOf(
                label(
                    "实时生成启动耗时",
                    "Realtime Generation Startup Timing",
                ),
            )
            lines.addDuration(
                "└─ ${label(
                    "总耗时",
                    "Total",
                )}",
                startedAt,
                readyAt,
            )
            lines.addDuration(
                "   ├─ ${label(
                    "创建会话",
                    "Session Creation",
                )}",
                times[Stage.SESSION_START],
                times[Stage.SESSION_END],
            )
            lines.addDuration(
                "   ├─ ${label(
                    "RTC 进房",
                    "RTC Room Join",
                )}",
                times[Stage.ROOM_START],
                times[Stage.ROOM_END],
            )
            lines.addDuration(
                "   ├─ ${label(
                    "发布本地流",
                    "Local Stream Publication",
                )}",
                times[Stage.PUBLICATION_START],
                times[Stage.PUBLICATION_END],
            )
            lines.addDuration(
                "   └─ ${label(
                    "首帧到达",
                    "First Frame Arrival",
                )}",
                times[Stage.SIGNAL_START],
                readyAt,
            )

            return lines.joinToString("\n")
        }

        /**
         * Formats elapsed time, the pending stage, and the startup failure.
         *
         * @param failedAt The monotonic failure timestamp, in nanoseconds.
         * @param error The operation failure or SDK error.
         * @return The failed startup diagnostic lines.
         */
        private fun failureMessage(
            failedAt: Long,
            error: Throwable,
        ): String {
            val lines = mutableListOf(
                "实时生成启动未完成耗时 " +
                    "(Incomplete Realtime Generation Startup Timing)",
            )
            lines.addDuration(
                "├─ ${label(
                    "已耗时",
                    "Elapsed",
                )}",
                startedAt,
                failedAt,
            )
            val pendingStage = when {
                Stage.STREAM_CONFIRMED in times -> label(
                    "等待首帧",
                    "Waiting for First Frame",
                )
                Stage.SIGNAL_START in times -> label(
                    "等待结果流确认",
                    "Waiting for Result Stream",
                )
                Stage.CONNECTION_END in times -> label(
                    "连接后准备",
                    "Post-Connection Setup",
                )
                Stage.ROOM_START in times && Stage.ROOM_END !in times -> label(
                    "RTC 房间连接",
                    "RTC Room Connection",
                )
                Stage.SESSION_END in times -> label(
                    "RTC 房间连接准备",
                    "RTC Room Setup",
                )
                Stage.SESSION_START in times -> label(
                    "服务端会话创建",
                    "Server Session Creation",
                )
                else -> label(
                    "调用与本地准备",
                    "Call and Local Setup",
                )
            }
            lines.add(
                "├─ ${label(
                    "停留阶段：",
                    "Pending Stage: ",
                )}$pendingStage",
            )
            lines.addDuration(
                "├─ ${label(
                    "服务端会话创建",
                    "Server Session Creation",
                )}",
                times[Stage.SESSION_START],
                times[Stage.SESSION_END] ?: failedAt,
            )
            lines.addDuration(
                "├─ ${label(
                    "RTC 房间连接",
                    "RTC Room Connection",
                )}",
                times[Stage.ROOM_START],
                times[Stage.ROOM_END] ?: failedAt,
            )
            lines.addDuration(
                "├─ ${label(
                    "实时连接",
                    "Realtime Connection",
                )}",
                times[Stage.CONNECTION_START],
                times[Stage.CONNECTION_END] ?: failedAt,
            )
            lines.addDuration(
                "├─ ${label(
                    "等待生成结果流确认",
                    "Waiting for Result Stream Confirmation",
                )}",
                times[Stage.SIGNAL_START],
                times[Stage.STREAM_CONFIRMED] ?: failedAt,
            )
            lines.addDuration(
                "├─ ${label(
                    "结果流确认后等待首帧",
                    "Waiting for First Frame after Confirmation",
                )}",
                times[Stage.STREAM_CONFIRMED],
                failedAt,
            )
            lines.add(
                "└─ ${label(
                    "失败原因：",
                    "Failure Reason: ",
                )}${ErrorMessageFormatter.format(error)}",
            )

            return lines.joinToString("\n")
        }

        /**
         * The coroutine context key for the current startup measurement.
         */
        companion object Key : CoroutineContext.Key<Attempt>
    }

    /**
     * A timestamped stage in generation startup.
     */
    enum class Stage {

        /**
         * Realtime connection starts.
         */
        CONNECTION_START,

        /**
         * Server session creation starts.
         */
        SESSION_START,

        /**
         * Server session creation completes.
         */
        SESSION_END,

        /**
         * Joining the RTC room starts.
         */
        ROOM_START,

        /**
         * RTC room entry is confirmed.
         */
        ROOM_END,

        /**
         * Local media publication starts.
         */
        PUBLICATION_START,

        /**
         * The local media publication call completes.
         */
        PUBLICATION_END,

        /**
         * The realtime connection and related components are ready.
         */
        CONNECTION_END,

        /**
         * Sending the generation request starts.
         */
        SIGNAL_START,

        /**
         * The current task's result stream is confirmed.
         */
        STREAM_CONFIRMED,
    }
}
