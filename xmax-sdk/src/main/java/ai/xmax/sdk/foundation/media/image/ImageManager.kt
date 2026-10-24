//
//  ImageManager.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.media.image

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * Decodes images using Android BitmapFactory.
 */
internal class ImageManager : ImageManaging {

    /**
     * Decodes image bytes and normalizes EXIF orientation.
     *
     * @param data The binary data to read, transfer, or retain.
     * @return The decoded image with normalized orientation.
     */
    override fun decode(
        data: ByteArray,
    ): DecodedImage {
        if (data.isEmpty()) throw processingError("Image source data must not be empty")

        val decoded = BitmapFactory.decodeByteArray(
            data,
            0,
            data.size,
        )
            ?: throw processingError("Failed to decode image")

        return try {
            BitmapDecodedImage(
                applyOrientation(
                    decoded,
                    readOrientation(data),
                ),
            )
        } catch (
            error: Throwable,
        ) {
            if (!decoded.isRecycled) decoded.recycle()
            throw XmaxError.from(error)
        }
    }

    /**
     * Converts a Bitmap into an image that can supply video frames.
     *
     * @param bitmap The Android image to process or display.
     * @return The decoded image with normalized orientation.
     */
    override fun decode(
        bitmap: Bitmap,
    ): DecodedImage {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            throw processingError("Bitmap image is unavailable")
        }

        val copy = bitmap.copy(
            Bitmap.Config.ARGB_8888,
            false,
        )
            ?: throw processingError("Failed to copy bitmap image")

        return BitmapDecodedImage(copy)
    }

    /**
     * Reads EXIF orientation for correct image presentation.
     *
     * @param data The binary data to read, transfer, or retain.
     * @return The EXIF orientation, or the default when unavailable.
     */
    private fun readOrientation(
        data: ByteArray,
    ): Int = runCatching {
        ExifInterface(ByteArrayInputStream(data)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * Rotates or flips an image according to EXIF orientation.
     *
     * @param bitmap The Android image to process or display.
     * @param orientation The image's EXIF orientation.
     * @return An image for local display.
     */
    private fun applyOrientation(
        bitmap: Bitmap,
        orientation: Int,
    ): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(
                -1f,
                1f,
            )
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(
                1f,
                -1f,
            )
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(
                    -1f,
                    1f,
                )
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(
                    -1f,
                    1f,
                )
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return ensureArgb8888(bitmap)
        }

        val transformed = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
        if (transformed !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return ensureArgb8888(transformed)
    }

    /**
     * Returns an image readable as ARGB_8888 pixels.
     *
     * @param bitmap The Android image to process or display.
     * @return An image for local display.
     */
    private fun ensureArgb8888(
        bitmap: Bitmap,
    ): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap

        val copy = bitmap.copy(
            Bitmap.Config.ARGB_8888,
            false,
        )
            ?: throw processingError("Failed to normalize image pixels")
        if (!bitmap.isRecycled) bitmap.recycle()
        return copy
    }

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
}
