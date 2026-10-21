//
//  VideoEncodingConfiguration.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

/**
 * RTC 主视频流编码参数。
 */
internal data class VideoEncodingConfiguration(
    /**
     * 画面宽度，单位为像素。
     */
    val width: Int,

    /**
     * 画面高度，单位为像素。
     */
    val height: Int,

    /**
     * 视频帧率，单位为 fps。
     */
    val frameRate: Int,

    /**
     * 最低编码码率，单位为 kbps；默认值为 `0`。
     */
    val minimumBitrate: Int = 0,

    /**
     * 最高编码码率，单位为 kbps。
     */
    val maximumBitrate: Int = -1,

    /**
     * 带宽不足时优先保帧率或保画质的编码策略；默认值为 `EncoderPreference.MAINTAIN_FRAMERATE`。
     */
    val encoderPreference: EncoderPreference = EncoderPreference.MAINTAIN_FRAMERATE,
) {

    /**
     * 网络受限时上行编码保帧率或保画质的偏好。
     */
    enum class EncoderPreference {

        /**
         * 由 RTC 自动选择编码质量与帧率的取舍。
         */
        AUTO,

        /**
         * 带宽受限时优先维持视频帧率。
         */
        MAINTAIN_FRAMERATE,

        /**
         * 带宽受限时优先维持单帧画质。
         */
        MAINTAIN_QUALITY,
    }
}
