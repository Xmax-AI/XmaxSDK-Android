//
//  RtcRoomFailurePolicy.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

/**
 * Keeps the business session alive during Agora reconnection, ending it only for explicit failure,
 * authentication, or send errors.
 *
 * @param reason The reason for the state transition or failure.
 * @return True if RTC can continue reconnecting.
 */
internal fun isRetryableRoomFailure(
    reason: String?,
): Boolean = reason == "RECONNECT"

/**
 * Checks whether a room-state reason requires terminating the connection.
 *
 * @param reason The reason for the state transition or failure.
 * @return True if the RTC connection must end.
 */
internal fun isTerminalRoomFailure(
    reason: String?,
): Boolean = reason in setOf(
    "AGORA_CONNECTION_FAILED",
    "AGORA_SEND_FAILED",
    "INVALID_TOKEN",
    "TOKEN_EXPIRED",
)
