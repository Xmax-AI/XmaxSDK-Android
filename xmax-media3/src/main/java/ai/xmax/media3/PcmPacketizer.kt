//
//  PcmPacketizer.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.media3

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Resamples PCM16 across buffers into 10 ms packets, retaining phase to prevent rounding-induced clock drift.
 */
internal class PcmPacketizer(
    /**
     * The callback receiving complete audio packets and microsecond timestamps.
     */
    private val emit: (ByteArray, Long) -> Unit,
) {

    /**
     * The input sample rate.
     */
    private var rate = 0

    /**
     * The input PCM channel count.
     */
    private var channels = 0

    /**
     * The current audio timeline origin, in microseconds.
     */
    private var originUs = 0L

    /**
     * The total input sample count.
     */
    private var inputSamples = 0L

    /**
     * The sample count emitted on the current timeline.
     */
    private var outputSamples = 0L

    /**
     * The number of samples buffered in the current packet.
     */
    private var packetSamples = 0

    /**
     * The previous mono sample used for resampling interpolation.
     */
    private var previous = 0.0

    /**
     * The buffer holding 10 ms of 48 kHz mono PCM.
     */
    private val packet = ByteBuffer.allocate(960).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Resets packetization for a new format and media timeline.
     */
    fun reset() { rate = 0; channels = 0; inputSamples = 0; outputSamples = 0; packetSamples = 0; packet.clear() }

    /**
     * Resamples decoded PCM into the SDK's audio packets.
     *
     * @param data The buffer containing valid PCM bytes.
     * @param sampleRate The sample rate, in Hz.
     * @param channelCount The number of audio channels.
     * @param timestampUs The monotonic frame timestamp, in microseconds.
     */
    fun append(
        data: ByteBuffer,
        sampleRate: Int,
        channelCount: Int,
        timestampUs: Long,
    ) {
        require(sampleRate > 0 && channelCount > 0)
        val expectedUs = originUs + inputSamples * 1_000_000L / sampleRate
        if (rate != sampleRate || channels != channelCount || kotlin.math.abs(timestampUs - expectedUs) > 30_000L) {
            reset(); rate = sampleRate; channels = channelCount; originUs = timestampUs
        }
        val input = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= channelCount * 2) {
            var sum = 0L
            repeat(channelCount) { sum += input.short }
            val current = sum.toDouble() / channelCount
            // Interpolate when the output sample falls between the previous and current input samples.
            while (outputSamples.toDouble() * rate / 48_000 <= inputSamples) {
                val position = outputSamples.toDouble() * rate / 48_000
                val value = if (inputSamples == 0L) current else {
                    val fraction = (position - (inputSamples - 1)).coerceIn(
                        0.0,
                        1.0,
                    )
                    previous + (current - previous) * fraction
                }
                packet.putShort(
                    value.roundToInt().coerceIn(
                        -32768,
                        32767,
                    ).toShort(),
                )
                outputSamples++; packetSamples++
                if (packetSamples == 480) {
                    emit(
                        packet.array().copyOf(),
                        originUs + (outputSamples - 480) * 1_000_000 / 48_000,
                    )
                    packet.clear(); packetSamples = 0
                }
            }
            previous = current
            inputSamples++
        }
    }
}
