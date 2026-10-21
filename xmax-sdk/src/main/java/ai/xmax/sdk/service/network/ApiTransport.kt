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
 * 网络层发送给底层 HTTP Transport 的请求。
 */
internal data class ApiHttpRequest(
    /**
     * HTTP 请求方法。
     */
    val method: ApiMethod,

    /**
     * 资源或请求的网络地址。
     */
    val url: URL,

    /**
     * HTTP 请求或响应头。
     */
    val headers: Map<String, String>,

    /**
     * HTTP 正文的原始字节。
     */
    val body: ByteArray?,

    /**
     * 建立网络连接的超时时间，单位为毫秒。
     */
    val connectTimeoutMs: Int,

    /**
     * 读取响应数据的超时时间，单位为毫秒。
     */
    val readTimeoutMs: Int,
)

/**
 * 底层 HTTP Transport 返回的原始响应。
 */
internal data class ApiHttpResponse(
    /**
     * HTTP 响应状态码。
     */
    val statusCode: Int,

    /**
     * HTTP 正文的原始字节。
     */
    val body: ByteArray,
)

/**
 * 执行 HTTP 请求并返回状态码与响应内容。
 */
internal fun interface ApiTransport {

    /**
     * 发送原始 HTTP 请求并返回状态、响应头和正文字节。
     *
     * @param request 包含方法、地址、请求头及正文的 HTTP 请求。
     * @return 包含 HTTP 状态、响应头及正文的原始响应。
     */
    suspend fun execute(
        request: ApiHttpRequest
    ): ApiHttpResponse
}

/**
 * 使用 Android/Java 原生 URLConnection 的默认 Transport。
 */
internal class UrlConnectionApiTransport(
    /**
     * 执行阻塞网络或文件操作的协程调度器；默认值为 `Dispatchers.IO`。
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,

    /**
     * 按 URL 创建 HTTP 连接的函数。
     */
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) : ApiTransport {

    /**
     * 发送原始 HTTP 请求并返回状态、响应头和正文字节。
     *
     * @param request 包含方法、地址、请求头及正文的 HTTP 请求。
     * @return 包含 HTTP 状态、响应头及正文的原始响应。
     */
    override suspend fun execute(
        request: ApiHttpRequest
    ): ApiHttpResponse =
        withCancellableConnection(request.url, ioDispatcher, connectionFactory) { connection, ensureActive ->
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
            ApiHttpResponse(statusCode, responseBody)
        }
}
