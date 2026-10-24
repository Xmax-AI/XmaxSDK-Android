//
//  XmaxConfiguration.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * The credentials, service environment, and logging options for an Xmax client.
 *
 * @param apiKey The API key issued by the developer platform; do not log or expose it.
 */
public class XmaxConfiguration @JvmOverloads constructor(
    apiKey: String,

    /**
     * Enabled log categories; disabled by default. Creating a client updates the shared logger.
     */
    public val loggerOptions: XmaxLoggerOption = XmaxLoggerOption.none,
) {

    /**
     * The service environment selected for this client.
     */
    private var configuredEnvironment: XmaxEnvironment = XmaxEnvironment.CHINA

    /**
     * The SDK service environment; defaults to China.
     */
    public val environment: XmaxEnvironment
        get() = configuredEnvironment

    /**
     * The API key used to access Xmax services.
     */
    public val apiKey: String = apiKey.trim()

    /**
     * Creates a client configuration.
     *
     * @param apiKey The API key issued by the developer platform; do not log or expose it.
     * @param environment The service environment, which also selects the log language.
     * @param loggerOptions Enabled SDK log categories; defaults to `XmaxLoggerOption.none`.
     */
    @JvmOverloads
    public constructor(
        apiKey: String,
        environment: XmaxEnvironment,
        loggerOptions: XmaxLoggerOption = XmaxLoggerOption.none,
    ) : this(
        apiKey,
        loggerOptions,
    ) {
        configuredEnvironment = environment
    }

    /**
     * Verifies that the API key is not empty.
     *
     * Credential validity is checked by the server when an authenticated request is made.
     */
    public fun validate() {
        if (apiKey.isEmpty()) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_API_KEY,
                message = "API key cannot be empty",
            )
        }
    }

    /**
     * Returns a configuration description with the API key redacted.
     */
    override fun toString(): String =
        "XmaxConfiguration(apiKey=***, environment=$environment, loggerOptions=${loggerOptions.rawValue})"
}
