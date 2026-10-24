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
 */
internal data class StorageCredential(
    /**
     * The temporary storage access key identifier.
     */
    val accessKeyId: String,

    /**
     * The temporary secret access key; never log it.
     */
    val secretAccessKey: String,

    /**
     * The temporary storage session token.
     */
    val sessionToken: String,
)

/**
 * The object storage location and temporary credentials.
 */
internal data class StorageConfiguration(
    /**
     * The storage bucket name.
     */
    val bucket: String,

    /**
     * The storage region.
     */
    val region: String,

    /**
     * The storage endpoint.
     */
    val endpoint: String,

    /**
     * The temporary credentials for this transfer.
     */
    val credential: StorageCredential,
)

/**
 * The permitted object prefix and storage configuration for an upload.
 */
internal data class TemporaryStorageConfiguration(
    /**
     * The prefix for object or record keys.
     */
    val prefix: String,

    /**
     * The storage endpoint, region, bucket, and temporary credentials.
     */
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
     */
    data class LocalFile(
        /**
         * A readable local file.
         */
        val file: File,
    ) : StorageSource {

        /**
         * The content size of the source.
         */
        override val byteCount: Long = file.length()
    }

    /**
     * An upload source backed by a byte array.
     */
    data class Bytes(
        /**
         * The binary data to read, transfer, or retain.
         */
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
 */
internal data class StoredFile(
    /**
     * The resource or request URL.
     */
    val url: String,

    /**
     * The complete object key within the bucket.
     */
    val objectKey: String,

    /**
     * The storage entity tag, or null if the service omits it.
     */
    val etag: String? = null,
)

/**
 * The local file and actual size after a successful download.
 */
internal data class DownloadedFile(
    /**
     * A readable local file.
     */
    val file: File,

    /**
     * The size, in bytes.
     */
    val byteCount: Long,
)
