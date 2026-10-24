//
//  RtcStatsLogger.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.XmaxLoggerOption
import ai.xmax.sdk.UplinkVideoStatistics
import ai.xmax.sdk.DownlinkVideoStatistics

/**
 * Formats RTC statistics for Xmax logs.
 *
 * Unavailable values are displayed as a dash.
 */
internal object RtcStatsLogger {

    /**
     * Logs uplink statistics when performance logging is enabled.
     *
     * @param stats The RTC video statistics snapshot.
     */
    fun logUplinkVideoStatistics(
        stats: UplinkVideoStatistics,
    ) {
        XmaxLogger.rtc.debug(
            message = { uplinkVideoStatisticsMessage(stats) },
            option = XmaxLoggerOption.performance,
        )
    }

    /**
     * Logs downlink statistics when performance logging is enabled.
     *
     * @param stats The RTC video statistics snapshot.
     */
    fun logDownlinkVideoStatistics(
        stats: DownlinkVideoStatistics,
    ) {
        XmaxLogger.rtc.debug(
            message = { downlinkVideoStatisticsMessage(stats) },
            option = XmaxLoggerOption.performance,
        )
    }

    /**
     * Formats uplink metrics as log lines.
     *
     * @param stats The RTC video statistics snapshot.
     * @return The uplink statistics log lines.
     */
    internal fun uplinkVideoStatisticsMessage(
        stats: UplinkVideoStatistics,
    ): String =
        "视频上行统计 (Video Uplink Statistics)\n" +
            "├─ ${label(
                "分辨率：",
                "Resolution: ",
            )}${resolution(
                stats.width,
                stats.height,
            )}\n" +
            "├─ ${label(
                "发送码率：",
                "Send Bitrate: ",
            )}${metric(
                stats.bitrateKbps,
                "kbps",
            )}\n" +
            "├─ ${label(
                "采集帧率：",
                "Capture Frame Rate: ",
            )}${metric(
                stats.captureFrameRate,
                "fps",
            )}\n" +
            "├─ ${label(
                "编码帧率：",
                "Encode Frame Rate: ",
            )}${metric(
                stats.encodeFrameRate,
                "fps",
            )}\n" +
            "├─ ${label(
                "发送帧率：",
                "Send Frame Rate: ",
            )}${metric(
                stats.sendFrameRate,
                "fps",
            )}\n" +
            "└─ ${label(
                "视频丢包率：",
                "Video Packet Loss: ",
            )}${metric(
                stats.packetLossPercent,
                "%",
            )}"

    /**
     * Formats downlink metrics as log lines.
     *
     * @param stats The RTC video statistics snapshot.
     * @return The downlink statistics log lines.
     */
    internal fun downlinkVideoStatisticsMessage(
        stats: DownlinkVideoStatistics,
    ): String =
        "视频下行统计 (Video Downlink Statistics)\n" +
            "├─ ${label(
                "分辨率：",
                "Resolution: ",
            )}${resolution(
                stats.width,
                stats.height,
            )}\n" +
            "├─ ${label(
                "接收码率：",
                "Receive Bitrate: ",
            )}${metric(
                stats.bitrateKbps,
                "kbps",
            )}\n" +
            "├─ ${label(
                "解码帧率：",
                "Decode Frame Rate: ",
            )}${metric(
                stats.decodeFrameRate,
                "fps",
            )}\n" +
            "├─ ${label(
                "渲染帧率：",
                "Render Frame Rate: ",
            )}${metric(
                stats.renderFrameRate,
                "fps",
            )}\n" +
            "├─ ${label(
                "视频丢包率：",
                "Video Packet Loss: ",
            )}${metric(
                stats.packetLossPercent,
                "%",
            )}\n" +
            "└─ ${label(
                "端到端时延：",
                "End-to-End Delay: ",
            )}${metric(
                stats.endToEndDelayMs,
                "ms",
            )}"

    /**
     * Formats a metric and unit, marking missing values as unavailable.
     *
     * @param value The metric value, or null when unavailable.
     * @param unit The unit suffix.
     * @return The formatted metric and unit, or an unavailable marker.
     */
    private fun metric(
        value: Int?,
        unit: String,
    ): String = value?.let { "$it $unit" } ?: "—"

    /**
     * Formats video dimensions, marking missing dimensions as unavailable.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @return The resolution text, or an unavailable marker.
     */
    private fun resolution(
        width: Int?,
        height: Int?,
    ): String =
        if (width != null && height != null) "$width × $height" else "—"

    /**
     * Selects Chinese or English text from the logging environment.
     *
     * @param chinese The text used in the China environment.
     * @param english The text used in the global environment.
     * @return Text in the configured log language.
     */
    private fun label(
        chinese: String,
        english: String,
    ): String = XmaxLogger.localized(
        chinese,
        english,
    )
}
