//
//  ExternalAudioFrameConverter.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.audio

import ai.xmax.sdk.AudioFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Converts a serialized external PCM16 stream into timestamped, 10 ms RTC audio packets.
 */
internal class ExternalAudioFrameConverter {

    /**
     * The sample rate fixed by the first accepted buffer.
     */
    private var rate = 0

    /**
     * The channel count fixed by the first accepted buffer.
     */
    private var channels = 0

    /**
     * The first sample's timestamp in the current contiguous segment, in microseconds.
     */
    private var anchorUs = 0L

    /**
     * The expected start time of the next source buffer, in microseconds.
     */
    private var nextInputUs: Double? = null

    /**
     * The input sample count within the current segment.
     */
    private var inputSamples = 0L

    /**
     * The converted sample count within the current segment.
     */
    private var outputSamples = 0L

    /**
     * The previous mono sample used for interpolation across buffer boundaries.
     */
    private var previous = 0.0

    /**
     * The partial output packet, retaining less than 10 ms between submissions.
     */
    private val packet = ByteBuffer.allocate(AudioFrame.samplesPerFrame * 2).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Converts PCM while preserving its timeline and rejecting changes to the binding's audio format.
     *
     * @param pcm Complete, interleaved PCM16 little-endian audio frames.
     * @param timestampUs The first sample's timestamp on the video clock, in microseconds.
     * @param sampleRate The input sample rate, in Hz.
     * @param channelCount The input channel count.
     * @param handler The synchronous receiver of copied 48 kHz mono packets.
     */
    fun append(
        pcm: ByteArray,
        timestampUs: Long,
        sampleRate: Int,
        channelCount: Int,
        handler: (AudioFrame) -> Unit,
    ) {
        if (timestampUs < 0) return

        require(sampleRate > 0 && channelCount > 0 && channelCount <= Int.MAX_VALUE / 2) {
            "External audio requires a positive sample rate and channel count"
        }
        require(rate == 0 || rate == sampleRate && channels == channelCount) {
            "External PCM format cannot change during a binding"
        }
        val stride = channelCount * 2
        require(pcm.isNotEmpty() && pcm.size % stride == 0) { "External audio requires complete PCM16 frames" }

        val expected = nextInputUs
        val difference = expected?.let { timestampUs.toDouble() - it }
        // One microsecond accounts for integer timestamp rounding at arbitrary input sample rates.
        val toleranceUs = maxOf(
            1.0,
            500_000.0 / sampleRate,
        )
        if (difference != null && difference < -toleranceUs) return

        if (expected == null || difference != null && abs(difference) > toleranceUs) {
            anchorUs = timestampUs
            inputSamples = 0
            outputSamples = 0
            previous = 0.0
            packet.clear()
        }
        rate = sampleRate
        channels = channelCount
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        while (input.hasRemaining()) {
            var sum = 0L
            repeat(channels) { sum += input.short }
            val current = sum.toDouble() / channels
            while (outputSamples.toDouble() * rate / AudioFrame.sampleRate <= inputSamples) {
                val position = outputSamples.toDouble() * rate / AudioFrame.sampleRate
                val fraction = (position - (inputSamples - 1)).coerceIn(
                    0.0,
                    1.0,
                )
                val value = if (inputSamples == 0L) current else previous + (current - previous) * fraction
                packet.putShort(
                    value.roundToInt().coerceIn(
                        -32768,
                        32767,
                    ).toShort(),
                )
                outputSamples++
                if (!packet.hasRemaining()) {
                    handler(
                        AudioFrame(
                            packet.array(),
                            anchorUs + (outputSamples - AudioFrame.samplesPerFrame) * 1_000_000 / AudioFrame.sampleRate,
                        ),
                    )
                    packet.clear()
                }
            }
            previous = current
            inputSamples++
        }
        nextInputUs = timestampUs + (pcm.size / stride) * 1_000_000.0 / sampleRate
    }
}
