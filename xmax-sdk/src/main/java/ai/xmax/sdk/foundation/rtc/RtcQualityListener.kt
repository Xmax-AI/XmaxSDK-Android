//
//  RtcQualityListener.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.UplinkVideoStatistics
import ai.xmax.sdk.DownlinkVideoStatistics

/**
 * A vendor-neutral RTC network quality rating.
 */
internal enum class RtcQualityLevel {

    /**
     * No network quality sample is available.
     */
    UNKNOWN,

    /**
     * Excellent network quality.
     */
    EXCELLENT,

    /**
     * Good network quality.
     */
    GOOD,

    /**
     * Fair network quality that may affect realtime transmission.
     */
    POOR,

    /**
     * Poor network quality requiring reduced transmission load.
     */
    BAD,

    /**
     * Very poor network quality that substantially limits realtime transmission.
     */
    VERY_BAD,

    /**
     * The network is unavailable or transmission is interrupted.
     */
    DOWN,
}

/**
 * Receives vendor-neutral video statistics, network quality, and performance fallback events.
 */
internal interface RtcQualityListener {

    /**
     * Receives uplink statistics, or null when no valid statistics exist.
     *
     * @param statistics The current statistics, or null when unavailable.
     */
    fun onUplinkVideoStatistics(
        statistics: UplinkVideoStatistics?,
    )

    /**
     * Receives downlink statistics, or null when no valid statistics exist.
     *
     * @param statistics The current statistics, or null when unavailable.
     */
    fun onDownlinkVideoStatistics(
        statistics: DownlinkVideoStatistics?,
    )

    /**
     * Receives uplink and downlink network quality samples.
     *
     * @param uplink The uplink network quality rating.
     * @param downlink The downlink network quality rating.
     */
    fun onNetworkQuality(
        uplink: RtcQualityLevel,
        downlink: RtcQualityLevel,
    )

    /**
     * Receives device performance limits and a suggested video format.
     *
     * @param limited Whether device performance is limited.
     * @param suggestedWidth The suggested width, in pixels.
     * @param suggestedHeight The suggested height, in pixels.
     * @param suggestedFrameRate The suggested frame rate, in fps.
     */
    fun onPerformanceAlarm(
        limited: Boolean,
        suggestedWidth: Int,
        suggestedHeight: Int,
        suggestedFrameRate: Int,
    )
}
