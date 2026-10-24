//
//  MediaFileMetadataManager.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.media

import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads local media metadata using the Android media framework.
 *
 * @param context The Android context used for system services and media resources.
 */
internal class MediaFileMetadataManager(
    context: Context,
) : MediaFileMetadataManaging {

    /**
     * The Android context for media and file access.
     */
    private val applicationContext = context.applicationContext

    /**
     * Reads media dimensions, rotation, duration, and audio track metadata.
     *
     * @param uri A media URI your app can read.
     * @return The media dimensions, rotation, duration, and audio metadata.
     */
    override suspend fun readMetadata(
        uri: Uri,
    ): MediaFileMetadata =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()

            try {
                retriever.setDataSource(
                    applicationContext,
                    uri,
                )
                val width = retriever.requiredPositiveInt(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                )
                val height = retriever.requiredPositiveInt(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                )
                val durationMs = retriever.requiredPositiveLong(
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                )
                val rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
                )?.toIntOrNull().orEmptyRotation()
                val durationUs = Math.multiplyExact(
                    durationMs,
                    MICROSECONDS_PER_MILLISECOND,
                )
                MediaFileMetadata(
                    width = width,
                    height = height,
                    rotation = rotation,
                    durationUs = durationUs,
                    hasAudio = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO,
                    ) == "yes",
                )
            } catch (
                error: XmaxError,
            ) {
                throw error
            } catch (
                error: Throwable,
            ) {
                throw mediaError(
                    "Failed to read media file metadata",
                    error,
                )
            } finally {
                retriever.release()
            }
        }

    /**
     * Reads a required positive integer metadata value or throws a media error.
     *
     * @param key The Android media metadata key.
     * @return The validated positive integer value.
     */
    private fun MediaMetadataRetriever.requiredPositiveInt(
        key: Int,
    ): Int =
        extractMetadata(key)?.toIntOrNull()?.takeIf { it > 0 }
            ?: throw mediaError("The media file has invalid video dimensions")

    /**
     * Reads a required positive long metadata value or throws a media error.
     *
     * @param key The Android media metadata key.
     * @return The validated positive long value.
     */
    private fun MediaMetadataRetriever.requiredPositiveLong(
        key: Int,
    ): Long =
        extractMetadata(key)?.toLongOrNull()?.takeIf { it > 0L }
            ?: throw mediaError("The media file has an invalid duration")

    /**
     * Converts rotation metadata to video orientation, treating missing metadata as unrotated.
     *
     * @return The corresponding rotation.
     */
    private fun Int?.orEmptyRotation(): VideoRotation = when (this) {
        90 -> VideoRotation.ROTATION_90
        180 -> VideoRotation.ROTATION_180
        270 -> VideoRotation.ROTATION_270
        else -> VideoRotation.ROTATION_0
    }

    /**
     * Creates a media error with the processing failure description.
     *
     * @param message The message to send or log.
     * @param cause The underlying exception that caused this error.
     */
    private fun mediaError(
        message: String,
        cause: Throwable? = null,
    ): XmaxError =
        XmaxError(
            XmaxErrorCode.MEDIA_ERROR,
            message,
            cause = cause,
        )

    /**
     * Media timestamp conversion constants.
     */
    private companion object {

        /**
         * The number of microseconds per millisecond.
         */
        const val MICROSECONDS_PER_MILLISECOND = 1_000L
    }
}
