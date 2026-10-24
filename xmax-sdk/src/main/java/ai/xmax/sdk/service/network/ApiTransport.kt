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
 */
internal data class ApiHttpRequest(
    /**
     * The HTTP request method.
     */
    val method: ApiMethod,

    /**
     * The resource or request URL.
     */
    val url: URL,

    /**
     * The HTTP request or response headers.
     */
    val headers: Map<String, String>,

    /**
     * The raw HTTP body bytes.
     */
    val body: ByteArray?,

    /**
     * The connection timeout, in milliseconds.
     */
    val connectTimeoutMs: Int,

    /**
     * The response read timeout, in milliseconds.
     */
    val readTimeoutMs: Int,
)

/**
 * A raw response from the HTTP transport.
 */
internal data class ApiHttpResponse(
    /**
     * The HTTP response status code.
     */
    val statusCode: Int,

    /**
     * The raw HTTP body bytes.
     */
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
 */
internal class UrlConnectionApiTransport(
    /**
     * The dispatcher for blocking network or file I/O; defaults to `Dispatchers.IO`.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    /**
     * The HTTP connection factory.
     */
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
                request.headers.forEach(::setRequestProperty)
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
