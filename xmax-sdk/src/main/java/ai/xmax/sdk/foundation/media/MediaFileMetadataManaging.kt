//
//  MediaFileMetadataManaging.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.media

import android.net.Uri

/**
 * An interface for local media metadata.
 */
internal interface MediaFileMetadataManaging {

    /**
     * Reads media dimensions, rotation, duration, and audio track metadata.
     *
     * @param uri A media URI your app can read.
     * @return The media dimensions, rotation, duration, and audio metadata.
     */
    suspend fun readMetadata(
        uri: Uri,
    ): MediaFileMetadata
}
