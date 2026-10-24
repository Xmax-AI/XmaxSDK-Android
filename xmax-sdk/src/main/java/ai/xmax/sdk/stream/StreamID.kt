//
//  StreamID.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream

/**
 * The internal local and remote media stream identifiers.
 */
internal enum class StreamID(
    /**
     * The string representation used for configuration or display.
     */
    val value: String,
) {

    /**
     * The local input stream identifier.
     */
    LOCAL("stream-local"),

    /**
     * The remote generation stream identifier.
     */
    REMOTE("stream-remote"),
}
