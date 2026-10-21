//
//  StatisticsPanel.kt
//  XmaxSDK
//  Repo: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime

import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.RealtimeNetworkQualityLevel
import ai.xmax.sdk.RealtimeQualityLevel
import ai.xmax.sdk.DownlinkVideoStatistics
import ai.xmax.sdk.UplinkVideoStatistics
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xmax.xlab.R
import com.xmax.xlab.xLabText

/**
 * 展示上下行视频统计与网络质量。
 */
@Composable
internal fun StatisticsPanel(
    uplink: UplinkVideoStatistics?,
    downlink: DownlinkVideoStatistics?,
    quality: RealtimeNetworkQuality?,
    uplinkLevel: RealtimeQualityLevel?,
    downlinkLevel: RealtimeQualityLevel?,
    canAdjustQuality: Boolean,
    isAdjustingQuality: Boolean,
    qualityError: String?,
    onQualityLevel: (RealtimeQualityLevel, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(184.dp)
            .background(Color.Black.copy(alpha = 0.64f), RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        StatisticsHeading(xLabText(R.string.statistics_uplink))
        QualityButtons(uplinkLevel, canAdjustQuality && !isAdjustingQuality && uplinkLevel != null,
            xLabText(R.string.statistics_uplink)) { onQualityLevel(it, false) }
        StatisticsRow(xLabText(R.string.statistics_resolution), statisticsResolution(uplink?.width, uplink?.height))
        StatisticsRow(xLabText(R.string.statistics_capture_frame_rate), statisticsMetric(uplink?.captureFrameRate, "fps"))
        StatisticsRow(xLabText(R.string.statistics_encode_frame_rate), statisticsMetric(uplink?.encodeFrameRate, "fps"))
        StatisticsRow(xLabText(R.string.statistics_send_frame_rate), statisticsMetric(uplink?.sendFrameRate, "fps"))
        StatisticsRow(xLabText(R.string.statistics_bitrate), statisticsMetric(uplink?.bitrateKbps, "kbps"))
        StatisticsRow(xLabText(R.string.statistics_loss), statisticsMetric(uplink?.packetLossPercent, "%"))
        StatisticsRow(xLabText(R.string.statistics_network), qualityText(quality?.uplink))
        StatisticsHeading(xLabText(R.string.statistics_downlink), Modifier.padding(top = 6.dp))
        QualityButtons(downlinkLevel, canAdjustQuality && !isAdjustingQuality && downlinkLevel != null,
            xLabText(R.string.statistics_downlink)) { onQualityLevel(it, true) }
        StatisticsRow(xLabText(R.string.statistics_resolution), statisticsResolution(downlink?.width, downlink?.height))
        StatisticsRow(xLabText(R.string.statistics_decode_frame_rate), statisticsMetric(downlink?.decodeFrameRate, "fps"))
        StatisticsRow(xLabText(R.string.statistics_render_frame_rate), statisticsMetric(downlink?.renderFrameRate, "fps"))
        StatisticsRow(xLabText(R.string.statistics_bitrate), statisticsMetric(downlink?.bitrateKbps, "kbps"))
        StatisticsRow(xLabText(R.string.statistics_loss), statisticsMetric(downlink?.packetLossPercent, "%"))
        StatisticsRow(xLabText(R.string.statistics_network), qualityText(quality?.downlink))
        StatisticsRow(xLabText(R.string.statistics_e2e), statisticsMetric(downlink?.endToEndDelayMs, "ms"))
        Text(xLabText(R.string.statistics_level_hint), color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp,
            modifier = Modifier.padding(top = 4.dp))
        qualityError?.let { Text(it, color = Color(0xFFFF7373), fontSize = 10.sp) }
    }
}

@Composable
private fun QualityButtons(
    current: RealtimeQualityLevel?,
    enabled: Boolean,
    direction: String,
    onClick: (RealtimeQualityLevel) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        RealtimeQualityLevel.entries.forEach { level ->
            Box(
                Modifier.weight(1f).heightIn(min = 28.dp)
                    .background(if (current == level) Color(0xFF278BE8) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .semantics { selected = current == level; contentDescription = "$direction ${level.name}" }
                    .clickable(enabled = enabled && current != level, role = Role.Button) { onClick(level) },
                contentAlignment = Alignment.Center,
            ) {
                Text(level.name, color = Color.White.copy(alpha = if (enabled) 1f else 0.5f),
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StatisticsHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Color.White, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun StatisticsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, lineHeight = 14.sp)
        Text(value, color = Color.White, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

/**
 * 绘制统计图标，蓝色表示面板已打开。
 */
@Composable
internal fun StatisticsGlyph(selected: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val color = if (selected) Color(0xFF64B5FF) else Color.White
        listOf(0.45f, 0.85f, 0.65f).forEachIndexed { index, height ->
            val x = size.width * (0.2f + index * 0.3f)
            drawLine(color, Offset(x, size.height * 0.9f), Offset(x, size.height * (0.9f - height)),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/**
 * 将统计值格式化为带单位的文本；无数据时显示占位符。
 */
internal fun statisticsMetric(value: Int?, unit: String): String = value?.let { "$it $unit" } ?: "—"

internal fun statisticsResolution(width: Int?, height: Int?): String =
    if (width != null && height != null) "$width × $height" else "—"

@Composable
private fun qualityText(quality: RealtimeNetworkQualityLevel?): String = when (quality) {
    RealtimeNetworkQualityLevel.EXCELLENT -> xLabText(R.string.statistics_quality_excellent)
    RealtimeNetworkQualityLevel.GOOD -> xLabText(R.string.statistics_quality_good)
    RealtimeNetworkQualityLevel.POOR -> xLabText(R.string.statistics_quality_poor)
    RealtimeNetworkQualityLevel.BAD -> xLabText(R.string.statistics_quality_bad)
    RealtimeNetworkQualityLevel.VERY_BAD -> xLabText(R.string.statistics_quality_very_bad)
    RealtimeNetworkQualityLevel.DOWN -> xLabText(R.string.statistics_quality_down)
    else -> "—"
}
