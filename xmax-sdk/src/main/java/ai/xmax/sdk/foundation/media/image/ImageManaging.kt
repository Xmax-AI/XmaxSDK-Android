//
//  ImageManaging.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.media.image

import android.graphics.Bitmap

/**
 * An interface for image decoding.
 */
internal interface ImageManaging {

    /**
     * Decodes image bytes and normalizes EXIF orientation.
     *
     * @param data The binary data to read, transfer, or retain.
     * @return The decoded image with normalized orientation.
     */
    fun decode(
        data: ByteArray,
    ): DecodedImage

    /**
     * Converts a Bitmap into an image that can supply video frames.
     *
     * @param bitmap The Android image to process or display.
     * @return The decoded image with normalized orientation.
     */
    fun decode(
        bitmap: Bitmap,
    ): DecodedImage
}
