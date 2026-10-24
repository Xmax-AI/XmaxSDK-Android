//
//  RemoteAudioTimeline.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.video

import ai.xmax.sdk.RealtimeAudioFrame
import ai.xmax.sdk.foundation.rtc.RtcRemoteAudioFrame
import kotlin.math.abs

/**
 * Preserves sample-accurate audio cadence while anchoring discontinuities to the RTC render clock.
 */
internal class RemoteAudioTimeline {

    /**
     * The RTC render clock origin of the current audio segment, in microseconds.
     */
    private var anchorUs: Long? = null

    /**
     * The cumulative samples since the current clock anchor.
     */
    private var sampleOffset = 0L

    /**
     * The sample rate of the current audio segment.
     */
    private var sampleRate = 0

    /**
     * The interleaved channel count of the current segment.
     */
    private var channels = 0

    /**
     * Starts a new audio timeline at the next valid packet.
     */
    fun reset() {
        anchorUs = null
        sampleOffset = 0
        sampleRate = 0
        channels = 0
    }

    /**
     * Resolves PCM timing from cumulative samples, reanchoring format changes or clock jumps.
     *
     * @param frame The owned RTC audio packet.
     * @return The validated audio packet and continuity flag, or null for invalid audio.
     */
    fun resolve(
        frame: RtcRemoteAudioFrame,
    ): Pair<RealtimeAudioFrame, Boolean>? {
        if (frame.renderTimeMs <= 0 || frame.renderTimeMs > Long.MAX_VALUE / 1_000 ||
            frame.sampleRate !in 8_000..192_000 || frame.channels !in 1..2 ||
            frame.samplesPerChannel !in 1..192_000 ||
            frame.data.size.toLong() != frame.samplesPerChannel.toLong() * frame.channels * 2
        ) {
            reset()
            return null
        }

        val nativeUs = frame.renderTimeMs * 1_000
        val predictedUs = anchorUs?.let { it + sampleOffset * 1_000_000 / sampleRate }
        val discontinuous = predictedUs == null || sampleRate != frame.sampleRate ||
            channels != frame.channels || abs(nativeUs - predictedUs) > 100_000
        if (discontinuous) {
            anchorUs = nativeUs
            sampleOffset = 0
            sampleRate = frame.sampleRate
            channels = frame.channels
        }

        val startUs = checkNotNull(anchorUs) + sampleOffset * 1_000_000 / sampleRate
        sampleOffset += frame.samplesPerChannel
        val endUs = checkNotNull(anchorUs) + sampleOffset * 1_000_000 / sampleRate
        return RealtimeAudioFrame(
            pcm = frame.data,
            sampleRate = sampleRate,
            channels = channels,
            samplesPerChannel = frame.samplesPerChannel,
            presentationTimeUs = startUs,
            durationUs = endUs - startUs,
        ) to discontinuous
    }
}
