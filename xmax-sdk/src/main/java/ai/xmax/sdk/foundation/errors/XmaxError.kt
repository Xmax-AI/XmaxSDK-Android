//
//  XmaxError.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import kotlinx.coroutines.CancellationException

/**
 * The SDK's public error codes.
 */
public enum class XmaxErrorCode {

    /**
     * The API key is missing or fails local validation.
     */
    INVALID_API_KEY,

    /**
     * The configuration or invocation state is invalid.
     */
    INVALID_CONFIGURATION,

    /**
     * An otherwise unclassified SDK failure.
     */
    INTERNAL_ERROR,

    /**
     * A network connection or transfer failure.
     */
    NETWORK_ERROR,

    /**
     * A failed server API response.
     */
    API_ERROR,

    /**
     * A realtime session creation, renewal, or state failure.
     */
    SESSION_ERROR,

    /**
     * An RTC engine, room, or media operation failure.
     */
    RTC_ERROR,

    /**
     * A media reading, decoding, or processing failure.
     */
    MEDIA_ERROR,

    /**
     * Your app has not obtained camera permission.
     */
    CAMERA_PERMISSION_DENIED,

    /**
     * Your app has not obtained recording permission.
     */
    MICROPHONE_PERMISSION_DENIED,

    /**
     * A media upload failure.
     */
    UPLOAD_ERROR,

    /**
     * A media download failure.
     */
    DOWNLOAD_ERROR,

    /**
     * The image failed the server's content safety check.
     */
    UNSAFE_IMAGE,

    /**
     * The operation was cancelled.
     */
    CANCELLED,

    /**
     * The operation exceeded its allowed time.
     */
    TIMEOUT,
}

/**
 * An SDK failure; the active operation determines cleanup scope independently of the error.
 *
 * @param message The message to send or log.
 * @param cause The underlying exception that caused this error.
 */
public class XmaxError(
    /**
     * The SDK error category.
     */
    public val code: XmaxErrorCode,

    message: String,

    /**
     * The server business error code, or null if absent.
     */
    public val apiCode: Int? = null,

    /**
     * The HTTP status code, or null without a valid response.
     */
    public val httpStatus: Int? = null,

    cause: Throwable? = null,
) : Exception(
    message,
    cause,
) {

    /**
     * Converts an underlying exception into an SDK error.
     */
    public companion object {

        /**
         * Existing SDK errors are retained; coroutine cancellation propagates without becoming a business
         * failure.
         *
         * @param error The operation failure or SDK error.
         */
        public fun from(
            error: Throwable,
        ): XmaxError = when (error) {
            is CancellationException -> throw error
            is XmaxError -> error
            else -> XmaxError(
                XmaxErrorCode.INTERNAL_ERROR,
                ErrorMessageFormatter.format(error),
                cause = error,
            )
        }
    }
}
