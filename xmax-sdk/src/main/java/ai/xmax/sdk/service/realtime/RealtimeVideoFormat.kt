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
 * 实时视频的编码策略偏好。
 */
public enum class RealtimeVideoEncoderPreference {

    /**
     * 平衡帧率和分辨率。
     */
    AUTO,

    /**
     * 优先保障帧率。
     */
    MAINTAIN_FRAMERATE,

    /**
     * 优先保障分辨率。
     */
    MAINTAIN_QUALITY,
}

/**
 * 实时视频的尺寸、帧率和上行编码配置。
 * 本地图片和视频流以此描述媒体输入基准；模型生成画布独立选择，传输档位不会覆盖输入基准。
 */
public data class RealtimeVideoFormat @JvmOverloads constructor(
    /**
     * 视频宽度，单位为像素，必须为正偶数。
     */
    public val width: Int,

    /**
     * 视频高度，单位为像素，必须为正偶数。
     */
    public val height: Int,

    /**
     * 视频帧率，单位为 fps，必须大于 0。
     */
    public val fps: Int,

    /**
     * 最低上行码率，单位为 kbps；null 使用 500 且不超过最高码率，0 表示不设最低码率。
     */
    public val minimumBitrate: Int? = null,

    /**
     * 最高上行码率，单位为 kbps；null 使用 SDK 默认值，指定时必须大于 0 且不低于最低码率。
     */
    public val maximumBitrate: Int? = null,

    /**
     * 上行编码策略偏好；默认流畅优先，优先保障帧率。
     */
    public val encoderPreference: RealtimeVideoEncoderPreference =
        RealtimeVideoEncoderPreference.MAINTAIN_FRAMERATE,
) {

    /**
     * 校验尺寸、帧率和显式指定的码率范围。
     *
     * @throws XmaxError 尺寸、帧率或码率范围不满足约束时抛出配置错误。
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
