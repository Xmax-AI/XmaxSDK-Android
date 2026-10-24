//
//  XmaxStorageManaging.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import java.io.File

/**
 * An interface for uploading and downloading media files.
 *
 * Suspending calls return transfer results or throw failures. Cancellation propagates to the
 * transfer. Progress arrives on the transfer thread; your app must dispatch UI updates to the main
 * thread. Downloads preserve the existing destination on failure; concurrent writes to the same
 * destination are rejected.
 */
public interface XmaxStorageManaging {

    /**
     * Uploads encoded image data.
     *
     * @param data Encoded image file bytes.
     * @param fileName The filename, including its extension, used to form the object name.
     * @param contentType The media MIME type.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    public suspend fun uploadImage(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /**
     * Uploads a local image file.
     *
     * @param file A readable local file.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    public suspend fun uploadImageFile(
        file: File,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /**
     * Uploads a local image file.
     *
     * @param filePath A readable local file path.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    public suspend fun uploadImageFile(
        filePath: String,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /**
     * Uploads encoded image data and checks its content before returning.
     *
     * @param data Encoded image file bytes.
     * @param fileName The filename, including its extension, used to form the object name.
     * @param contentType The media MIME type.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    public suspend fun uploadImageWithSafetyCheck(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /**
     * Uploads a local image file and checks its content before returning.
     *
     * Content rejected by the service produces [XmaxErrorCode.UNSAFE_IMAGE].
     *
     * @param file A readable local file.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    public suspend fun uploadImageFileWithSafetyCheck(
        file: File,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /**
     * Uploads a local image file and checks its content before returning.
     *
     * @param filePath A readable local file path.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    public suspend fun uploadImageFileWithSafetyCheck(
        filePath: String,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /**
     * Uploads encoded video data.
     *
     * @param data Encoded video file bytes.
     * @param fileName The filename, including its extension, used to form the object name.
     * @param contentType The media MIME type.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    public suspend fun uploadVideo(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /**
     * Uploads a local video file.
     *
     * @param file A readable local file.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    public suspend fun uploadVideoFile(
        file: File,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /**
     * Uploads a local video file.
     *
     * @param filePath A readable local file path.
     * @param contentType The media MIME type, or null to infer it from the extension.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    public suspend fun uploadVideoFile(
        filePath: String,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /**
     * Downloads an image to the specified local file.
     *
     * @param remoteUrl The HTTP or HTTPS URL to download.
     * @param destination The destination file replaced after a successful download.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The downloaded local path and actual byte count.
     */
    public suspend fun downloadImage(
        remoteUrl: String,
        destination: File,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile

    /**
     * Downloads an image to the specified local file.
     *
     * @param remoteUrl The HTTP or HTTPS URL to download.
     * @param destinationPath The destination path replaced after a successful download.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The downloaded local path and actual byte count.
     */
    public suspend fun downloadImage(
        remoteUrl: String,
        destinationPath: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile

    /**
     * Downloads a video to the specified local file.
     *
     * @param remoteUrl The HTTP or HTTPS URL to download.
     * @param destination The destination file replaced after a successful download.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The downloaded local path and actual byte count.
     */
    public suspend fun downloadVideo(
        remoteUrl: String,
        destination: File,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile

    /**
     * Downloads a video to the specified local file.
     *
     * @param remoteUrl The HTTP or HTTPS URL to download.
     * @param destinationPath The destination path replaced after a successful download.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The downloaded local path and actual byte count.
     */
    public suspend fun downloadVideo(
        remoteUrl: String,
        destinationPath: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile
}
