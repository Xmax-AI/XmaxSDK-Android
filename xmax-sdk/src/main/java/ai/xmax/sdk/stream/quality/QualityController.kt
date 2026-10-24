//
//  QualityController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.UplinkVideoStatistics
import ai.xmax.sdk.UplinkVideoStatisticsListener
import ai.xmax.sdk.DownlinkVideoStatistics
import ai.xmax.sdk.DownlinkVideoStatisticsListener
import ai.xmax.sdk.RealtimeNetworkQualityLevel
import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimePerformanceAlarm
import ai.xmax.sdk.RealtimePerformanceAlarmListener
import ai.xmax.sdk.RealtimePerformanceStatus
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.foundation.rtc.RtcQualityLevel
import ai.xmax.sdk.foundation.rtc.RtcQualityListener
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.ErrorMessageFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Converts RTC quality events and notifies realtime listeners.
 *
 * Owns directional quality policies and history; public statistics and alarms are delivered on the
 * main thread.
 *
 * @param rtcManager The manager for RTC capture, publication, subscription, and rendering.
 */
internal class QualityController(
    rtcManager: RtcManaging,

    /**
     * The scope dispatching asynchronous callbacks.
     */
    private val callbackScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main,
    ),

    /**
     * Successful directional quality levels retained across connections.
     */
    private val history: QualityHistory = QualityHistory(),

    /**
     * The monotonic millisecond clock used for sample freshness and adjustment intervals.
     */
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : QualityControlling, RtcQualityListener {

    /**
     * The lock protecting quality policies and listener settings.
     */
    private val listenerLock = Any()

    /**
     * The active uplink policy, absent for server-read video.
     */
    private var uplinkQuality: QualityDirection? = null

    /**
     * The active downlink policy.
     */
    private var downlinkQuality: QualityDirection? = null

    /**
     * The fixed media baseline defining this connection's transport levels.
     */
    private var baseline: RealtimeVideoFormat? = null

    /**
     * The minimum uplink pixel area for the active model.
     */
    private var minimumInputPixels = 0

    /**
     * Whether the current connection uses automatic adaptation and saved quality history.
     */
    private var isAdaptiveQualityEnabled = false

    /**
     * The network quality listener registration revision.
     */
    private var networkVersion = 0L

    /**
     * The performance listener revision rejecting stale callbacks.
     */
    private var performanceVersion = 0L

    /**
     * The internal automatic quality sample callback.
     */
    private var adaptiveQualityListener: ((RealtimeNetworkQuality) -> Unit)? = null

    /**
     * The app's network quality listener.
     */
    private var networkQualityListener: RealtimeNetworkQualityListener? = null

    /**
     * The app's device performance alarm listener.
     */
    private var performanceAlarmListener: RealtimePerformanceAlarmListener? = null

    /**
     * The uplink statistics listener revision.
     */
    private var uplinkStatisticsVersion = 0L

    /**
     * The downlink statistics listener revision rejecting stale callbacks.
     */
    private var downlinkStatisticsVersion = 0L

    /**
     * Whether uplink statistics are collected and delivered.
     */
    private var uplinkStatisticsEnabled = false

    /**
     * The remote user selected for downlink statistics.
     */
    private var downlinkStatisticsUser: String? = null

    /**
     * The uplink video statistics listener.
     */
    private var uplinkStatisticsListener: UplinkVideoStatisticsListener? = null

    /**
     * The downlink video statistics listener.
     */
    private var downlinkStatisticsListener: DownlinkVideoStatisticsListener? = null

    init {
        rtcManager.setQualityListener(this)
    }

    /**
     * The committed transport levels and fixed media baseline.
     */
    override val qualityConfiguration: QualityConfiguration?
        get() = synchronized(listenerLock) {
            val media = baseline ?: return@synchronized null
            val downlink = downlinkQuality ?: return@synchronized null
            QualityConfiguration(
                baseline = media,
                uplinkLevel = uplinkQuality?.level,
                downlinkLevel = downlink.level,
                minimumInputPixels = minimumInputPixels,
            )
        }

    /**
     * Prepares initial transport quality from the media baseline and model history.
     *
     * @param baseline The fixed, resolved media format.
     * @param minimumInputPixels The minimum uplink pixel area.
     * @param publishLocalMedia Whether to prepare a local uplink policy.
     * @param isAdaptiveQualityEnabled Whether to adapt quality; disabled connections start at L1 without
     * reading or updating history.
     * @return The prepared directional transport configuration.
     */
    override fun prepareQuality(
        baseline: RealtimeVideoFormat,
        minimumInputPixels: Int,
        publishLocalMedia: Boolean,
        isAdaptiveQualityEnabled: Boolean,
    ): QualityConfiguration = synchronized(listenerLock) {
        this.baseline = baseline
        this.minimumInputPixels = minimumInputPixels
        this.isAdaptiveQualityEnabled = isAdaptiveQualityEnabled
        uplinkQuality = if (publishLocalMedia) {
            val level = if (isAdaptiveQualityEnabled) history.read(downlink = false) else QualityTiers.INITIAL_LEVEL
            val previous = uplinkQuality
            if (previous?.baseline == baseline && previous.level == level && previous.minimumInputPixels == minimumInputPixels) {
                previous.apply { resetSamples() }
            } else {
                QualityDirection(
                    baseline,
                    level,
                    downlink = false,
                    minimumInputPixels = minimumInputPixels,
                )
            }
        } else null
        downlinkQuality = QualityDirection(
            baseline,
            if (isAdaptiveQualityEnabled) history.read(downlink = true) else QualityTiers.INITIAL_LEVEL,
            downlink = true,
        )
        checkNotNull(qualityConfiguration)
    }

    /**
     * Proposes quality levels without committing them.
     *
     * @param quality The directional network quality.
     * @param sampledAt The sample time on the monotonic millisecond clock.
     * @return The proposed directional adjustments.
     */
    override fun proposeQuality(
        quality: RealtimeNetworkQuality,
        sampledAt: Long,
    ): QualityChange = synchronized(listenerLock) {
        if (!isAdaptiveQualityEnabled) return@synchronized QualityChange(null, null)

        val currentTime = now()
        QualityChange(
            uplink = uplinkQuality?.observe(
                quality.uplink,
                sampledAt,
                currentTime,
            ),
            downlink = downlinkQuality?.observe(
                quality.downlink,
                sampledAt,
                currentTime,
            ),
        )
    }

    /**
     * Commits an adjustment result, persisting successful levels only.
     *
     * @param downlink Whether to update the returned video policy.
     * @param level The attempted quality level.
     * @param success Whether the configuration was applied successfully.
     */
    override fun completeQuality(
        downlink: Boolean,
        level: Int,
        success: Boolean,
    ) = synchronized(listenerLock) {
        val direction = (if (downlink) downlinkQuality else uplinkQuality) ?: return@synchronized

        direction.complete(
            QualityDirection.Change(
                direction.level,
                level,
                "configuration",
            ),
            success,
            now(),
        )

        if (success && isAdaptiveQualityEnabled) history.write(
            downlink,
            level,
        )
    }

    /**
     * Discards consecutive samples while retaining committed levels and cooldowns.
     */
    override fun resetQualitySamples(): Unit = synchronized(listenerLock) {
        uplinkQuality?.resetSamples()
        downlinkQuality?.resetSamples()
    }

    /**
     * Persists an initial configuration without delaying its first network samples.
     *
     * @param downlink Whether the initial level affects returned video.
     */
    override fun completeInitialQuality(
        downlink: Boolean,
    ): Unit = synchronized(listenerLock) {
        if (!isAdaptiveQualityEnabled) return@synchronized

        val direction = (if (downlink) downlinkQuality else uplinkQuality) ?: return@synchronized
        history.write(
            downlink,
            direction.level,
        )
    }

    /**
     * Clears connection policies and queued quality callbacks, retaining saved levels and public listeners.
     */
    override fun resetQuality() = synchronized(listenerLock) {
        networkVersion++
        performanceVersion++
        uplinkQuality = null
        downlinkQuality = null
        baseline = null
        minimumInputPixels = 0
        isAdaptiveQualityEnabled = false
    }

    /**
     * Sets the uplink video statistics listener.
     *
     * Callbacks run on the main thread. The listener remains registered after disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setUplinkVideoStatisticsListener(
        listener: UplinkVideoStatisticsListener?,
    ) {
        synchronized(listenerLock) {
            uplinkStatisticsVersion++
            uplinkStatisticsListener = listener
        }
    }

    /**
     * Sets the downlink video statistics listener.
     *
     * Callbacks run on the main thread. The listener remains registered after disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setDownlinkVideoStatisticsListener(
        listener: DownlinkVideoStatisticsListener?,
    ) {
        synchronized(listenerLock) {
            downlinkStatisticsVersion++
            downlinkStatisticsListener = listener
        }
    }

    /**
     * Updates uplink statistics availability and clears previous statistics.
     *
     * @param enabled Whether the capability is enabled.
     */
    override fun setUplinkVideoStatisticsEnabled(
        enabled: Boolean,
    ) {
        synchronized(listenerLock) {
            uplinkStatisticsVersion++
            uplinkStatisticsEnabled = enabled
        }
        onUplinkVideoStatistics(null)
    }

    /**
     * Changes the downlink statistics target and clears previous statistics.
     *
     * Null stops collection.
     *
     * @param userId The room's business user identifier.
     */
    override fun setDownlinkVideoStatisticsUser(
        userId: String?,
    ) {
        synchronized(listenerLock) {
            downlinkStatisticsVersion++
            downlinkStatisticsUser = userId
        }
        onDownlinkVideoStatistics(null)
    }

    /**
     * Receives uplink statistics, or null when no valid statistics exist.
     *
     * @param statistics The current statistics, or null when unavailable.
     */
    override fun onUplinkVideoStatistics(
        statistics: UplinkVideoStatistics?,
    ) {
        val registration = synchronized(listenerLock) {
            if (statistics != null && !uplinkStatisticsEnabled) return

            uplinkStatisticsVersion to uplinkStatisticsListener
        }
        if (registration.second == null) return

        callbackScope.launch {
            if (synchronized(listenerLock) { uplinkStatisticsVersion != registration.first }) return@launch

            protect { registration.second?.onStatistics(statistics) }
        }
    }

    /**
     * Receives downlink statistics, or null when no valid statistics exist.
     *
     * @param statistics The current statistics, or null when unavailable.
     */
    override fun onDownlinkVideoStatistics(
        statistics: DownlinkVideoStatistics?,
    ) {
        val registration = synchronized(listenerLock) {
            if (statistics != null && statistics.userId != downlinkStatisticsUser) return

            downlinkStatisticsVersion to downlinkStatisticsListener
        }
        if (registration.second == null) return

        callbackScope.launch {
            if (synchronized(listenerLock) { downlinkStatisticsVersion != registration.first }) return@launch

            protect { registration.second?.onStatistics(statistics) }
        }
    }

    /**
     * Sets the internal adaptation listener independently of your app's network listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setAdaptiveQualityListener(
        listener: ((RealtimeNetworkQuality) -> Unit)?,
    ) {
        synchronized(listenerLock) { adaptiveQualityListener = listener }
    }

    /**
     * Sets the network quality listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setNetworkQualityListener(
        listener: RealtimeNetworkQualityListener?,
    ) {
        synchronized(listenerLock) {
            networkVersion++
            networkQualityListener = listener
        }
    }

    /**
     * Sets the device performance alarm listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setPerformanceAlarmListener(
        listener: RealtimePerformanceAlarmListener?,
    ) {
        synchronized(listenerLock) {
            performanceVersion++
            performanceAlarmListener = listener
        }
    }

    /**
     * Receives uplink and downlink network quality.
     *
     * @param uplink The uplink network quality rating.
     * @param downlink The downlink network quality rating.
     */
    override fun onNetworkQuality(
        uplink: RtcQualityLevel,
        downlink: RtcQualityLevel,
    ) {
        val quality = RealtimeNetworkQuality(
            networkQualityLevel(uplink),
            networkQualityLevel(downlink),
        )
        val adaptive = synchronized(listenerLock) { adaptiveQualityListener }

        protect { adaptive?.invoke(quality) }
        val registration = synchronized(listenerLock) { networkVersion to networkQualityListener }

        callbackScope.launch {
            if (synchronized(listenerLock) { networkVersion != registration.first }) return@launch

            protect { registration.second?.onNetworkQualityChanged(quality) }
        }
    }

    /**
     * Receives performance limitation or recovery events.
     *
     * @param limited Whether device performance is limited.
     * @param suggestedWidth The suggested width, in pixels.
     * @param suggestedHeight The suggested height, in pixels.
     * @param suggestedFrameRate The suggested frame rate, in fps.
     */
    override fun onPerformanceAlarm(
        limited: Boolean,
        suggestedWidth: Int,
        suggestedHeight: Int,
        suggestedFrameRate: Int,
    ) {
        val registration = synchronized(listenerLock) { performanceVersion to performanceAlarmListener }

        callbackScope.launch {
            if (synchronized(listenerLock) { performanceVersion != registration.first }) return@launch

            protect { registration.second?.onPerformanceAlarm(
                RealtimePerformanceAlarm(
                    status = if (limited) {
                        RealtimePerformanceStatus.LIMITED
                    } else {
                        RealtimePerformanceStatus.RECOVERED
                    },
                    suggestedVideoFormat = suggestedVideoFormat(
                        width = suggestedWidth,
                        height = suggestedHeight,
                        frameRate = suggestedFrameRate,
                    ),
                ),
            ) }
        }
    }

    /**
     * Isolates listener exceptions so statistics and automatic adaptation continue.
     *
     * @param action The operation to perform.
     */
    private fun protect(
        action: () -> Unit,
    ) {
        try { action() } catch (
            error: Throwable,
        ) {
            XmaxLogger.realtime.warn(
                message = { "Quality listener failed: ${ErrorMessageFormatter.format(error)}" },
            )
        }
    }

    /**
     * Maps RTC network quality to the public rating.
     *
     * @param level The quality level to apply or retain; 1 is highest.
     * @return The public network quality rating.
     */
    private fun networkQualityLevel(
        level: RtcQualityLevel,
    ): RealtimeNetworkQualityLevel =
        when (level) {
            RtcQualityLevel.UNKNOWN -> RealtimeNetworkQualityLevel.UNKNOWN
            RtcQualityLevel.EXCELLENT -> RealtimeNetworkQualityLevel.EXCELLENT
            RtcQualityLevel.GOOD -> RealtimeNetworkQualityLevel.GOOD
            RtcQualityLevel.POOR -> RealtimeNetworkQualityLevel.POOR
            RtcQualityLevel.BAD -> RealtimeNetworkQualityLevel.BAD
            RtcQualityLevel.VERY_BAD -> RealtimeNetworkQualityLevel.VERY_BAD
            RtcQualityLevel.DOWN -> RealtimeNetworkQualityLevel.DOWN
        }

    /**
     * Resolves a valid video format from a performance suggestion.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @param frameRate The video frame rate, in fps.
     * @return A valid suggested format, or null if none can be formed.
     */
    private fun suggestedVideoFormat(
        width: Int,
        height: Int,
        frameRate: Int,
    ): RealtimeVideoFormat? = if (width > 0 && height > 0 && frameRate > 0) {
        RealtimeVideoFormat(
            width,
            height,
            frameRate,
        )
    } else {
        null
    }
}
