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
 * 中性视频帧支持的像素格式。
 */
internal enum class VideoPixelFormat(
    /**
     * 需要规范化、校验或显示的文本。
     */
    val value: String
) {

    /**
     * Y、U、V 独立平面的 YUV420 格式。
     */
    I420("i420"),

    /**
     * Y 平面与 UV 交错平面的 YUV420 格式。
     */
    NV12("nv12"),

    /**
     * Y 平面与 VU 交错平面的 YUV420 格式。
     */
    NV21("nv21"),

    /**
     * 按红、绿、蓝、透明度排列的四通道像素格式。
     */
    RGBA("rgba"),

    /**
     * 按蓝、绿、红、透明度排列的四通道像素格式。
     */
    BGRA("bgra"),

    /**
     * 按透明度、红、绿、蓝排列的四通道像素格式。
     */
    ARGB("argb"),
}
