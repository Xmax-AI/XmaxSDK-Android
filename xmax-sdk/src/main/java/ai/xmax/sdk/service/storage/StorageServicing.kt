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
 * An interface for image and video transfers and image safety.
 */
internal interface StorageServicing {

    /**
     * Uploads encoded image data.
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
     * Uploads a local image file.
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
     * Uploads image data and verifies content safety.
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
     * Uploads a local image and verifies content safety.
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
     * Uploads encoded video data.
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
     * Uploads a local video file.
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
     * Downloads a remote image to a local file.
     *
     * The existing destination is retained on failure.
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
     * Downloads a remote video to a local file.
     *
     * The existing destination is retained on failure.
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
