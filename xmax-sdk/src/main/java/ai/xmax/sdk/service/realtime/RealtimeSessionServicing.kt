//
//  RealtimeSessionServicing.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.realtime

import ai.xmax.sdk.RealtimeModel

/**
 * An interface for session requests and heartbeat lifecycle.
 */
internal interface RealtimeSessionServicing {

    /**
     * Creates a realtime session for the requested model.
     *
     * @param model The model defining server capabilities and input constraints.
     * @return The server session identifier, state, and available RTC connection details.
     */
    suspend fun createSession(
        model: RealtimeModel,
    ): RealtimeSession

    /**
     * Starts a session heartbeat, replacing the previous task.
     *
     * @param sessionId The server session identifier.
     * @param onFailure Receives the session identifier and error when renewal fails.
     */
    fun startHeartbeat(
        sessionId: String,
        onFailure: RealtimeSessionHeartbeatFailureHandler,
    )

    /**
     * Stops the heartbeat and invalidates pending failure notifications.
     */
    suspend fun stopHeartbeat()

    /**
     * Closes a server session.
     *
     * @param sessionId The server session identifier.
     */
    suspend fun closeSession(
        sessionId: String,
    )
}
