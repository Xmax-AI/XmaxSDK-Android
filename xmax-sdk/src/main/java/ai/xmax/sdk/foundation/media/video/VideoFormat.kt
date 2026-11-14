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
 * The fixed dimensions and pixel format of a video frame.
 *
 * @property width The frame width, in pixels.
 * @property height The frame height, in pixels.
 * @property pixelFormat The arrangement of video pixels.
 */
internal data class VideoFormat(
    val width: Int,
    val height: Int,
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
