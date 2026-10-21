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
 * 本地媒体文件中视频、音频和时间线所需的元数据。
 */
internal data class MediaFileMetadata(
    /**
     * 画面宽度，单位为像素。
     */
    val width: Int,

    /**
     * 画面高度，单位为像素。
     */
    val height: Int,

    /**
     * 应用到源画面的旋转方向。
     */
    val rotation: VideoRotation,

    /**
     * 媒体持续时间，单位为微秒。
     */
    val durationUs: Long,

    /**
     * 输入媒体是否包含可用音频。
     */
    val hasAudio: Boolean,
)
