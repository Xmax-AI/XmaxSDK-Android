//
//  ApiServicing.kt
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
import org.json.JSONObject

/**
 * HTTP methods supported by the Xmax API.
 */
internal enum class ApiMethod(
    /**
     * The protocol string value.
     */
    internal val wireValue: String,
) {

    /**
     * Reads a server resource.
     */
    GET("GET"),

    /**
     * Submits a business request.
     */
    POST("POST"),

    /**
     * Updates a server resource.
     */
    PUT("PUT"),

    /**
     * Deletes a server resource.
     */
    DELETE("DELETE"),
}

/**
 * The basic Xmax API request capabilities.
 */
internal interface ApiServicing {

    /**
     * Sends a request and returns business data from the response envelope.
     *
     * @param method The HTTP method.
     * @param path The request path relative to the API base URL.
     * @param body The optional JSON request body; null sends no body.
     * @return JSON for the current operation.
     */
    suspend fun request(
        method: ApiMethod,
        path: String,
        body: JSONObject? = null,
    ): JSONObject

    /**
     * Sends a request and decodes business data into an internal model.
     *
     * @param method The HTTP method.
     * @param path The request path relative to the API base URL.
     * @param body The optional JSON request body; null sends no body.
     * @param decode Converts response JSON to the requested type.
     * @return The business response produced by the decoder.
     */
    suspend fun <Response> request(
        method: ApiMethod,
        path: String,
        body: JSONObject? = null,
        decode: (JSONObject) -> Response,
    ): Response {
        val payload = request(
            method,
            path,
            body,
        )
        return try {
            decode(payload)
        } catch (
            error: XmaxError,
        ) {
            throw error
        } catch (
            error: Throwable,
        ) {
            throw XmaxError(
                code = XmaxErrorCode.API_ERROR,
                message = "Server returned invalid response data: ${ErrorMessageFormatter.format(error)}",
                cause = error,
            )
        }
    }

    /**
     * Sends GET and returns response business data.
     *
     * @param path The request path relative to the API base URL.
     * @return JSON for the current operation.
     */
    suspend fun get(
        path: String,
    ): JSONObject = request(
        ApiMethod.GET,
        path,
    )

    /**
     * Sends GET and returns response business data.
     *
     * @param path The request path relative to the API base URL.
     * @param decode Converts response JSON to the requested type.
     * @return The business response produced by the decoder.
     */
    suspend fun <Response> get(
        path: String,
        decode: (JSONObject) -> Response,
    ): Response = request(
        ApiMethod.GET,
        path,
        decode = decode,
    )

    /**
     * Sends POST and returns response business data.
     *
     * @param path The request path relative to the API base URL.
     * @return JSON for the current operation.
     */
    suspend fun post(
        path: String,
    ): JSONObject = request(
        ApiMethod.POST,
        path,
    )

    /**
     * Sends POST and returns response business data.
     *
     * @param path The request path relative to the API base URL.
     * @param body The optional JSON request body; null sends no body.
     * @return JSON for the current operation.
     */
    suspend fun post(
        path: String,
        body: JSONObject,
    ): JSONObject =
        request(
            ApiMethod.POST,
            path,
            body,
        )

    /**
     * Sends POST and returns response business data.
     *
     * @param path The request path relative to the API base URL.
     * @param body The optional JSON request body; null sends no body.
     * @param decode Converts response JSON to the requested type.
     * @return The business response produced by the decoder.
     */
    suspend fun <Response> post(
        path: String,
        body: JSONObject? = null,
        decode: (JSONObject) -> Response,
    ): Response = request(
        ApiMethod.POST,
        path,
        body,
        decode,
    )

    /**
     * Sends PUT and returns response business data.
     *
     * @param path The request path relative to the API base URL.
     * @return JSON for the current operation.
     */
    suspend fun put(
        path: String,
    ): JSONObject = request(
        ApiMethod.PUT,
        path,
    )

    /**
     * Sends PUT and returns response business data.
     *
     * @param path The request path relative to the API base URL.
     * @param body The optional JSON request body; null sends no body.
     * @return JSON for the current operation.
     */
    suspend fun put(
        path: String,
        body: JSONObject,
    ): JSONObject =
        request(
            ApiMethod.PUT,
            path,
            body,
        )

    /**
     * Sends PUT and returns response business data.
     *
     * @param path The request path relative to the API base URL.
     * @param body The optional JSON request body; null sends no body.
     * @param decode Converts response JSON to the requested type.
     * @return The business response produced by the decoder.
     */
    suspend fun <Response> put(
        path: String,
        body: JSONObject? = null,
        decode: (JSONObject) -> Response,
    ): Response = request(
        ApiMethod.PUT,
        path,
        body,
        decode,
    )

    /**
     * Sends DELETE and returns response business data.
     *
     * @param path The request path relative to the API base URL.
     * @return JSON for the current operation.
     */
    suspend fun delete(
        path: String,
    ): JSONObject = request(
        ApiMethod.DELETE,
        path,
    )

    /**
     * Sends DELETE and returns response business data.
     *
     * @param path The request path relative to the API base URL.
     * @param decode Converts response JSON to the requested type.
     * @return The business response produced by the decoder.
     */
    suspend fun <Response> delete(
        path: String,
        decode: (JSONObject) -> Response,
    ): Response = request(
        ApiMethod.DELETE,
        path,
        decode = decode,
    )
}
