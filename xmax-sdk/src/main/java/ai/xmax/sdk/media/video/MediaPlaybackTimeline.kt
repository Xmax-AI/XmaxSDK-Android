//
//  MediaPlaybackTimeline.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.AudioFrame
import android.os.SystemClock
import kotlin.math.ceil

/**
 * A shared looped-playback clock for a file's audio and video.
 *
 * @param mediaDurationUs The duration of one playback loop, in microseconds.
 * @param nowNanoseconds The current monotonic timestamp, in nanoseconds.
 */
internal class MediaPlaybackTimeline(
    mediaDurationUs: Long,
    nowNanoseconds: Long = SystemClock.elapsedRealtimeNanos(),
) {

    /**
     * The sample count of one media loop.
     */
    val cycleSampleCount: Int

    /**
     * The duration of one playback loop, in nanoseconds.
     */
    val cycleDurationNanoseconds: Long

    /**
     * The monotonic playback origin, in nanoseconds.
     */
    private val anchorNanoseconds: Long

    init {
        require(mediaDurationUs > 0L) { "Media duration must be positive" }
        val rawSampleCount = ceil(
            mediaDurationUs.toDouble() * AudioFrame.sampleRate / MICROSECONDS_PER_SECOND,
        ).toLong()
        val packetCount = ceil(
            rawSampleCount.toDouble() / AudioFrame.samplesPerFrame,
        ).toLong()
        val alignedSamples = maxOf(
            AudioFrame.samplesPerFrame.toLong(),
            packetCount *
                AudioFrame.samplesPerFrame,
        )
        require(alignedSamples <= Int.MAX_VALUE) { "Media duration is too long" }
        cycleSampleCount = alignedSamples.toInt()
        cycleDurationNanoseconds = alignedSamples * NANOSECONDS_PER_SECOND /
            AudioFrame.sampleRate
        anchorNanoseconds = nowNanoseconds + START_DELAY_NANOSECONDS
    }

    /**
     * Resolves the playback deadline for a position within a loop.
     *
     * @param loopIndex The zero-based playback loop number.
     * @param mediaOffsetUs The position within this loop, in microseconds.
     * @return The frame submission deadline on the monotonic nanosecond clock.
     */
    fun target(
        loopIndex: Int,
        mediaOffsetUs: Long,
    ): Long =
        anchorNanoseconds + cycleDurationNanoseconds * loopIndex +
            mediaOffsetUs.coerceAtLeast(0L) * NANOSECONDS_PER_MICROSECOND

    /**
     * Resolves the playback deadline for a position within a loop.
     *
     * @param loopIndex The zero-based playback loop number.
     * @param sampleOffset The packet's starting sample position on the media timeline.
     * @return The frame submission deadline on the monotonic nanosecond clock.
     */
    fun target(
        loopIndex: Int,
        sampleOffset: Int,
    ): Long = target(
        loopIndex,
        sampleOffset.toLong() * MICROSECONDS_PER_SECOND / AudioFrame.sampleRate,
    )

    /**
     * Playback startup buffering and time conversion constants.
     */
    private companion object {

        /**
         * The startup buffering interval, in nanoseconds.
         */
        const val START_DELAY_NANOSECONDS = 100_000_000L

        /**
         * The number of nanoseconds per microsecond.
         */
        const val NANOSECONDS_PER_MICROSECOND = 1_000L

        /**
         * The number of nanoseconds per second.
         */
        const val NANOSECONDS_PER_SECOND = 1_000_000_000L

        /**
         * The number of microseconds per second.
         */
        const val MICROSECONDS_PER_SECOND = 1_000_000L
    }
}
