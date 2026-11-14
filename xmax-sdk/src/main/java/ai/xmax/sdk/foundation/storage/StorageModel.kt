//
//  StorageModel.kt
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
 * Temporary credentials for object storage transfers.
 *
 * @property accessKeyId The temporary storage access key identifier.
 * @property secretAccessKey The temporary secret access key; never log it.
 * @property sessionToken The temporary storage session token.
 */
internal data class StorageCredential(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String,
)

/**
 * The object storage location and temporary credentials.
 *
 * @property bucket The storage bucket name.
 * @property region The storage region.
 * @property endpoint The storage endpoint.
 * @property credential The temporary credentials for this transfer.
 */
internal data class StorageConfiguration(
    val bucket: String,
    val region: String,
    val endpoint: String,
    val credential: StorageCredential,
)

/**
 * The permitted object prefix and storage configuration for an upload.
 *
 * @property prefix The prefix for object or record keys.
 * @property configuration The storage endpoint, region, bucket, and temporary credentials.
 */
internal data class TemporaryStorageConfiguration(
    val prefix: String,
    val configuration: StorageConfiguration,
)

/**
 * A file or byte source for uploading.
 */
internal sealed interface StorageSource {

    /**
     * The content size of the source.
     */
    val byteCount: Long

    /**
     * An upload source backed by a local file.
     *
     * @property file A readable local file.
     */
    data class LocalFile(
        val file: File,
    ) : StorageSource {

        /**
         * The content size of the source.
         */
        override val byteCount: Long = file.length()
    }

    /**
     * An upload source backed by a byte array.
     *
     * @property data The binary data to read, transfer, or retain.
     */
    data class Bytes(
        val data: ByteArray,
    ) : StorageSource {

        /**
         * The content size of the source.
         */
        override val byteCount: Long = data.size.toLong()
    }
}

/**
 * An accessible object after a successful upload.
 *
 * @property url The resource or request URL.
 * @property objectKey The complete object key within the bucket.
 * @property etag The storage entity tag, or null if the service omits it.
 */
internal data class StoredFile(
    val url: String,
    val objectKey: String,
    val etag: String? = null,
)

/**
 * The local file and actual size after a successful download.
 *
 * @property file A readable local file.
 * @property byteCount The size, in bytes.
 */
internal data class DownloadedFile(
    val file: File,
    val byteCount: Long,
)
