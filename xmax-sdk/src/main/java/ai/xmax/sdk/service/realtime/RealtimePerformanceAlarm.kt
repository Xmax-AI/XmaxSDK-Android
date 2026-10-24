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
 * A realtime performance limitation state.
 */
public enum class RealtimePerformanceStatus(
    /**
     * The string representation used for configuration or display.
     */
    public val value: String,
) {

    /**
     * Device performance is limited.
     */
    LIMITED("Limited"),

    /**
     * Device performance has recovered.
     */
    RECOVERED("Recovered"),
}

/**
 * A realtime device performance alarm.
 */
public data class RealtimePerformanceAlarm(
    /**
     * The current device limitation or recovery state.
     */
    public val status: RealtimePerformanceStatus,

    /**
     * The suggested video format, or null without a valid suggestion.
     */
    public val suggestedVideoFormat: RealtimeVideoFormat?,
)

/**
 * A realtime performance alarm listener.
 */
public fun interface RealtimePerformanceAlarmListener {

    /**
     * Receives performance limitation or recovery events.
     *
     * @param alarm The device limitation and suggested format snapshot.
     */
    public fun onPerformanceAlarm(
        alarm: RealtimePerformanceAlarm,
    )
}
