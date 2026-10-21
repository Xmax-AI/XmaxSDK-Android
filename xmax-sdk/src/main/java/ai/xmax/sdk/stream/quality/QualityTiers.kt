//
//  QualityTiers.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.quality

/**
 * 上下行共用的尺寸比例与上行帧率上限，档位编号从 1 开始。
 */
internal object QualityTiers {

    /**
     * 默认档位与最低输出尺寸约束。
     */
    const val INITIAL_LEVEL = 2

    /**
     * 自动调档允许的最小短边，单位为像素。
     */
    const val MINIMUM_SHORT_SIDE = 240

    /**
     * 各档位的分辨率比例和帧率上限。
     */
    val percentages = listOf(100, 80, 65, 50, 50)

    /**
     * L1 至 L5 各档位的目标帧率，单位为 fps。
     */
    val frameRates = listOf(30, 24, 24, 20, 16)
}
