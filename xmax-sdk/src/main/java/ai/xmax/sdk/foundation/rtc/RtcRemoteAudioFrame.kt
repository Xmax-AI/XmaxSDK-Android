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
 */
internal data class RtcRemoteAudioFrame(
    /**
     * The interleaved PCM16 little-endian samples; empty data invalidates continuity.
     */
    val data: ByteArray,

    /**
     * The samples per second per channel.
     */
    val sampleRate: Int,

    /**
     * The number of interleaved channels.
     */
    val channels: Int,

    /**
     * The number of samples per channel in this packet.
     */
    val samplesPerChannel: Int,

    /**
     * The RTC render timestamp, in milliseconds.
     */
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
