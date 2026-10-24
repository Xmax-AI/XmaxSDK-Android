//
//  StorageManaging.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.storage

import java.io.File

/**
 * An interface for file uploads and downloads.
 */
internal interface StorageManaging {

    /**
     * Uploads data or a local file using temporary credentials.
     *
     * @param source The local file or bytes to upload.
     * @param objectKey The complete object key within the bucket.
     * @param contentType The media MIME type.
     * @param configuration The storage endpoint, region, bucket, and temporary credentials.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The uploaded URL, object key, and entity tag.
     */
    suspend fun upload(
        source: StorageSource,
        objectKey: String,
        contentType: String,
        configuration: StorageConfiguration,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * Downloads a file to the specified local location.
     *
     * The destination is replaced only on success; existing contents are retained on failure.
     *
     * @param remoteUrl The HTTP or HTTPS URL to download.
     * @param destination The destination file replaced after a successful download.
     * @param progress The transferred-byte and total-byte callback, or null to omit progress.
     * @return The downloaded file and actual byte count.
     */
    suspend fun download(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile
}
