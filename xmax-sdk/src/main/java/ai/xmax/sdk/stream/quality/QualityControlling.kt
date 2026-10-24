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
 * An interface for video statistics, network quality, and performance alarms.
 */
internal interface QualityControlling {

    /**
     * The current connection quality configuration, or null before preparation or after reset.
     */
    val qualityConfiguration: QualityConfiguration?

    /**
     * Prepares initial transport quality from the media baseline and model history.
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
     * Proposes quality levels without committing them.
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
     * Commits an adjustment result, persisting successful levels only.
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
     * Sets the internal adaptation listener independently of your app's network listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setAdaptiveQualityListener(
        listener: ((RealtimeNetworkQuality) -> Unit)?,
    )

    /**
     * Updates uplink statistics availability and clears previous statistics.
     *
     * @param enabled Whether the capability is enabled.
     */
    fun setUplinkVideoStatisticsEnabled(
        enabled: Boolean,
    )

    /**
     * Changes the downlink statistics target and clears previous statistics.
     *
     * Null stops collection.
     *
     * @param userId The room's business user identifier.
     */
    fun setDownlinkVideoStatisticsUser(
        userId: String?,
    )

    /**
     * Sets the uplink video statistics listener.
     *
     * Callbacks run on the main thread. The listener remains registered after disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setUplinkVideoStatisticsListener(
        listener: UplinkVideoStatisticsListener?,
    )

    /**
     * Sets the downlink video statistics listener.
     *
     * Callbacks run on the main thread. The listener remains registered after disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setDownlinkVideoStatisticsListener(
        listener: DownlinkVideoStatisticsListener?,
    )

    /**
     * Sets the network quality listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setNetworkQualityListener(
        listener: RealtimeNetworkQualityListener?,
    )

    /**
     * Sets the device performance alarm listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setPerformanceAlarmListener(
        listener: RealtimePerformanceAlarmListener?,
    )
}
