//
//  ApiLogger.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.network

import ai.xmax.sdk.ErrorMessageFormatter
import ai.xmax.sdk.XmaxLogger

/**
 * API diagnostics excluding credentials and response bodies.
 */
internal object ApiLogger {

    /**
     * Logs request paths, response status, and duration without credentials or response content.
     *
     * @param method The HTTP method.
     * @param path The request path relative to the API base URL.
     * @param statusCode The HTTP response status.
     * @param bodyByteCount The response body size, in bytes.
     * @param durationMs The request duration, in milliseconds.
     * @param successful Whether the request succeeded.
     */
    fun logResponse(
        method: ApiMethod,
        path: String,
        statusCode: Int,
        bodyByteCount: Int,
        durationMs: Long,
        successful: Boolean,
    ) {
        val message = responseMessage(
            method,
            path,
            statusCode,
            bodyByteCount,
            durationMs,
        )
        if (successful) {
            XmaxLogger.api.debug(message = { message })
        } else {
            XmaxLogger.api.error(message = { message })
        }
    }

    /**
     * Logs failed request paths, duration, and cause without credentials.
     *
     * @param method The HTTP method.
     * @param path The request path relative to the API base URL.
     * @param error The operation failure or SDK error.
     * @param durationMs The request duration, in milliseconds.
     */
    fun logFailure(
        method: ApiMethod,
        path: String,
        error: Throwable,
        durationMs: Long,
    ) {
        XmaxLogger.api.error(
            message = {
                "${method.wireValue} $path 失败 (Request Failed)\n" +
                    "├─ ${XmaxLogger.localized(
                        "耗时：",
                        "Duration: ",
                    )}$durationMs ms\n" +
                    "└─ ${XmaxLogger.localized(
                        "原因：",
                        "Reason: ",
                    )}${ErrorMessageFormatter.format(error)}"
            },
        )
    }

    /**
     * Formats response status, body size, and request timing.
     *
     * @param method The HTTP method.
     * @param path The request path relative to the API base URL.
     * @param statusCode The HTTP response status.
     * @param bodyByteCount The response body size, in bytes.
     * @param durationMs The request duration, in milliseconds.
     * @return The request status and duration log text.
     */
    fun responseMessage(
        method: ApiMethod,
        path: String,
        statusCode: Int,
        bodyByteCount: Int,
        durationMs: Long,
    ): String = "${method.wireValue} $path\n" +
        "├─ ${XmaxLogger.localized(
            "状态：",
            "Status: ",
        )}$statusCode\n" +
        "├─ ${XmaxLogger.localized(
            "耗时：",
            "Duration: ",
        )}$durationMs ms\n" +
        "└─ ${XmaxLogger.localized(
            "响应：",
            "Response: ",
        )}$bodyByteCount bytes"
}
