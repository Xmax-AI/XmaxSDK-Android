//
//  RemoteStream.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

/**
 * 标识 RTC 房间中的一条远端主流。
 */
internal data class RemoteStream(
    /**
     * RTC 房间标识。
     */
    val roomId: String,

    /**
     * 房间中的业务用户标识。
     */
    val userId: String,
) {

    /**
     * 生成跨房间唯一的远端流键。
     */
    val key: String
        get() = "$roomId:$userId"
}
