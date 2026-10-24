//
//  StorageServicing.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.storage

import ai.xmax.sdk.foundation.storage.DownloadedFile
import ai.xmax.sdk.foundation.storage.StorageProgressListener
import ai.xmax.sdk.foundation.storage.StoredFile
import java.io.File

/**
 * The image/video transfer and image content safety capabilities.
 */
internal interface StorageServicing {

    /**
     * Uploads image bytes and returns accessible storage metadata.
     *
     * @param data Encoded image file bytes.
     * @param fileName The filename, including its extension, used to form the object name.
     * @param contentType The media MIME type.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    suspend fun uploadImage(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * Uploads a local image and returns accessible storage metadata.
     *
     * @param file A readable local file.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    suspend fun uploadImageFile(
        file: File,
        contentType: String?,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * Uploads image bytes and returns the result after content safety validation.
     *
     * @param data Encoded image file bytes.
     * @param fileName The filename, including its extension, used to form the object name.
     * @param contentType The media MIME type.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    suspend fun uploadImageWithSafetyCheck(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * Uploads a local image and returns the result after content safety validation.
     *
     * @param file A readable local file.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    suspend fun uploadImageFileWithSafetyCheck(
        file: File,
        contentType: String?,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * Uploads video bytes and returns accessible storage metadata.
     *
     * @param data Encoded video file bytes.
     * @param fileName The filename, including its extension, used to form the object name.
     * @param contentType The media MIME type.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    suspend fun uploadVideo(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * Uploads a local video and returns accessible storage metadata.
     *
     * @param file A readable local file.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    suspend fun uploadVideoFile(
        file: File,
        contentType: String?,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * Downloads an image and returns local file metadata, retaining the existing destination on failure.
     *
     * @param remoteUrl The HTTP or HTTPS URL to download.
     * @param destination The destination file replaced after a successful download.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The downloaded file and actual byte count.
     */
    suspend fun downloadImage(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile

    /**
     * Downloads a video and returns local file metadata, retaining the existing destination on failure.
     *
     * @param remoteUrl The HTTP or HTTPS URL to download.
     * @param destination The destination file replaced after a successful download.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The downloaded file and actual byte count.
     */
    suspend fun downloadVideo(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile
}
