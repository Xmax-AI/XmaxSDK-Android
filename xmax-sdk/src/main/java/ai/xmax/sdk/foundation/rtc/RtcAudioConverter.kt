//
//  RtcAudioConverter.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode

/**
 * Validates 48 kHz mono PCM16LE for Agora external audio.
 */
internal object RtcAudioConverter {

    /**
     * Validates a 10 ms, 48 kHz mono PCM16LE frame and returns its valid bytes.
     *
     * @param frame The PCM audio frame to play or submit.
     * @return An array containing the valid bytes.
     */
    fun convertFrame(
        frame: AudioFrame,
    ): ByteArray {
        val bytes = frame.dataBytes()
        val expected = AudioFrame.samplesPerFrame * AudioFrame.channelCount * Short.SIZE_BYTES
        if (bytes.size != expected) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Audio frame must contain exactly $expected bytes",
            )
        }

        return bytes
    }
}
