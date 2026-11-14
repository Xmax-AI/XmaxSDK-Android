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
 *
 * @property width The frame width, in pixels.
 * @property height The frame height, in pixels.
 * @property rotation The rotation applied to the source image.
 * @property durationUs The media duration, in microseconds.
 * @property hasAudio Whether the input provides audio.
 */
internal data class MediaFileMetadata(
    val width: Int,
    val height: Int,
    val rotation: VideoRotation,
    val durationUs: Long,
    val hasAudio: Boolean,
)
