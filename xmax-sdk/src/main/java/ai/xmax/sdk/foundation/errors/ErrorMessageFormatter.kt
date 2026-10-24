//
//  ErrorMessageFormatter.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * Produces stable, nonempty error descriptions.
 */
internal object ErrorMessageFormatter {

    /**
     * Formats an error for display or diagnostics.
     *
     * @param error The operation failure or SDK error.
     * @return Readable text including the error cause.
     */
    fun format(
        error: Throwable,
    ): String = error.message
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: error.javaClass.simpleName.takeIf(String::isNotEmpty)
        ?: error.toString()
}
