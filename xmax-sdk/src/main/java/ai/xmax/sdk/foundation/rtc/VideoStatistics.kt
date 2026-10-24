//
//  VideoStatistics.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * Uplink video statistics. Unavailable metrics are null.
 */
public data class UplinkVideoStatistics(
    /**
     * The actual encoded width, in pixels.
     */
    public val width: Int? = null,

    /**
     * The actual encoded height, in pixels.
     */
    public val height: Int? = null,

    /**
     * The actual capture frame rate, in fps.
     */
    public val captureFrameRate: Int? = null,

    /**
     * The actual encoding frame rate, in fps.
     */
    public val encodeFrameRate: Int? = null,

    /**
     * The actual transmission frame rate, in fps.
     */
    public val sendFrameRate: Int? = null,

    /**
     * The actual transmitted bitrate, in kbps.
     */
    public val bitrateKbps: Int? = null,

    /**
     * Uplink video packet loss from this device to the RTC cloud, in percent.
     */
    public val packetLossPercent: Int? = null,
)

/**
 * Downlink video statistics. Unavailable metrics are null.
 */
public data class DownlinkVideoStatistics(
    /**
     * The remote user identifier shared with the video track.
     */
    public val userId: String,

    /**
     * The received width, in pixels.
     */
    public val width: Int? = null,

    /**
     * The received height, in pixels.
     */
    public val height: Int? = null,

    /**
     * The actual decoding frame rate, in fps.
     */
    public val decodeFrameRate: Int? = null,

    /**
     * The actual rendering frame rate, in fps.
     */
    public val renderFrameRate: Int? = null,

    /**
     * The actual received bitrate, in kbps.
     */
    public val bitrateKbps: Int? = null,

    /**
     * Downlink video packet loss, in percent.
     */
    public val packetLossPercent: Int? = null,

    /**
     * The RTC media end-to-end delay estimate, in milliseconds; excludes the full AI generation pipeline.
     */
    public val endToEndDelayMs: Int? = null,
)

/**
 * Receives uplink statistics on the main thread; null indicates no publication or a cleared connection.
 */
public fun interface UplinkVideoStatisticsListener {

    /**
     * Receives an uplink video statistics snapshot.
     *
     * @param statistics The current snapshot; see [UplinkVideoStatisticsListener] for null semantics.
     */
    public fun onStatistics(
        statistics: UplinkVideoStatistics?,
    )
}

/**
 * Receives downlink result statistics on the main thread; null indicates unavailable or cleared statistics.
 */
public fun interface DownlinkVideoStatisticsListener {

    /**
     * Receives a downlink video statistics snapshot.
     *
     * @param statistics The current snapshot; see [DownlinkVideoStatisticsListener] for null semantics.
     */
    public fun onStatistics(
        statistics: DownlinkVideoStatistics?,
    )
}
