//
//  RtcQualityConverter.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import io.agora.rtc2.Constants
import ai.xmax.sdk.UplinkVideoStatistics
import ai.xmax.sdk.DownlinkVideoStatistics
import io.agora.rtc2.IRtcEngineEventHandler.LocalVideoStats
import io.agora.rtc2.IRtcEngineEventHandler.RemoteVideoStats

/**
 * Converts Agora network quality and video statistics without substituting invented values for unavailable
 * measurements.
 */
internal object RtcQualityConverter {

    /**
     * Converts local RTC video statistics to uplink metrics.
     *
     * @param stats The RTC video statistics snapshot.
     * @return Uplink statistics using SDK units and fields.
     */
    fun uplinkVideoStatistics(
        stats: LocalVideoStats,
    ): UplinkVideoStatistics = UplinkVideoStatistics(
        width = stats.encodedFrameWidth.takeIf { it > 0 },
        height = stats.encodedFrameHeight.takeIf { it > 0 },
        captureFrameRate = stats.captureFrameRate.takeIf { it >= 0 },
        encodeFrameRate = stats.encoderOutputFrameRate.takeIf { it >= 0 },
        sendFrameRate = stats.sentFrameRate.takeIf { it >= 0 },
        bitrateKbps = stats.sentBitrate.takeIf { it >= 0 },
        packetLossPercent = stats.txPacketLossRate.takeIf { it in 0..100 },
    )

    /**
     * Converts remote RTC video statistics to downlink metrics.
     *
     * @param userId The room's business user identifier.
     * @param stats The RTC video statistics snapshot.
     * @return Downlink statistics using SDK units and fields.
     */
    fun downlinkVideoStatistics(
        userId: String,
        stats: RemoteVideoStats,
    ): DownlinkVideoStatistics = DownlinkVideoStatistics(
        userId = userId,
        width = stats.width.takeIf { it > 0 },
        height = stats.height.takeIf { it > 0 },
        decodeFrameRate = stats.decoderOutputFrameRate.takeIf { it >= 0 },
        renderFrameRate = stats.rendererOutputFrameRate.takeIf { it >= 0 },
        bitrateKbps = stats.receivedBitrate.takeIf { it >= 0 },
        packetLossPercent = stats.packetLossRate.takeIf { it in 0..100 },
        endToEndDelayMs = stats.e2eDelay.takeIf { it >= 0 },
    )

    /**
     * Converts vendor network quality ratings to internal SDK levels.
     *
     * @param quality The vendor's network quality rating.
     * @return The corresponding internal network quality level.
     */
    fun convertLevel(
        quality: Int,
    ): RtcQualityLevel = when (quality) {
        Constants.QUALITY_EXCELLENT -> RtcQualityLevel.EXCELLENT
        Constants.QUALITY_GOOD -> RtcQualityLevel.GOOD
        Constants.QUALITY_POOR -> RtcQualityLevel.POOR
        Constants.QUALITY_BAD -> RtcQualityLevel.BAD
        Constants.QUALITY_VBAD -> RtcQualityLevel.VERY_BAD
        Constants.QUALITY_DOWN -> RtcQualityLevel.DOWN
        else -> RtcQualityLevel.UNKNOWN
    }
}
