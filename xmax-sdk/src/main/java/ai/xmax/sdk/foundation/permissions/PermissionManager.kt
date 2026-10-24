//
//  PermissionManager.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.permissions

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Checks camera and microphone permissions; your app's Activity owns runtime permission requests.
 */
internal class PermissionManager(
    /**
     * The function checking whether camera permission has been granted.
     */
    private val isCameraPermissionGranted: () -> Boolean,

    /**
     * The function checking whether recording permission has been granted.
     */
    private val isMicrophonePermissionGranted: () -> Boolean,
) : PermissionManaging {

    /**
     * Creates a permission manager.
     *
     * @param isCameraPermissionGranted Checks whether camera permission has been granted.
     */
    constructor(
        isCameraPermissionGranted: () -> Boolean,
    ) : this(
        isCameraPermissionGranted = isCameraPermissionGranted,
        isMicrophonePermissionGranted = { false },
    )

    /**
     * Creates a permission manager.
     *
     * @param context The Android context used for system services and media resources.
     */
    constructor(
        context: Context,
    ) : this(
        isCameraPermissionGranted = {
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        },
        isMicrophonePermissionGranted = {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        },
    )

    /**
     * Checks camera permission and throws a camera permission error if missing.
     */
    override suspend fun ensureCameraPermission() {
        if (isCameraPermissionGranted()) return

        throw XmaxError(
            code = XmaxErrorCode.CAMERA_PERMISSION_DENIED,
            message = "Camera permission is unavailable or was denied",
        )
    }

    /**
     * Checks microphone permission and throws a microphone permission error if missing.
     */
    override suspend fun ensureMicrophonePermission() {
        if (isMicrophonePermissionGranted()) return

        throw XmaxError(
            code = XmaxErrorCode.MICROPHONE_PERMISSION_DENIED,
            message = "Microphone permission is unavailable or was denied",
        )
    }
}
