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
 * RTC 外部音频链路使用的 48 kHz 单声道 PCM 音频帧。
 *
 * 构造时复制 PCM 数据，后续修改输入数组不会影响该帧。
 *
 * @param data 48 kHz 单声道、16 位 PCM 音频数据。
 */
internal class AudioFrame(
    data: ByteArray,

    /**
     * 帧的单调时间戳，单位为微秒。
     */
    val timestampUs: Long,
) {

    /**
     * 由当前帧持有的 PCM 数据。
     */
    private val bytes: ByteArray = data.copyOf()

    /**
     * PCM 数据的副本，修改副本不会影响原始帧。
     */
    val data: ByteArray
        get() = bytes.copyOf()

    /**
     * 读取内部 PCM 缓冲区，调用方不得修改返回数组。
     *
     * @return 当前帧持有的 PCM 数据。
     */
    internal fun dataBytes(): ByteArray = bytes

    /**
     * 比较对象的值是否相等。
     *
     * @param other 用于比较或组合的另一个对象。
     * @return 对象的内容相等时为 true，否则为 false。
     */
    override fun equals(
        other: Any?
    ): Boolean =
        this === other ||
            other is AudioFrame &&
            timestampUs == other.timestampUs &&
            bytes.contentEquals(other.bytes)

    /**
     * 生成与值相等规则一致的哈希值。
     *
     * @return 与值相等规则一致的哈希值。
     */
    override fun hashCode(): Int = 31 * bytes.contentHashCode() + timestampUs.hashCode()

    /**
     * RTC 外部音频输入使用的固定 PCM 规格。
     */
    internal companion object {

        /**
         * PCM 采样率，单位为 Hz。
         */
        const val sampleRate: Int = 48_000

        /**
         * PCM 输出的声道数。
         */
        const val channelCount: Int = 1

        /**
         * 每个音频帧包含的采样点数。
         */
        const val samplesPerFrame: Int = 480
    }
}
