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
 * The credentials for joining an RTC room.
 *
 * @property roomId The RTC room identifier.
 * @property userId The room's business user identifier.
 * @property token The RTC room authentication token.
 * @property appId The RTC App ID, not an App Certificate or token; defaults to `RtcEngineManager.DEFAULT_APP_ID`.
 */
internal data class RoomJoinConfiguration(
    val roomId: String,
    val userId: String,
    val token: String,
    val appId: String = RtcEngineManager.DEFAULT_APP_ID,
)
