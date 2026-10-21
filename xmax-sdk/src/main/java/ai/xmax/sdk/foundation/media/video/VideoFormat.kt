//
//  VideoFormat.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * 表示视频帧的固定尺寸和像素格式。
 */
internal data class VideoFormat(
    /**
     * 画面宽度，单位为像素。
     */
    val width: Int,

    /**
     * 画面高度，单位为像素。
     */
    val height: Int,

    /**
     * 视频像素数据的排列格式。
     */
    val pixelFormat: VideoPixelFormat,
) {
    init {
        if (width <= 0 || height <= 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Video width and height must be positive integers",
            )
        }
    }
}
