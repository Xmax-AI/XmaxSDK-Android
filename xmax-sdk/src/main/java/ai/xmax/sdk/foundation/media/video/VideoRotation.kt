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
 * 视频帧需要顺时针旋转的角度。
 */
internal enum class VideoRotation(
    /**
     * 旋转角度，单位为度。
     */
    val degrees: Int
) {

    /**
     * 画面无需旋转。
     */
    ROTATION_0(0),

    /**
     * 画面顺时针旋转 90 度。
     */
    ROTATION_90(90),

    /**
     * 画面旋转 180 度。
     */
    ROTATION_180(180),

    /**
     * 画面顺时针旋转 270 度。
     */
    ROTATION_270(270),
}
