//
//  CancellableConnection.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.network

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Runs cancellable HTTP I/O, disconnecting on cancellation and awaiting underlying I/O completion before
 * returning.
 *
 * @param url The resource or request URL.
 * @param dispatcher The I/O dispatcher; defaults to `Dispatchers.IO`.
 * @param factory Creates an HTTP connection for the URL.
 * @param block Uses the connection and checks cancellation.
 * @return The operation's successful result.
 */
internal suspend fun <T> withCancellableConnection(
    url: URL,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    factory: (URL) -> HttpURLConnection,
    block: (HttpURLConnection, () -> Unit) -> T,
): T = coroutineScope {
    val owner = this
    suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        continuation.invokeOnCancellation { runCatching { connection.get()?.disconnect() } }
        owner.launch(dispatcher) {
            val context = currentCoroutineContext()
            val result = runCatching {
                context.ensureActive()
                val opened = factory(url)
                connection.set(opened)

                try {
                    context.ensureActive()
                    block(opened) { context.ensureActive() }
                } finally {
                    connection.set(null)
                    runCatching { opened.disconnect() }
                }
            }
            continuation.resumeWith(result)
        }
    }
}
