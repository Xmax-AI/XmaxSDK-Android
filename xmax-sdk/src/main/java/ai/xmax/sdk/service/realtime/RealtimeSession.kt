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
 * An Xmax realtime generation session.
 *
 * @property id The server-assigned generation session identifier.
 * @property userId The room's business user identifier.
 * @property status The server session state.
 * @property connection The RTC room, user, credentials, and result stream returned by the server.
 * @property closeReason The server closure reason, or null if absent.
 * @property modelId The assigned model instance identifier used for HTTP event relay.
 */
internal data class RealtimeSession(
    val id: String,
    val userId: String?,
    val status: String?,
    val connection: RealtimeSessionConnection?,
    val closeReason: String?,
    val modelId: String? = null,
)

/**
 * The RTC connection parameters for a realtime session.
 *
 * @property roomId The RTC room identifier.
 * @property userId The room's business user identifier.
 * @property token The RTC room authentication token.
 * @property botName The user publishing generated results.
 * @property appId The RTC App ID, not a certificate or token; defaults to `RtcEngineManager.DEFAULT_APP_ID`.
 */
internal data class RealtimeSessionConnection(
    val roomId: String,
    val userId: String,
    val token: String,
    val botName: String?,
    val appId: String = ai.xmax.sdk.foundation.rtc.RtcEngineManager.DEFAULT_APP_ID,
)

/**
 * The realtime session heartbeat failure callback.
 */
internal typealias RealtimeSessionHeartbeatFailureHandler = suspend (
    sessionId: String,
    error: XmaxError,
) -> Unit
