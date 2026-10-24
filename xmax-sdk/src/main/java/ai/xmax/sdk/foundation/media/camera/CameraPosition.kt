//
//  CameraPosition.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * The camera used for capture.
 */
public enum class CameraPosition(
    /**
     * The string representation used for configuration or display.
     */
    public val value: String,
) {

    /**
     * The front camera.
     */
    FRONT("front"),

    /**
     * The rear camera.
     */
    BACK("back"),
}
