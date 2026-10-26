//
//  MediaService.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.media

import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.RealtimeMediaSource
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Size
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.ln

/**
 * The sizing rules for local media, generation canvases, and returned media.
 */
internal class MediaService(
    /**
     * The model defining input constraints; defaults to `RealtimeModel.X2_1`.
     */
    override val model: RealtimeModel = RealtimeModel.X2_1,
) : MediaServicing {

    /**
     * Selects the closest generation canvas without changing local media dimensions.
     *
     * @param size The media display dimensions, in pixels.
     * @return The model canvas with the closest aspect ratio.
     */
    override fun resolveGenerationSize(
        size: IntSize,
    ): IntSize {
        if (size.width <= 0 || size.height <= 0) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Media input dimensions must be positive",
            )
        }

        val aspect = size.width.toDouble() / size.height
        return model.supportedVideoFormats.map { IntSize(
            it.width,
            it.height,
        ) }.minByOrNull { bucket ->
            abs(ln(aspect / (bucket.width.toDouble() / bucket.height)))
        } ?: resolveGenerationCanvas(size)
    }

    /**
     * Resolves local media dimensions and validates camera resolution buckets.
     *
     * Media is upscaled or downscaled into the model's area limits with four-pixel alignment.
     *
     * @param size The media frame dimensions, in pixels.
     * @param source The source kind; only cameras must exactly match a fixed bucket.
     * @return Media dimensions within the area limits, preserving exact aspect ratio where possible.
     * @throws XmaxError If dimensions are invalid or a camera misses its configured buckets.
     */
    override fun resolveMediaSize(
        size: IntSize,
        source: RealtimeMediaSource,
    ): IntSize {
        if (size.width <= 0 || size.height <= 0) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Media dimensions must be positive",
            )
        }

        val buckets = model.supportedVideoFormats.map { IntSize(
            it.width,
            it.height,
        ) }
        if (source == RealtimeMediaSource.CAMERA && buckets.isNotEmpty()) {
            if (size !in buckets) {
                val supportedSizes = buckets.joinToString { "${it.width}×${it.height}" }
                throw XmaxError(
                    code = XmaxErrorCode.INVALID_CONFIGURATION,
                    message = "Model ${model.id} does not support camera resolution " +
                        "${size.width}×${size.height}. Supported resolutions: $supportedSizes",
                )
            }
        }

        val pixels = size.width.toDouble() * size.height
        val pixelLimits = model.minimumInputPixels..model.maximumInputPixels
        val targetPixels = pixels.coerceIn(
            pixelLimits.first.toDouble(),
            pixelLimits.last.toDouble(),
        )

        return alignedSize(
            size,
            scale = sqrt(targetPixels / pixels),
            pixelLimits = pixelLimits,
        )
    }

    /**
     * Resolves returned dimensions within quality limits.
     *
     * Dimensions use four-pixel alignment without enlarging local media.
     *
     * @param sourceSize The fixed media baseline dimensions, in pixels.
     * @param maximumSize The downlink dimension bounds, which may include fractional pixels.
     * @return Downlink dimensions within the bounds, preserving exact aspect ratio where possible.
     */
    override fun resolveTargetSize(
        sourceSize: IntSize,
        maximumSize: Size,
    ): IntSize {
        if (sourceSize.width <= 0 || sourceSize.height <= 0 || !maximumSize.width.isFinite() || !maximumSize.height.isFinite() || maximumSize.width <= 0f || maximumSize.height <= 0f) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Source dimensions and target bounds must be positive and finite",
            )
        }

        val scale = minOf(
            1.0,
            maximumSize.width.toDouble() / sourceSize.width,
            maximumSize.height.toDouble() / sourceSize.height,
        )

        return alignedSize(
            sourceSize,
            scale,
        )
    }

    /**
     * Resolves four-pixel-aligned dimensions at a uniform scale, preferring exact aspect ratio.
     *
     * @param size The original integer media dimensions.
     * @param scale The requested uniform dimension scale.
     * @param pixelLimits Optional inclusive area bounds; null only rounds dimensions down.
     * @return Aligned dimensions within the area limits, or the scaled bounds when limits are absent.
     * @throws XmaxError If the scaled bounds cannot fit four by four pixels without area limits.
     */
    private fun alignedSize(
        size: IntSize,
        scale: Double,
        pixelLimits: IntRange? = null,
    ): IntSize {
        var divisor = size.width
        var remainder = size.height
        while (remainder != 0) {
            val next = divisor % remainder
            divisor = remainder
            remainder = next
        }

        val ratioWidth = size.width / divisor
        val ratioHeight = size.height / divisor
        var alignedScale = floor(divisor * scale / 4).toInt() * 4
        if (pixelLimits != null) {
            val ratioPixels = ratioWidth.toDouble() * ratioHeight
            val minimumScale = sqrt(pixelLimits.first / ratioPixels)
            val minimumAlignedScale = ceil(minimumScale / 4).toInt() * 4
            alignedScale = maxOf(
                alignedScale,
                minimumAlignedScale,
            )
        }

        val exactWidth = ratioWidth.toDouble() * alignedScale
        val exactHeight = ratioHeight.toDouble() * alignedScale
        val maximumPixels = pixelLimits?.last?.toDouble() ?: Double.POSITIVE_INFINITY
        if (alignedScale >= 4 && exactWidth * exactHeight <= maximumPixels) {
            return IntSize(
                exactWidth.toInt(),
                exactHeight.toInt(),
            )
        }

        val width = floor(size.width * scale / 4).toInt() * 4
        val height = floor(size.height * scale / 4).toInt() * 4
        val alignedPixels = width.toLong() * height
        if (pixelLimits != null && alignedPixels !in pixelLimits.first.toLong()..pixelLimits.last.toLong()) {
            return boundedAlignedSize(
                width = size.width * scale,
                height = size.height * scale,
                alignment = 4,
                pixelLimits = pixelLimits,
            )
        }

        if (width < 4 || height < 4) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "No aligned video size is available within the size limits",
            )
        }

        return IntSize(
            width,
            height,
        )
    }

    /**
     * Resolves a canvas within model area and alignment constraints when no fixed buckets exist.
     *
     * @param size The media input dimensions, in pixels.
     * @return Canvas dimensions within the model's pixel-area bounds.
     */
    private fun resolveGenerationCanvas(
        size: IntSize,
    ): IntSize {
        val width = size.width
        val height = size.height
        if (width <= 0 || height <= 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Image width and height must be greater than zero",
            )
        }

        val pixels = width.toLong() * height.toLong()
        val scale: Double
        val rounding: (Double) -> Double
        when {
            pixels < model.minimumInputPixels -> {
                scale = sqrt(model.minimumInputPixels.toDouble() / pixels)
                rounding = ::ceil
            }
            pixels > model.maximumInputPixels -> {
                scale = sqrt(model.maximumInputPixels.toDouble() / pixels)
                rounding = ::floor
            }
            else -> {
                scale = 1.0
                rounding = ::round
            }
        }

        val alignedWidth = alignedDimension(
            width,
            scale,
            rounding,
        )
        val alignedHeight = alignedDimension(
            height,
            scale,
            rounding,
        )
        val alignedPixels = alignedWidth.toLong() * alignedHeight.toLong()
        if (alignedPixels in model.minimumInputPixels.toLong()..model.maximumInputPixels.toLong()) {
            return IntSize(
                alignedWidth,
                alignedHeight,
            )
        }

        // Independent alignment can exceed area bounds near an edge; choose the valid dimensions closest to the target.
        return boundedAlignedSize(
            width = size.width * scale,
            height = size.height * scale,
            alignment = model.inputSizeAlignment,
            pixelLimits = model.minimumInputPixels..model.maximumInputPixels,
        )
    }

    /**
     * Selects the closest dimensions satisfying area and alignment limits.
     *
     * @param width The scaled target width, in pixels.
     * @param height The scaled target height, in pixels.
     * @param alignment The required multiple for each output dimension.
     * @param pixelLimits The inclusive output area bounds, in pixels.
     * @return Valid dimensions with the smallest relative width and height error.
     */
    private fun boundedAlignedSize(
        width: Double,
        height: Double,
        alignment: Int,
        pixelLimits: IntRange,
    ): IntSize {
        val unitPixels = alignment.toLong() * alignment
        val minimumUnits = (pixelLimits.first.toLong() + unitPixels - 1L) / unitPixels
        val maximumUnits = pixelLimits.last.toLong() / unitPixels
        var bestSize = IntSize.Zero
        var bestDistance = Double.POSITIVE_INFINITY

        for (widthUnits in 1L..maximumUnits) {
            val minimumHeight = (minimumUnits + widthUnits - 1L) / widthUnits
            val maximumHeight = maximumUnits / widthUnits
            if (minimumHeight > maximumHeight) continue

            val preferredHeight = round(height / alignment).toLong()
            val heightUnits = preferredHeight.coerceIn(
                minimumHeight,
                maximumHeight,
            )
            val candidateWidth = widthUnits * alignment
            val candidateHeight = heightUnits * alignment
            val widthDistance = (candidateWidth - width) / width
            val heightDistance = (candidateHeight - height) / height
            val distance = widthDistance * widthDistance + heightDistance * heightDistance
            if (distance < bestDistance) {
                bestDistance = distance
                bestSize = IntSize(
                    candidateWidth.toInt(),
                    candidateHeight.toInt(),
                )
            }
        }

        return bestSize
    }

    /**
     * Scales and rounds one dimension to model alignment.
     *
     * @param dimension The pixel dimension to scale.
     * @param scale The scale relative to the baseline.
     * @param rounding The rounding function for the scaled value.
     * @return The aligned pixel dimension.
     */
    private fun alignedDimension(
        dimension: Int,
        scale: Double,
        rounding: (Double) -> Double,
    ): Int = maxOf(
        rounding(dimension * scale / model.inputSizeAlignment).toInt() * model.inputSizeAlignment,
        model.inputSizeAlignment,
    )
}
