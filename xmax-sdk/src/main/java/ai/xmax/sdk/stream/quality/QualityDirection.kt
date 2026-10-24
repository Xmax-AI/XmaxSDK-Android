//
//  QualityDirection.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeNetworkQualityLevel
import ai.xmax.sdk.RealtimeVideoFormat

/**
 * One direction's quality state machine, committing a candidate level only after successful application.
 *
 * @param level The quality level to apply or retain; 1 is highest.
 */
internal class QualityDirection(
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
