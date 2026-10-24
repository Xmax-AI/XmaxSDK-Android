//
//  QualityControlling.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.UplinkVideoStatisticsListener
import ai.xmax.sdk.DownlinkVideoStatisticsListener
import ai.xmax.sdk.RealtimePerformanceAlarmListener

/**
 * The video statistics, network quality, and device alarm listener capabilities.
 */
internal interface QualityControlling {

    /**
     * Receives internal adaptation samples independently of the app's network listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setAdaptiveQualityListener(
        listener: ((RealtimeNetworkQuality) -> Unit)?,
    )

    /**
     * Enables or disables uplink statistics and clears the current snapshot.
     *
     * @param enabled Whether the capability is enabled.
     */
    fun setUplinkVideoStatisticsEnabled(
        enabled: Boolean,
    )

    /**
     * Sets the downlink statistics user and clears the snapshot; null stops collection.
     *
     * @param userId The room's business user identifier.
     */
    fun setDownlinkVideoStatisticsUser(
        userId: String?,
    )

    /**
     * Sets the main-thread uplink statistics listener; null unregisters it. The listener survives
     * disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setUplinkVideoStatisticsListener(
        listener: UplinkVideoStatisticsListener?,
    )

    /**
     * Sets the main-thread downlink statistics listener; null unregisters it. The listener survives
     * disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setDownlinkVideoStatisticsListener(
        listener: DownlinkVideoStatisticsListener?,
    )

    /**
     * Sets the RTC network quality listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setNetworkQualityListener(
        listener: RealtimeNetworkQualityListener?,
    )

    /**
     * Sets the RTC performance alarm listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setPerformanceAlarmListener(
        listener: RealtimePerformanceAlarmListener?,
    )
}
