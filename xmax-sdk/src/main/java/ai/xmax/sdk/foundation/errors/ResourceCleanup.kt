//
//  ResourceCleanup.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Attempts independent cleanup steps in order without skipping later steps after failure.
 *
 * @param actions The cleanup operations in execution order.
 */
internal suspend fun cleanupResources(
    vararg actions: suspend () -> Unit,
) {
    val failure = withContext(NonCancellable) {
        var failure: Throwable? = null
        for (action in actions) {
            try {
                action()
            } catch (
                error: Throwable,
            ) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        failure
    }
    // Preserve the original exception and any suppressed cleanup failures.
    failure?.let { throw it }
}

/**
 * Attach rollback failures without replacing the original error, including CancellationException.
 *
 * @param error The operation failure or SDK error.
 * @param actions The cleanup operations in execution order.
 */
internal suspend fun cleanupAfterFailure(
    error: Throwable,
    vararg actions: suspend () -> Unit,
) {
    try {
        cleanupResources(*actions)
    } catch (
        cleanupError: Throwable,
    ) {
        if (cleanupError !== error) error.addSuppressed(cleanupError)
    }
}
