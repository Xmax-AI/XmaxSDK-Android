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
 * The image and video storage API; suspending calls return transfer results or throw failures.
 * Cancellation propagates to the transfer. Progress arrives on the transfer thread; your app must dispatch UI
 * updates to the main thread.
 * Downloads write a temporary file before replacing the destination; concurrent writes to the same
 * destination are rejected.
 */
public interface XmaxStorageManaging {

    /**
     * Uploads image bytes; fileName forms the object name, and contentType must identify an image.
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
     * Uploads a readable image file, inferring its MIME type from the extension when contentType is null.
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
     * The local-path overload of [uploadImageFile], with the same parameter meanings as the File overload.
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
     * Uploads image bytes and returns the result after the server content safety check passes.
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
     * Uploads a local image and checks content safety; rejected images throw UNSAFE_IMAGE.
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
     * The local-path overload of [uploadImageFileWithSafetyCheck].
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
     * Uploads video bytes; fileName forms the object name, and contentType must identify video.
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
     * Uploads a readable video file, inferring its MIME type from the extension when contentType is null.
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
     * The local-path overload of [uploadVideoFile].
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
     * Downloads an image from HTTP(S), returning the destination path and actual byte count.
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
     * The destination-path overload of [downloadImage].
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
     * Downloads a video from HTTP(S), returning the destination path and actual byte count.
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
     * The destination-path overload of [downloadVideo].
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
