//
//  VideoRotation.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * The clockwise rotation applied to a video frame.
 */
internal enum class VideoRotation(
    /**
     * The rotation angle, in degrees.
     */
    val degrees: Int,
) {

    /**
     * No rotation.
     */
    ROTATION_0(0),

    /**
     * A 90-degree clockwise rotation.
     */
    ROTATION_90(90),

    /**
     * A 180-degree rotation.
     */
    ROTATION_180(180),

    /**
     * A 270-degree clockwise rotation.
     */
    ROTATION_270(270),
}
