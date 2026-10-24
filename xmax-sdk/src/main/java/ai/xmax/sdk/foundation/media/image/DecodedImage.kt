//
//  DecodedImage.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.media.image

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoFramePlane
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.unit.IntSize
import java.util.UUID

/**
 * An orientation-corrected, decoded image.
 */
internal interface DecodedImage {

    /**
     * The dimensions and pixels of an image or video.
     */
    val size: IntSize

    /**
     * Converts a decoded image to an RGBA frame with the requested dimensions and frame rate.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @return A frame with the requested pixel format, dimensions, and orientation.
     */
    fun makeVideoFrame(
        videoFormat: RealtimeVideoFormat,
    ): VideoFrame
}

/**
 * An orientation-normalized Android Bitmap that supplies reusable RGBA frames.
 */
internal class BitmapDecodedImage(
    /**
     * The Android Bitmap to process or display.
     */
    private val bitmap: Bitmap,
) : DecodedImage {

    /**
     * The dimensions and pixels of an image or video.
     */
    override val size: IntSize = IntSize(
        bitmap.width,
        bitmap.height,
    )

    /**
     * Converts a decoded image to an RGBA frame with the requested dimensions and frame rate.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @return A frame with the requested pixel format, dimensions, and orientation.
     */
    override fun makeVideoFrame(
        videoFormat: RealtimeVideoFormat,
    ): VideoFrame {
        videoFormat.validate()
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            throw processingError("Decoded image is unavailable")
        }

        val target = Bitmap.createBitmap(
            videoFormat.width,
            videoFormat.height,
            Bitmap.Config.ARGB_8888,
        )

        try {
            Canvas(target).drawBitmap(
                bitmap,
                Rect(
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                ),
                Rect(
                    0,
                    0,
                    videoFormat.width,
                    videoFormat.height,
                ),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            val pixels = IntArray(videoFormat.width * videoFormat.height)
            target.getPixels(
                pixels,
                0,
                videoFormat.width,
                0,
                0,
                videoFormat.width,
                videoFormat.height,
            )
            val rgba = ByteArray(pixels.size * RGBA_BYTES_PER_PIXEL)
            pixels.forEachIndexed { index, pixel ->
                val offset = index * RGBA_BYTES_PER_PIXEL
                rgba[offset] = (pixel ushr 16).toByte()
                rgba[offset + 1] = (pixel ushr 8).toByte()
                rgba[offset + 2] = pixel.toByte()
                rgba[offset + 3] = (pixel ushr 24).toByte()
            }

            return VideoFrame(
                format = VideoFormat(
                    width = videoFormat.width,
                    height = videoFormat.height,
                    pixelFormat = VideoPixelFormat.RGBA,
                ),
                timestampUs = 0L,
                planes = listOf(
                    VideoFramePlane(
                        data = rgba,
                        stride = videoFormat.width * RGBA_BYTES_PER_PIXEL,
                        copyData = false,
                    ),
                ),
                bufferReuseId = UUID.randomUUID(),
            )
        } catch (
            error: XmaxError,
        ) {
            throw error
        } catch (
            error: Throwable,
        ) {
            throw XmaxError(
                code = XmaxErrorCode.MEDIA_ERROR,
                message = "Failed to prepare image video frame",
                cause = error,
            )
        } finally {
            target.recycle()
        }
    }

    /**
     * The RGBA pixel layout constants.
     */
    internal companion object {

        /**
         * Creates an image processing error.
         *
         * @param message The message to send or log.
         */
        private fun processingError(
            message: String,
        ): XmaxError =
            XmaxError(
                XmaxErrorCode.MEDIA_ERROR,
                message,
            )

        /**
         * The number of bytes per RGBA pixel.
         */
        private const val RGBA_BYTES_PER_PIXEL = 4
    }
}
