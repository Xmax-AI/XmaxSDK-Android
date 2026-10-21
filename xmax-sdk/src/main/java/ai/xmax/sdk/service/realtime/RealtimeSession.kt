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
 * Xmax 实时生成会话。
 */
internal data class RealtimeSession(
    /**
     * 服务端分配的实时生成会话标识。
     */
    val id: String,

    /**
     * 房间中的业务用户标识。
     */
    val userId: String?,

    /**
     * 服务端会话状态。
     */
    val status: String?,

    /**
     * 服务端返回的 RTC 房间、用户、鉴权及结果流信息。
     */
    val connection: RealtimeSessionConnection?,

    /**
     * 服务端会话关闭原因；null 表示未提供。
     */
    val closeReason: String?,
)

/**
 * 实时会话对应的 RTC 连接参数。
 */
internal data class RealtimeSessionConnection(
    /**
     * RTC 房间标识。
     */
    val roomId: String,

    /**
     * 房间中的业务用户标识。
     */
    val userId: String,

    /**
     * RTC 进房鉴权 Token。
     */
    val token: String,

    /**
     * 生成结果发布者的用户标识。
     */
    val botName: String?,

    /**
     * RTC 应用标识，不是应用证书或房间 Token；默认值为 `ai.xmax.sdk.foundation.rtc.RtcEngineManager.DEFAULT_APP_ID`。
     */
    val appId: String = ai.xmax.sdk.foundation.rtc.RtcEngineManager.DEFAULT_APP_ID,
)

/**
 * 实时会话心跳失败回调。
 */
internal typealias RealtimeSessionHeartbeatFailureHandler = suspend (
    sessionId: String,
    error: XmaxError,
) -> Unit
