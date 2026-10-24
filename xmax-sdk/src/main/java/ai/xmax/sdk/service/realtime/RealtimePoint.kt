//
//  RealtimePoint.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * A point in normalized coordinates.
 */
public data class RealtimePoint(
    /**
     * The relative horizontal media position, from 0 to 1.
     */
    public val x: Double,

    /**
     * The relative vertical media position, from 0 to 1.
     */
    public val y: Double,
)
