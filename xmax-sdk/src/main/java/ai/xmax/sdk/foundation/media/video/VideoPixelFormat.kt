//
//  VideoPixelFormat.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * Pixel formats supported by platform-neutral video frames.
 */
internal enum class VideoPixelFormat(
    /**
     * The string representation used for configuration or display.
     */
    val value: String,
) {

    /**
     * YUV420 with separate Y, U, and V planes.
     */
    I420("i420"),

    /**
     * YUV420 with Y and interleaved UV planes.
     */
    NV12("nv12"),

    /**
     * YUV420 with Y and interleaved VU planes.
     */
    NV21("nv21"),

    /**
     * Four-channel pixels ordered red, green, blue, alpha.
     */
    RGBA("rgba"),

    /**
     * Four-channel pixels ordered blue, green, red, alpha.
     */
    BGRA("bgra"),

    /**
     * Four-channel pixels ordered alpha, red, green, blue.
     */
    ARGB("argb"),
}
