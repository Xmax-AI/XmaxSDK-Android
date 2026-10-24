//
//  PermissionManaging.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.permissions

/**
 * The platform permission checks required by XmaxSDK.
 */
internal interface PermissionManaging {

    /**
     * Checks camera permission and throws a camera permission error if missing.
     */
    suspend fun ensureCameraPermission()

    /**
     * Checks microphone permission and throws a microphone permission error if missing.
     */
    suspend fun ensureMicrophonePermission()
}
