//
//  Yuv420VideoFrameConverter.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.VideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoFramePlane
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

/**
 * Converts flexible Android YUV420 decoder output to compact I420 for RTC.
 */
internal object Yuv420VideoFrameConverter {

    /**
     * Converts a decoded image to I420 with the requested dimensions and rotation.
     *
     * @param image The Android decoder image.
     * @param outputWidth The output width, in pixels.
     * @param outputHeight The output height, in pixels.
     * @param rotation The rotation applied to the source image.
     * @param timestampUs The monotonic frame timestamp, in microseconds.
     * @return A frame with the requested pixel format, dimensions, and orientation.
     */
    fun convert(
        image: Image,
        outputWidth: Int,
        outputHeight: Int,
        rotation: VideoRotation,
        timestampUs: Long,
    ): VideoFrame {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < PLANE_COUNT) {
            throw mediaError("The video decoder output is not YUV 4:2:0")
        }
        val crop = image.cropRect
        return convert(
            source = Source(
                width = image.width,
                height = image.height,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropWidth = crop.width(),
                cropHeight = crop.height(),
                planes = image.planes.take(PLANE_COUNT).map { plane ->
                    Plane(
                        buffer = plane.buffer.duplicate(),
                        rowStride = plane.rowStride,
                        pixelStride = plane.pixelStride,
                        baseOffset = plane.buffer.position(),
                    )
                },
            ),
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            rotation = rotation,
            timestampUs = timestampUs,
        )
    }

    /**
     * Converts a decoded image to I420 with the requested dimensions and rotation.
     *
     * @param source The source dimensions, crop region, and pixel planes.
     * @param outputWidth The output width, in pixels.
     * @param outputHeight The output height, in pixels.
     * @param rotation The rotation applied to the source image.
     * @param timestampUs The monotonic frame timestamp, in microseconds.
     * @return A frame with the requested pixel format, dimensions, and orientation.
     */
    internal fun convert(
        source: Source,
        outputWidth: Int,
        outputHeight: Int,
        rotation: VideoRotation,
        timestampUs: Long,
    ): VideoFrame {
        validate(
            source,
            outputWidth,
            outputHeight,
        )
        val y = ByteArray(outputWidth * outputHeight)
        val chromaWidth = outputWidth / CHROMA_SUBSAMPLING
        val chromaHeight = outputHeight / CHROMA_SUBSAMPLING
        val u = ByteArray(chromaWidth * chromaHeight)
        val v = ByteArray(chromaWidth * chromaHeight)

        val convertedNatively = NativeYuv420Converter.convert(
            source = source,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            rotation = rotation,
            y = y,
            u = u,
            v = v,
        )
        if (!convertedNatively) {
            val mapping = SourceMapping(
                source,
                outputWidth,
                outputHeight,
                rotation,
            )
            copyLuma(
                source.planes[Y_PLANE],
                mapping,
                y,
            )
            copyChroma(
                uPlane = source.planes[U_PLANE],
                vPlane = source.planes[V_PLANE],
                mapping = mapping,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                u = u,
                v = v,
            )
        }

        return VideoFrame(
            format = VideoFormat(
                outputWidth,
                outputHeight,
                VideoPixelFormat.I420,
            ),
            timestampUs = timestampUs,
            planes = listOf(
                VideoFramePlane(
                    y,
                    stride = outputWidth,
                    copyData = false,
                ),
                VideoFramePlane(
                    u,
                    stride = chromaWidth,
                    copyData = false,
                ),
                VideoFramePlane(
                    v,
                    stride = chromaWidth,
                    copyData = false,
                ),
            ),
        )
    }

    /**
     * Converts a frame to a local preview Bitmap without changing uplink pixels.
     *
     * @param frame The video frame to process, display, or submit.
     * @return An image for local display.
     */
    fun makePreviewBitmap(
        frame: VideoFrame,
    ): Bitmap {
        if (frame.format.pixelFormat != VideoPixelFormat.I420 || frame.planes.size != PLANE_COUNT) {
            throw IllegalArgumentException("Decoded video preview requires an I420 frame")
        }
        val width = frame.format.width
        val height = frame.format.height
        val yPlane = frame.planes[Y_PLANE]
        val uPlane = frame.planes[U_PLANE]
        val vPlane = frame.planes[V_PLANE]
        val y = yPlane.selectedBytesView()
        val u = uPlane.selectedBytesView()
        val v = vPlane.selectedBytesView()
        val pixels = IntArray(width * height)

        if (
            NativeYuv420Converter.convertI420ToArgb(
                y = y,
                yStride = yPlane.stride,
                u = u,
                uStride = uPlane.stride,
                v = v,
                vStride = vPlane.stride,
                width = width,
                height = height,
                pixels = pixels,
            )
        ) {
            return Bitmap.createBitmap(
                pixels,
                width,
                height,
                Bitmap.Config.ARGB_8888,
            )
        }

        var outputOffset = 0
        for (row in 0 until height) {
            val yRow = row * yPlane.stride
            val chromaRow = row / CHROMA_SUBSAMPLING
            for (column in 0 until width) {
                val luma = (y[yRow + column].toInt() and 0xFF) - LUMA_OFFSET
                val chromaOffset = chromaRow * uPlane.stride + column / CHROMA_SUBSAMPLING
                val chromaU = (u[chromaOffset].toInt() and 0xFF) - CHROMA_OFFSET
                val chromaV = (v[chromaOffset].toInt() and 0xFF) - CHROMA_OFFSET
                val normalizedLuma = luma.coerceAtLeast(0)
                val red = clampColor(
                    (YUV_LUMA_MULTIPLIER * normalizedLuma + YUV_RED_V * chromaV + ROUNDING) shr 8,
                )
                val green = clampColor(
                    (YUV_LUMA_MULTIPLIER * normalizedLuma - YUV_GREEN_U * chromaU -
                        YUV_GREEN_V * chromaV + ROUNDING) shr 8,
                )
                val blue = clampColor(
                    (YUV_LUMA_MULTIPLIER * normalizedLuma + YUV_BLUE_U * chromaU + ROUNDING) shr 8,
                )
                pixels[outputOffset] = OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
                outputOffset += 1
            }
        }
        return Bitmap.createBitmap(
            pixels,
            width,
            height,
            Bitmap.Config.ARGB_8888,
        )
    }

    /**
     * Copies the Y luma plane using output-to-source coordinates.
     *
     * @param plane The pixel plane to read.
     * @param mapping The output-to-source coordinate mapping.
     * @param output The output byte array.
     */
    private fun copyLuma(
        plane: Plane,
        mapping: SourceMapping,
        output: ByteArray,
    ) {
        var outputOffset = 0
        for (targetY in mapping.targetYs.indices) {
            val displayY = mapping.targetYs[targetY]
            for (targetX in mapping.targetXs.indices) {
                val displayX = mapping.targetXs[targetX]
                val sourceX = mapping.sourceX(
                    displayX,
                    displayY,
                )
                val sourceY = mapping.sourceY(
                    displayX,
                    displayY,
                )
                output[outputOffset] = plane.sample(
                    sourceX,
                    sourceY,
                )
                outputOffset += 1
            }
        }
    }

    /**
     * Copies U and V chroma using output-to-source coordinates.
     *
     * @param uPlane The source U chroma plane.
     * @param vPlane The source V chroma plane.
     * @param mapping The output-to-source coordinate mapping.
     * @param outputWidth The output width, in pixels.
     * @param outputHeight The output height, in pixels.
     * @param u The U pixel plane.
     * @param v The V pixel plane.
     */
    private fun copyChroma(
        uPlane: Plane,
        vPlane: Plane,
        mapping: SourceMapping,
        outputWidth: Int,
        outputHeight: Int,
        u: ByteArray,
        v: ByteArray,
    ) {
        var outputOffset = 0
        for (targetY in 0 until outputHeight / CHROMA_SUBSAMPLING) {
            val lumaY = (targetY * CHROMA_SUBSAMPLING + 1).coerceAtMost(outputHeight - 1)
            val displayY = mapping.targetYs[lumaY]
            for (targetX in 0 until outputWidth / CHROMA_SUBSAMPLING) {
                val lumaX = (targetX * CHROMA_SUBSAMPLING + 1).coerceAtMost(outputWidth - 1)
                val displayX = mapping.targetXs[lumaX]
                val sourceX = mapping.sourceX(
                    displayX,
                    displayY,
                ) / CHROMA_SUBSAMPLING
                val sourceY = mapping.sourceY(
                    displayX,
                    displayY,
                ) / CHROMA_SUBSAMPLING
                u[outputOffset] = uPlane.sample(
                    sourceX,
                    sourceY,
                )
                v[outputOffset] = vPlane.sample(
                    sourceX,
                    sourceY,
                )
                outputOffset += 1
            }
        }
    }

    /**
     * Validates planes, crop bounds, and output dimensions for YUV420 conversion.
     *
     * @param source The source dimensions, crop region, and pixel planes.
     * @param outputWidth The output width, in pixels.
     * @param outputHeight The output height, in pixels.
     */
    private fun validate(
        source: Source,
        outputWidth: Int,
        outputHeight: Int,
    ) {
        if (source.width <= 0 || source.height <= 0 ||
            source.cropWidth <= 0 || source.cropHeight <= 0 ||
            source.cropLeft < 0 || source.cropTop < 0 ||
            source.cropLeft + source.cropWidth > source.width ||
            source.cropTop + source.cropHeight > source.height ||
            source.planes.size != PLANE_COUNT ||
            outputWidth <= 0 || outputHeight <= 0 ||
            outputWidth % CHROMA_SUBSAMPLING != 0 ||
            outputHeight % CHROMA_SUBSAMPLING != 0
        ) {
            throw mediaError("The decoded video frame dimensions are invalid")
        }
    }

    /**
     * Clamps a color channel to 0 through 255.
     *
     * @param value The color channel value to clamp.
     * @return A color channel value from 0 to 255.
     */
    private fun clampColor(
        value: Int,
    ): Int = value.coerceIn(
        0,
        255,
    )

    /**
     * A YUV420 source image with dimensions, crop region, and pixel planes.
     */
    internal data class Source(
        /**
         * The frame width, in pixels.
         */
        val width: Int,

        /**
         * The frame height, in pixels.
         */
        val height: Int,

        /**
         * The left crop edge, in pixels.
         */
        val cropLeft: Int,

        /**
         * The top crop edge, in pixels.
         */
        val cropTop: Int,

        /**
         * The crop width, in pixels.
         */
        val cropWidth: Int,

        /**
         * The crop height, in pixels.
         */
        val cropHeight: Int,

        /**
         * The pixel planes in format order.
         */
        val planes: List<Plane>,
    )

    /**
     * A pixel plane buffer with row and pixel strides.
     */
    internal data class Plane(
        /**
         * The buffer containing data to process.
         */
        val buffer: ByteBuffer,

        /**
         * The row stride, in bytes.
         */
        val rowStride: Int,

        /**
         * The pixel stride within each row, in bytes.
         */
        val pixelStride: Int,

        /**
         * The valid data's starting byte offset.
         */
        val baseOffset: Int = buffer.position(),
    ) {

        init {
            require(rowStride > 0 && pixelStride > 0 && baseOffset >= 0)
        }

        /**
         * Reads a plane sample at the requested coordinates.
         *
         * @param x The horizontal image coordinate, in pixels.
         * @param y The vertical image coordinate, in pixels.
         * @return The pixel sample at the requested position.
         */
        fun sample(
            x: Int,
            y: Int,
        ): Byte = buffer.get(
            baseOffset + y * rowStride + x * pixelStride,
        )
    }

    /**
     * Maps rotated and scaled output pixels to source image coordinates.
     *
     * @param outputWidth The output width, in pixels.
     * @param outputHeight The output height, in pixels.
     */
    private class SourceMapping(
        /**
         * The source dimensions, crop region, and pixel planes.
         */
        private val source: Source,

        outputWidth: Int,
        outputHeight: Int,

        /**
         * The rotation applied to the source image.
         */
        private val rotation: VideoRotation,
    ) {

        /**
         * The source display dimensions after rotation.
         */
        private val displayWidth: Int

        /**
         * The source height after rotation, in pixels.
         */
        private val displayHeight: Int

        /**
         * The source coordinates corresponding to output pixel columns.
         */
        val targetXs: IntArray

        /**
         * The source coordinates corresponding to output pixel rows.
         */
        val targetYs: IntArray

        init {
            val swapsDimensions = rotation == VideoRotation.ROTATION_90 ||
                rotation == VideoRotation.ROTATION_270
            displayWidth = if (swapsDimensions) source.cropHeight else source.cropWidth
            displayHeight = if (swapsDimensions) source.cropWidth else source.cropHeight
            val scale = maxOf(
                outputWidth.toDouble() / displayWidth,
                outputHeight.toDouble() / displayHeight,
            )
            val visibleWidth = outputWidth / scale
            val visibleHeight = outputHeight / scale
            val displayLeft = (displayWidth - visibleWidth) / 2.0
            val displayTop = (displayHeight - visibleHeight) / 2.0
            targetXs = IntArray(outputWidth) { targetX ->
                (displayLeft + (targetX + 0.5) / scale)
                    .toInt().coerceIn(
                        0,
                        displayWidth - 1,
                    )
            }
            targetYs = IntArray(outputHeight) { targetY ->
                (displayTop + (targetY + 0.5) / scale)
                    .toInt().coerceIn(
                        0,
                        displayHeight - 1,
                    )
            }
        }

        /**
         * Maps output coordinates to a source horizontal coordinate.
         *
         * @param displayX The horizontal output coordinate, in pixels.
         * @param displayY The vertical output coordinate, in pixels.
         * @return The mapped source horizontal coordinate, in pixels.
         */
        fun sourceX(
            displayX: Int,
            displayY: Int,
        ): Int = source.cropLeft + when (rotation) {
            VideoRotation.ROTATION_0 -> displayX
            VideoRotation.ROTATION_90 -> displayY
            VideoRotation.ROTATION_180 -> source.cropWidth - 1 - displayX
            VideoRotation.ROTATION_270 -> source.cropWidth - 1 - displayY
        }

        /**
         * Maps output coordinates to a source vertical coordinate.
         *
         * @param displayX The horizontal output coordinate, in pixels.
         * @param displayY The vertical output coordinate, in pixels.
         * @return The mapped source vertical coordinate, in pixels.
         */
        fun sourceY(
            displayX: Int,
            displayY: Int,
        ): Int = source.cropTop + when (rotation) {
            VideoRotation.ROTATION_0 -> displayY
            VideoRotation.ROTATION_90 -> source.cropHeight - 1 - displayX
            VideoRotation.ROTATION_180 -> source.cropHeight - 1 - displayY
            VideoRotation.ROTATION_270 -> displayX
        }
    }

    /**
     * Creates a media error with the processing failure description.
     *
     * @param message The message to send or log.
     */
    private fun mediaError(
        message: String,
    ): XmaxError = XmaxError(
        code = XmaxErrorCode.MEDIA_ERROR,
        message = message,
    )

    /**
     * Pixel format constants and color conversion coefficients.
     */
    private const val PLANE_COUNT = 3

    /**
     * The source luma plane index.
     */
    private const val Y_PLANE = 0

    /**
     * The source U chroma plane index.
     */
    private const val U_PLANE = 1

    /**
     * The source V chroma plane index.
     */
    private const val V_PLANE = 2

    /**
     * The chroma subsampling interval relative to YUV420 luma.
     */
    private const val CHROMA_SUBSAMPLING = 2

    /**
     * The limited-range YUV luma offset.
     */
    private const val LUMA_OFFSET = 16

    /**
     * The offset converting unsigned chroma to signed values.
     */
    private const val CHROMA_OFFSET = 128

    /**
     * The fixed-point luma-to-RGB multiplier.
     */
    private const val YUV_LUMA_MULTIPLIER = 298

    /**
     * The fixed-point V contribution to red.
     */
    private const val YUV_RED_V = 409

    /**
     * The fixed-point U contribution to green.
     */
    private const val YUV_GREEN_U = 100

    /**
     * The fixed-point V contribution to green.
     */
    private const val YUV_GREEN_V = 208

    /**
     * The fixed-point U contribution to blue.
     */
    private const val YUV_BLUE_U = 516

    /**
     * The fixed-point color rounding offset.
     */
    private const val ROUNDING = 128

    /**
     * The alpha mask for opaque ARGB pixels.
     */
    private const val OPAQUE_ALPHA = -0x1000000
}
