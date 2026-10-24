//
//  PCMFramePacketizer.kt
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
import java.util.ArrayDeque

/**
 * Packetizes decoded PCM into fixed 10 ms RTC audio frames.
 */
internal class PCMFramePacketizer(
    /**
     * The total number of samples to process.
     */
    private val totalSamples: Int,
) {

    /**
     * The current audio packet buffer.
     */
    private val pendingSamples = ArrayDeque<Short>()

    /**
     * The number of samples written into the current packet.
     */
    private var writtenSampleCount = 0

    /**
     * The total emitted sample count used for timestamps.
     */
    private var emittedSampleCount = 0

    init {
        require(totalSamples >= AudioFrame.samplesPerFrame)
        require(totalSamples % AudioFrame.samplesPerFrame == 0)
    }

    /**
     * Buffers timeline-positioned PCM16 samples into fixed-size packets.
     *
     * @param samples The PCM16 samples to packetize.
     * @param atSample The starting sample position on the media timeline.
     */
    fun append(
        samples: ShortArray,
        atSample: Int,
    ) {
        if (writtenSampleCount >= totalSamples || samples.isEmpty()) return

        val normalizedStart = atSample.coerceAtLeast(0)
        while (writtenSampleCount < normalizedStart && writtenSampleCount < totalSamples) {
            pendingSamples.addLast(0)
            writtenSampleCount += 1
        }

        val skippedSamples = (writtenSampleCount - normalizedStart).coerceAtLeast(0)
        var index = skippedSamples
        while (index < samples.size && writtenSampleCount < totalSamples) {
            pendingSamples.addLast(samples[index])
            writtenSampleCount += 1
            index += 1
        }
    }

    /**
     * Pads the incomplete final audio frame with silence.
     */
    fun finishWithSilence() {
        while (writtenSampleCount < totalSamples) {
            pendingSamples.addLast(0)
            writtenSampleCount += 1
        }
    }

    /**
     * Returns the next complete PCM packet, or null while more samples are needed.
     *
     * @return The next complete packet, or null if incomplete.
     */
    fun nextFrame(): Packet? {
        if (pendingSamples.size < AudioFrame.samplesPerFrame ||
            emittedSampleCount >= totalSamples
        ) {
            return null
        }

        val bytes = ByteBuffer.allocate(AudioFrame.samplesPerFrame * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(AudioFrame.samplesPerFrame) {
            bytes.putShort(pendingSamples.removeFirst())
        }

        return Packet(
            data = bytes.array(),
            sampleOffset = emittedSampleCount,
        ).also {
            emittedSampleCount += AudioFrame.samplesPerFrame
        }
    }

    /**
     * A PCM packet and its timeline position.
     */
    internal data class Packet(
        /**
         * The binary data to read, transfer, or retain.
         */
        val data: ByteArray,

        /**
         * The packet's starting sample position on the media timeline.
         */
        val sampleOffset: Int,
    )
}
