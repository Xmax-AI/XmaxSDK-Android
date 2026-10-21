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
 * RTC 房间加入参数。
 */
internal data class RoomJoinConfiguration(
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
     * RTC 应用标识，不是应用证书或房间 Token；默认值为 `RtcEngineManager.DEFAULT_APP_ID`。
     */
    val appId: String = RtcEngineManager.DEFAULT_APP_ID,
)
