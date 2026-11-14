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
 *
 * @property roomId The RTC room identifier.
 * @property userId The room's business user identifier.
 */
internal data class RemoteStream(
    val roomId: String,
    val userId: String,
) {

    /**
     * Builds a remote stream key unique across rooms.
     */
    val key: String
        get() = "$roomId:$userId"
}
