//
//  RoomController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeTiming
import kotlinx.coroutines.currentCoroutineContext
import ai.xmax.sdk.cleanupAfterFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.foundation.rtc.RoomJoinConfiguration
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import ai.xmax.sdk.stream.encoding.EncodingBitratePolicy
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Manages RTC room lifecycle, heartbeat, and generation signaling.
 */
internal class RoomController(
    /**
     * The manager for RTC capture, publication, subscription, and rendering.
     */
    private val rtcManager: RtcManaging,

    /**
     * The component maintaining RTC room heartbeats.
     */
    private val heartbeat: RoomHeartbeat = RoomHeartbeat(rtcManager),

    /**
     * The scope scheduling room command retries and receipt deadlines.
     */
    private val commandScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),

    /**
     * Submits each complete generation control once through an independent HTTP request.
     */
    private val relayMessage: (suspend (String) -> Unit)? = null,
) : RoomControlling {

    /**
     * The lock protecting room lifecycle state.
     */
    private val stateLock = Any()

    /**
     * The mutex serializing room exit.
     */
    private val leaveMutex = Mutex()

    /**
     * The current room lifecycle state.
     */
    private var state: State = State.Idle

    /**
     * The command scheduler for the current room lifetime.
     */
    private var commands = makeCommands()

    /**
     * The decoder for fragmented room receipts.
     */
    private val decoder = RoomMessageCodec()

    /**
     * Joins RTC and starts room heartbeats; room signaling is available when this call completes.
     *
     * @param connection The server-provided RTC room, user, credentials, and result publisher.
     * @param ensureActive Checks operation validity and throws on cancellation.
     */
    override suspend fun join(
        connection: RealtimeSessionConnection,
        ensureActive: () -> Unit,
    ) {
        try {
            ensureActive()
        } catch (
            error: Throwable,
        ) {
            throw XmaxError.from(error)
        }

        val operationId = synchronized(stateLock) {
            if (state != State.Idle) {
                throw XmaxError(
                    code = XmaxErrorCode.INVALID_CONFIGURATION,
                    message = "Leave the current RTC room before joining another one",
                )
            }

            UUID.randomUUID().also {
                commands = makeCommands()
                decoder.clear()
                state = State.Joining(it)
            }
        }

        try {
            heartbeat.stop()
            val timing = currentCoroutineContext()[RealtimeTiming.Attempt]
            timing?.mark(RealtimeTiming.Stage.ROOM_START)
            rtcManager.joinRoom(
                RoomJoinConfiguration(
                    roomId = connection.roomId,
                    userId = connection.userId,
                    token = connection.token,
                    appId = connection.appId,
                ),
            )
            timing?.mark(RealtimeTiming.Stage.ROOM_END)
            ensureActive()

            val activated = synchronized(stateLock) {
                if (state != State.Joining(operationId)) {
                    false
                } else {
                    state = State.Joined(
                        connection.userId,
                        connection.roomId,
                        connection.botName,
                        operationId,
                    )
                    rtcManager.setRoomMessageListener { stream, message ->
                        handleIncomingMessage(
                            stream,
                            message,
                            operationId,
                        )
                    }
                    heartbeat.start(connection.userId)
                    true
                }
            }
            if (!activated) {
                throw CancellationException("RTC room join was cancelled")
            }
        } catch (
            error: Throwable,
        ) {
            val shouldRollback = synchronized(stateLock) {
                state == State.Joining(operationId)
            }
            if (shouldRollback) cleanupAfterFailure(
                error,
                { leave() },
            )

            throw XmaxError.from(error)
        }
    }

    /**
     * Stops heartbeats and leaves the current RTC room.
     */
    override suspend fun leave() {
        leaveMutex.withLock {
            val hasResources = synchronized(stateLock) {
                when (state) {
                    State.Idle,
                    State.Leaving,
                    -> false

                    is State.Joined,
                    is State.Joining,
                    -> {
                        commands.close()
                        rtcManager.setRoomMessageListener(null)
                        decoder.clear()
                        state = State.Leaving
                        true
                    }
                }
            }
            if (!hasResources) return

            try {
                heartbeat.stop()
                rtcManager.leaveRoom()
            } finally {
                synchronized(stateLock) {
                    if (state == State.Leaving) state = State.Idle
                }
            }
        }
    }

    /**
     * Sends a start signal with input format, generation conditions, and output bounds.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param context The generation prompt and reference media.
     * @param faceRestoration Whether to enable face restoration through `face_replace`.
     * @param targetVideoFormat The downlink output format.
     * @param paddingContain Whether the server fits input and removes padding from output.
     * @return The first complete send result, independent of service receipts.
     */
    override fun startGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean,
        targetVideoFormat: RealtimeVideoFormat?,
        paddingContain: Boolean,
    ): Deferred<Unit> = synchronized(stateLock) {
        val userId = requireUserId()
        val (minimum, maximum) = downlinkBitrates(targetVideoFormat ?: videoFormat)
        commands.send(
            kind = RoomCommandController.Kind.START,
            taskId = taskId,
            createMessage = { xid ->
                RoomEvent.start(
                    userId = userId,
                    taskId = taskId,
                    videoFormat = videoFormat,
                    context = context,
                    minimumBitrate = minimum,
                    maximumBitrate = maximum,
                    faceRestoration = faceRestoration,
                    targetVideoFormat = targetVideoFormat,
                    paddingContain = paddingContain,
                    xid = xid,
                )
            },
        )
    }

    /**
     * Updates output dimensions and bitrate after checking operation validity.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param targetVideoFormat The downlink output format.
     * @param ensureActive Checks operation validity and throws on cancellation.
     * @return The first complete send result, independent of service receipts.
     */
    override fun changeTargetSize(
        taskId: String,
        targetVideoFormat: RealtimeVideoFormat,
        ensureActive: () -> Unit,
    ): Deferred<Unit> = synchronized(stateLock) {
        ensureActive()
        if (taskId.isBlank()) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Realtime generation task ID cannot be empty",
            )
        }

        val (minimum, maximum) = downlinkBitrates(targetVideoFormat)
        val userId = requireUserId()
        commands.send(
            kind = RoomCommandController.Kind.CHANGE_TARGET_SIZE,
            taskId = taskId,
            createMessage = { xid ->
                RoomEvent.changeTargetSize(
                    userId,
                    taskId,
                    targetVideoFormat,
                    minimum,
                    maximum,
                    xid,
                )
            },
            ensureCurrent = ensureActive,
        )
    }

    /**
     * Resolves the downlink bitrate budget.
     *
     * The minimum is 500 kbps; the maximum is estimated from output dimensions at 24 fps and
     * clamped to 500 through 10,000 kbps.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @return The minimum and maximum downlink encoder bitrates, in kbps.
     */
    private fun downlinkBitrates(
        videoFormat: RealtimeVideoFormat,
    ): Pair<Int, Int> {
        val (_, maximum) = EncodingBitratePolicy.resolve(
            videoFormat.width,
            videoFormat.height,
            fps = 24,
        )
        return 500 to maximum.coerceIn(
            500,
            10_000,
        )
    }

    /**
     * Sends updated conditions for the current task.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param context The generation prompt and reference media.
     * @param faceRestoration Whether to enable face restoration through `face_replace`.
     * @param paddingContain Whether the server fits input and removes padding from output.
     * @return The first complete send result, independent of service receipts.
     */
    override fun changeGenerationCondition(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean,
        paddingContain: Boolean,
    ): Deferred<Unit> = synchronized(stateLock) {
        val userId = requireUserId()
        commands.send(
            kind = RoomCommandController.Kind.CHANGE_CONDITION,
            taskId = taskId,
            createMessage = { xid ->
                RoomEvent.changeCondition(
                    userId = userId,
                    taskId = taskId,
                    videoFormat = videoFormat,
                    context = context,
                    faceRestoration = faceRestoration,
                    paddingContain = paddingContain,
                    xid = xid,
                )
            },
        )
    }

    /**
     * Enqueues a stop signal if the room and task are active.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     */
    override fun stopGeneration(
        taskId: String,
    ) {
        cancelGenerationCommands(taskId)
        val userId = synchronized(stateLock) {
            if (taskId.isEmpty()) return

            (state as? State.Joined)?.userId ?: return
        }

        send(
            RoomEvent.stop(
                userId,
                taskId,
            ),
            discardPending = true,
        )
    }

    /**
     * Cancels command attempts belonging to an ended generation task.
     *
     * @param taskId The ended task, or an empty string to cancel every room command.
     */
    override fun cancelGenerationCommands(
        taskId: String,
    ) {
        synchronized(stateLock) { commands.cancel(taskId.takeIf(String::isNotEmpty)) }
    }

    /**
     * Creates the command scheduler for one room lifetime.
     *
     * @return A scheduler using cancellable RTC sends.
     */
    private fun makeCommands(): RoomCommandController = RoomCommandController(
        sendMessage = { message ->
            rtcManager.discardPendingMessages()
            XmaxLogger.room.debug(message = { formatSignalLog(message) })
            rtcManager.sendRoomMessage(
                message,
                reportFailure = false,
            )
        },
        scope = commandScope,
        relayMessage = relayMessage,
    )

    /**
     * Accepts matching command receipts from the current room's result publisher.
     *
     * @param stream The remote sender's room and user identifiers.
     * @param message The incoming JSON signal or fragment.
     * @param operationId The room lifetime that registered the receiver.
     */
    private fun handleIncomingMessage(
        stream: RemoteStream,
        message: String,
        operationId: UUID,
    ) {
        synchronized(stateLock) {
            val joined = state as? State.Joined ?: return
            if (joined.operationId != operationId || stream.roomId != joined.roomId ||
                stream.userId.isBlank() || stream.userId == joined.userId ||
                (!joined.botId.isNullOrBlank() && stream.userId != joined.botId)
            ) return

            val decoded = decoder.decode(stream.userId, message) ?: return
            val event = runCatching { JSONObject(decoded) }.getOrNull() ?: return
            if (event.optString("event") != "xid_ack") return
            val recipient = event.opt("user_id")
            if (recipient != null && recipient !== JSONObject.NULL && recipient != joined.userId) return

            val xid = event.opt("xid") as? String ?: return
            val count = (event.opt("count") as? Number)?.toDouble() ?: return
            if (!count.isFinite() || count < 1 || count > 9_007_199_254_740_991.0 || count % 1.0 != 0.0) return

            commands.acknowledge(
                xid,
                count.toLong(),
            )
        }
    }

    /**
     * Sends a generation task's trajectory.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param points Normalized media trajectory points from 0 to 1.
     */
    override fun sendTracks(
        taskId: String,
        points: List<RealtimePoint>,
    ) {
        if (taskId.isEmpty() || points.isEmpty()) return

        send(
            RoomEvent.tracks(
                userId = requireUserId(),
                taskId = taskId,
                points = points,
            ),
            replacingPendingMessage = "tracks:$taskId",
        )
    }

    /**
     * Sends a business signal to the joined RTC room.
     *
     * @param message The message to send or log.
     * @param replacingPendingMessage The coalescing key; retains only the latest unsent message for the same
     * key.
     * @param discardPending Whether to discard pending trajectories first.
     */
    private fun send(
        message: String,
        replacingPendingMessage: String? = null,
        discardPending: Boolean = false,
    ) {
        if (discardPending) rtcManager.discardPendingMessages()
        rtcManager.sendRoomMessage(
            message,
            replacingPendingMessage,
        )
        XmaxLogger.room.debug(
            message = { formatSignalLog(message) },
        )
    }

    /**
     * Formats signaling for readable diagnostics.
     *
     * @param message The message to send or log.
     * @return The formatted signal text.
     */
    internal fun formatSignalLog(
        message: String,
    ): String = try {
        val event = JSONObject(message)
        val eventType = event.optString(
            "event",
            "unknown",
        )
        val formattedMessage = event.toString(2).replace(
            "\n",
            "\n   ",
        )
        "发送房间信令 (Outbound Room Signaling)\n" +
            "├─ ${XmaxLogger.localized(
                "类型：",
                "Type: ",
            )}$eventType\n" +
            "└─ ${XmaxLogger.localized(
                "内容：",
                "Content: ",
            )}\n" +
            "   $formattedMessage"
    } catch (
        _: Throwable,
    ) {
        "发送房间信令 (Outbound Room Signaling)\n" +
            "└─ ${XmaxLogger.localized(
                "内容：",
                "Content: ",
            )}$message"
    }

    /**
     * Returns the joined room's user identifier or throws if no room is joined.
     *
     * @return The current joined-room user identifier.
     */
    private fun requireUserId(): String = synchronized(stateLock) {
        (state as? State.Joined)?.userId ?: throw XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = "RTC room is not joined",
        )
    }

    /**
     * The controller's lifecycle and publication state.
     */
    private sealed interface State {

        /**
         * No RTC room is joined.
         */
        data object Idle : State

        /**
         * The specified room join is pending.
         */
        data class Joining(
            /**
             * The room join operation identifier rejecting old results.
             */
            val id: UUID,
        ) : State

        /**
         * The room is joined and can send business signals.
         */
        data class Joined(
            /**
             * The room's business user identifier.
             */
            val userId: String,

            /**
             * The joined room identifier.
             */
            val roomId: String,

            /**
             * The expected service sender, or null when the session does not provide one.
             */
            val botId: String?,

            /**
             * The room lifetime rejecting stale receipts.
             */
            val operationId: UUID,
        ) : State

        /**
         * RTC room resources are being released.
         */
        data object Leaving : State
    }
}
