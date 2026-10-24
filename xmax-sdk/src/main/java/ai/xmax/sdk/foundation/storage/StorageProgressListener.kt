//
//  StorageProgressListener.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.storage

/**
 * The byte-progress listener for storage transfers.
 */
internal fun interface StorageProgressListener {

    /**
     * Receives transferred and total byte counts.
     *
     * @param completedBytes The transferred byte count.
     * @param totalBytes The total byte count.
     */
    fun onProgress(
        completedBytes: Long,
        totalBytes: Long,
    )
}
