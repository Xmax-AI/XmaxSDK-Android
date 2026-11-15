//
//  MicrophoneDevice.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import android.media.AudioDeviceInfo

/**
 * An audio input reported by Android's audio service.
 *
 * @property id The current Android audio device ID as a string. It may change after reconnecting.
 * @property name The Android product name, or a generic label when no name is available.
 * @property type The Android input type, one of the `TYPE_*` constants in [AudioDeviceInfo].
 */
public data class MicrophoneDevice internal constructor(
    public val id: String,
    public val name: String,
    public val type: Int,
)
