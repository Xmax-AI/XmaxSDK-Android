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

import ai.xmax.sdk.RealtimeVideoFormat

/**
 * The video statistics, network quality, and device alarm listener capabilities.
 */
internal interface QualityControlling {

    /**
     * The current connection quality configuration, or null before preparation or after reset.
     */
    val qualityConfiguration: QualityConfiguration?

    /**
     * Prepares independent transport levels from media dimensions and successful history.
     *
     * @param baseline The fixed, resolved local media format.
     * @param minimumInputPixels The minimum uplink pixel area.
     * @param publishLocalMedia Whether the connection publishes local video.
     * @return The initial directional transport configuration.
     */
    fun prepareQuality(
        baseline: RealtimeVideoFormat,
        minimumInputPixels: Int = 0,
        publishLocalMedia: Boolean = true,
    ): QualityConfiguration

    /**
     * Proposes transport levels from a fresh sample without committing them.
     *
     * @param quality The directional network quality.
     * @param sampledAt The sample time on the monotonic millisecond clock.
     * @return The proposed directional adjustments.
     */
    fun proposeQuality(
        quality: RealtimeNetworkQuality,
        sampledAt: Long,
    ): QualityChange

    /**
     * Commits an adjustment result and persists successful levels.
     *
     * @param downlink Whether the adjustment affects returned video.
     * @param level The attempted quality level.
     * @param success Whether the configuration was applied successfully.
     */
    fun completeQuality(
        downlink: Boolean,
        level: Int,
        success: Boolean,
    )

    /**
     * Clears consecutive samples without discarding committed levels.
     */
    fun resetQualitySamples()

    /**
     * Persists an initially applied level without starting an adaptation cooldown.
     *
     * @param downlink Whether the initial level affects returned video.
     */
    fun completeInitialQuality(
        downlink: Boolean,
    )

    /**
     * Releases connection quality state while retaining history and public listeners.
     */
    fun resetQuality()

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
