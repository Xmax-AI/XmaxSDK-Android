//
//  FrameAudioSink.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.media3

import ai.xmax.sdk.RealtimeExternalFrameSink
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * Forwards playback-synchronized PCM to RTC while preserving local audio preview.
 *
 * The AudioTrack playback clock prevents prebuffered audio from reaching RTC ahead of playback.
 *
 * @param delegate The sink performing audio playback.
 */
@UnstableApi
internal class FrameAudioSink(
    delegate: AudioSink,

    /**
     * The function returning the current external media sink.
     */
    private val input: () -> RealtimeExternalFrameSink?,
) : ForwardingAudioSink(delegate) {

    /**
     * A PCM packet and its timeline position.
     */
    private data class Packet(
        /**
         * The binary data to read, transfer, or retain.
         */
        val data: ByteArray,

        /**
         * The packet's media presentation timestamp, in microseconds.
         */
        val ptsUs: Long,

        /**
         * The input sample rate, in Hz.
         */
        val sampleRate: Int,

        /**
         * The number of interleaved PCM channels.
         */
        val channelCount: Int,
    )

    /**
     * The pending PCM packet queue.
     */
    private val pending = ArrayDeque<Packet>()

    /**
     * The packetizer splitting original PCM into playback-paced chunks without format conversion.
     */
    private val packetizer = PcmPacketizer { bytes, pts ->
        val format = checkNotNull(format)
        if (pending.size >= 100) pending.removeFirst()
        pending.addLast(
            Packet(
                bytes,
                pts,
                format.sampleRate,
                format.channelCount,
            ),
        )
    }

    /**
     * The current input audio format.
     */
    private var format: Format? = null

    /**
     * The SDK receiver for external audio frames.
     */
    private var binding: RealtimeExternalFrameSink? = null

    /**
     * The monotonic offset mapping the current playback segment onto the video clock.
     */
    private var clockOffsetUs: Long? = null

    /**
     * Checks whether the sink supports an audio format.
     *
     * @param format The audio encoding, sample rate, and channel configuration.
     * @return True if the audio format is supported.
     */
    override fun supportsFormat(
        format: Format,
    ): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)

    /**
     * Returns the sink's support level for an audio format.
     *
     * @param format The audio encoding, sample rate, and channel configuration.
     * @return The Media3 audio format support constant.
     */
    override fun getFormatSupport(
        format: Format,
    ): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED

    /**
     * Configures PCM16 playback and buffers, discarding pending data from the previous format.
     *
     * @param inputFormat The input encoding, sample rate, and channels.
     * @param specifiedBufferSize The player-requested buffer size, in bytes.
     * @param outputChannels The output channel mapping, or null for the default.
     */
    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        require(inputFormat.pcmEncoding == C.ENCODING_PCM_16BIT) { "Media3 adapter requires PCM16 audio" }
        format = inputFormat
        clearPending()
        super.configure(
            inputFormat,
            specifiedBufferSize,
            outputChannels,
        )
    }

    /**
     * Passes decoded audio to playback and buffers external PCM for submission.
     *
     * @param buffer The buffer containing the data to process.
     * @param presentationTimeUs The media presentation timestamp, in microseconds.
     * @param encodedAccessUnitCount The encoded audio access-unit count in the buffer.
     * @return True if the audio sink consumed the entire buffer.
     */
    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val start = buffer.position()
        val copy = buffer.duplicate()
        val consumed = super.handleBuffer(
            buffer,
            presentationTimeUs,
            encodedAccessUnitCount,
        )
        val sink = input()?.takeIf { it.isRequestingFrames }
        if (binding !== sink) { clearPending(); binding = sink }
        val f = format
        if (sink != null && f != null && buffer.position() > start) {
            copy.limit(buffer.position())
            packetizer.append(
                copy,
                f.sampleRate,
                f.channelCount,
                presentationTimeUs + start.toLong() / (2 * f.channelCount) * 1_000_000 / f.sampleRate,
            )
            drain(sink)
        }

        return consumed
    }

    /**
     * Submits due PCM packets according to playback position, dropping severely late packets.
     *
     * @param sink The SDK receiver for external media frames.
     */
    private fun drain(
        sink: RealtimeExternalFrameSink,
    ) {
        val positionUs = super.getCurrentPositionUs(false)
        if (positionUs == Long.MIN_VALUE) return

        while (pending.isNotEmpty() && pending.first.ptsUs <= positionUs + 10_000) {
            val packet = pending.removeFirst()
            val ageUs = positionUs - packet.ptsUs
            if (ageUs > 100_000) continue

            val observedUs = (SystemClock.elapsedRealtimeNanos() / 1000 - ageUs).coerceAtLeast(0)
            val offsetUs = clockOffsetUs
            if (offsetUs == null || kotlin.math.abs(packet.ptsUs + offsetUs - observedUs) > 30_000) {
                clockOffsetUs = observedUs - packet.ptsUs
            }
            sink.pushAudio(
                packet.data,
                packet.ptsUs + checkNotNull(clockOffsetUs),
                packet.sampleRate,
                packet.channelCount,
            )
        }
    }

    /**
     * Submits the buffered audio tail and completes playback.
     */
    override fun playToEndOfStream() {
        super.playToEndOfStream()
        input()?.takeIf { it.isRequestingFrames }?.let(::drain)
    }

    /**
     * Clears audio buffers and resets playback progress.
     */
    override fun flush() { clearPending(); super.flush() }

    /**
     * Resets the audio sink and discards pending PCM.
     */
    override fun reset() { clearPending(); super.reset() }

    /**
     * Discards pending old audio when the timeline becomes discontinuous.
     */
    override fun handleDiscontinuity() { clearPending(); super.handleDiscontinuity() }

    /**
     * Clears pending PCM and packetization state.
     */
    private fun clearPending() {
        pending.clear()
        packetizer.reset()
        clockOffsetUs = null
    }
}
