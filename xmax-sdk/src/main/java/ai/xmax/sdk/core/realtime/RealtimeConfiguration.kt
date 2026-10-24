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
 * The model and adaptive quality settings for a realtime manager.
 */
public data class RealtimeConfiguration @JvmOverloads constructor(
    /**
     * The model for new sessions; the client environment selects the service region.
     */
    public val model: RealtimeModel = RealtimeModel.X2_1_1_PREVIEW_CN,

    /**
     * Whether uplink and downlink quality levels adapt automatically.
     *
     * When disabled, each connection starts at L1 without reading or updating quality history. Manual
     * changes remain available.
     */
    public val isAdaptiveQualityEnabled: Boolean = true,
)
