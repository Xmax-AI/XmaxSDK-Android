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
 * 归一化坐标系中的二维点。
 */
public data class RealtimePoint(
    /**
     * 媒体横向相对位置，范围为 [0, 1]。
     */
    public val x: Double,

    /**
     * 媒体纵向相对位置，范围为 [0, 1]。
     */
    public val y: Double,
)
