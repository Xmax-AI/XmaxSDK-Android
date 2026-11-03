//
//  RoomControlling.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import kotlinx.coroutines.Deferred

/**
 * An interface for room lifecycle and signaling.
 */
internal interface RoomControlling {

    /**
     * Joins RTC and starts room heartbeats; room signaling is available when this call completes.
     *
     * @param connection The server-provided RTC room, user, credentials, and result publisher.
     * @param ensureActive Checks operation validity and throws on cancellation.
     */
    suspend fun join(
        connection: RealtimeSessionConnection,
        ensureActive: () -> Unit,
    )

    /**
     * Stops heartbeats and leaves the current RTC room.
     */
    suspend fun leave()

    /**
     * Sends a start signal with input format, generation conditions, and output bounds.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param context The generation prompt and reference media.
     * @param faceRestoration Whether to enable face restoration through `face_replace`; defaults to false.
     * @param targetVideoFormat The downlink output format.
     * @param paddingContain Whether the server fits input and removes padding from output; defaults to false.
     * @return The first complete send result, independent of service receipts.
     */
    fun startGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean = false,
        targetVideoFormat: RealtimeVideoFormat? = null,
        paddingContain: Boolean = false,
    ): Deferred<Unit>

    /**
     * Updates output dimensions and bitrate after checking operation validity.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param targetVideoFormat The downlink output format.
     * @param ensureActive Checks operation validity and throws on cancellation.
     * @return The first complete send result, independent of service receipts.
     */
    fun changeTargetSize(
        taskId: String,
        targetVideoFormat: RealtimeVideoFormat,
        ensureActive: () -> Unit,
    ): Deferred<Unit>

    /**
     * Sends updated conditions for the current task.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param context The generation prompt and reference media.
     * @param faceRestoration Whether to enable face restoration through `face_replace`; defaults to false.
     * @param paddingContain Whether the server fits input and removes padding from output; defaults to false.
     * @return The first complete send result, independent of service receipts.
     */
    fun changeGenerationCondition(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean = false,
        paddingContain: Boolean = false,
    ): Deferred<Unit>

    /**
     * Cancels command attempts belonging to an ended generation task.
     *
     * @param taskId The ended task, or an empty string to cancel every room command.
     */
    fun cancelGenerationCommands(
        taskId: String,
    )

    /**
     * Enqueues a stop signal if the room and task are active.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     */
    fun stopGeneration(
        taskId: String,
    )

    /**
     * Sends a generation task's trajectory.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param points Normalized media trajectory points from 0 to 1.
     */
    fun sendTracks(
        taskId: String,
        points: List<RealtimePoint>,
    )
}
