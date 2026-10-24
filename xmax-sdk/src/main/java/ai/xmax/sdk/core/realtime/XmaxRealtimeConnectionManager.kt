//
//  XmaxRealtimeConnectionManager.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.media.interaction.InteractionControlling
import ai.xmax.sdk.render.RenderControlling
import ai.xmax.sdk.service.realtime.RealtimeSession
import ai.xmax.sdk.service.realtime.RealtimeSessionServicing
import ai.xmax.sdk.stream.StreamControlling
import ai.xmax.sdk.stream.StreamID
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Owns the server session, RTC room, local publication, and remote tracks as one connection.
 * operationMutex serializes connection, disconnection, and rollback to prevent reuse of unreleased resources.
 * stateLock protects session and track snapshots without suspending while held.
 */
internal class XmaxRealtimeConnectionManager(
    /**
     * The service creating, renewing, and closing server sessions.
     */
    private val sessionService: RealtimeSessionServicing,

    /**
     * The controller submitting interaction trajectories.
     */
    private val interactionController: InteractionControlling,

    /**
     * The controller binding video tracks to preview views.
     */
    private val renderController: RenderControlling,

    /**
     * The component managing RTC publication, subscriptions, and generation signaling.
     */
    private val streamController: StreamControlling,
) {

    /**
     * The lock protecting connection state.
     */
    private val stateLock = Any()

    /**
     * The mutex serializing connection and disconnection.
     */
    private val operationMutex = Mutex()

    /**
     * The active server session.
     */
    private var activeRemoteTrack: RealtimeVideoTrack? = null

    /**
     * The server session used by the current connection.
     */
    private var activeSession: RealtimeSession? = null

    /**
     * The input format used for local publication.
     */
    private var inputFormat: RealtimeVideoFormat? = null

    /**
     * Whether the current input includes audio.
     */
    private var inputHasAudio = false

    /**
     * Checks reuse against the actual room configuration rather than a media snapshot that may already be
     * unbound.
     *
     * @param format Video dimensions, frame rate, and uplink encoding options.
     * @param hasAudio Whether the input provides audio.
     * @return True if the current connection can accept the input.
     */
    fun acceptsInput(
        format: RealtimeVideoFormat,
        hasAudio: Boolean,
    ): Boolean = synchronized(stateLock) {
        activeSession != null && inputFormat == format && inputHasAudio == hasAudio
    }

    /**
     * The connected session identifier; empty before connection completes or after disconnection.
     */
    val currentSessionId: String
        get() = synchronized(stateLock) { activeSession?.id.orEmpty() }

    /**
     * The remote track container; its existence does not indicate generation or first-frame readiness.
     */
    val currentRemoteStream: RealtimeMediaStream?
        get() = synchronized(stateLock) {
            if (activeSession == null) null else activeRemoteTrack?.let {
                RealtimeMediaStream(
                    StreamID.REMOTE.value,
                    it,
                )
            }
        }

    /**
     * Updates the active remote track's actual video format; ignores updates when no track is active.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     */
    fun updateRemoteVideoFormat(
        videoFormat: RealtimeVideoFormat,
    ) {
        val track = synchronized(stateLock) { activeRemoteTrack } ?: return
        track.updateVideoFormat(videoFormat)
    }

    /**
     * Creates a session, joins RTC, publishes media when required, and registers rendering and heartbeats.
     * Each asynchronous result must still belong to the current operation. Failures roll back under the
     * operation lock, preserving the original error and cancellation.
     * Heartbeat failures return through onHeartbeatFailure without waiting for connection cleanup in the
     * heartbeat coroutine.
     *
     * @param model The model defining server capabilities and input constraints.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param includeLocalAudio Whether to publish local audio alongside video.
     * @param publishLocalMedia Whether to publish local media; false for server-read network video, true by
     * default.
     * @param isCurrent Checks whether this operation still owns the result.
     * @param onHeartbeatFailure Receives the session identifier and error when renewal fails.
     */
    suspend fun connect(
        model: RealtimeModel,
        videoFormat: RealtimeVideoFormat,
        includeLocalAudio: Boolean,
        publishLocalMedia: Boolean = true,
        isCurrent: () -> Boolean,
        onHeartbeatFailure: suspend (String, XmaxError) -> Unit,
    ): RealtimeMediaStream = operationMutex.withLock {
        if (currentSessionId.isNotEmpty()) throw XmaxError(
            XmaxErrorCode.INVALID_CONFIGURATION,
            "Realtime connection is already open",
        )
        val timing = currentCoroutineContext()[RealtimeTiming.Attempt]
        timing?.mark(RealtimeTiming.Stage.CONNECTION_START)
        var session: RealtimeSession? = null
        try {
            timing?.mark(RealtimeTiming.Stage.SESSION_START)
            session = sessionService.createSession(model)
            timing?.mark(RealtimeTiming.Stage.SESSION_END)
            ensureCurrent(isCurrent)
            val connection = session.connection ?: throw XmaxError(
                XmaxErrorCode.SESSION_ERROR,
                "Session does not contain complete RTC join information",
            )
            streamController.connect(
                connection,
                includeLocalAudio,
                publishLocalMedia,
            ) {
                ensureCurrent(isCurrent)
            }
            ensureCurrent(isCurrent)

            sessionService.startHeartbeat(
                session.id,
                onHeartbeatFailure,
            )
            val remoteTrack = RealtimeVideoTrack(
                id = connection.botName ?: "video-remote",
                videoFormat = videoFormat,
            )
            renderController.registerRemoteTrack(
                remoteTrack,
                IntSize(
                    videoFormat.width,
                    videoFormat.height,
                ),
            ) { frame ->
                interactionController.submitInteraction(frame)
            }
            synchronized(stateLock) {
                activeSession = session
                activeRemoteTrack = remoteTrack
                inputFormat = videoFormat
                inputHasAudio = includeLocalAudio
            }
            ensureCurrent(isCurrent)
            timing?.mark(RealtimeTiming.Stage.CONNECTION_END)
            return@withLock RealtimeMediaStream(
                StreamID.REMOTE.value,
                remoteTrack,
            )
        } catch (
            error: Throwable,
        ) {
            // Rollback still holds the operation lock, so a later connection cannot yet own these resources.
            cleanupAfterFailure(
                error,
                { sessionService.stopHeartbeat() },
                { val track = synchronized(stateLock) {
                    activeRemoteTrack.also { activeSession = null; activeRemoteTrack = null }
                }; renderController.resetRemoteTrack(track) },
                { streamController.disconnect() },
                { session?.id?.let { closeSessionBestEffort(it) } },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * Clears the connection snapshot and releases its resources without stopping local media.
     *
     * @return The closed session identifier, or null if no session was active.
     */
    suspend fun disconnect(): String? = operationMutex.withLock {
        val resources = synchronized(stateLock) {
            ConnectionResources(
                activeSession,
                activeRemoteTrack,
            ).also {
                activeSession = null
                activeRemoteTrack = null
            }
        }
        cleanupResources(
            { sessionService.stopHeartbeat() },
            { renderController.resetRemoteTrack(resources.remoteTrack) },
            { streamController.disconnect() },
            { resources.session?.id?.let { closeSessionBestEffort(it) } },
        )
        resources.session?.id
    }

    /**
     * Server closure waits at most five seconds; failures are logged without blocking local cleanup
     * indefinitely.
     *
     * @param sessionId The server session identifier.
     */
    private suspend fun closeSessionBestEffort(
        sessionId: String,
    ) {
        try { withTimeout(5_000L) { sessionService.closeSession(sessionId) } }
        catch (
            error: Throwable,
        ) { logCleanupFailure(
            "Failed to close realtime session $sessionId",
            error,
        ) }
    }

    /**
     * Records cleanup failures without replacing the error that initiated cleanup.
     *
     * @param title The log entry title.
     * @param error The operation failure or SDK error.
     */
    private fun logCleanupFailure(
        title: String,
        error: Throwable,
    ) {
        XmaxLogger.realtime.error(
            message = {
                "$title\n└─ ${XmaxLogger.localized(
                    "原因：",
                    "Reason: ",
                )}${ErrorMessageFormatter.format(error)}"
            },
        )
    }

    /**
     * Treats connection results that lost ownership as cancellation rather than a new app-visible failure.
     *
     * @param isCurrent Checks whether this operation still owns the result.
     */
    private fun ensureCurrent(
        isCurrent: () -> Boolean,
    ) {
        if (!isCurrent()) {
            throw CancellationException("Realtime connection was cancelled")
        }
    }

    /**
     * A disconnected connection snapshot whose resources remain owned by its cleanup operation.
     */
    private data class ConnectionResources(
        /**
         * The server session and its connection details.
         */
        val session: RealtimeSession?,

        /**
         * The active remote video track.
         */
        val remoteTrack: RealtimeVideoTrack?,
    )
}
