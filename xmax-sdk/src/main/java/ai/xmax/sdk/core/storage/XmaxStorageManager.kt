//
//  XmaxStorageManager.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.foundation.storage.DownloadedFile
import ai.xmax.sdk.foundation.storage.StorageProgressListener
import ai.xmax.sdk.foundation.storage.StoredFile
import ai.xmax.sdk.service.storage.StorageServicing
import java.io.File

/**
 * Provides public file transfer operations backed by the storage service.
 */
internal class XmaxStorageManager(
    /**
     * The service validating and executing file uploads and downloads.
     */
    private val storageService: StorageServicing,
) : XmaxStorageManaging {

    /**
     * Uploads encoded image data.
     *
     * @param data Encoded image file bytes.
     * @param fileName The filename, including its extension, used to form the object name.
     * @param contentType The media MIME type.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    override suspend fun uploadImage(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = storageService
        .uploadImage(
            data,
            fileName,
            contentType,
            progress.toStorageProgressListener(),
        )
        .toPublicModel()

    /**
     * Uploads a local image file.
     *
     * @param file A readable local file.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    override suspend fun uploadImageFile(
        file: File,
        contentType: String?,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = storageService
        .uploadImageFile(
            file,
            contentType,
            progress.toStorageProgressListener(),
        )
        .toPublicModel()

    /**
     * Uploads an image from a local file path.
     *
     * @param filePath A readable local file path.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    override suspend fun uploadImageFile(
        filePath: String,
        contentType: String?,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = uploadImageFile(
        File(filePath),
        contentType,
        progress,
    )

    /**
     * Uploads encoded image data and checks its content before returning.
     *
     * @param data Encoded image file bytes.
     * @param fileName The filename, including its extension, used to form the object name.
     * @param contentType The media MIME type.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    override suspend fun uploadImageWithSafetyCheck(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = storageService
        .uploadImageWithSafetyCheck(
            data,
            fileName,
            contentType,
            progress.toStorageProgressListener(),
        )
        .toPublicModel()

    /**
     * Uploads a local image file and checks its content before returning.
     *
     * @param file A readable local file.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    override suspend fun uploadImageFileWithSafetyCheck(
        file: File,
        contentType: String?,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = storageService
        .uploadImageFileWithSafetyCheck(
            file,
            contentType,
            progress.toStorageProgressListener(),
        )
        .toPublicModel()

    /**
     * Uploads a local image file and checks its content before returning.
     *
     * @param filePath A readable local file path.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    override suspend fun uploadImageFileWithSafetyCheck(
        filePath: String,
        contentType: String?,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = uploadImageFileWithSafetyCheck(
        File(filePath),
        contentType,
        progress,
    )

    /**
     * Uploads encoded video data.
     *
     * @param data Encoded video file bytes.
     * @param fileName The filename, including its extension, used to form the object name.
     * @param contentType The media MIME type.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    override suspend fun uploadVideo(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = storageService
        .uploadVideo(
            data,
            fileName,
            contentType,
            progress.toStorageProgressListener(),
        )
        .toPublicModel()

    /**
     * Uploads a local video file.
     *
     * @param file A readable local file.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    override suspend fun uploadVideoFile(
        file: File,
        contentType: String?,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = storageService
        .uploadVideoFile(
            file,
            contentType,
            progress.toStorageProgressListener(),
        )
        .toPublicModel()

    /**
     * Uploads a video from a local file path.
     *
     * @param filePath A readable local file path.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    override suspend fun uploadVideoFile(
        filePath: String,
        contentType: String?,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = uploadVideoFile(
        File(filePath),
        contentType,
        progress,
    )

    /**
     * Downloads an image to the specified local file.
     *
     * @param remoteUrl The HTTP or HTTPS URL to download.
     * @param destination The destination file replaced after a successful download.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The downloaded local path and actual byte count.
     */
    override suspend fun downloadImage(
        remoteUrl: String,
        destination: File,
        progress: XmaxStorageProgressListener?,
    ): XmaxDownloadedFile = storageService
        .downloadImage(
            remoteUrl,
            destination,
            progress.toStorageProgressListener(),
        )
        .toPublicModel()

    /**
     * Downloads an image to the specified local file.
     *
     * @param remoteUrl The HTTP or HTTPS URL to download.
     * @param destinationPath The destination path replaced after a successful download.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The downloaded local path and actual byte count.
     */
    override suspend fun downloadImage(
        remoteUrl: String,
        destinationPath: String,
        progress: XmaxStorageProgressListener?,
    ): XmaxDownloadedFile = downloadImage(
        remoteUrl,
        File(destinationPath),
        progress,
    )

    /**
     * Downloads a video to the specified local file.
     *
     * @param remoteUrl The HTTP or HTTPS URL to download.
     * @param destination The destination file replaced after a successful download.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The downloaded local path and actual byte count.
     */
    override suspend fun downloadVideo(
        remoteUrl: String,
        destination: File,
        progress: XmaxStorageProgressListener?,
    ): XmaxDownloadedFile = storageService
        .downloadVideo(
            remoteUrl,
            destination,
            progress.toStorageProgressListener(),
        )
        .toPublicModel()

    /**
     * Downloads a video to the specified local file.
     *
     * @param remoteUrl The HTTP or HTTPS URL to download.
     * @param destinationPath The destination path replaced after a successful download.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The downloaded local path and actual byte count.
     */
    override suspend fun downloadVideo(
        remoteUrl: String,
        destinationPath: String,
        progress: XmaxStorageProgressListener?,
    ): XmaxDownloadedFile = downloadVideo(
        remoteUrl,
        File(destinationPath),
        progress,
    )
}

/**
 * Returns public upload metadata without exposing transport implementation types.
 *
 * @return The uploaded URL, object key, and entity tag.
 */
private fun StoredFile.toPublicModel(): XmaxUploadedFile =
    XmaxUploadedFile(
        url = url,
        objectKey = objectKey,
        etag = etag,
    )

/**
 * Retains the download destination and actual bytes written.
 *
 * @return The downloaded local path and actual byte count.
 */
private fun DownloadedFile.toPublicModel(): XmaxDownloadedFile =
    XmaxDownloadedFile(
        filePath = file.path,
        byteCount = byteCount,
    )

/**
 * Adapts progress without changing its callback thread; creates no adapter when no listener is supplied.
 *
 * @return The adapted progress listener, or null if no public listener was supplied.
 */
private fun XmaxStorageProgressListener?.toStorageProgressListener(): StorageProgressListener? =
    this?.let { listener ->
        StorageProgressListener { completedBytes, totalBytes ->
            listener.onProgress(
                XmaxStorageProgress(
                    completedBytes = completedBytes,
                    totalBytes = totalBytes,
                ),
            )
        }
    }
