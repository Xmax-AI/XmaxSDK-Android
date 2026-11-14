//
//  RtcManager.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import android.content.Context
import android.view.View
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import ai.xmax.sdk.cleanupResources
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ai.xmax.sdk.cleanupAfterFailure
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Manages RTC leases, connection waits, and event validity.
 *
 * @property engineManager The manager owning the process-wide RTC engine.
 * @property joinTimeoutMillis The room join timeout in milliseconds; defaults to `JOIN_TIMEOUT_MILLIS`.
 * @param callbackScope The scope dispatching asynchronous callbacks.
 */
internal class RtcManager(
    private val engineManager: RtcEngineManager,
    private val joinTimeoutMillis: Long = JOIN_TIMEOUT_MILLIS,
    callbackScope: CoroutineScope? = null,
) : RtcManaging {

    /**
     * The scope dispatching RTC callbacks.
     */
    private val eventCallbackScope: CoroutineScope by lazy {
        callbackScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    /**
     * The gate protecting native engine and room access.
     */
    private val lifecycleMutex = Mutex()

    /**
     * Native resources remain valid during reads; destruction waits for all readers.
     */
    private val nativeGate = ReentrantReadWriteLock(true)

    /**
     * The lock protecting room and callback registrations.
     */
    private val stateLock = Any()

    /**
     * The current engine lease.
     */
    private var engineLease: RtcEngineLease? = null

    /**
     * The connected room context.
     */
    private var activeRoom: RoomContext? = null

    /**
     * The pending room join.
     */
    private var pendingJoin: PendingJoin? = null

    /**
     * The RTC event listener.
     */
    private var eventListener: WeakReference<RtcEventListener>? = null

    /**
     * The receiver for current-room command receipts.
     */
    private var roomMessageListener: ((RemoteStream, String) -> Unit)? = null

    /**
     * The RTC network quality and statistics listener.
     */
    private var qualityListener: WeakReference<RtcQualityListener>? = null

    /**
     * The remote frame subscription registration.
     */
    private var remoteFrameRegistration: RemoteFrameRegistration? = null

    /**
     * The PCM receiver bound to the active room and engine lease.
     */
    private var remoteAudioRegistration: RemoteAudioRegistration? = null

    /**
     * Creates an RTC manager.
     *
     * @param context The Android context used for system services and media resources.
     */
    internal constructor(
        context: Context,
    ) : this(
        engineManager = RtcEngineManager.shared(context.applicationContext),
    )

    /**
     * Initializes the RTC engine without acquiring another lease on repeated calls.
     */
    override suspend fun initialize() {
        lifecycleMutex.withLock {
            if (synchronized(stateLock) { engineLease != null }) return

            val lease = try {
                engineManager.acquire()
            } catch (
                error: CancellationException,
            ) {
                throw error
            } catch (
                error: Throwable,
            ) {
                throw XmaxError.from(error)
            }

            try {
                nativeGate.write {
                    synchronized(stateLock) { engineLease = lease }
                    val bridge = object : RtcEventListener {

                        /**
                         * Handles remote publication changes only for the current room, excluding stale room
                         * events.
                         *
                         * @param stream The remote stream identified by room and user.
                         * @param published Whether the remote video is published.
                         */
                        override fun onRemoteVideoPublished(
                            stream: RemoteStream,
                            published: Boolean,
                        ) {
                            if (isCurrentLease(lease)) handleRemoteVideoPublished(
                                stream,
                                published,
                            )
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
                            if (isCurrentLease(lease)) handleUserMessageReceived(
                                stream,
                                message,
                            )
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
                            if (isCurrentLease(lease)) handleSeiMessageReceived(
                                stream,
                                message,
                            )
                        }
                    }
                    lease.engine.setEventListener(bridge)
                    lease.engine.setQualityListener(synchronized(stateLock) { qualityListener?.get() })
                }
            } catch (
                error: Throwable,
            ) {
                cleanupAfterFailure(
                    error,
                    {
                        nativeGate.write { synchronized(stateLock) { engineLease = null } }
                        engineManager.release(lease)
                    },
                )
                throw XmaxError.from(error)
            }
        }
    }

    /**
     * Leaves the room and destroys the RTC engine; repeated calls are safe.
     */
    override suspend fun destroy() = withContext(NonCancellable) {
        lifecycleMutex.withLock {
            var lease: RtcEngineLease? = null
            cleanupResources(
                { nativeGate.write { leaveRoomLocked() } },
                { nativeGate.write {
                    lease = synchronized(stateLock) {
                        engineLease.also {
                            engineLease = null
                            remoteFrameRegistration = null
                        }
                    }
                    lease?.engine?.setEventListener(null)
                    lease?.engine?.setQualityListener(null)
                } },
                { lease?.let(engineManager::release) },
            )
        }
    }

    /**
     * Applies the uplink encoder configuration.
     *
     * @param configuration Uplink dimensions, frame rate, bitrate, and encoding preference.
     */
    override fun configureVideoEncoding(
        configuration: VideoEncodingConfiguration,
    ) {
        nativeGate.read {
            validateVideoDimensions(
                width = configuration.width,
                height = configuration.height,
                frameRate = configuration.frameRate,
            )
            val engine = synchronized(stateLock) {
                engineLease?.engine
            } ?: throw rtcError("RTC Engine is not initialized")
            val result = try {
                engine.configureVideoEncoding(configuration)
            } catch (
                error: Throwable,
            ) {
                throw rtcOperationError(
                    "setVideoEncoderConfig",
                    error,
                )
            }
            if (result < 0) {
                throw rtcResultError(
                    "setVideoEncoderConfig",
                    result,
                )
            }
        }
    }

    /**
     * Submits one external video frame with optional SEI metadata.
     *
     * @param frame The video frame to process, display, or submit.
     * @param seiData Optional video metadata; unused by the current Agora implementation.
     */
    override fun pushExternalVideoFrame(
        frame: VideoFrame,
        seiData: ByteArray?,
    ) {
        withEngine("pushExternalVideoFrame") { engine ->
            engine.pushExternalVideoFrame(
                frame,
                seiData,
            )
        }
    }

    /**
     * Submits one external 10 ms PCM audio frame.
     *
     * @param frame The PCM audio frame to play or submit.
     */
    override fun pushExternalAudioFrame(
        frame: AudioFrame,
    ) {
        withEngine("pushExternalAudioFrame") { engine ->
            engine.pushExternalAudioFrame(frame)
        }
    }

    /**
     * Selects external frames as the primary video source.
     */
    override fun useExternalVideoSource() {
        performEngineOperation("setVideoSourceType") {
            it.useExternalVideoSource()
        }
    }

    /**
     * Selects external PCM as the primary audio source.
     */
    override fun startExternalAudioSource() {
        performEngineOperation("setAudioSourceType") {
            it.startExternalAudioSource()
        }
    }

    /**
     * Stops RTC external audio input.
     */
    override fun stopExternalAudioSource() {
        performOptionalEngineOperation("stopExternalAudioSource") {
            it.stopExternalAudioSource()
        }
    }

    /**
     * Binds a remote stream to its rendering view.
     *
     * @param stream The remote stream identified by room and user.
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    override fun bindRemoteVideo(
        stream: RemoteStream,
        view: View,
        contentMode: VideoContentMode,
    ) {
        requireActiveRemoteStream(stream)
        performEngineOperation("setRemoteVideoCanvas") {
            it.bindRemoteVideo(
                stream.userId,
                view,
                contentMode,
            )
        }
    }

    /**
     * Unbinds a remote stream's rendering view.
     *
     * @param stream The remote stream identified by room and user.
     */
    override fun unbindRemoteVideo(
        stream: RemoteStream,
    ) {
        performOptionalEngineOperation("setRemoteVideoCanvas") {
            it.unbindRemoteVideo(stream.userId)
        }
    }

    /**
     * Joins an RTC room and waits for join confirmation.
     *
     * @param configuration The room, user account, App ID, and room token.
     */
    override suspend fun joinRoom(
        configuration: RoomJoinConfiguration,
    ) {
        val normalizedConfiguration = configuration.normalized()
        val pending = lifecycleMutex.withLock {
            beginJoin(normalizedConfiguration)
        }

        try {
            withTimeout(joinTimeoutMillis) {
                pending.result.await()
            }
        } catch (
            error: TimeoutCancellationException,
        ) {
            currentCoroutineContext().ensureActive()
            val timeoutError = XmaxError(
                code = XmaxErrorCode.TIMEOUT,
                message = "RTC join room timed out",
                cause = error,
            )
            cleanupPendingJoin(
                pending.id,
                timeoutError,
            )
            throw timeoutError
        } catch (
            error: CancellationException,
        ) {
            cleanupPendingJoin(
                pending.id,
                error,
            )
            throw error
        } catch (
            error: Throwable,
        ) {
            throw XmaxError.from(error)
        }
    }

    /**
     * Leaves the active or pending room; repeated calls are safe.
     */
    override suspend fun leaveRoom() {
        lifecycleMutex.withLock {
            leaveRoomLocked()
        }
    }

    /**
     * Publishes local video.
     */
    override fun publishLocalVideo() {
        performRoomOperation("publishStreamVideo") {
            it.publishLocalVideo(true)
        }
    }

    /**
     * Unpublishes local video, safely returning if no room is active.
     */
    override fun unpublishLocalVideo() {
        performOptionalRoomOperation("publishStreamVideo") {
            it.publishLocalVideo(false)
        }
    }

    /**
     * Publishes local audio.
     */
    override fun publishLocalAudio() {
        performRoomOperation("publishStreamAudio") {
            it.publishLocalAudio(true)
        }
    }

    /**
     * Unpublishes local audio, safely returning if no room is active.
     */
    override fun unpublishLocalAudio() {
        performOptionalRoomOperation("publishStreamAudio") {
            it.publishLocalAudio(false)
        }
    }

    /**
     * Updates remote video subscription.
     *
     * @param userId The room's business user identifier.
     * @param subscribe True to subscribe, false to unsubscribe.
     */
    override fun subscribeRemoteVideo(
        userId: String,
        subscribe: Boolean,
    ) {
        val normalizedUserId = normalizeRemoteUserId(userId)
        performRoomOperation("subscribeStreamVideo") {
            it.subscribeRemoteVideo(
                normalizedUserId,
                subscribe,
            )
        }
    }

    /**
     * Updates remote audio subscription.
     *
     * @param userId The room's business user identifier.
     * @param subscribe True to subscribe, false to unsubscribe.
     */
    override fun subscribeRemoteAudio(
        userId: String,
        subscribe: Boolean,
    ) {
        val normalizedUserId = normalizeRemoteUserId(userId)
        performRoomOperation("subscribeStreamAudio") {
            it.subscribeRemoteAudio(
                normalizedUserId,
                subscribe,
            )
        }
    }

    /**
     * Sets playback volume for a remote user's audio.
     *
     * @param volume The remote volume, from 0 to 100.
     * @param userId The room's business user identifier.
     */
    override fun setRemoteAudioVolume(
        volume: Int,
        userId: String,
    ) {
        nativeGate.read {
            val normalizedUserId = normalizeRemoteUserId(userId)
            if (volume !in 0..100) {
                throw XmaxError(
                    code = XmaxErrorCode.INVALID_CONFIGURATION,
                    message = "RTC audio volume must be between 0 and 100",
                )
            }

            val resources = synchronized(stateLock) {
                val engine = engineLease?.engine ?: throw rtcError("RTC Engine is not initialized")
                val streamId = activeRoom?.room?.resolveRemoteStreamId(normalizedUserId)
                    ?: normalizedUserId
                engine to streamId
            }
            val result = try {
                resources.first.setRemoteAudioVolume(
                    resources.second,
                    volume,
                )
            } catch (
                error: Throwable,
            ) {
                throw rtcOperationError(
                    "setRemoteAudioPlaybackVolume",
                    error,
                )
            }

            checkResult(
                "setRemoteAudioPlaybackVolume",
                result,
            )
        }
    }

    /**
     * Sends a message to the current RTC room.
     *
     * @param message The message to send or log.
     * @param replacingPendingMessage The coalescing key; retains only the latest unsent message for the same
     * key.
     * @param reportFailure Whether asynchronous send failure is reported as a room failure.
     * @return The complete send result; cancelling it discards remaining unsent packets.
     */
    override fun sendRoomMessage(
        message: String,
        replacingPendingMessage: String?,
        reportFailure: Boolean,
    ): Deferred<Unit> = nativeGate.read {
        if (message.isEmpty()) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "RTC room message cannot be empty",
            )
        }

        val room = synchronized(stateLock) {
            activeRoom?.room
        } ?: throw rtcError("RTC room is not joined")

        try {
            room.sendRoomMessage(
                message,
                replacingPendingMessage,
                reportFailure,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: XmaxError) {
            throw error
        } catch (error: Throwable) {
            throw rtcOperationError(
                "sendRoomMessage",
                error,
            )
        }
    }

    /**
     * Discards unsent coalescible signals for the current room.
     *
     * @param key The coalescing key to discard, or null for all coalescible signals.
     */
    override fun discardPendingMessages(
        key: String?,
    ) {
        nativeGate.read {
            val room = synchronized(stateLock) { activeRoom?.room }

            room?.discardPendingMessages(key)
        }
    }

    /**
     * Sets the receiver for current-room command receipts.
     *
     * @param listener The room message receiver, or null to unregister it.
     */
    override fun setRoomMessageListener(
        listener: ((RemoteStream, String) -> Unit)?,
    ) {
        synchronized(stateLock) { roomMessageListener = listener }
    }

    /**
     * Waits for queued room signals to be submitted without blocking the main thread.
     */
    override suspend fun awaitPendingMessages() {
        val room = synchronized(stateLock) { activeRoom?.room } ?: throw rtcError("RTC room is not joined")

        room.awaitPendingMessages()
    }

    /**
     * Sets the RTC connection and media event listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setEventListener(
        listener: RtcEventListener?,
    ) {
        synchronized(stateLock) {
            eventListener = listener?.let(::WeakReference)
        }
    }

    /**
     * Registers continuous remote frames for the current generation; clearing it also stops the receiver's
     * remote rendering.
     *
     * @param stream The remote stream identified by room and user.
     * @param listener The event listener, or null to unregister it.
     */
    override fun setRemoteVideoFrameListener(
        stream: RemoteStream,
        listener: RtcRemoteVideoSink?,
    ) {
        nativeGate.write {
            if (listener == null) {
                if (synchronized(stateLock) { remoteFrameRegistration?.stream == stream }) {
                    clearRemoteFrameRegistrationLocked()
                }

                return
            }

            val registration = synchronized(stateLock) {
                val lease = engineLease ?: throw rtcError("RTC Engine is not initialized")
                val room = activeRoom?.takeIf { it.roomId == stream.roomId }
                    ?: throw rtcError("Remote stream does not belong to the active RTC room")
                RemoteFrameRegistration(
                    stream,
                    room.room.resolveRemoteStreamId(stream.userId),
                    room,
                    lease,
                    listener,
                )
            }

            clearRemoteFrameRegistrationLocked()
            synchronized(stateLock) { remoteFrameRegistration = registration }

            try {
                val result = registration.lease.engine.setRemoteVideoFrameListener(
                    registration.streamId,
                    object : RtcRemoteVideoSink {

                        /**
                         * Reports the first subscribed frame and its dimensions.
                         *
                         * @param width The frame width, in pixels.
                         * @param height The frame height, in pixels.
                         */
                        override fun onFirstFrame(
                            width: Int,
                            height: Int,
                        ) {
                            handleRemoteVideoFrame(
                                registration,
                                width,
                                height,
                            )
                        }

                        /**
                         * Receives a frame from the subscribed video stream.
                         *
                         * @param frame The video frame to process, display, or submit.
                         */
                        override fun onFrame(
                            frame: RtcRemoteVideoFrame,
                        ) {
                            // Native frames cannot cross threads; release nativeGate and stateLock before calling consumers.
                            val active = synchronized(stateLock) { isCurrentFrameRegistration(registration) }
                            if (active) registration.listener.onFrame(frame)
                        }

                        /**
                         * Reports a failure in the current media or RTC operation.
                         *
                         * @param error The operation failure or SDK error.
                         */
                        override fun onError(
                            error: XmaxError,
                        ) {
                            eventCallbackScope.launch {
                                val active = synchronized(stateLock) { isCurrentFrameRegistration(registration) }
                                if (active) registration.listener.onError(error)
                            }
                        }
                    },
                )
                if (result < 0) throw rtcResultError(
                    "setRemoteVideoSink",
                    result,
                )
            } catch (
                error: Throwable,
            ) {
                runCatching { clearRemoteFrameRegistrationLocked() }
                    .onFailure { if (it !== error) error.addSuppressed(it) }
                if (error is CancellationException || error is XmaxError) throw error

                throw rtcOperationError(
                    "setRemoteVideoSink",
                    error,
                )
            }
        }
    }

    /**
     * Unregisters remote frames so invalid subscriptions cannot deliver further callbacks.
     */
    private fun clearRemoteFrameRegistrationLocked() {
        val registration = synchronized(stateLock) {
            remoteFrameRegistration?.also { it.isActive = false }
        } ?: return
        val result = registration.lease.engine.setRemoteVideoFrameListener(
            registration.streamId,
            null,
        )
        if (result < 0) throw rtcResultError(
            "setRemoteVideoSink",
            result,
        )

        synchronized(stateLock) { remoteFrameRegistration = null }
    }

    /**
     * Registers PCM output for the active remote stream and rejects obsolete room callbacks.
     *
     * @param stream The remote stream identified by room and user.
     * @param listener The PCM receiver, or null to unregister it.
     */
    override fun setRemoteAudioFrameListener(
        stream: RemoteStream,
        listener: RtcRemoteAudioFrameListener?,
    ) = nativeGate.write {
        if (listener == null) {
            if (synchronized(stateLock) { remoteAudioRegistration?.stream == stream }) {
                clearRemoteAudioRegistrationLocked()
            }
            return@write
        }

        val registration = synchronized(stateLock) {
            val lease = engineLease ?: throw rtcError("RTC Engine is not initialized")
            val room = activeRoom?.takeIf { it.roomId == stream.roomId }
                ?: throw rtcError("Remote stream does not belong to the active RTC room")
            RemoteAudioRegistration(
                stream,
                room.room.resolveRemoteStreamId(stream.userId),
                room,
                lease,
                listener,
            )
        }
        clearRemoteAudioRegistrationLocked()
        synchronized(stateLock) { remoteAudioRegistration = registration }
        try {
            val result = registration.lease.engine.setRemoteAudioFrameListener(registration.streamId) { frame ->
                val active = synchronized(stateLock) {
                    registration.isActive && remoteAudioRegistration === registration &&
                        activeRoom === registration.room && engineLease === registration.lease
                }
                if (active) registration.listener.onFrame(frame)
            }
            if (result < 0) throw rtcResultError(
                "setRemoteAudioFrameListener",
                result,
            )
        } catch (
            error: Throwable,
        ) {
            runCatching { clearRemoteAudioRegistrationLocked() }
                .onFailure { if (it !== error) error.addSuppressed(it) }
            if (error is CancellationException || error is XmaxError) throw error

            throw rtcOperationError(
                "setRemoteAudioFrameListener",
                error,
            )
        }
    }

    /**
     * Invalidates PCM delivery before unregistering the native receiver.
     */
    private fun clearRemoteAudioRegistrationLocked() {
        val registration = synchronized(stateLock) {
            remoteAudioRegistration?.also { it.isActive = false }
        } ?: return
        val result = registration.lease.engine.setRemoteAudioFrameListener(
            registration.streamId,
            null,
        )
        if (result < 0) throw rtcResultError(
            "setRemoteAudioFrameListener",
            result,
        )

        synchronized(stateLock) { remoteAudioRegistration = null }
    }

    /**
     * A PCM registration tied to one room and engine lease.
     *
     * @property stream The owning remote stream.
     * @property streamId The native publisher identifier.
     * @property room The owning room context.
     * @property lease The owning engine lease.
     * @property listener The PCM receiver.
     * @property isActive Whether native callbacks may still be delivered.
     */
    private class RemoteAudioRegistration(
        val stream: RemoteStream,
        val streamId: String,
        val room: RoomContext,
        val lease: RtcEngineLease,
        val listener: RtcRemoteAudioFrameListener,
        var isActive: Boolean = true,
    )

    /**
     * Sets the RTC network and media quality listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setQualityListener(
        listener: RtcQualityListener?,
    ) {
        nativeGate.read {
            synchronized(stateLock) {
                qualityListener = listener?.let(::WeakReference)
                engineLease?.engine?.setQualityListener(listener)
            }
        }
    }

    /**
     * Runs an operation on the current room and converts negative RTC results to SDK errors.
     *
     * @param operation The RTC operation name.
     * @param action The operation to perform.
     */
    private fun performRoomOperation(
        operation: String,
        action: (RtcRoom) -> Int,
    ) {
        nativeGate.read {
            val room = synchronized(stateLock) {
                activeRoom?.room
            } ?: throw rtcError("RTC room is not joined")

            performRoomOperation(
                room,
                operation,
                action,
            )
        }
    }

    /**
     * Runs an operation on the current engine and converts negative RTC results to SDK errors.
     *
     * @param operation The RTC operation name.
     * @param action The operation to perform.
     */
    private fun performEngineOperation(
        operation: String,
        action: (RtcPlatform) -> Int,
    ) {
        nativeGate.read {
            val engine = synchronized(stateLock) {
                engineLease?.engine
            } ?: throw rtcError("RTC Engine is not initialized")
            val result = try {
                action(engine)
            } catch (
                error: XmaxError,
            ) {
                throw error
            } catch (
                error: Throwable,
            ) {
                throw rtcOperationError(
                    operation,
                    error,
                )
            }

            checkResult(
                operation,
                result,
            )
        }
    }

    /**
     * Runs and validates an operation if a room exists; otherwise does nothing.
     *
     * @param operation The RTC operation name.
     * @param action The operation to perform.
     */
    private fun performOptionalRoomOperation(
        operation: String,
        action: (RtcRoom) -> Int,
    ) {
        nativeGate.read {
            val room = synchronized(stateLock) {
                activeRoom?.room
            } ?: return

            performRoomOperation(
                room,
                operation,
                action,
            )
        }
    }

    /**
     * Runs and validates an operation if an engine exists; otherwise does nothing.
     *
     * @param operation The RTC operation name.
     * @param action The operation to perform.
     */
    private fun performOptionalEngineOperation(
        operation: String,
        action: (RtcPlatform) -> Int,
    ) {
        nativeGate.read {
            val engine = synchronized(stateLock) {
                engineLease?.engine
            } ?: return
            val result = try {
                action(engine)
            } catch (
                error: Throwable,
            ) {
                throw rtcOperationError(
                    operation,
                    error,
                )
            }

            checkResult(
                operation,
                result,
            )
        }
    }

    /**
     * Runs an operation on the current room and converts negative RTC results to SDK errors.
     *
     * @param room The RTC room associated with this operation.
     * @param operation The RTC operation name.
     * @param action The operation to perform.
     */
    private fun performRoomOperation(
        room: RtcRoom,
        operation: String,
        action: (RtcRoom) -> Int,
    ) {
        nativeGate.read {
            val result = try {
                action(room)
            } catch (
                error: Throwable,
            ) {
                throw rtcOperationError(
                    operation,
                    error,
                )
            }

            checkResult(
                operation,
                result,
            )
        }
    }

    /**
     * Converts unsuccessful RTC results into contextual SDK errors.
     *
     * @param operation The RTC operation name.
     * @param result The RTC status or error code.
     */
    private fun checkResult(
        operation: String,
        result: Int,
    ) {
        if (result < 0) throw rtcResultError(
            operation,
            result,
        )
    }

    /**
     * Runs an operation on the leased engine, failing if no engine is acquired.
     *
     * @param operation The RTC operation name.
     * @param action The operation to perform.
     */
    private fun withEngine(
        operation: String,
        action: (RtcPlatform) -> Unit,
    ) {
        nativeGate.read {
            val engine = synchronized(stateLock) {
                engineLease?.engine
            } ?: throw rtcError("RTC Engine is not initialized")

            try {
                action(engine)
            } catch (
                error: XmaxError,
            ) {
                throw error
            } catch (
                error: Throwable,
            ) {
                throw rtcOperationError(
                    operation,
                    error,
                )
            }
        }
    }

    /**
     * Reports publication changes to subscribers in the current room.
     *
     * @param stream The remote stream identified by room and user.
     * @param published Whether the remote video is published.
     */
    private fun handleRemoteVideoPublished(
        stream: RemoteStream,
        published: Boolean,
    ) {
        // Publication may arrive immediately after native join confirmation, before the main thread activates the room.
        // Retain each user's latest state in PendingJoin; discard it on cancellation or failure rather than passing it to a later connection.
        val room = synchronized(stateLock) {
            val pending = pendingJoin?.takeIf { it.context.roomId == stream.roomId }
            if (pending != null) {
                pending.remoteVideoPublications[stream] = published
                return
            }

            activeRoom?.takeIf { it.roomId == stream.roomId } ?: return
        }

        eventCallbackScope.launch {
            val listener = synchronized(stateLock) {
                eventListener?.get().takeIf { activeRoom === room }
            }

            listener?.onRemoteVideoPublished(
                stream,
                published,
            )
        }
    }

    /**
     * Forwards current-room user messages, ignoring callbacks from old rooms.
     *
     * @param stream The remote stream identified by room and user.
     * @param message The message to send or log.
     */
    private fun handleUserMessageReceived(
        stream: RemoteStream,
        message: String,
    ) {
        val room = synchronized(stateLock) { activeRoom?.takeIf { it.roomId == stream.roomId } } ?: return

        eventCallbackScope.launch {
            val (listener, roomListener) = synchronized(stateLock) {
                if (activeRoom !== room) return@launch

                eventListener?.get() to roomMessageListener
            }

            roomListener?.invoke(stream, message)
            listener?.onUserMessageReceived(
                stream,
                message,
            )
        }
    }

    /**
     * Forwards SEI from the current remote stream.
     *
     * @param stream The remote stream identified by room and user.
     * @param message The message to send or log.
     */
    private fun handleSeiMessageReceived(
        stream: RemoteStream,
        message: String,
    ) {
        val room = synchronized(stateLock) { activeRoom?.takeIf { it.roomId == stream.roomId } } ?: return

        eventCallbackScope.launch {
            val listener = synchronized(stateLock) {
                eventListener?.get().takeIf { activeRoom === room }
            }

            listener?.onSeiMessageReceived(
                stream,
                message,
            )
        }
    }

    /**
     * Reports first-frame dimensions only for a valid current subscription.
     *
     * @param registration The remote frame subscription registration.
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     */
    private fun handleRemoteVideoFrame(
        registration: RemoteFrameRegistration,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return

        synchronized(stateLock) {
            if (!registration.isActive || remoteFrameRegistration !== registration || activeRoom !== registration.room ||
                engineLease !== registration.lease || registration.framePending
            ) return

            registration.framePending = true
        }
        eventCallbackScope.launch {
            val listener = synchronized(stateLock) {
                registration.listener.takeIf {
                    registration.isActive && remoteFrameRegistration === registration && activeRoom === registration.room &&
                        engineLease === registration.lease
                }
            }

            listener?.onFirstFrame(
                width,
                height,
            )
        }
    }

    /**
     * Checks whether the remote frame listener still belongs to the current room and engine.
     *
     * @param registration The remote frame subscription registration.
     * @return True if the registration belongs to the active connection.
     */
    private fun isCurrentFrameRegistration(
        registration: RemoteFrameRegistration,
    ): Boolean =
        registration.isActive && remoteFrameRegistration === registration && activeRoom === registration.room &&
            engineLease === registration.lease

    /**
     * A remote frame listener bound to its room and engine, preventing cross-connection frame delivery.
     *
     * @property stream The remote stream identified by room and user.
     * @property streamId The remote media stream or data channel identifier.
     * @property room The RTC room associated with this operation.
     * @property lease The current exclusive engine lease.
     * @property listener The event listener.
     * @property isActive Whether this subscription is still active; defaults to true.
     * @property framePending Whether this subscription awaits its first-frame notification; defaults to false.
     */
    private class RemoteFrameRegistration(
        val stream: RemoteStream,
        val streamId: String,
        val room: RoomContext,
        val lease: RtcEngineLease,
        val listener: RtcRemoteVideoSink,
        var isActive: Boolean = true,
        var framePending: Boolean = false,
    )

    /**
     * Creates a pending room join and its completion waiter.
     *
     * @param configuration The room, user account, App ID, and room token.
     * @return The operation tracking and awaiting room entry.
     */
    private fun beginJoin(
        configuration: RoomJoinConfiguration,
    ): PendingJoin {
        nativeGate.write {
            val lease = synchronized(stateLock) {
                engineLease
            } ?: throw rtcError("RTC Engine is not initialized")

            synchronized(stateLock) {
                if (activeRoom != null || pendingJoin != null) {
                    throw rtcError("RTC room is already active")
                }
            }

            val room = lease.engine.createRoom(configuration.roomId)
                ?: throw rtcError("Failed to create RTC room")
            val context = RoomContext(
                configuration.roomId,
                room,
            )
            val pending = PendingJoin(context = context)
            val listenerResult = try {
                room.setEventListener { roomId, joined, reason ->
                    eventCallbackScope.launch { handleRoomState(
                        pending.id,
                        roomId,
                        joined,
                        reason,
                    ) }
                }
            } catch (
                error: Throwable,
            ) {
                room.destroy()
                throw rtcOperationError(
                    "setRTCRoomEventHandler",
                    error,
                )
            }
            if (listenerResult < 0) {
                room.destroy()
                throw rtcResultError(
                    "setRTCRoomEventHandler",
                    listenerResult,
                )
            }

            synchronized(stateLock) {
                pendingJoin = pending
            }
            val joinResult = try {
                room.join(configuration)
            } catch (
                error: Throwable,
            ) {
                val rtcError = rtcOperationError(
                    "joinRoom",
                    error,
                )
                cleanupPendingJoin(
                    pending.id,
                    rtcError,
                )
                throw rtcError
            }
            if (joinResult < 0) {
                val error = rtcResultError(
                    "joinRoom",
                    joinResult,
                )
                cleanupPendingJoin(
                    pending.id,
                    error,
                )
                throw error
            }

            return pending
        }
    }

    /**
     * Converts RTC room state into the pending join's result.
     *
     * @param pendingId The pending room join identifier.
     * @param roomId The RTC room identifier.
     * @param joined Whether room entry succeeded.
     * @param reason The reason for the state transition or failure.
     */
    private fun handleRoomState(
        pendingId: UUID,
        roomId: String,
        joined: Boolean,
        reason: String?,
    ) {
        val pending = synchronized(stateLock) {
            pendingJoin?.takeIf {
                it.id == pendingId && it.context.roomId == roomId
            }
        }
        if (pending == null) {
            val listener = synchronized(stateLock) {
                eventListener?.get().takeIf { activeRoom?.id == pendingId && activeRoom?.roomId == roomId }
            }
            if (!joined && isTerminalRoomFailure(reason)) {
                listener?.onRoomTerminated(
                    roomId,
                    rtcError("RTC room terminated: $reason"),
                )
            }

            return
        }

        if (!joined && isRetryableRoomFailure(reason)) return

        if (joined) {
            val publications = synchronized(stateLock) {
                if (pendingJoin?.id != pendingId) {
                    null
                } else {
                    activeRoom = pending.context
                    pendingJoin = null
                    pending.remoteVideoPublications.toMap()
                }
            }
            if (publications != null) {
                // Already on the callback queue: replay pending publication state before processing later queued events.
                try {
                    publications.forEach { (stream, published) ->
                        val listener = synchronized(stateLock) {
                            eventListener?.get().takeIf { activeRoom === pending.context }
                        }

                        listener?.onRemoteVideoPublished(
                            stream,
                            published,
                        )
                    }
                } finally {
                    pending.result.complete(Unit)
                }
            }

            return
        }

        cleanupPendingJoin(
            pendingId,
            rtcError(
                buildString {
                    append("RTC join room failed")
                    reason?.trim()?.takeIf(String::isNotEmpty)?.let {
                        append(": ")
                        append(it)
                    }
                },
            ),
        )
    }

    /**
     * Terminates a pending join and releases its room.
     *
     * @param pendingId The pending room join identifier.
     * @param error The operation failure or SDK error.
     */
    private fun cleanupPendingJoin(
        pendingId: UUID,
        error: Throwable,
    ) {
        nativeGate.write {
            val resources = synchronized(stateLock) {
                val pending = pendingJoin?.takeIf { it.id == pendingId }
                val joined = activeRoom?.takeIf { it.id == pendingId }
                if (pending != null) pendingJoin = null
                if (joined != null) activeRoom = null
                pending to joined
            }

            try {
                resources.first?.context?.let { tearDownRoom(
                    it,
                    leave = false,
                ) }
                resources.second?.let { tearDownRoom(
                    it,
                    leave = true,
                ) }
            } finally {
                resources.first?.result?.completeExceptionally(error)
            }
        }
    }

    /**
     * Unbinds the active room and ends a pending join.
     */
    private fun leaveRoomLocked() {
        nativeGate.write {
            val resources = synchronized(stateLock) {
                RoomResources(
                    active = activeRoom,
                    pending = pendingJoin,
                ).also {
                    activeRoom = null
                    pendingJoin = null
                    roomMessageListener = null
                }
            }

            resources.pending?.result?.completeExceptionally(
                cancelledError("RTC join room"),
            )
            var failure: Throwable? = null
            val releases: List<() -> Unit> = listOf(
                { clearRemoteFrameRegistrationLocked() },
                { clearRemoteAudioRegistrationLocked() },
                { resources.pending?.context?.let { tearDownRoom(
                    it,
                    leave = false,
                ) } },
                { resources.active?.let { tearDownRoom(
                    it,
                    leave = true,
                ) } },
            )
            releases.forEach { release ->
                try {
                    release()
                } catch (
                    error: Throwable,
                ) {
                    if (failure == null) failure = error
                    else if (failure !== error) failure.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    /**
     * Releases a room and its callbacks, optionally leaving first.
     *
     * @param context The identifiers and resources for this room operation.
     * @param leave Whether to leave before releasing the room.
     */
    private fun tearDownRoom(
        context: RoomContext,
        leave: Boolean,
    ) {
        try {
            if (leave) {
                runCatching { context.room.publishLocalVideo(false) }
                runCatching { context.room.publishLocalAudio(false) }
                context.room.leave()
            }
        } finally {
            context.room.destroy()
            synchronized(stateLock) {
                if (remoteFrameRegistration?.room === context) remoteFrameRegistration = null
            }
        }
    }

    /**
     * Normalizes join identifiers and validates required configuration.
     *
     * @return The normalized join configuration with valid required identifiers.
     */
    private fun RoomJoinConfiguration.normalized(): RoomJoinConfiguration {
        val roomId = roomId.trim().requireValue("RTC room ID")
        val userId = userId.trim().requireValue("RTC user ID")
        val token = token.trim().requireValue("RTC room token")
        return RoomJoinConfiguration(
            roomId,
            userId,
            token,
        )
    }

    /**
     * Validates positive video dimensions and frame rate.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @param frameRate The video frame rate, in fps.
     */
    private fun validateVideoDimensions(
        width: Int,
        height: Int,
        frameRate: Int,
    ) {
        if (width <= 0 || height <= 0 || frameRate <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "RTC video width and height must be positive even numbers, " +
                    "and frame rate must be greater than zero",
            )
        }
    }

    /**
     * Trims and validates a required identifier, throwing on blank input.
     *
     * @param name The field name used in errors.
     * @return The nonempty identifier with surrounding whitespace removed.
     */
    private fun String.requireValue(
        name: String,
    ): String = takeIf(String::isNotEmpty)
        ?: throw rtcError("$name cannot be empty")

    /**
     * Normalizes and validates a remote user identifier.
     *
     * @param userId The room's business user identifier.
     * @return The normalized, nonempty remote user identifier.
     */
    private fun normalizeRemoteUserId(
        userId: String,
    ): String =
        userId.trim().takeIf(String::isNotEmpty) ?: throw XmaxError(
            code = XmaxErrorCode.INVALID_CONFIGURATION,
            message = "RTC user ID cannot be empty",
        )

    /**
     * Rejects remote streams outside the current room.
     *
     * @param stream The remote stream identified by room and user.
     */
    private fun requireActiveRemoteStream(
        stream: RemoteStream,
    ) {
        val isActive = synchronized(stateLock) {
            activeRoom?.roomId == stream.roomId
        }
        if (!isActive) throw rtcError("RTC remote stream is not in the active room")
    }

    /**
     * Checks whether the engine lease is still active.
     *
     * @param lease The current exclusive engine lease.
     * @return True if the lease belongs to the active engine.
     */
    private fun isCurrentLease(
        lease: RtcEngineLease,
    ): Boolean = synchronized(stateLock) { engineLease === lease }

    /**
     * An active RTC room and its local operation identity.
     *
     * @property roomId The RTC room identifier.
     * @property room The RTC room associated with this operation.
     * @property id The connection identifier used to reject old room callbacks.
     */
    private data class RoomContext(
        val roomId: String,
        val room: RtcRoom,
        val id: UUID = UUID.randomUUID(),
    )

    /**
     * A pending join with remote publication events received during entry.
     *
     * @property context The identifiers and resources for this room operation.
     * @property id The room connection identity owning this pending join.
     * @property result The waiter completed when the operation finishes.
     * @property remoteVideoPublications Remote publication states received during room entry.
     */
    private data class PendingJoin(
        val context: RoomContext,
        val id: UUID = context.id,
        val result: CompletableDeferred<Unit> = CompletableDeferred(),
        val remoteVideoPublications: MutableMap<RemoteStream, Boolean> = linkedMapOf(),
    )

    /**
     * Rooms and pending joins that must be released when leaving.
     *
     * @property active The joined room, or null without an active room.
     * @property pending The pending room join, or null when no join is pending.
     */
    private data class RoomResources(
        val active: RoomContext?,
        val pending: PendingJoin?,
    )

    /**
     * The join timeout and RTC error helpers.
     */
    private companion object {

        /**
         * The RTC join timeout, in milliseconds.
         */
        const val JOIN_TIMEOUT_MILLIS: Long = 15_000L

        /**
         * Creates an SDK error describing an RTC failure.
         *
         * @param message The message to send or log.
         */
        fun rtcError(
            message: String,
        ): XmaxError = XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = message,
        )

        /**
         * Creates an operation error with an RTC result code.
         *
         * @param operation The RTC operation name.
         * @param result The RTC status or error code.
         */
        fun rtcResultError(
            operation: String,
            result: Number,
        ): XmaxError = rtcError(
            "RTC $operation failed: $result",
        )

        /**
         * Creates an RTC operation error with an underlying exception.
         *
         * @param operation The RTC operation name.
         * @param cause The underlying exception that caused this error.
         */
        fun rtcOperationError(
            operation: String,
            cause: Throwable,
        ): XmaxError = XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = "RTC $operation failed: ${cause.message ?: cause.javaClass.simpleName}",
            cause = cause,
        )

        /**
         * Creates a coroutine cancellation exception for an RTC operation.
         *
         * @param operation The RTC operation name.
         * @return A cancellation exception for the current operation.
         */
        fun cancelledError(
            operation: String,
        ): CancellationException =
            CancellationException("$operation was cancelled")
    }
}
