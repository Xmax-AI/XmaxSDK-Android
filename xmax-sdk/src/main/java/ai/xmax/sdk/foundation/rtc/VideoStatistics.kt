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
 *
 * @property width The actual encoded width, in pixels.
 * @property height The actual encoded height, in pixels.
 * @property captureFrameRate The actual capture frame rate, in fps.
 * @property encodeFrameRate The actual encoding frame rate, in fps.
 * @property sendFrameRate The actual transmission frame rate, in fps.
 * @property bitrateKbps The actual transmitted bitrate, in kbps.
 * @property packetLossPercent Uplink video packet loss from this device to the RTC cloud, in percent.
 */
public data class UplinkVideoStatistics(
    public val width: Int? = null,
    public val height: Int? = null,
    public val captureFrameRate: Int? = null,
    public val encodeFrameRate: Int? = null,
    public val sendFrameRate: Int? = null,
    public val bitrateKbps: Int? = null,
    public val packetLossPercent: Int? = null,
)

/**
 * Downlink video statistics. Unavailable metrics are null.
 *
 * @property userId The remote user identifier shared with the video track.
 * @property width The received width, in pixels.
 * @property height The received height, in pixels.
 * @property decodeFrameRate The actual decoding frame rate, in fps.
 * @property renderFrameRate The actual rendering frame rate, in fps.
 * @property bitrateKbps The actual received bitrate, in kbps.
 * @property packetLossPercent Downlink video packet loss, in percent.
 * @property endToEndDelayMs The RTC media end-to-end delay estimate, in milliseconds; excludes the full AI generation pipeline.
 */
public data class DownlinkVideoStatistics(
    public val userId: String,
    public val width: Int? = null,
    public val height: Int? = null,
    public val decodeFrameRate: Int? = null,
    public val renderFrameRate: Int? = null,
    public val bitrateKbps: Int? = null,
    public val packetLossPercent: Int? = null,
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
