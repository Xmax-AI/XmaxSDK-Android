//
//  EncodingController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.encoding

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoEncoderPreference
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.foundation.rtc.VideoEncodingConfiguration

/**
 * Applies RTC encoding settings for a realtime video format.
 */
internal class EncodingController(
    /**
     * The manager for RTC capture, publication, subscription, and rendering.
     */
    private val rtcManager: RtcManaging,
) : EncodingControlling {

    /**
     * Validates and applies a resolved encoder configuration.
     *
     * An unspecified minimum uses 500 kbps, capped by the maximum.
     *
     * @param videoFormat Four-pixel-aligned dimensions, frame rate, and uplink encoding options.
     * @throws XmaxError If dimensions, frame rate, or bitrate bounds are invalid.
     */
    override fun configure(
        videoFormat: RealtimeVideoFormat,
    ) {
        videoFormat.validate()
        if (videoFormat.width % 4 != 0 || videoFormat.height % 4 != 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Uplink video width and height must be multiples of 4",
            )
        }

        val minimum: Int
        val maximum: Int
        if (videoFormat.minimumBitrate != null && videoFormat.maximumBitrate != null) {
            minimum = videoFormat.minimumBitrate
            maximum = videoFormat.maximumBitrate
        } else {
            val (_, defaultMaximum) = EncodingBitratePolicy.resolve(
                videoFormat.width,
                videoFormat.height,
                videoFormat.fps,
            )
            maximum = videoFormat.maximumBitrate ?: defaultMaximum
            minimum = videoFormat.minimumBitrate ?: minOf(
                500,
                maximum,
            )
        }

        if (minimum > maximum) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Minimum bitrate must not exceed maximum bitrate after applying SDK defaults",
            )
        }

        rtcManager.configureVideoEncoding(
            VideoEncodingConfiguration(
                width = videoFormat.width,
                height = videoFormat.height,
                frameRate = videoFormat.fps,
                minimumBitrate = minimum,
                maximumBitrate = maximum,
                encoderPreference = resolveEncoderPreference(videoFormat.encoderPreference),
            ),
        )
    }

    /**
     * Converts the public encoding preference to the internal RTC preference.
     *
     * @param preference Whether encoding prioritizes frame rate or image quality.
     * @return The internal encoder preference.
     */
    private fun resolveEncoderPreference(
        preference: RealtimeVideoEncoderPreference,
    ): VideoEncodingConfiguration.EncoderPreference = when (preference) {
        RealtimeVideoEncoderPreference.AUTO ->
            VideoEncodingConfiguration.EncoderPreference.AUTO
        RealtimeVideoEncoderPreference.MAINTAIN_FRAMERATE ->
            VideoEncodingConfiguration.EncoderPreference.MAINTAIN_FRAMERATE
        RealtimeVideoEncoderPreference.MAINTAIN_QUALITY ->
            VideoEncodingConfiguration.EncoderPreference.MAINTAIN_QUALITY
    }
}
