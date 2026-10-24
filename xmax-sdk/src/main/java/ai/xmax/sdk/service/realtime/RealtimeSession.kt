//
//  RealtimeSession.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.realtime

import ai.xmax.sdk.XmaxError

/**
 * A realtime generation session.
 */
internal data class RealtimeSession(
    /**
     * The server-assigned generation session identifier.
     */
    val id: String,

    /**
     * The room's business user identifier.
     */
    val userId: String?,

    /**
     * The server session state.
     */
    val status: String?,

    /**
     * The RTC room, user, credentials, and result stream returned by the server.
     */
    val connection: RealtimeSessionConnection?,

    /**
     * The server closure reason, or null if absent.
     */
    val closeReason: String?,
)

/**
 * The RTC connection parameters for a realtime session.
 */
internal data class RealtimeSessionConnection(
    /**
     * The RTC room identifier.
     */
    val roomId: String,

    /**
     * The room's business user identifier.
     */
    val userId: String,

    /**
     * The RTC room authentication token.
     */
    val token: String,

    /**
     * The user publishing generated results.
     */
    val botName: String?,

    /**
     * The RTC App ID, not a certificate or token; defaults to `RtcEngineManager.DEFAULT_APP_ID`.
     */
    val appId: String = ai.xmax.sdk.foundation.rtc.RtcEngineManager.DEFAULT_APP_ID,
)

/**
 * The realtime session heartbeat failure callback.
 */
internal typealias RealtimeSessionHeartbeatFailureHandler = suspend (
    sessionId: String,
    error: XmaxError,
) -> Unit
