//
//  XmaxClient.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import android.content.Context
import ai.xmax.sdk.foundation.storage.StorageManager
import ai.xmax.sdk.service.network.ApiService
import ai.xmax.sdk.service.media.MediaService
import ai.xmax.sdk.service.storage.StorageService

/**
 * The SDK entry point, sharing client configuration and API access across services.
 *
 * Client construction configures logging and retains the application context without starting network or
 * media operations.
 * Realtime media, transport, and rendering components are created on first use.
 *
 * @param context An Android context; only its application context is retained. Required for managers that use
 * Android resources.
 */
public class XmaxClient(
    /**
     * SDK credentials, service environment, and logging options.
     */
    public val configuration: XmaxConfiguration,

    context: Context? = null,
) {

    init {
        XmaxLogger.configure(
            configuration.loggerOptions,
            language = if (configuration.environment == XmaxEnvironment.CHINA) {
                XmaxLogger.Language.CHINESE
            } else {
                XmaxLogger.Language.ENGLISH
            },
        )
    }

    /**
     * The Android application context.
     */
    private val applicationContext: Context? = context?.applicationContext

    /**
     * The shared API service.
     */
    private val apiService = ApiService(
        apiKey = configuration.apiKey,
        baseUrl = configuration.environment.apiBaseUrl,
    )

    /**
     * Creates a client with the Android context first.
     *
     * @param context An Android context; only its application context is retained. Required for managers that
     * use Android resources.
     * @param configuration SDK credentials, service environment, and logging options.
     */
    public constructor(
        context: Context,
        configuration: XmaxConfiguration,
    ) : this(
        configuration,
        context,
    )

    /**
     * Creates a storage manager; requires a nonempty API key and a client context.
     *
     * @return A manager for image and video uploads and downloads.
     */
    public fun createStorageManager(): XmaxStorageManaging {
        configuration.validate()
        val context = applicationContext ?: throw XmaxError(
            code = XmaxErrorCode.INVALID_CONFIGURATION,
            message = "Android Context is required to create a storage manager",
        )

        return XmaxStorageManager(
            StorageService(
                apiService = apiService,
                storageManager = StorageManager(context),
            ),
        )
    }

    /**
     * Creates a realtime manager with its own lifecycle; requires a client context.
     * Session creation, heartbeats, and closure use the environment's realtime endpoint.
     *
     * @param options The model and realtime configuration.
     * @return A realtime manager with independent media and connection ownership.
     */
    public fun createRealtimeManager(
        options: RealtimeConfiguration,
    ): XmaxRealtimeManaging {
        val context = applicationContext ?: throw XmaxError(
            code = XmaxErrorCode.INVALID_CONFIGURATION,
            message = "Android Context is required to create a realtime manager",
        )
        val realtimeApiService = ApiService(
            apiKey = configuration.apiKey,
            baseUrl = configuration.environment.realtimeApiBaseUrl,
        )

        return XmaxRealtimeManager(
            options,
            context,
            realtimeApiService,
        )
    }

    /**
     * Creates a model-specific media sizing service without requiring a context or network access.
     *
     * @param model The model whose input constraints apply; defaults to `RealtimeModel.X2_1`.
     * @return A media sizing service for the specified model.
     */
    @JvmOverloads
    public fun createMediaService(
        model: RealtimeModel = RealtimeModel.X2_1,
    ): MediaServicing = MediaService(model)
}
