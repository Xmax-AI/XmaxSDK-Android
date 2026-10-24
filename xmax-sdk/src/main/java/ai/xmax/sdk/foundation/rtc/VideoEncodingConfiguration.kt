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
 * Encoding settings for the primary RTC video stream.
 */
internal data class VideoEncodingConfiguration(
    /**
     * The frame width, in pixels.
     */
    val width: Int,

    /**
     * The frame height, in pixels.
     */
    val height: Int,

    /**
     * The video frame rate, in fps.
     */
    val frameRate: Int,

    /**
     * The minimum encoder bitrate, in kbps; defaults to 0.
     */
    val minimumBitrate: Int = 0,

    /**
     * The maximum encoder bitrate, in kbps.
     */
    val maximumBitrate: Int = -1,

    /**
     * The frame-rate or image-quality preference under bandwidth limits; defaults to
     * `EncoderPreference.MAINTAIN_FRAMERATE`.
     */
    val encoderPreference: EncoderPreference = EncoderPreference.MAINTAIN_FRAMERATE,
) {

    /**
     * The uplink encoder's preference under limited bandwidth.
     */
    enum class EncoderPreference {

        /**
         * Allows RTC to balance frame rate and image quality automatically.
         */
        AUTO,

        /**
         * Prioritizes frame rate under bandwidth limits.
         */
        MAINTAIN_FRAMERATE,

        /**
         * Prioritizes per-frame quality under bandwidth limits.
         */
        MAINTAIN_QUALITY,
    }
}
