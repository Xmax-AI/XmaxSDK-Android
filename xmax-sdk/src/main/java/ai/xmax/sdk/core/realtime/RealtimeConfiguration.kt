//
//  RealtimeConfiguration.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * The model configuration for a realtime manager.
 */
public data class RealtimeConfiguration(
    /**
     * The model for new sessions; the client environment selects the service region.
     */
    public val model: RealtimeModel = RealtimeModel.X2_1,
)
