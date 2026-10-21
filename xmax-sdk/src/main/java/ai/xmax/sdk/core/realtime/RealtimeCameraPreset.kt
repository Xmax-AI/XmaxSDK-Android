//
//  RealtimeCameraPreset.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import androidx.compose.ui.unit.IntSize

/**
 * 摄像头输出分辨率的快捷选项；实际支持范围由模型决定。
 */
public enum class RealtimeCameraPreset(
    /**
     * 输出宽高，单位为像素；不包含帧率或码率配置。
     */
    public val size: IntSize,
) {

    /**
     * 16:9 横向画面。
     */
    RESOLUTION_1920X1080(IntSize(1_920, 1_080)),

    /**
     * 9:16 竖向画面。
     */
    RESOLUTION_1080X1920(IntSize(1_080, 1_920)),

    /**
     * 1:1 正方形画面。
     */
    RESOLUTION_1408X1408(IntSize(1_408, 1_408)),

    /**
     * 3:4 竖向画面。
     */
    RESOLUTION_1152X1536(IntSize(1_152, 1_536)),

    /**
     * 4:3 横向画面。
     */
    RESOLUTION_1536X1152(IntSize(1_536, 1_152)),
}
