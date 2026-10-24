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
 * A remote main stream in an RTC room.
 */
internal data class RemoteStream(
    /**
     * The RTC room identifier.
     */
    val roomId: String,

    /**
     * The room's business user identifier.
     */
    val userId: String,
) {

    /**
     * Builds a remote stream key unique across rooms.
     */
    val key: String
        get() = "$roomId:$userId"
}
