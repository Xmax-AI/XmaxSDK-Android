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
 * The realtime encoder configuration capability.
 */
internal interface EncodingControlling {

    /**
     * Validates and applies a realtime video encoding format.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     */
    fun configure(
        videoFormat: RealtimeVideoFormat,
    )
}
