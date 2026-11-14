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
 *
 * @property value The string representation used for configuration or display.
 */
public enum class RealtimeNetworkQualityLevel(
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
 * The uplink and downlink quality of a realtime session.
 *
 * @property uplink The uplink network quality rating.
 * @property downlink The downlink network quality rating.
 */
public data class RealtimeNetworkQuality(
    public val uplink: RealtimeNetworkQualityLevel,
    public val downlink: RealtimeNetworkQualityLevel,
)

/**
 * A realtime network quality listener.
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
