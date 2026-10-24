//
//  VideoContentMode.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * The video's scaling mode within its rendering container.
 */
public enum class VideoContentMode(
    /**
     * The string representation used for configuration or display.
     */
    public val value: String,
) {

    /**
     * Fits the complete video while preserving aspect ratio, leaving unused container space empty.
     */
    FIT("fit"),

    /**
     * Fills the container while preserving aspect ratio and cropping overflow.
     */
    FILL("fill"),
}
