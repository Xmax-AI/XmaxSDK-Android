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
 * The basic file upload and download capabilities.
 */
internal interface StorageManaging {

    /**
     * Uploads a file or bytes to an object key and returns accessible object metadata.
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
     * Downloads a file, replacing the destination only on success and retaining existing contents on failure.
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
