//
//  RoomJoinConfiguration.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

/**
 * Parameters for joining an RTC room.
 */
internal data class RoomJoinConfiguration(
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
     * The RTC App ID, not an App Certificate or token; defaults to `RtcEngineManager.DEFAULT_APP_ID`.
     */
    val appId: String = RtcEngineManager.DEFAULT_APP_ID,
)
