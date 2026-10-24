//
//  RealtimeVideoFormat.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * The encoding preference for realtime video.
 */
public enum class RealtimeVideoEncoderPreference {

    /**
     * Balances frame rate and resolution.
     */
    AUTO,

    /**
     * Prioritizes frame rate.
     */
    MAINTAIN_FRAMERATE,

    /**
     * Prioritizes resolution.
     */
    MAINTAIN_QUALITY,
}

/**
 * The dimensions, frame rate, and uplink encoding settings for realtime video.
 *
 * Local images and videos use this as their media baseline; the generation canvas is separate and
 * quality adaptation does not change the baseline.
 */
public data class RealtimeVideoFormat @JvmOverloads constructor(
    /**
     * The width in pixels; must be positive and even.
     */
    public val width: Int,

    /**
     * The height in pixels; must be positive and even.
     */
    public val height: Int,

    /**
     * The frame rate in fps; must be positive.
     */
    public val fps: Int,

    /**
     * The minimum uplink bitrate, in kbps; null uses 500 capped at the maximum, and 0 disables the minimum.
     */
    public val minimumBitrate: Int? = null,

    /**
     * The maximum uplink bitrate, in kbps; null uses the SDK default. Explicit values must be positive and at
     * least the minimum.
     */
    public val maximumBitrate: Int? = null,

    /**
     * The uplink encoding preference; defaults to prioritizing frame rate.
     */
    public val encoderPreference: RealtimeVideoEncoderPreference =
        RealtimeVideoEncoderPreference.MAINTAIN_FRAMERATE,
) {

    /**
     * Validates dimensions, frame rate, and explicit bitrate bounds.
     *
     * @throws XmaxError If dimensions, frame rate, or bitrate bounds are invalid.
     */
    public fun validate() {
        if (width <= 0 || height <= 0 || fps <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Realtime video width and height must be positive even numbers, " +
                    "and fps must be greater than zero",
            )
        }

        if (minimumBitrate != null && minimumBitrate < 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Minimum bitrate must not be negative",
            )
        }

        if (maximumBitrate != null && maximumBitrate <= 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Maximum bitrate must be greater than zero",
            )
        }

        if (minimumBitrate != null && maximumBitrate != null && minimumBitrate > maximumBitrate) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Minimum bitrate must not exceed maximum bitrate",
            )
        }
    }
}
