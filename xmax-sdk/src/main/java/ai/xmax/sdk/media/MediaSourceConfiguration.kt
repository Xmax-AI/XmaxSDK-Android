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
 */
internal data class MediaSourceConfiguration(
    /**
     * The video dimensions, frame rate, and uplink encoding settings.
     */
    val videoFormat: RealtimeVideoFormat,

    /**
     * Whether the input provides audio.
     */
    val hasAudio: Boolean,
)
