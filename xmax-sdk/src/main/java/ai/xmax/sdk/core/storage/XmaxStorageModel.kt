//
//  XmaxStorageModel.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * Progress for one file transfer.
 */
public data class XmaxStorageProgress(
    /**
     * The number of bytes transferred.
     */
    public val completedBytes: Long,

    /**
     * The total byte count; may be nonpositive before the transport knows the size.
     */
    public val totalBytes: Long,
) {

    /**
     * The completion ratio clamped to 0 through 1; returns 0 for an unknown or zero total.
     */
    public val fractionCompleted: Float
        get() = if (totalBytes <= 0L) {
            0f
        } else {
            (completedBytes.toDouble() / totalBytes.toDouble()).coerceIn(
                0.0,
                1.0,
            ).toFloat()
        }
}

/**
 * File transfer progress notifications, not guaranteed on the main thread and not confirmation of a
 * successful content safety check.
 */
public fun interface XmaxStorageProgressListener {

    /**
     * Receives byte progress; the suspending call's return value confirms final success.
     *
     * @param progress The transferred and total byte counts.
     */
    public fun onProgress(
        progress: XmaxStorageProgress,
    )
}

/**
 * A successfully uploaded file.
 */
public data class XmaxUploadedFile(
    /**
     * The uploaded URL; content safety checks may return an adjusted server URL.
     */
    public val url: String,

    /**
     * The remote object key.
     */
    public val objectKey: String,

    /**
     * The storage entity tag, or null if not provided.
     */
    public val etag: String? = null,
)

/**
 * A successfully downloaded file.
 */
public data class XmaxDownloadedFile(
    /**
     * The local destination path supplied to the download call.
     */
    public val filePath: String,

    /**
     * The actual number of bytes written successfully.
     */
    public val byteCount: Long,
)
