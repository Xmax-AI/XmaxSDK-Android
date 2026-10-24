//
//  EncodingControlling.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.encoding

import ai.xmax.sdk.RealtimeVideoFormat

/**
 * An interface for realtime encoder configuration.
 */
internal interface EncodingControlling {

    /**
     * Validates and applies a resolved encoder configuration.
     *
     * @param videoFormat Four-pixel-aligned dimensions, frame rate, and uplink encoding options.
     */
    fun configure(
        videoFormat: RealtimeVideoFormat,
    )
}
