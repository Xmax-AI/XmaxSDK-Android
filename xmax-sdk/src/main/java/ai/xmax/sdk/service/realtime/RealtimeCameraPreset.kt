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
 * A camera resolution preset whose availability depends on the selected model.
 */
public enum class RealtimeCameraPreset(
    /**
     * The output dimensions, in pixels, without frame rate or bitrate settings.
     */
    public val size: IntSize,
) {

    /**
     * A 16:9 landscape image.
     */
    RESOLUTION_1920X1080(
        IntSize(
            1_920,
            1_080,
        ),
    ),

    /**
     * A 9:16 portrait image.
     */
    RESOLUTION_1080X1920(
        IntSize(
            1_080,
            1_920,
        ),
    ),

    /**
     * A square image.
     */
    RESOLUTION_1408X1408(
        IntSize(
            1_408,
            1_408,
        ),
    ),

    /**
     * A 3:4 portrait image.
     */
    RESOLUTION_1152X1536(
        IntSize(
            1_152,
            1_536,
        ),
    ),

    /**
     * A 4:3 landscape image.
     */
    RESOLUTION_1536X1152(
        IntSize(
            1_536,
            1_152,
        ),
    ),
}
