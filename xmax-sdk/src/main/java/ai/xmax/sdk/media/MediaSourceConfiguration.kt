//
//  MediaSourceConfiguration.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.RealtimeVideoFormat

/**
 * The output configuration of a prepared local video file.
 *
 * @property videoFormat The video dimensions, frame rate, and uplink encoding settings.
 * @property hasAudio Whether the input provides audio.
 */
internal data class MediaSourceConfiguration(
    val videoFormat: RealtimeVideoFormat,
    val hasAudio: Boolean,
)
