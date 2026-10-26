//
//  RealtimeCameraFormat.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime

import ai.xmax.sdk.RealtimeConnectionState
import ai.xmax.sdk.RealtimeVideoFormat
import androidx.annotation.StringRes
import com.xmax.xlab.R

/**
 * Formats camera dimensions as a reduced width-to-height ratio.
 */
internal fun cameraAspectRatioLabel(
    format: RealtimeVideoFormat,
): String {
    var divisor = format.width
    var remainder = format.height
    while (remainder != 0) {
        val next = divisor % remainder
        divisor = remainder
        remainder = next
    }

    divisor = divisor.coerceAtLeast(1)
    return "${format.width / divisor}:${format.height / divisor}"
}

/**
 * Returns the explanation for an unavailable ratio change, or null when the menu may open.
 */
@StringRes
internal fun cameraFormatHint(
    connectionState: RealtimeConnectionState,
    generationBusy: Boolean = false,
    cameraSwitching: Boolean = false,
    cameraFormatUpdating: Boolean = false,
    isSuspendedForBackground: Boolean = false,
): Int? {
    if (generationBusy || connectionState == RealtimeConnectionState.CONNECTING || connectionState == RealtimeConnectionState.CONNECTED || connectionState == RealtimeConnectionState.GENERATING) {
        return R.string.realtime_camera_format_stop_hint
    }

    if (connectionState != RealtimeConnectionState.READY || cameraSwitching || cameraFormatUpdating || isSuspendedForBackground) {
        return R.string.realtime_camera_format_wait_hint
    }

    return null
}
