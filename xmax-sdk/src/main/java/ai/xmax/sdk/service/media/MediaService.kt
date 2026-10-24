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
 * Resolves local media, model canvases, and downlink dimensions.
 */
internal class MediaService(
    /**
     * The model defining input constraints; defaults to `RealtimeModel.X2_1`.
     */
    override val model: RealtimeModel = RealtimeModel.X2_1,
) : MediaServicing {

    /**
     * Selects the closest model canvas aspect ratio for an aspect-preserving media stream.
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
     * Validates camera resolution buckets and resolves area-limited, four-pixel-aligned media without
     * enlargement.
     *
     * @param size The media frame dimensions, in pixels.
     * @param source The source kind; only cameras must exactly match a fixed bucket.
     * @return Media dimensions preserving the exact aspect ratio where possible.
     * @throws XmaxError If dimensions are invalid, a camera misses its buckets, or aligned output is below
     * the model's minimum input area.
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
        val scale = minOf(
            1.0,
            sqrt(model.maximumInputPixels / pixels),
        )
        val resolvedSize = alignedSize(
            size,
            scale,
        )
        if (resolvedSize.width.toLong() * resolvedSize.height < model.minimumInputPixels) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Media resolution must contain at least ${model.minimumInputPixels} pixels",
            )
        }

        return resolvedSize
    }

    /**
     * Resolves four-pixel-aligned downlink dimensions within the bounds without enlargement.
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
     * Use integer multiples of the reduced aspect ratio for four-pixel alignment, falling back to independent
     * downward alignment when needed.
     *
     * @param size The original integer media dimensions.
     * @param scale The aspect-preserving scale, no greater than 1.
     * @return Four-pixel-aligned dimensions within the scaled bounds.
     */
    private fun alignedSize(
        size: IntSize,
        scale: Double,
    ): IntSize {
        var divisor = size.width
        var remainder = size.height
        while (remainder != 0) {
            val next = divisor % remainder
            divisor = remainder
            remainder = next
        }
        val alignedScale = floor(divisor * scale / 4).toInt() * 4
        if (alignedScale >= 4) {
            return IntSize(
                size.width / divisor * alignedScale,
                size.height / divisor * alignedScale,
            )
        }

        val width = floor(size.width * scale / 4).toInt() * 4
        val height = floor(size.height * scale / 4).toInt() * 4
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
        )
    }

    /**
     * Calculates dimensions within pixel-area and model alignment constraints.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @return The width and height satisfying the sizing constraints.
     */
    private fun boundedAlignedSize(
        width: Double,
        height: Double,
    ): IntSize {
        val alignment = model.inputSizeAlignment
        val unitPixels = alignment.toLong() * alignment
        val minimumUnits = (model.minimumInputPixels.toLong() + unitPixels - 1L) / unitPixels
        val maximumUnits = model.maximumInputPixels.toLong() / unitPixels
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
