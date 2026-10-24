//
//  RealtimeQualityLevel.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * A transport quality level, from highest quality at L1 to lowest bandwidth at L5.
 *
 * Transport levels are independent of network quality ratings.
 */
public enum class RealtimeQualityLevel(
    /**
     * The tier number, from 1 to 5.
     */
    public val rawValue: Int,
) {

    /**
     * Retains the media baseline dimensions.
     */
    L1(1),

    /**
     * Targets 80% of the media baseline width and height.
     */
    L2(2),

    /**
     * Targets 65% of the media baseline width and height.
     */
    L3(3),

    /**
     * Targets 50% of the media baseline width and height.
     */
    L4(4),

    /**
     * Uses L4 dimensions with a lower uplink frame-rate limit.
     */
    L5(5),
}
