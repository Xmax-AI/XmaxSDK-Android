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
 * The encoding settings for the RTC main video stream.
 *
 * @property width The frame width, in pixels.
 * @property height The frame height, in pixels.
 * @property frameRate The video frame rate, in fps.
 * @property minimumBitrate The minimum encoder bitrate, in kbps; defaults to 0.
 * @property maximumBitrate The maximum encoder bitrate, in kbps.
 * @property encoderPreference The frame-rate or image-quality preference under bandwidth limits; defaults to
 * `EncoderPreference.MAINTAIN_FRAMERATE`.
 */
internal data class VideoEncodingConfiguration(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val minimumBitrate: Int = 0,
    val maximumBitrate: Int = -1,
    val encoderPreference: EncoderPreference = EncoderPreference.MAINTAIN_FRAMERATE,
) {

    /**
     * The preferred encoding fallback under network constraints.
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
