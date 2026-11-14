//
//  ApiTransport.kt
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * A network request passed to the HTTP transport.
 *
 * @property method The HTTP request method.
 * @property url The resource or request URL.
 * @property headers The HTTP request or response headers.
 * @property body The raw HTTP body bytes.
 * @property connectTimeoutMs The connection timeout, in milliseconds.
 * @property readTimeoutMs The response read timeout, in milliseconds.
 */
internal data class ApiHttpRequest(
    val method: ApiMethod,
    val url: URL,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
)

/**
 * A raw response from the HTTP transport.
 *
 * @property statusCode The HTTP response status code.
 * @property body The raw HTTP body bytes.
 */
internal data class ApiHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

/**
 * Executes HTTP requests and returns their status and response content.
 */
internal fun interface ApiTransport {

    /**
     * Sends an HTTP request and returns its raw status, headers, and body.
     *
     * @param request The HTTP method, URL, headers, and body.
     * @return The raw HTTP status, headers, and body.
     */
    suspend fun execute(
        request: ApiHttpRequest,
    ): ApiHttpResponse
}

/**
 * The default Android/Java URLConnection transport.
 *
 * @property ioDispatcher The dispatcher for blocking network or file I/O; defaults to `Dispatchers.IO`.
 * @property connectionFactory The HTTP connection factory.
 */
internal class UrlConnectionApiTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) : ApiTransport {

    /**
     * Sends an HTTP request and returns its raw status, headers, and body.
     *
     * @param request The HTTP method, URL, headers, and body.
     * @return The raw HTTP status, headers, and body.
     */
    override suspend fun execute(
        request: ApiHttpRequest,
    ): ApiHttpResponse =
        withCancellableConnection(
            request.url,
            ioDispatcher,
            connectionFactory,
        ) { connection, ensureActive ->
            connection.apply {
                requestMethod = request.method.wireValue
                connectTimeout = request.connectTimeoutMs
                readTimeout = request.readTimeoutMs
                useCaches = false
                for ((name, value) in request.headers) {
                    setRequestProperty(name, value)
                }
                request.body?.let { payload ->
                    doOutput = true
                    setFixedLengthStreamingMode(payload.size)
                }
            }
            ensureActive()
            request.body?.let { payload -> connection.outputStream.use { it.write(payload) } }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { it.readBytes() } ?: ByteArray(0)
            ensureActive()
            ApiHttpResponse(
                statusCode,
                responseBody,
            )
        }
}
