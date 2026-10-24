//
//  ImageSourceControlling.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.image

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.foundation.media.image.DecodedImage
import android.graphics.Bitmap
import android.net.Uri

/**
 * The capability to repeatedly output local image frames.
 */
internal interface ImageSourceControlling {

    /**
     * Decodes an image and prepares its aspect-preserving input format and video frame.
     *
     * @param imageData Encoded image file bytes, not raw pixels.
     * @return The image input format and corresponding video frame.
     */
    suspend fun prepare(
        imageData: ByteArray,
    ): Pair<RealtimeVideoFormat, VideoFrame>

    /**
     * Decodes an image and prepares its aspect-preserving input format and video frame.
     *
     * @param bitmap The Android image to process or display.
     * @return The image input format and corresponding video frame.
     */
    suspend fun prepare(
        bitmap: Bitmap,
    ): Pair<RealtimeVideoFormat, VideoFrame>

    /**
     * Decodes an image and prepares its aspect-preserving input format and video frame.
     *
     * @param uri A media URI your app can read.
     * @return The image input format and corresponding video frame.
     */
    suspend fun prepare(
        uri: Uri,
    ): Pair<RealtimeVideoFormat, VideoFrame>

    /**
     * Decodes an image and prepares its aspect-preserving input format and video frame.
     *
     * @param decodedImage The decoded, orientation-normalized image.
     * @return The image input format and corresponding video frame.
     */
    suspend fun prepare(
        decodedImage: DecodedImage,
    ): Pair<RealtimeVideoFormat, VideoFrame>

    /**
     * Starts repeating the prepared image at the configured frame rate.
     */
    fun start()

    /**
     * Stops image frame output and releases related resources.
     */
    suspend fun stop()
}
