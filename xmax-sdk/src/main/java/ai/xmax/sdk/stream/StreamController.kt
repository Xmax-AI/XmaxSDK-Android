//
//  StreamController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream

import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.cleanupAfterFailure
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.RealtimeTiming
import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.RealtimePerformanceAlarmListener
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.UplinkVideoStatisticsListener
import ai.xmax.sdk.DownlinkVideoStatisticsListener
import ai.xmax.sdk.ErrorMessageFormatter
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.foundation.rtc.RtcEventListener
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import ai.xmax.sdk.stream.encoding.EncodingController
import ai.xmax.sdk.stream.encoding.EncodingControlling
import ai.xmax.sdk.stream.quality.QualityController
import ai.xmax.sdk.stream.quality.QualityControlling
import ai.xmax.sdk.stream.room.RoomController
import ai.xmax.sdk.stream.room.RoomControlling
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

/**
 * Coordinates RTC rooms, media streams, encoding, and quality events.
 */
internal class StreamController(
    /**
     * The manager for RTC capture, publication, subscription, and rendering.
     */
    private val rtcManager: RtcManaging,

    /**
     * The controller for room membership and business signaling.
     */
    private val roomController: RoomControlling = RoomController(rtcManager),

    /**
     * The controller resolving and applying uplink encoding settings.
     */
    private val encodingController: EncodingControlling = EncodingController(rtcManager),

    /**
     * The controller providing network statistics and automatic quality samples.
     */
    private val qualityController: QualityControlling = QualityController(rtcManager),

    /**
     * The media failure callback.
     */
    private val errorListener: (XmaxError) -> Unit = {},

    /**
     * The remote stream change callback; null indicates no active result stream.
     */
    private val remoteStreamListener: (RemoteStream?) -> Unit = {},

    /**
     * The generation startup timeout configuration; defaults to `StreamGenerationTiming.live`.
     */
    private val generationTiming: StreamGenerationTiming = StreamGenerationTiming.live,

    /**
     * The scope handling generation confirmation and completion.
     */
    private val generationScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    ),

    /**
     * The dispatcher for view binding and display updates; defaults to `Dispatchers.Main.immediate`.
     */
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : StreamControlling, RtcEventListener {

    /**
     * The lock protecting publication and subscription state.
     */
    private val stateLock = Any()

    /**
     * The mutex serializing generation events and completion notification.
     */
    private val eventGate = ReentrantLock()

    /**
     * The current room and publication state.
     */
    private var state = State()

    /**
     * The playback volume for remote generation audio.
     */
    private val remoteAudioVolume = AtomicInteger(100)

    init {
        rtcManager.setEventListener(this)
    }

    /**
     * Updates uplink dimensions, frame rate, and encoder bitrate.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     */
    override fun setVideoEncoderConfig(
        videoFormat: RealtimeVideoFormat,
    ) {
        encodingController.configure(videoFormat)
    }

    /**
     * Receives internal adaptation samples without occupying the public network listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setAdaptiveQualityListener(
        listener: ((RealtimeNetworkQuality) -> Unit)?,
    ) {
        qualityController.setAdaptiveQualityListener(listener)
    }

    /**
     * Sets the main-thread uplink statistics listener; null unregisters it. The listener survives
     * disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setUplinkVideoStatisticsListener(
        listener: UplinkVideoStatisticsListener?,
    ) {
        qualityController.setUplinkVideoStatisticsListener(listener)
    }

    /**
     * Sets the main-thread downlink statistics listener; null unregisters it. The listener survives
     * disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setDownlinkVideoStatisticsListener(
        listener: DownlinkVideoStatisticsListener?,
    ) {
        qualityController.setDownlinkVideoStatisticsListener(listener)
    }

    /**
     * Sets the RTC network quality listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setNetworkQualityListener(
        listener: RealtimeNetworkQualityListener?,
    ) {
        qualityController.setNetworkQualityListener(listener)
    }

    /**
     * Sets the RTC performance alarm listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setPerformanceAlarmListener(
        listener: RealtimePerformanceAlarmListener?,
    ) {
        qualityController.setPerformanceAlarmListener(listener)
    }

    /**
     * Sets result audio volume from 0 to 1 and retains it for future subscriptions.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    override fun setRemoteAudioVolume(
        volume: Float,
    ) {
        val rtcVolume = (volume * 100f).roundToInt().coerceIn(
            0,
            100,
        )
        val userIds = synchronized(stateLock) {
            state.subscribedRemoteAudioUserIds.toList()
        }
        userIds.forEach { rtcManager.setRemoteAudioVolume(
            rtcVolume,
            it,
        ) }
        remoteAudioVolume.set(rtcVolume)
    }

    /**
     * Joins RTC, publishes media according to its source, and enables appropriate uplink statistics.
     *
     * @param connection The server-provided RTC room, user, credentials, and result publisher.
     * @param includeLocalAudio Whether to publish local audio alongside video.
     * @param publishLocalMedia Whether to publish local media; false for server-read video.
     * @param ensureActive Checks operation validity and throws on cancellation.
     */
    override suspend fun connect(
        connection: RealtimeSessionConnection,
        includeLocalAudio: Boolean,
        publishLocalMedia: Boolean,
        ensureActive: () -> Unit,
    ) {
        try {
            // Prepare room identity before joining because remote publication can arrive as soon as entry completes.
            configureRoom(
                connection.roomId,
                connection.botName,
            )
            roomController.join(
                connection,
                ensureActive,
            )
            ensureActive()
            if (publishLocalMedia) {
                val timing = currentCoroutineContext()[RealtimeTiming.Attempt]
                timing?.mark(RealtimeTiming.Stage.PUBLICATION_START)
                publishLocalStream(includeLocalAudio)
                timing?.mark(RealtimeTiming.Stage.PUBLICATION_END)
            }
            qualityController.setUplinkVideoStatisticsEnabled(publishLocalMedia)
        } catch (
            error: Throwable,
        ) {
            cleanupAfterFailure(
                error,
                { resetStream() },
                { roomController.leave() },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * Clears local publication, remote subscriptions, and generation waits, then leaves the room.
     */
    override suspend fun disconnect() {
        cleanupResources(
            { resetStream() },
            { roomController.leave() },
        )
    }

    /**
     * Whether the current generation requests external input frames.
     */
    internal val isRequestingExternalFrames: Boolean
        get() = synchronized(stateLock) { state.generationTask != null }

    /**
     * Submits local video to RTC while generation requests input frames.
     *
     * @param frame The video frame to process, display, or submit.
     */
    override fun pushLocalVideoFrame(
        frame: VideoFrame,
    ) {
        if (!isRequestingExternalFrames) return
        try {
            rtcManager.pushExternalVideoFrame(
                frame,
                null,
            )
        } catch (
            error: Throwable,
        ) {
            XmaxLogger.rtc.error(
                message = {
                    "推送 RTC 外部视频帧失败 (Failed to Push External RTC Video Frame)\n" +
                        "├─ ${XmaxLogger.localized(
                            "格式：",
                            "Format: ",
                        )}${frame.format.pixelFormat.value}\n" +
                        "├─ ${XmaxLogger.localized(
                            "分辨率：",
                            "Resolution: ",
                        )}${frame.format.width} × ${frame.format.height}\n" +
                        "├─ ${XmaxLogger.localized(
                            "时间戳：",
                            "Timestamp: ",
                        )}${frame.timestampUs} us\n" +
                        "└─ ${XmaxLogger.localized(
                            "原因：",
                            "Reason: ",
                        )}${ErrorMessageFormatter.format(error)}"
                },
            )
            throw error
        }
    }

    /**
     * Submits audio to RTC while local audio is published.
     *
     * @param frame The PCM audio frame to play or submit.
     */
    override fun pushLocalAudioFrame(
        frame: AudioFrame,
    ) {
        if (!synchronized(stateLock) { state.localAudioPublished }) return
        rtcManager.pushExternalAudioFrame(frame)
    }

    /**
     * Starts generation and waits for result confirmation; output dimensions default to the generation
     * format, and uplink bitrate fields do not control downlink.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param context The generation prompt and reference media.
     * @param faceRestoration Whether to enable face restoration through `face_replace`.
     * @param targetVideoFormat The downlink output format.
     * @param paddingContain Whether the server fits input and removes padding from output.
     * @return A waiter completed on result confirmation or failed if startup fails.
     */
    override suspend fun beginGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean,
        targetVideoFormat: RealtimeVideoFormat?,
        paddingContain: Boolean,
    ): CompletableDeferred<Unit> {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Realtime generation task ID cannot be empty",
            )
        }
        val timing = currentCoroutineContext()[RealtimeTiming.Attempt]
        val waiter = synchronized(stateLock) {
            if (state.roomId.isEmpty()) {
                throw XmaxError(
                    XmaxErrorCode.RTC_ERROR,
                    "RTC room is not configured",
                )
            }
            if (state.generationTask != null) {
                throw XmaxError(
                    XmaxErrorCode.RTC_ERROR,
                    "Realtime generation is already active",
                )
            }
            GenerationWaiter(
                normalizedTaskId,
                timing,
            ).also {
                state.generationTask = GenerationTask(
                    normalizedTaskId,
                    context.referenceVideo != null,
                )
                state.generationWaiter = it
            }
        }
        waiter.timeoutJob = generationScope.launch {
            delay(generationTiming.timeoutMillis)
            rejectGenerationStart(
                normalizedTaskId,
                XmaxError(
                    XmaxErrorCode.TIMEOUT,
                    "Realtime generation start timed out",
                ),
            )
        }
        try {
            timing?.beginSignal(normalizedTaskId)
            roomController.startGeneration(
                normalizedTaskId,
                videoFormat,
                context,
                faceRestoration,
                targetVideoFormat,
                paddingContain,
            )
            withTimeout(generationTiming.timeoutMillis) { rtcManager.awaitPendingMessages() }
            synchronized(stateLock) { waiter.signalSent = true }
            // The Agora bot may publish before start; reuse its subscription but await a fresh first frame for this task.
            withContext(renderDispatcher) {
                eventGate.withLock {
                    val users = synchronized(stateLock) { state.subscribedRemoteUserIds.toList() }
                    users.forEach { confirmRemoteStream(
                        RemoteStream(
                            state.roomId,
                            it,
                        ),
                    ) }
                }
            }
            return waiter.result
        } catch (
            error: Throwable,
        ) {
            rejectGenerationStart(
                normalizedTaskId,
                error,
            )
            cleanupAfterFailure(
                error,
                { stopGeneration(normalizedTaskId) },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * Enables network video completion notification after both the server completion event and first frame.
     *
     * @param onFinish The completion callback for the network video task, or null to omit it.
     */
    override fun activateNetworkVideoCompletion(
        onFinish: (() -> Unit)?,
    ) {
        val task = synchronized(stateLock) {
            state.generationTask?.takeIf { it.isNetworkVideo && !it.completionCancelled }?.also {
                it.onFinish = onFinish
                it.completionReady = true
            }
        } ?: return
        scheduleNetworkVideoCompletion(task)
    }

    /**
     * Receives structured task notifications from remote room users.
     *
     * @param stream The remote stream identified by room and user.
     * @param message The message to send or log.
     */
    override fun onUserMessageReceived(
        stream: RemoteStream,
        message: String,
    ) {
        val event = runCatching { org.json.JSONObject(message) }.getOrNull() ?: return
        if (event.optString("event") != "video_stopped") return
        val task = synchronized(stateLock) {
            state.generationTask?.takeIf {
                it.isNetworkVideo && event.optString("uid") == it.id &&
                    stream.roomId == state.roomId && stream.userId.isNotBlank() &&
                    (state.botName.isEmpty() || stream.userId == state.botName)
            }?.also { it.finished = true }
        } ?: return
        scheduleNetworkVideoCompletion(task)
    }

    /**
     * Schedules completion notification for the current network video task.
     *
     * @param task The current generation task state.
     */
    private fun scheduleNetworkVideoCompletion(
        task: GenerationTask,
    ) {
        val shouldSchedule = synchronized(stateLock) {
            if (state.generationTask !== task || task.completionCancelled || !task.finished || !task.completionReady ||
                task.completionScheduled || task.onFinish == null
            ) false else {
                task.completionScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        generationScope.launch(renderDispatcher) {
            // Recheck task ownership on the main thread so queued notifications cannot reach a task created after close or retry.
            val callback = synchronized(stateLock) {
                task.onFinish.takeIf { state.generationTask === task && !task.completionCancelled }
            }
            runCatching { callback?.invoke() }.onFailure {
                XmaxLogger.stream.warn(message = { "Network video completion listener failed: ${it.message}" })
            }
        }
    }

    /**
     * Subscribes to result audio and applies remote playback volume.
     */
    override fun activateRemoteAudio() {
        eventGate.withLock {
            val remoteStream = synchronized(stateLock) {
                if (state.generationTask == null) null else state.activeRemoteStream
            } ?: throw XmaxError(
                XmaxErrorCode.RTC_ERROR,
                "Remote generation audio stream is unavailable",
            )
            subscribeRemoteAudio(remoteStream.userId)
        }
    }

    /**
     * Updates the current task's conditions without creating another server session.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param context The generation prompt and reference media.
     * @param faceRestoration Whether to enable face restoration through `face_replace`.
     * @param paddingContain Whether the server fits input and removes padding from output.
     */
    override suspend fun updateGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean,
        paddingContain: Boolean,
    ) {
        roomController.changeGenerationCondition(
            taskId,
            videoFormat,
            context,
            faceRestoration,
            paddingContain,
        )
    }

    /**
     * Sends a stop signal for the generation task.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     */
    override suspend fun stopGeneration(
        taskId: String,
    ) {
        val result = stopStreamGeneration(taskId) ?: return
        if (taskId.isNotEmpty() && result.taskId.isEmpty()) return
        roomController.stopGeneration(result.taskId)
    }

    /**
     * Updates downlink dimensions and bitrate, estimating bitrate at a fixed 24 fps.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param targetVideoFormat The downlink output format.
     * @param ensureActive Checks operation validity and throws on cancellation.
     */
    override suspend fun updateDownlinkVideoResolution(
        taskId: String,
        targetVideoFormat: RealtimeVideoFormat,
        ensureActive: () -> Unit,
    ) {
        eventGate.withLock {
            synchronized(stateLock) {
                ensureActive()
                val task = state.generationTask
                if (task == null || task.id != taskId || task.completionCancelled || state.activeRemoteStream == null) {
                    throw CancellationException("Downlink video update requires the current active generation")
                }

                roomController.changeTargetSize(
                    taskId,
                    targetVideoFormat,
                    ensureActive,
                )
            }
        }
    }

    /**
     * Submits media-normalized trajectories for the current task.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param points Normalized media trajectory points from 0 to 1.
     */
    override suspend fun sendTracks(
        taskId: String,
        points: List<RealtimePoint>,
    ) {
        roomController.sendTracks(
            taskId,
            points,
        )
    }

    /**
     * Handles remote publication changes only for the current room, excluding stale room events.
     *
     * @param stream The remote stream identified by room and user.
     * @param published Whether the remote video is published.
     */
    override fun onRemoteVideoPublished(
        stream: RemoteStream,
        published: Boolean,
    ) {
        eventGate.withLock {
            val normalizedUserId = stream.userId.trim()
            if (normalizedUserId.isEmpty()) return
            val currentState = synchronized(stateLock) { state.copy() }
            if (currentState.roomId.isEmpty() || currentState.roomId != stream.roomId ||
                (currentState.botName.isNotEmpty() && currentState.botName != normalizedUserId)
            ) {
                return
            }
            if (published) {
                subscribeRemoteVideo(normalizedUserId)
                confirmRemoteStream(stream)
            } else {
                val shouldClear = synchronized(stateLock) {
                    state.subscribedRemoteUserIds.remove(normalizedUserId)
                    if (state.activeRemoteStream?.userId == normalizedUserId) {
                        state.activeRemoteStream = null
                        true
                    } else {
                        false
                    }
                }
                unsubscribeRemoteAudio(normalizedUserId)
                if (shouldClear) clearRemoteStream()
            }
        }
    }

    /**
     * Reports an unrecoverable failure in the current RTC room.
     *
     * @param roomId The RTC room identifier.
     * @param error The operation failure or SDK error.
     */
    override fun onRoomTerminated(
        roomId: String,
        error: XmaxError,
    ) {
        if (synchronized(stateLock) { state.roomId == roomId }) reportError(error)
    }

    /**
     * Handles SEI carried by remote video.
     *
     * @param stream The remote stream identified by room and user.
     * @param message The message to send or log.
     */
    override fun onSeiMessageReceived(
        stream: RemoteStream,
        message: String,
    ) {
        // Agora readiness follows the target bot's publication, without SEI task matching.
    }

    /**
     * Confirms result-stream readiness for the current task, ignoring other tasks.
     *
     * @param stream The remote stream identified by room and user.
     */
    private fun confirmRemoteStream(
        stream: RemoteStream,
    ) {
        eventGate.withLock {
            val taskId: String
            val waiter: GenerationWaiter?
            synchronized(stateLock) {
                val task = state.generationTask ?: return
                if (stream.roomId != state.roomId || stream.userId !in state.subscribedRemoteUserIds ||
                    (state.botName.isNotEmpty() && stream.userId != state.botName)
                ) return

                waiter = state.generationWaiter
                if (waiter != null && !waiter.signalSent) return
                if (waiter == null && state.activeRemoteStream == stream) return
                taskId = task.id
            }

            try {
                waiter?.timing?.confirmRemoteStream(taskId)
                remoteStreamListener(stream)
                qualityController.setDownlinkVideoStatisticsUser(stream.userId)
                synchronized(stateLock) {
                    if (state.generationTask?.id == taskId) state.activeRemoteStream = stream
                }
                resolveGenerationStart(taskId)
            } catch (
                error: Throwable,
            ) {
                if (waiter != null) rejectGenerationStart(
                    taskId,
                    XmaxError.from(error),
                )
                else reportError(XmaxError.from(error))
            }
        }
    }

    /**
     * Sets the room and bot identities used by subscription and signaling.
     *
     * @param roomId The RTC room identifier.
     * @param botName The user publishing generated results.
     */
    private fun configureRoom(
        roomId: String,
        botName: String?,
    ) {
        val normalizedRoomId = roomId.trim()
        if (normalizedRoomId.isEmpty()) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "RTC room ID cannot be empty",
            )
        }
        synchronized(stateLock) {
            if (state.localVideoPublished || state.localAudioPublished ||
                state.subscribedRemoteUserIds.isNotEmpty() || state.generationTask != null
            ) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Reset the current RTC room before configuring another one",
                )
            }
            state.roomId = normalizedRoomId
            state.botName = botName?.trim().orEmpty()
        }
    }

    /**
     * Publishes local video and optional audio, rolling back partial publication on failure.
     *
     * @param includeAudio Whether to publish local audio.
     */
    private fun publishLocalStream(
        includeAudio: Boolean,
    ) {
        val currentState = synchronized(stateLock) { state.copy() }
        if (currentState.roomId.isEmpty()) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Configure an RTC room before publishing the local stream",
            )
        }
        var publishedVideo = false
        try {
            if (!currentState.localVideoPublished) {
                rtcManager.publishLocalVideo()
                synchronized(stateLock) { state.localVideoPublished = true }
                publishedVideo = true
            }
            if (includeAudio && !currentState.localAudioPublished) {
                rtcManager.publishLocalAudio()
                synchronized(stateLock) { state.localAudioPublished = true }
            }
        } catch (
            error: Throwable,
        ) {
            if (publishedVideo) {
                performCleanup(
                    "回滚 RTC 本地视频发布失败 " +
                        "(Failed to Roll Back RTC Local Video Publication)",
                ) {
                    rtcManager.unpublishLocalVideo()
                }
            }
            throw XmaxError.from(error)
        }
    }

    /**
     * Clears stream state, local publication, and remote subscriptions.
     */
    private suspend fun resetStream() {
        qualityController.setUplinkVideoStatisticsEnabled(false)
        stopStreamGeneration("")
        val previous = synchronized(stateLock) {
            state.also { state = State() }
        }
        previous.subscribedRemoteUserIds.sorted().forEach {
            performCleanup(
                "取消订阅 RTC 远端视频失败 (Failed to Unsubscribe RTC Remote Video)",
            ) {
                rtcManager.subscribeRemoteVideo(
                    it,
                    false,
                )
            }
        }
        if (previous.localAudioPublished) {
            performCleanup(
                "取消发布 RTC 本地音频失败 (Failed to Unpublish RTC Local Audio)",
            ) {
                rtcManager.unpublishLocalAudio()
            }
        }
        if (previous.localVideoPublished) {
            performCleanup(
                "取消发布 RTC 本地视频失败 (Failed to Unpublish RTC Local Video)",
            ) {
                rtcManager.unpublishLocalVideo()
            }
        }
    }

    /**
     * Removes the current task and waiter, retaining a cleanup snapshot.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @return Resources to clean up for the stopped task, or null without a matching task.
     */
    private suspend fun stopStreamGeneration(
        taskId: String,
    ): StopResult? {
        // Invalidate queued completion notifications before dispatching display cleanup to the main thread.
        synchronized(stateLock) {
            state.generationTask?.takeIf { taskId.isEmpty() || it.id == taskId }?.let {
                it.completionCancelled = true
                it.onFinish = null
            }
        }
        return withContext(NonCancellable + renderDispatcher) {
            // Restore local preview before clearing the remote canvas on the main thread; do not hold event or rendering locks during dispatch.
            eventGate.withLock {
                val result = synchronized(stateLock) {
                    val currentTaskId = state.generationTask?.id.orEmpty()
                    if (taskId.isNotEmpty() && taskId != currentTaskId) return@withContext null
                    StopResult(
                        taskId = currentTaskId,
                        waiter = state.generationWaiter,
                        remoteAudioUserIds = state.subscribedRemoteAudioUserIds.toSet(),
                    ).also {
                        state.generationTask = null
                        state.generationWaiter = null
                        state.activeRemoteStream = null
                        state.subscribedRemoteAudioUserIds.clear()
                    }
                }
                result.waiter?.let {
                    it.timeoutJob?.cancel()
                    it.result.completeExceptionally(
                        CancellationException("Realtime generation start cancelled"),
                    )
                }
                result.remoteAudioUserIds.sorted().forEach {
                    performCleanup(
                        "取消订阅 RTC 远端音频失败 (Failed to Unsubscribe RTC Remote Audio)",
                    ) {
                        rtcManager.subscribeRemoteAudio(
                            it,
                            false,
                        )
                    }
                }
                clearRemoteStream()
                result
            }
        }
    }

    /**
     * Subscribes to video from the current result publisher.
     *
     * @param userId The room's business user identifier.
     */
    private fun subscribeRemoteVideo(
        userId: String,
    ) {
        if (synchronized(stateLock) { userId in state.subscribedRemoteUserIds }) return
        try {
            rtcManager.subscribeRemoteVideo(
                userId,
                true,
            )
            synchronized(stateLock) { state.subscribedRemoteUserIds += userId }
        } catch (
            error: Throwable,
        ) {
            val xmaxError = XmaxError.from(error)
            val taskId = synchronized(stateLock) { state.generationWaiter?.taskId }
            if (taskId != null) rejectGenerationStart(
                taskId,
                xmaxError,
            ) else reportError(xmaxError)
        }
    }

    /**
     * Subscribes to audio from the current result publisher.
     *
     * @param userId The room's business user identifier.
     */
    private fun subscribeRemoteAudio(
        userId: String,
    ) {
        if (synchronized(stateLock) { userId in state.subscribedRemoteAudioUserIds }) return
        rtcManager.setRemoteAudioVolume(
            remoteAudioVolume.get(),
            userId,
        )
        rtcManager.subscribeRemoteAudio(
            userId,
            true,
        )
        synchronized(stateLock) { state.subscribedRemoteAudioUserIds += userId }
    }

    /**
     * Unsubscribes from a remote user's audio.
     *
     * @param userId The room's business user identifier.
     */
    private fun unsubscribeRemoteAudio(
        userId: String,
    ) {
        val subscribed = synchronized(stateLock) {
            state.subscribedRemoteAudioUserIds.remove(userId)
        }
        if (subscribed) {
            performCleanup(
                "取消订阅 RTC 远端音频失败 (Failed to Unsubscribe RTC Remote Audio)",
            ) {
                rtcManager.subscribeRemoteAudio(
                    userId,
                    false,
                )
            }
        }
    }

    /**
     * Completes the matching task's generation startup waiter.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     */
    private fun resolveGenerationStart(
        taskId: String,
    ) {
        val waiter = synchronized(stateLock) {
            state.generationWaiter?.takeIf { it.taskId == taskId }?.also {
                state.generationWaiter = null
            }
        } ?: return
        waiter.timeoutJob?.cancel()
        waiter.result.complete(Unit)
    }

    /**
     * Fails the matching task's startup waiter with the supplied error.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param error The operation failure or SDK error.
     */
    private fun rejectGenerationStart(
        taskId: String,
        error: Throwable,
    ) {
        val waiter = synchronized(stateLock) {
            state.generationWaiter?.takeIf { it.taskId == taskId }?.also {
                state.generationWaiter = null
            }
        } ?: return
        waiter.timeoutJob?.cancel()
        waiter.result.completeExceptionally(error)
    }

    /**
     * Clears the result stream and asks Render to unbind it.
     */
    private fun clearRemoteStream() {
        qualityController.setDownlinkVideoStatisticsUser(null)
        runCatching { remoteStreamListener(null) }
            .onFailure {
                XmaxLogger.stream.error(
                    message = {
                        "清理 RTC 远端生成流失败 " +
                            "(Failed to Clean Up RTC Remote Generation Stream)\n" +
                            "└─ ${XmaxLogger.localized(
                                "原因：",
                                "Reason: ",
                            )}${ErrorMessageFormatter.format(it)}"
                    },
                )
            }
    }

    /**
     * Attempts cleanup and logs failures without preventing other resource release.
     *
     * @param title The log entry title.
     * @param action The operation to perform.
     */
    private inline fun performCleanup(
        title: String,
        action: () -> Unit,
    ) {
        runCatching(action).onFailure { error ->
            XmaxLogger.stream.error(
                message = {
                    "$title\n└─ ${XmaxLogger.localized(
                        "原因：",
                        "Reason: ",
                    )}${ErrorMessageFormatter.format(error)}"
                },
            )
        }
    }

    /**
     * Reports a failure in the current media input or generation task.
     *
     * @param error The operation failure or SDK error.
     */
    private fun reportError(
        error: XmaxError,
    ) {
        runCatching { errorListener(error) }
    }

    /**
     * The controller's lifecycle and publication state.
     */
    private data class State(
        /**
         * The RTC room identifier.
         */
        var roomId: String = "",

        /**
         * The user publishing generated results.
         */
        var botName: String = "",

        /**
         * Whether local video is published; defaults to false.
         */
        var localVideoPublished: Boolean = false,

        /**
         * Whether local audio is published; defaults to false.
         */
        var localAudioPublished: Boolean = false,

        /**
         * Remote users whose video is subscribed.
         */
        val subscribedRemoteUserIds: MutableSet<String> = mutableSetOf(),

        /**
         * Remote users whose audio is subscribed.
         */
        val subscribedRemoteAudioUserIds: MutableSet<String> = mutableSetOf(),

        /**
         * The current task, or null without active generation.
         */
        var generationTask: GenerationTask? = null,

        /**
         * The startup waiter, or null when no start is pending.
         */
        var generationWaiter: GenerationWaiter? = null,

        /**
         * The current result stream, or null if absent.
         */
        var activeRemoteStream: RemoteStream? = null,
    )

    /**
     * The generation kind and completion state of the current task.
     */
    private class GenerationTask(
        /**
         * The identifier associating generation requests, signals, and result frames.
         */
        val id: String,

        /**
         * Whether this task reads network video directly on the server.
         */
        val isNetworkVideo: Boolean,
    ) {

        /**
         * Whether the network video task is ready to deliver completion.
         */
        var finished = false

        /**
         * Whether this generation's completion notification was cancelled.
         */
        var completionCancelled = false

        /**
         * Whether this generation has met its completion conditions.
         */
        var completionReady = false

        /**
         * Whether this generation's completion callback is scheduled.
         */
        var completionScheduled = false

        /**
         * The generation completion callback.
         */
        var onFinish: (() -> Unit)? = null
    }

    /**
     * A generation task and its startup waiter.
     */
    private class GenerationWaiter(
        /**
         * The task identifier associating generation requests with result streams.
         */
        val taskId: String,

        /**
         * The recorder for generation startup timing.
         */
        val timing: RealtimeTiming.Attempt?,
    ) {

        /**
         * The waiter for generation result confirmation.
         */
        val result = CompletableDeferred<Unit>()

        /**
         * The generation startup timeout job.
         */
        var timeoutJob: Job? = null

        /**
         * Whether the start signal has been sent.
         */
        var signalSent = false
    }

    /**
     * A stopped task's waiter and audio subscriptions awaiting cleanup.
     */
    private data class StopResult(
        /**
         * The task identifier associating generation requests with result streams.
         */
        val taskId: String,

        /**
         * The generation waiter to complete or cancel.
         */
        val waiter: GenerationWaiter?,

        /**
         * Remote users whose audio must be unsubscribed.
         */
        val remoteAudioUserIds: Set<String>,
    )
}

/**
 * The generation confirmation timeout.
 */
internal data class StreamGenerationTiming(
    /**
     * The maximum operation wait, in milliseconds.
     */
    val timeoutMillis: Long,
) {

    /**
     * The default generation startup timeout configuration.
     */
    companion object {

        /**
         * A generation timing configuration with a 30-second startup timeout.
         */
        val live = StreamGenerationTiming(timeoutMillis = 30_000L)
    }
}
