//
//  RtcRemoteAudioSink.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import io.agora.rtc2.IAudioFrameObserver
import io.agora.rtc2.audio.AudioParams
import java.nio.ByteBuffer

/**
 * Copies unmixed PCM only for the selected remote publisher, preserving native playback buffers.
 */
internal class RtcRemoteAudioSink(
    /**
     * The active PCM receiver resolver for a channel and publisher.
     */
    private val listenerProvider: (String, Int) -> RtcRemoteAudioFrameListener?,
) : IAudioFrameObserver {

    /**
     * Leaves unobserved audio unchanged for normal RTC processing.
     *
     * @param channelId The owning RTC channel.
     * @param type The native audio frame type.
     * @param samplesPerChannel The number of samples per channel.
     * @param bytesPerSample The sample storage width in bytes.
     * @param channels The number of interleaved channels.
     * @param samplesPerSec The sample rate in samples per second.
     * @param buffer The borrowed PCM buffer.
     * @param renderTimeMs The shared RTC render timestamp in milliseconds.
     * @param avsyncType The native synchronization mode.
     * @return True to preserve native audio processing.
     */
    override fun onRecordAudioFrame(
        channelId: String,
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer,
        renderTimeMs: Long,
        avsyncType: Int,
    ): Boolean = true

    /**
     * Leaves unobserved audio unchanged for normal RTC processing.
     *
     * @param channelId The owning RTC channel.
     * @param type The native audio frame type.
     * @param samplesPerChannel The number of samples per channel.
     * @param bytesPerSample The sample storage width in bytes.
     * @param channels The number of interleaved channels.
     * @param samplesPerSec The sample rate in samples per second.
     * @param buffer The borrowed PCM buffer.
     * @param renderTimeMs The shared RTC render timestamp in milliseconds.
     * @param avsyncType The native synchronization mode.
     * @return True to preserve native audio processing.
     */
    override fun onPlaybackAudioFrame(
        channelId: String,
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer,
        renderTimeMs: Long,
        avsyncType: Int,
    ): Boolean = true

    /**
     * Leaves unobserved audio unchanged for normal RTC processing.
     *
     * @param channelId The owning RTC channel.
     * @param type The native audio frame type.
     * @param samplesPerChannel The number of samples per channel.
     * @param bytesPerSample The sample storage width in bytes.
     * @param channels The number of interleaved channels.
     * @param samplesPerSec The sample rate in samples per second.
     * @param buffer The borrowed PCM buffer.
     * @param renderTimeMs The shared RTC render timestamp in milliseconds.
     * @param avsyncType The native synchronization mode.
     * @return True to preserve native audio processing.
     */
    override fun onMixedAudioFrame(
        channelId: String,
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer,
        renderTimeMs: Long,
        avsyncType: Int,
    ): Boolean = true

    /**
     * Leaves unobserved audio unchanged for normal RTC processing.
     *
     * @param type The native audio frame type.
     * @param samplesPerChannel The number of samples per channel.
     * @param bytesPerSample The sample storage width in bytes.
     * @param channels The number of interleaved channels.
     * @param samplesPerSec The sample rate in samples per second.
     * @param buffer The borrowed PCM buffer.
     * @param renderTimeMs The shared RTC render timestamp in milliseconds.
     * @param avsyncType The native synchronization mode.
     * @return True to preserve native audio processing.
     */
    override fun onEarMonitoringAudioFrame(
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer,
        renderTimeMs: Long,
        avsyncType: Int,
    ): Boolean = true

    /**
     * Copies the selected remote publisher's PCM before mixing.
     *
     * @param channelId The owning RTC channel.
     * @param uid The remote publisher identifier.
     * @param type The native audio frame type.
     * @param samplesPerChannel The number of samples per channel.
     * @param bytesPerSample The sample storage width in bytes.
     * @param channels The number of interleaved channels.
     * @param samplesPerSec The sample rate in samples per second.
     * @param buffer The borrowed PCM buffer.
     * @param renderTimeMs The shared RTC render timestamp in milliseconds.
     * @param avsyncType The native synchronization mode.
     * @param rtpTimestamp The transport timestamp.
     * @param presentationMs The native presentation metadata.
     * @return True to preserve native audio processing.
     */
    override fun onPlaybackAudioFrameBeforeMixing(
        channelId: String,
        uid: Int,
        type: Int,
        samplesPerChannel: Int,
        bytesPerSample: Int,
        channels: Int,
        samplesPerSec: Int,
        buffer: ByteBuffer,
        renderTimeMs: Long,
        avsyncType: Int,
        rtpTimestamp: Int,
        presentationMs: Long,
    ): Boolean {
        val listener = listenerProvider(
            channelId,
            uid,
        ) ?: return true
        val count = samplesPerChannel.toLong() * channels * bytesPerSample
        val valid = type == 0 && bytesPerSample == 2 && channels in 1..2 &&
            samplesPerSec in 8_000..192_000 && samplesPerChannel in 1..192_000 &&
            count > 0 && count <= buffer.remaining()
        val data = if (valid) ByteArray(count.toInt()).also { buffer.duplicate().get(it) } else byteArrayOf()
        listener.onFrame(
            RtcRemoteAudioFrame(
                data,
                samplesPerSec,
                channels,
                samplesPerChannel,
                renderTimeMs,
            ),
        )
        return true
    }

    /**
     * Selects unmixed remote audio without observing microphone or mixed playback.
     *
     * @return The before-mixing observation flag.
     */
    override fun getObservedAudioFramePosition(): Int = 0x0008

    /**
     * Provides the unused observer position's default PCM format.
     *
     * @return The 48 kHz mono read-only format with 480 samples per call.
     */
    override fun getRecordAudioParams(): AudioParams = AudioParams(
        48_000,
        1,
        0,
        480,
    )

    /**
     * Provides the unused observer position's default PCM format.
     *
     * @return The 48 kHz mono read-only format with 480 samples per call.
     */
    override fun getPlaybackAudioParams(): AudioParams = AudioParams(
        48_000,
        1,
        0,
        480,
    )

    /**
     * Provides the unused observer position's default PCM format.
     *
     * @return The 48 kHz mono read-only format with 480 samples per call.
     */
    override fun getMixedAudioParams(): AudioParams = AudioParams(
        48_000,
        1,
        0,
        480,
    )

    /**
     * Provides the unused observer position's default PCM format.
     *
     * @return The 48 kHz mono read-only format with 480 samples per call.
     */
    override fun getEarMonitoringAudioParams(): AudioParams = AudioParams(
        48_000,
        1,
        0,
        480,
    )
}
