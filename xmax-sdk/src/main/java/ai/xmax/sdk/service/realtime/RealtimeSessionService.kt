//
//  RealtimeSessionService.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.realtime

import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.service.network.ApiServicing
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ai.xmax.sdk.foundation.rtc.RtcEngineManager
import org.json.JSONObject

/**
 * Creates, maintains heartbeats for, and closes realtime sessions.
 */
internal class RealtimeSessionService(
    /**
     * The server API client.
     */
    private val apiService: ApiServicing,

    /**
     * The suspending heartbeat interval controller; defaults to `RealtimeHeartbeatSleeper.live`.
     */
    private val heartbeatSleeper: RealtimeHeartbeatSleeper = RealtimeHeartbeatSleeper.live,

    /**
     * The scope running session heartbeats.
     */
    private val heartbeatScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : RealtimeSessionServicing {

    /**
     * The lock protecting session heartbeat ownership.
     */
    private val heartbeatLock = Any()

    /**
     * The current heartbeat lifecycle context.
     */
    private val heartbeatVersion = AtomicLong(0L)

    /**
     * The coroutine renewing the server session periodically.
     */
    private var heartbeatJob: Job? = null

    /**
     * Creates a realtime session and returns its RTC connection details.
     *
     * @param model The model defining server capabilities and input constraints.
     * @return The server session identifier, state, and available RTC connection details.
     */
    override suspend fun createSession(
        model: RealtimeModel,
    ): RealtimeSession {
        val payload = apiService.post(
            path = "/session",
            body = JSONObject().put(
                "model",
                model.id,
            ),
        )
        try {
            return makeSession(
                payload,
                requiresConnection = true,
            ).also(::ensureSessionActive)
        } catch (
            error: Throwable,
        ) {
            payload.nonEmptyString("sessionUid")?.let { id ->
                withContext(NonCancellable) {
                    try {
                        withTimeout(5_000L) { closeSession(id) }
                    } catch (
                        cleanup: Throwable,
                    ) {
                        if (cleanup !== error) error.addSuppressed(cleanup)
                    }
                }
            }
            throw error
        }
    }

    /**
     * Starts periodic heartbeats for a session.
     *
     * @param sessionId The server session identifier.
     * @param onFailure Receives the session identifier and error when renewal fails.
     */
    override fun startHeartbeat(
        sessionId: String,
        onFailure: RealtimeSessionHeartbeatFailureHandler,
    ) {
        synchronized(heartbeatLock) {
            val version = heartbeatVersion.incrementAndGet()
            heartbeatJob?.cancel()
            heartbeatJob = heartbeatScope.launch {
                runHeartbeat(
                    HeartbeatContext(
                        version = version,
                        sessionId = sessionId,
                        onFailure = onFailure,
                    ),
                )
            }
        }
    }

    /**
     * Stops the heartbeat, preventing invalidated late results from reporting failures.
     */
    override suspend fun stopHeartbeat() {
        val job = synchronized(heartbeatLock) {
            heartbeatVersion.incrementAndGet()
            heartbeatJob.also { heartbeatJob = null }
        }
        job?.cancelAndJoin()
    }

    /**
     * Closes a realtime session.
     *
     * @param sessionId The server session identifier.
     */
    override suspend fun closeSession(
        sessionId: String,
    ) {
        apiService.delete("/session/$sessionId")
    }

    /**
     * Renews the active session periodically, reporting a session failure if renewal fails.
     *
     * @param context The heartbeat lifecycle identity and failure callback.
     */
    private suspend fun runHeartbeat(
        context: HeartbeatContext,
    ) {
        while (isCurrent(context.version)) {
            try {
                heartbeatSleeper.sleep()
                currentCoroutineContext().ensureActive()
                if (!isCurrent(context.version)) return

                val session = heartbeatSession(context.sessionId)
                if (!isCurrent(context.version)) return
                ensureSessionActive(session)
            } catch (
                error: CancellationException,
            ) {
                throw error
            } catch (
                error: Throwable,
            ) {
                if (!invalidate(context.version)) return
                context.onFailure(
                    context.sessionId,
                    XmaxError.from(error),
                )
                return
            }
        }
    }

    /**
     * Renews a server session and returns its updated metadata.
     *
     * @param sessionId The server session identifier.
     * @return The server session identifier, state, and available RTC connection details.
     */
    private suspend fun heartbeatSession(
        sessionId: String,
    ): RealtimeSession =
        apiService.put("/session/$sessionId/heartbeat") { payload ->
            makeSession(
                payload,
                requiresConnection = false,
            )
        }

    /**
     * Checks that the server session remains usable, throwing a session error otherwise.
     *
     * @param session The server session and connection details.
     */
    private fun ensureSessionActive(
        session: RealtimeSession,
    ) {
        val status = session.status ?: return
        if (status == ACTIVE_STATUS) return
        throw XmaxError(
            code = XmaxErrorCode.SESSION_ERROR,
            message = session.closeReason ?: "Session is no longer active: $status",
        )
    }

    /**
     * Parses server session data and optionally validates connection details.
     *
     * @param payload The server business JSON to decode.
     * @param requiresConnection Whether complete RTC connection details are required.
     * @return The server session identifier, state, and available RTC connection details.
     */
    private fun makeSession(
        payload: JSONObject,
        requiresConnection: Boolean,
    ): RealtimeSession {
        val sessionId = payload.nonEmptyString("sessionUid") ?: throw XmaxError(
            code = XmaxErrorCode.SESSION_ERROR,
            message = "Invalid session response",
        )
        val userId = payload.nonEmptyString("userUid")
        val connection = makeConnection(payload.opt("modelExtra"))
        if (requiresConnection && connection == null) {
            throw XmaxError(
                code = XmaxErrorCode.SESSION_ERROR,
                message = "Session does not contain complete RTC join information",
            )
        }

        return RealtimeSession(
            id = sessionId,
            userId = userId,
            status = payload.nonEmptyString("status"),
            connection = connection,
            closeReason = payload.nonEmptyString("closeReason"),
        )
    }

    /**
     * Parses RTC join configuration from model extension data.
     *
     * @param modelExtra The model extension containing RTC join details.
     * @return Complete RTC connection details, or null when extension fields are unavailable.
     */
    private fun makeConnection(
        modelExtra: Any?,
    ): RealtimeSessionConnection? {
        val payload = when (modelExtra) {
            is JSONObject -> modelExtra
            is String -> try {
                JSONObject(modelExtra)
            } catch (
                _: Exception,
            ) {
                return null
            }
            else -> return null
        }

        val roomId = payload.nonEmptyString("room_id") ?: return null
        val token = payload.nonEmptyString("room_token") ?: return null
        val userId = payload.nonEmptyString("rtc_user_id") ?: return null
        val appId = payload.nonEmptyString("rtc_app_id") ?: return null
        if (appId != RtcEngineManager.DEFAULT_APP_ID) return null
        return RealtimeSessionConnection(
            roomId = roomId,
            userId = userId,
            token = token,
            botName = payload.nonEmptyString("rtc_bot_id") ?: return null,
            appId = appId,
        )
    }

    /**
     * Reads a nonblank string, returning null if missing or blank.
     *
     * @param key The JSON field name.
     * @return The nonblank field value, or null if missing or blank.
     */
    private fun JSONObject.nonEmptyString(
        key: String,
    ): String? =
        (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)

    /**
     * Checks whether a heartbeat result belongs to the current lifecycle.
     *
     * @param version The revision identifying the owning task or callback.
     * @return True if the task or room remains valid.
     */
    private fun isCurrent(
        version: Long,
    ): Boolean = heartbeatVersion.get() == version

    /**
     * Invalidates a heartbeat so it cannot update the session or report failures.
     *
     * @param version The revision identifying the owning task or callback.
     * @return True if this call invalidated the requested heartbeat lifecycle.
     */
    private fun invalidate(
        version: Long,
    ): Boolean =
        heartbeatVersion.compareAndSet(
            version,
            version + 1L,
        )

    /**
     * The session identifier, revision, and failure callback for one heartbeat lifecycle.
     */
    private data class HeartbeatContext(
        /**
         * The revision identifying the owning task or callback.
         */
        val version: Long,

        /**
         * The server session identifier.
         */
        val sessionId: String,

        /**
         * The callback receiving a session identifier and renewal failure.
         */
        val onFailure: RealtimeSessionHeartbeatFailureHandler,
    )

    /**
     * The protocol values for server session state.
     */
    private companion object {

        /**
         * The server's active session state value.
         */
        const val ACTIVE_STATUS = "ACTIVE"
    }
}

/**
 * The server session heartbeat interval.
 */
internal fun interface RealtimeHeartbeatSleeper {

    /**
     * Suspends until the next heartbeat time.
     */
    suspend fun sleep()

    /**
     * The default session heartbeat wait strategy.
     */
    companion object {

        /**
         * The strategy waiting ten seconds between heartbeats.
         */
        val live: RealtimeHeartbeatSleeper = RealtimeHeartbeatSleeper {
            delay(10_000L)
        }
    }
}
