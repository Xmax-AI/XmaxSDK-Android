//
//  ApiService.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.network

import ai.xmax.sdk.ErrorMessageFormatter
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.runtime.RuntimeInfo
import java.io.IOException
import java.net.URI
import java.net.URL
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONException
import org.json.JSONObject

/**
 * Sends Xmax API requests and handles responses, logging, and errors.
 *
 * @param apiKey The API key issued by the developer platform; do not log or expose it.
 */
internal class ApiService(
    apiKey: String,

    /**
     * The API base URL.
     */
    private val baseUrl: String,

    /**
     * The request timeout in milliseconds; defaults to `DEFAULT_TIMEOUT_MS`.
     */
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,

    /**
     * The transport sending HTTP requests and returning raw responses.
     */
    private val transport: ApiTransport = UrlConnectionApiTransport(),
) : ApiServicing {

    /**
     * The API authentication credentials.
     */
    private val apiKey: String = apiKey.trim()

    /**
     * Sends a request and returns business data from the response envelope.
     *
     * @param method The HTTP method.
     * @param path The request path relative to the API base URL.
     * @param body The optional JSON request body; null sends no body.
     * @return JSON for the current operation.
     */
    override suspend fun request(
        method: ApiMethod,
        path: String,
        body: JSONObject?,
    ): JSONObject {
        validateConfiguration()
        val runtime = RuntimeInfo.current
        val request = ApiHttpRequest(
            method = method,
            url = resolveUrl(path),
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "X-Api-Key" to apiKey,
                "X-Platform" to runtime.platform,
                "X-OS-Version" to runtime.osVersion,
                "X-SDK-Version" to runtime.sdkVersion,
                "X-Device-Model" to runtime.deviceModel,
            ),
            body = body?.toString()?.toByteArray(Charsets.UTF_8),
            connectTimeoutMs = timeoutMs,
            readTimeoutMs = timeoutMs,
        )
        val startedAt = System.nanoTime()

        val response = try {
            transport.execute(request)
        } catch (
            error: XmaxError,
        ) {
            ApiLogger.logFailure(
                method,
                path,
                error,
                durationMs(startedAt),
            )
            throw error
        } catch (
            error: CancellationException,
        ) {
            throw error
        } catch (
            error: IOException,
        ) {
            currentCoroutineContext().ensureActive()
            val resolvedError = XmaxError(
                code = XmaxErrorCode.NETWORK_ERROR,
                message = "HTTP request failed: ${ErrorMessageFormatter.format(error)}",
                cause = error,
            )
            ApiLogger.logFailure(
                method,
                path,
                resolvedError,
                durationMs(startedAt),
            )
            throw resolvedError
        } catch (
            error: Throwable,
        ) {
            currentCoroutineContext().ensureActive()
            val resolvedError = XmaxError(
                code = XmaxErrorCode.NETWORK_ERROR,
                message = "HTTP request failed: ${ErrorMessageFormatter.format(error)}",
                cause = error,
            )
            ApiLogger.logFailure(
                method,
                path,
                resolvedError,
                durationMs(startedAt),
            )
            throw resolvedError
        }

        currentCoroutineContext().ensureActive()
        return try {
            parseEnvelope(response).also {
                ApiLogger.logResponse(
                    method = method,
                    path = path,
                    statusCode = response.statusCode,
                    bodyByteCount = response.body.size,
                    durationMs = durationMs(startedAt),
                    successful = true,
                )
            }
        } catch (
            error: Throwable,
        ) {
            ApiLogger.logResponse(
                method = method,
                path = path,
                statusCode = response.statusCode,
                bodyByteCount = response.body.size,
                durationMs = durationMs(startedAt),
                successful = false,
            )
            throw error
        }
    }

    /**
     * Validates API credentials, the base URL, and request timeout.
     */
    private fun validateConfiguration() {
        if (apiKey.isEmpty()) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_API_KEY,
                message = "API key cannot be empty",
            )
        }

        val uri = parseBaseUri()
        if (!uri.scheme.equals(
            "https",
            ignoreCase = true,
        ) ||
            uri.host.isNullOrBlank() ||
            timeoutMs <= 0
        ) {
            throw invalidConfigurationError()
        }
    }

    /**
     * Resolves a relative path to a same-origin API URL, rejecting paths outside the base.
     *
     * @param path The request path relative to the API base URL.
     * @return The URL used for the network request.
     */
    private fun resolveUrl(
        path: String,
    ): URL {
        val normalizedPath = path.trim()
        if (normalizedPath.isEmpty() ||
            normalizedPath.contains("://") ||
            normalizedPath.startsWith("//")
        ) {
            throw invalidPathError()
        }

        val base = baseUrl.trim().trimEnd('/')
        val relativePath = if (normalizedPath.startsWith('/')) {
            normalizedPath
        } else {
            "/$normalizedPath"
        }
        val resolved = try {
            URI(base + relativePath)
        } catch (
            error: Exception,
        ) {
            throw invalidPathError(error)
        }
        val baseUri = parseBaseUri()
        if (!resolved.scheme.equals(
            baseUri.scheme,
            ignoreCase = true,
        ) ||
            !resolved.host.equals(
                baseUri.host,
                ignoreCase = true,
            )
        ) {
            throw invalidPathError()
        }

        return try {
            resolved.toURL()
        } catch (
            error: Exception,
        ) {
            throw invalidPathError(error)
        }
    }

    /**
     * Parses and validates the API base URL.
     *
     * @return The validated base URI.
     */
    private fun parseBaseUri(): URI = try {
        URI(baseUrl.trim())
    } catch (
        error: Exception,
    ) {
        throw invalidConfigurationError(error)
    }

    /**
     * Decodes the API response envelope and converts server failures to SDK errors.
     *
     * @param response The HTTP status, headers, and body.
     * @return JSON for the current operation.
     */
    private fun parseEnvelope(
        response: ApiHttpResponse,
    ): JSONObject {
        val envelope = try {
            JSONObject(response.body.toString(Charsets.UTF_8))
        } catch (
            error: JSONException,
        ) {
            throw XmaxError(
                code = XmaxErrorCode.API_ERROR,
                message = "Server returned invalid JSON",
                httpStatus = response.statusCode,
                cause = error,
            )
        }

        if (response.statusCode !in 200..299 || envelope.opt("success") != true) {
            throw XmaxError(
                code = XmaxErrorCode.API_ERROR,
                message = envelope.optString("message")
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?: "Xmax API request failed",
                apiCode = envelope.opt("code") as? Int,
                httpStatus = response.statusCode,
            )
        }

        return envelope.optJSONObject("data") ?: throw XmaxError(
            code = XmaxErrorCode.API_ERROR,
            message = "Server returned invalid response data",
            httpStatus = response.statusCode,
        )
    }

    /**
     * Creates an invalid API configuration error.
     *
     * @param cause The underlying exception that caused this error.
     */
    private fun invalidConfigurationError(
        cause: Throwable? = null,
    ): XmaxError = XmaxError(
        code = XmaxErrorCode.INVALID_CONFIGURATION,
        message = "API service configuration is invalid",
        cause = cause,
    )

    /**
     * Creates an invalid API path error.
     *
     * @param cause The underlying exception that caused this error.
     */
    private fun invalidPathError(
        cause: Throwable? = null,
    ): XmaxError = XmaxError(
        code = XmaxErrorCode.INVALID_CONFIGURATION,
        message = "API request path is invalid",
        cause = cause,
    )

    /**
     * Computes elapsed milliseconds from a monotonic start timestamp.
     *
     * @param startedAt The monotonic operation start time, in nanoseconds.
     * @return The elapsed time, in milliseconds.
     */
    private fun durationMs(
        startedAt: Long,
    ): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    /**
     * The default API timeout configuration.
     */
    private companion object {

        /**
         * The default request timeout, in milliseconds.
         */
        const val DEFAULT_TIMEOUT_MS = 15_000
    }
}
