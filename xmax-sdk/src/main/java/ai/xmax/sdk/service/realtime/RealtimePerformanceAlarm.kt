//
//  RealtimePerformanceAlarm.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * 实时性能限制状态。
 */
public enum class RealtimePerformanceStatus(
    /**
     * 需要规范化、校验或显示的文本。
     */
    public val value: String
) {

    /**
     * 设备性能受限。
     */
    LIMITED("Limited"),

    /**
     * 设备性能已恢复。
     */
    RECOVERED("Recovered"),
}

/**
 * 实时性能告警信息。
 */
public data class RealtimePerformanceAlarm(
    /**
     * 当前设备性能受限或恢复状态。
     */
    public val status: RealtimePerformanceStatus,

    /**
     * 设备建议的视频规格；null 表示没有有效建议。
     */
    public val suggestedVideoFormat: RealtimeVideoFormat?,
)

/**
 * 实时性能告警监听器。
 */
public fun interface RealtimePerformanceAlarmListener {

    /**
     * 接收设备性能受限状态及建议的视频规格。
     *
     * @param alarm 设备性能限制及建议规格快照。
     */
    public fun onPerformanceAlarm(
        alarm: RealtimePerformanceAlarm
    )
}
