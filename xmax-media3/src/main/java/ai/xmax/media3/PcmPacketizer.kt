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

/**
 * Splits original PCM16 into short, playback-paced chunks without resampling or mixing channels.
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
     * The first sample index of the current packet.
     */
    private var packetStartSample = 0L

    /**
     * The number of samples buffered in the current packet.
     */
    private var packetSamples = 0

    /**
     * The number of input samples per playback-paced chunk.
     */
    private var samplesPerPacket = 0

    /**
     * The buffer holding approximately 10 ms of original interleaved PCM.
     */
    private var packet = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Resets packetization for a new format and media timeline.
     */
    fun reset() {
        rate = 0
        channels = 0
        inputSamples = 0
        packetStartSample = 0
        packetSamples = 0
        packet.clear()
    }

    /**
     * Retains original PCM in short chunks for synchronized SDK submission.
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
            reset()
            rate = sampleRate
            channels = channelCount
            originUs = timestampUs
            samplesPerPacket = (rate / 100).coerceAtLeast(1)
            packet = ByteBuffer.allocate(samplesPerPacket * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        }

        val input = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= channels * 2) {
            repeat(channels) { packet.putShort(input.short) }
            inputSamples++
            packetSamples++
            if (packetSamples == samplesPerPacket) {
                emit(
                    packet.array().copyOf(),
                    originUs + packetStartSample * 1_000_000 / rate,
                )
                packet.clear()
                packetSamples = 0
                packetStartSample = inputSamples
            }
        }
    }
}
