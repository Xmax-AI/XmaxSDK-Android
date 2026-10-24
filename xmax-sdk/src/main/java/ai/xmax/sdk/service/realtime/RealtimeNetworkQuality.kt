//
//  RealtimeNetworkQuality.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * An RTC network quality rating.
 */
public enum class RealtimeNetworkQualityLevel(
    /**
     * The string representation used for configuration or display.
     */
    public val value: String,
) {

    /**
     * No network quality sample is available.
     */
    UNKNOWN("Unknown"),

    /**
     * Excellent network quality.
     */
    EXCELLENT("Excellent"),

    /**
     * Good network quality.
     */
    GOOD("Good"),

    /**
     * Fair network quality that may affect realtime transmission.
     */
    POOR("Poor"),

    /**
     * Poor network quality requiring reduced transmission load.
     */
    BAD("Bad"),

    /**
     * Very poor network quality that substantially limits realtime transmission.
     */
    VERY_BAD("VeryBad"),

    /**
     * The network is unavailable or transmission is interrupted.
     */
    DOWN("Down"),
}

/**
 * Uplink and downlink network quality for a realtime session.
 */
public data class RealtimeNetworkQuality(
    /**
     * The uplink network quality rating.
     */
    public val uplink: RealtimeNetworkQualityLevel,

    /**
     * The downlink network quality rating.
     */
    public val downlink: RealtimeNetworkQualityLevel,
)

/**
 * The realtime network quality listener.
 */
public fun interface RealtimeNetworkQualityListener {

    /**
     * Reports current uplink and downlink quality.
     *
     * @param quality The current network quality sample.
     */
    public fun onNetworkQualityChanged(
        quality: RealtimeNetworkQuality,
    )
}
