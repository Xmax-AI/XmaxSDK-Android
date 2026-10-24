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
 * Converts RTC quality events, feeds internal adaptation samples, and dispatches public statistics and alarms
 * on the main thread.
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
) : QualityControlling, RtcQualityListener {

    /**
     * The lock protecting quality listener settings.
     */
    private val listenerLock = Any()

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

    /**
     * Sets the main-thread uplink statistics listener; null unregisters it. The listener survives
     * disconnection.
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
     * Sets the main-thread downlink statistics listener; null unregisters it. The listener survives
     * disconnection.
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
     * Enables or disables uplink statistics and clears the current snapshot.
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
     * Sets the downlink statistics user and clears the snapshot; null stops collection.
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

    init {
        rtcManager.setQualityListener(this)
    }

    /**
     * Receives internal adaptation samples without occupying the public network listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setAdaptiveQualityListener(
        listener: ((RealtimeNetworkQuality) -> Unit)?,
    ) {
        synchronized(listenerLock) { adaptiveQualityListener = listener }
    }

    /**
     * Sets the RTC network quality listener; null unregisters it.
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
     * Sets the RTC performance alarm listener; null unregisters it.
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
     * Receives uplink and downlink network quality samples.
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
     * Receives device performance limits and a suggested video format.
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
     * Converts internal RTC quality to the public network rating.
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
     * Builds a suggested format, returning null for invalid dimensions or frame rate.
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

    /**
     * One direction's quality state machine, committing a candidate level only after successful application.
     *
     * @param level The quality level to apply or retain; 1 is highest.
     */
    internal class Direction(
        /**
         * The original input format used to calculate quality levels.
         */
        val baseline: RealtimeVideoFormat,

        level: Int,

        /**
         * Whether this direction is downlink rather than uplink.
         */
        val downlink: Boolean,

        /**
         * The thresholds and cooldowns for quality adaptation.
         */
        private val policy: QualityPolicy = QualityPolicy(),

        /**
         * The minimum uplink pixel area; ignored for downlink.
         */
        val minimumInputPixels: Int = 0,
    ) {

        /**
         * The currently committed quality level.
         */
        @Volatile var level = QualityVideoFormat.initialLevel(
            baseline,
            level,
            downlink,
            minimumInputPixels,
        )
            private set

        /**
         * The transmission format derived from the baseline and level.
         */
        val format: RealtimeVideoFormat get() = QualityVideoFormat.resolve(
            baseline,
            level,
            downlink,
            minimumInputPixels,
        )

        /**
         * The consecutive good network sample count.
         */
        private var good = 0

        /**
         * The consecutive poor network sample count.
         */
        private var poor = 0

        /**
         * The latest network sample time, in milliseconds.
         */
        private var lastSample: Long? = null

        /**
         * The end of the post-adjustment stabilization interval.
         */
        private var stableUntil = 0L

        /**
         * The earliest permitted upgrade time, in milliseconds.
         */
        private var upgradeAfter = 0L

        /**
         * The upgrade observation deadline, in milliseconds.
         */
        private var probeUntil = 0L

        /**
         * A quality adjustment with its old level, target, and reason.
         */
        data class Change(
            /**
             * The previous quality level.
             */
            val from: Int,

            /**
             * The target quality level.
             */
            val to: Int,

            /**
             * The reason for the transition or failure.
             */
            val reason: String,
        )

        /**
         * Clears consecutive samples while retaining the level, stabilization interval, and upgrade cooldown.
         */
        @Synchronized
        fun resetSamples() {
            good = 0
            poor = 0
            lastSample = null
        }

        /**
         * Evaluates fresh consecutive samples for the next quality adjustment.
         *
         * @param quality The current network quality sample.
         * @param sampleAt The sample time on the monotonic millisecond clock.
         * @param now The current monotonic time, in milliseconds.
         * @return A suggested level change, or null if no change is needed or currently allowed.
         */
        @Synchronized
        fun observe(
            quality: RealtimeNetworkQualityLevel,
            sampleAt: Long,
            now: Long,
        ): Change? {
            if (sampleAt > now || (lastSample != null && sampleAt <= lastSample!!)) return null

            if (lastSample != null && sampleAt - lastSample!! > policy.sampleTimeoutMs) resetSamples()

            lastSample = sampleAt
            if (now - sampleAt > policy.sampleTimeoutMs || sampleAt < stableUntil) {
                good = 0
                poor = 0
                return null
            }

            val step = when (quality) {
                RealtimeNetworkQualityLevel.BAD, RealtimeNetworkQualityLevel.VERY_BAD -> {
                    good = 0
                    if (++poor < policy.poorSamples) return null
                    1
                }
                RealtimeNetworkQualityLevel.EXCELLENT, RealtimeNetworkQualityLevel.GOOD -> {
                    poor = 0
                    if (sampleAt < upgradeAfter) {
                        good = 0
                        return null
                    }

                    if (++good < policy.goodSamples) return null
                    -1
                }
                else -> {
                    good = 0
                    poor = 0
                    return null
                }
            }
            var next = level + step
            while (next in 1..QualityTiers.percentages.size && QualityVideoFormat.resolve(
                baseline,
                next,
                downlink,
                minimumInputPixels,
            ) == format) {
                next += step
            }
            good = 0
            poor = 0
            if (next !in 1..QualityTiers.percentages.size) return null

            next = QualityVideoFormat.canonicalLevel(
                baseline,
                next,
                downlink,
                minimumInputPixels,
            )
            if (!downlink && !QualityVideoFormat.supportsEncoding(
                QualityVideoFormat.resolve(
                    baseline,
                    next,
                    false,
                    minimumInputPixels,
                ),
            )) return null

            return Change(
                level,
                next,
                if (step < 0) "probeUpgrade" else if (now < probeUntil) "failedProbe" else "poorNetwork",
            )
        }

        /**
         * Records adjustment success and updates stabilization and upgrade probe state.
         *
         * @param change The quality adjustment being applied.
         * @param success Whether the level was applied successfully.
         * @param now The current monotonic time, in milliseconds.
         */
        @Synchronized
        fun complete(
            change: Change,
            success: Boolean,
            now: Long,
        ) {
            resetSamples()
            stableUntil = now + policy.stabilizationMs
            if (!success) return

            if (change.to < level) {
                probeUntil = now + policy.probeMs
            } else if (change.to > level) {
                if (now < probeUntil) upgradeAfter = now + policy.upgradeCooldownMs
                probeUntil = 0
            }
            level = change.to
        }
    }
}
