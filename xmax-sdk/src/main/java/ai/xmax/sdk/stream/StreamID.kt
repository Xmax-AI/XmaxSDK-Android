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
 * SDK 内部使用的本地与远端媒体流标识。
 */
internal enum class StreamID(
    /**
     * 需要规范化、校验或显示的文本。
     */
    val value: String
) {

    /**
     * 本地输入媒体流标识。
     */
    LOCAL("stream-local"),

    /**
     * 远端生成媒体流标识。
     */
    REMOTE("stream-remote"),
}
