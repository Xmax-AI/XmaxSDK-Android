//
//  MediaFileMetadata.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.media

import ai.xmax.sdk.VideoRotation

/**
 * The video, audio, and timeline metadata of a local media file.
 */
internal data class MediaFileMetadata(
    /**
     * The frame width, in pixels.
     */
    val width: Int,

    /**
     * The frame height, in pixels.
     */
    val height: Int,

    /**
     * The rotation applied to the source image.
     */
    val rotation: VideoRotation,

    /**
     * The media duration, in microseconds.
     */
    val durationUs: Long,

    /**
     * Whether the input provides audio.
     */
    val hasAudio: Boolean,
)
