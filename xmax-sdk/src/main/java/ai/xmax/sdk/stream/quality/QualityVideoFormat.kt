//
//  QualityVideoFormat.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.stream.encoding.EncodingBitratePolicy
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Resolves quality formats from the original baseline, preserving orientation, explicit uplink bitrates, and
 * encoder preference.
 */
internal object QualityVideoFormat {

    /**
     * Resolves downlink dimension bounds without enlargement, retaining fractional pixels.
     *
     * @param baseline The fixed media baseline format.
     * @param level The target downlink level, from 1 to 5.
     * @return The downlink dimension bounds before pixel alignment.
     */
    fun resolveDownlinkMaximumSize(
        baseline: RealtimeVideoFormat,
        level: Int,
    ): Size {
        val scale = sizeScale(
            baseline,
            level,
        )
        return Size(
            (baseline.width * scale).toFloat(),
            (baseline.height * scale).toFloat(),
        )
    }

    /**
     * Resolves transmission dimensions with even alignment, preferring the exact media aspect ratio.
     * The downlink's 24 fps is used only for bitrate estimation, not to set server frame rate.
     *
     * @param baseline The original input format used to calculate quality tiers.
     * @param level The quality level to apply or retain; 1 is highest.
     * @param downlink True for downlink, false for uplink.
     * @param minimumInputPixels The minimum uplink pixel area; ignored for downlink and never enlarges media.
     * @return The resolved video dimensions, frame rate, and encoding settings.
     */
    fun resolve(
        baseline: RealtimeVideoFormat,
        level: Int,
        downlink: Boolean,
        minimumInputPixels: Int = 0,
    ): RealtimeVideoFormat {
        val scale = sizeScale(
            baseline,
            level,
        )

        val size = scaledSize(
            IntSize(
                baseline.width,
                baseline.height,
            ),
            scale,
            minimumPixels = if (downlink) 0 else minimumInputPixels,
        )
        return baseline.copy(
            width = size.width,
            height = size.height,
            fps = if (downlink) 24 else min(
                baseline.fps,
                QualityTiers.frameRates[level - 1],
            ),
            minimumBitrate = if (downlink) null else baseline.minimumBitrate,
            maximumBitrate = if (downlink) null else baseline.maximumBitrate,
        )
    }

    /**
     * Downscales media with even alignment and a minimum pixel area, preferring the exact aspect ratio.
     *
     * @param size The original integer pixel dimensions.
     * @param scale The requested dimension scale, no greater than one.
     * @param minimumPixels The minimum output area, capped at the source area to prevent enlargement.
     * @return Even dimensions satisfying the minimum area without enlarging the source.
     */
    fun scaledSize(
        size: IntSize,
        scale: Double,
        minimumPixels: Int = 0,
    ): IntSize {
        var divisor = size.width
        var remainder = size.height
        while (remainder != 0) {
            val next = divisor % remainder
            divisor = remainder
            remainder = next
        }
        val pixels = size.width.toDouble() * size.height
        val minimumScale = sqrt(min(minimumPixels.coerceAtLeast(0).toDouble(), pixels) / pixels)
        val minimumUnits = ceil(divisor * minimumScale / 2).toInt() * 2
        val units = floor(divisor * scale + 0.0000001).toInt() / 2 * 2
        if (units >= 2) {
            val resolvedUnits = max(
                units,
                minimumUnits,
            )
            return IntSize(
                size.width / divisor * resolvedUnits,
                size.height / divisor * resolvedUnits,
            )
        }

        return IntSize(
            maxOf(
                2,
                floor(size.width * scale / 2).toInt() * 2,
                ceil(size.width * minimumScale / 2).toInt() * 2,
            ),
            maxOf(
                2,
                floor(size.height * scale / 2).toInt() * 2,
                ceil(size.height * minimumScale / 2).toInt() * 2,
            ),
        )
    }

    /**
     * Resolves a quality scale without enlarging media or falling below its minimum short side.
     *
     * @param baseline The fixed media baseline format.
     * @param level The target level, from 1 to 5.
     * @return An aspect-preserving scale no greater than 1.
     */
    private fun sizeScale(
        baseline: RealtimeVideoFormat,
        level: Int,
    ): Double {
        require(level in 1..QualityTiers.percentages.size)
        baseline.validate()
        val shortSide = min(
            baseline.width,
            baseline.height,
        )
        val minimum = min(
            shortSide,
            QualityTiers.MINIMUM_SHORT_SIDE,
        )
        return max(
            QualityTiers.percentages[level - 1] / 100.0,
            minimum.toDouble() / shortSide,
        )
    }

    /**
     * Merges levels with identical dimensions and frame rate, preferring the level closest to L1.
     *
     * @param baseline The original input format used to calculate quality tiers.
     * @param level The quality level to apply or retain; 1 is highest.
     * @param downlink True for downlink, false for uplink.
     * @param minimumInputPixels The minimum uplink pixel area; ignored for downlink.
     * @return The highest-quality level producing the same format.
     */
    fun canonicalLevel(
        baseline: RealtimeVideoFormat,
        level: Int,
        downlink: Boolean,
        minimumInputPixels: Int = 0,
    ): Int {
        val format = resolve(
            baseline,
            level,
            downlink,
            minimumInputPixels,
        )
        return (1..level).first { resolve(
            baseline,
            it,
            downlink,
            minimumInputPixels,
        ) == format }
    }

    /**
     * Checks whether default and explicit uplink bitrate bounds are compatible.
     *
     * @param format Video dimensions, frame rate, and uplink encoding options.
     * @return True if the format satisfies encoder constraints.
     */
    fun supportsEncoding(
        format: RealtimeVideoFormat,
    ): Boolean {
        val (_, defaultMaximum) = EncodingBitratePolicy.resolve(
            format.width,
            format.height,
            format.fps,
        )
        val maximum = format.maximumBitrate ?: defaultMaximum
        return (format.minimumBitrate ?: min(
            500,
            maximum,
        )) <= maximum
    }

    /**
     * Chooses an initial level at least as high as history while satisfying explicit bitrate constraints.
     *
     * @param baseline The original input format used to calculate quality tiers.
     * @param requested The requested quality level.
     * @param downlink True for downlink, false for uplink.
     * @param minimumInputPixels The minimum uplink pixel area; ignored for downlink.
     * @return An initial level satisfying encoder constraints.
     */
    fun initialLevel(
        baseline: RealtimeVideoFormat,
        requested: Int,
        downlink: Boolean,
        minimumInputPixels: Int = 0,
    ): Int {
        val level = if (downlink) requested else {
            (requested downTo 1).firstOrNull { supportsEncoding(
                resolve(
                    baseline,
                    it,
                    false,
                    minimumInputPixels,
                ),
            ) } ?: 1
        }
        return canonicalLevel(
            baseline,
            level,
            downlink,
            minimumInputPixels,
        )
    }
}
