//
//  AudioFrame.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * A 48 kHz mono PCM frame for external RTC audio input.
 *
 * Construction copies PCM bytes so later changes to the input array cannot change this frame.
 *
 * @param data 48 kHz mono, 16-bit PCM audio bytes.
 */
internal class AudioFrame(
    data: ByteArray,

    /**
     * The monotonic frame timestamp, in microseconds.
     */
    val timestampUs: Long,
) {

    /**
     * The PCM bytes owned by this frame.
     */
    private val bytes: ByteArray = data.copyOf()

    /**
     * A copy of the PCM bytes; changing it does not modify the frame.
     */
    val data: ByteArray
        get() = bytes.copyOf()

    /**
     * Returns the internal PCM array for read-only use.
     *
     * @return The PCM bytes owned by this frame.
     */
    internal fun dataBytes(): ByteArray = bytes

    /**
     * Compares objects by value.
     *
     * @param other The object to compare or combine.
     */
    override fun equals(
        other: Any?,
    ): Boolean =
        this === other ||
            other is AudioFrame &&
            timestampUs == other.timestampUs &&
            bytes.contentEquals(other.bytes)

    /**
     * Computes a hash consistent with value equality.
     */
    override fun hashCode(): Int = 31 * bytes.contentHashCode() + timestampUs.hashCode()

    /**
     * The fixed PCM format used by RTC external audio input.
     */
    internal companion object {

        /**
         * The PCM sample rate, in Hz.
         */
        const val sampleRate: Int = 48_000

        /**
         * The number of PCM output channels.
         */
        const val channelCount: Int = 1

        /**
         * The sample count per audio frame.
         */
        const val samplesPerFrame: Int = 480
    }
}
