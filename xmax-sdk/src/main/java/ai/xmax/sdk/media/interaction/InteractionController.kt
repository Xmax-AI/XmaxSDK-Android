//
//  InteractionController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.interaction

import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.ErrorMessageFormatter
import ai.xmax.sdk.XmaxLogger
import android.util.SizeF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Receives the active task identifier and media-normalized trajectories asynchronously.
 */
internal typealias InteractionListener = suspend (String, List<RealtimePoint>) -> Unit

/**
 * Maps viewport interaction to normalized coordinates using the fixed media baseline and submits
 * trajectories.
 */
internal class InteractionController(
    /**
     * The event listener.
     */
    private val listener: InteractionListener = { _, _ -> },

    /**
     * The scope for background tasks.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : InteractionControlling {

    /**
     * The lock protecting interaction tasks and pending trajectories.
     */
    private val stateLock = Any()

    /**
     * The current interaction task.
     */
    private var activeInteraction: ActiveInteraction? = null

    /**
     * The latest trajectory awaiting submission.
     */
    private var pendingSubmission: Submission? = null

    /**
     * The coroutine sending pending trajectories.
     */
    private var drainJob: Job? = null

    /**
     * The sending revision used to reject stale interaction tasks.
     */
    private var drainGeneration = 0L

    /**
     * Enables interaction submission for a generation task.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat The fixed, resolved media format defining normalized coordinates.
     */
    override suspend fun startInteraction(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
    ) {
        stopInteraction()
        synchronized(stateLock) {
            activeInteraction = ActiveInteraction(
                taskId,
                SizeF(
                    videoFormat.width.toFloat(),
                    videoFormat.height.toFloat(),
                ),
            )
        }
    }

    /**
     * Stops interaction submission and discards pending trajectories.
     */
    override suspend fun stopInteraction() {
        val job = synchronized(stateLock) {
            activeInteraction = null
            drainGeneration++
            pendingSubmission = null
            drainJob.also { drainJob = null }
        }
        job?.cancelAndJoin()
    }

    /**
     * Submits a trajectory for the currently displayed image.
     *
     * @param frame The current trajectory and displayed image dimensions.
     */
    override fun submitInteraction(
        frame: InteractionFrame,
    ) {
        val interaction = synchronized(stateLock) { activeInteraction } ?: return
        if (frame.points.isEmpty()) return
        val points = frame.points.mapNotNull { point ->
            InteractionCoordinateMapper.map(
                point = point,
                viewportSize = frame.viewportSize,
                resolvedMediaSize = interaction.resolvedMediaSize,
                contentMode = frame.contentMode,
            )
        }
        if (points.isEmpty()) return

        synchronized(stateLock) {
            val current = activeInteraction
            if (current == null || current.taskId != interaction.taskId) return
            pendingSubmission = Submission(
                current.taskId,
                points,
            )
            if (drainJob?.isActive == true) return
            val generation = drainGeneration
            drainJob = scope.launch { drainPendingFrames(generation) }
        }
    }

    /**
     * Sends pending trajectories for the current interaction task, ignoring invalidated tasks.
     *
     * @param generation The identifier associating events with the current request.
     */
    private suspend fun drainPendingFrames(
        generation: Long,
    ) {
        while (true) {
            val submission = synchronized(stateLock) {
                if (generation != drainGeneration) return
                pendingSubmission.also { pendingSubmission = null }
            }
            if (submission == null) {
                synchronized(stateLock) {
                    if (generation == drainGeneration && pendingSubmission == null) {
                        drainJob = null
                        return
                    }
                }
                continue
            }
            try {
                currentCoroutineContext().ensureActive()
                listener(
                    submission.taskId,
                    submission.points,
                )
            } catch (
                error: CancellationException,
            ) {
                throw error
            } catch (
                error: Throwable,
            ) {
                // Trajectories prefer the latest frame; an individual send failure does not stop later interaction.
                XmaxLogger.interaction.warn(
                    message = {
                        "发送交互轨迹失败，已丢弃当前采样帧 " +
                            "(Failed to Send Interaction Trajectory; Current Sample Dropped)\n" +
                            "└─ ${XmaxLogger.localized(
                                "原因：",
                                "Reason: ",
                            )}${ErrorMessageFormatter.format(error)}"
                    },
                )
            }
        }
    }

    /**
     * The task and media format currently permitted to submit trajectories.
     */
    private data class ActiveInteraction(
        /**
         * The task identifier associating generation requests with result streams.
         */
        val taskId: String,

        /**
         * The fixed, resolved media dimensions used for trajectory normalization.
         */
        val resolvedMediaSize: SizeF,
    )

    /**
     * A task identifier and trajectory points ready for submission.
     */
    private data class Submission(
        /**
         * The task identifier associating generation requests with result streams.
         */
        val taskId: String,

        /**
         * Trajectory points in normalized media coordinates, from 0 to 1.
         */
        val points: List<RealtimePoint>,
    )
}
