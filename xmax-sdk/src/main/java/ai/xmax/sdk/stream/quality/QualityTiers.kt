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
 * Shared dimension scales and uplink frame-rate limits for one-based quality levels.
 */
internal object QualityTiers {

    /**
     * The initial transmission level when automatic adaptation is disabled.
     */
    const val INITIAL_LEVEL = 1

    /**
     * The minimum short side allowed by adaptation, in pixels.
     */
    const val MINIMUM_SHORT_SIDE = 240

    /**
     * The dimension percentage for each quality level.
     */
    val percentages = listOf(
        100,
        90,
        75,
        60,
        50,
    )

    /**
     * The frame-rate limit for L1 through L5, in fps.
     */
    val frameRates = listOf(
        30,
        24,
        24,
        20,
        16,
    )
}
