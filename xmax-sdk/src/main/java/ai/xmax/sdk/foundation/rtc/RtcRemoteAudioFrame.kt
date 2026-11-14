//
//  RtcRemoteAudioFrame.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

/**
 * Owned PCM from one remote publisher on the RTC render clock.
 *
 * @property data The interleaved PCM16 little-endian samples; empty data invalidates continuity.
 * @property sampleRate The samples per second per channel.
 * @property channels The number of interleaved channels.
 * @property samplesPerChannel The number of samples per channel in this packet.
 * @property renderTimeMs The RTC render timestamp, in milliseconds.
 */
internal data class RtcRemoteAudioFrame(
    val data: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val samplesPerChannel: Int,
    val renderTimeMs: Long,
)

/**
 * Receives copied PCM without retaining a borrowed RTC buffer.
 */
internal fun interface RtcRemoteAudioFrameListener {

    /**
     * Receives a remote publisher's PCM packet.
     *
     * @param frame The owned audio data and native timing metadata.
     */
    fun onFrame(
        frame: RtcRemoteAudioFrame,
    )
}
