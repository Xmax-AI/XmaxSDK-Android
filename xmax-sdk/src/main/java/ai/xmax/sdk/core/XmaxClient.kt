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
import ai.xmax.sdk.service.network.ApiServicing
import ai.xmax.sdk.service.media.MediaService
import ai.xmax.sdk.service.storage.StorageService

/**
 * A client that provides access to realtime generation, storage, media services, and account credit queries.
 *
 * @param context An Android context; only its application context is retained. Required for managers that use
 * Android resources.
 */
public class XmaxClient internal constructor(
    /**
     * SDK credentials, service environment, and logging options.
     */
    public val configuration: XmaxConfiguration,

    context: Context?,

    /**
     * The service used for storage credentials, media safety checks, and account credit queries.
     */
    private val apiService: ApiServicing,
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
     * Creates an SDK client.
     *
     * @param configuration SDK credentials, service environment, and logging options.
     * @param context An Android context; only its application context is retained. Required for managers
     * that use Android resources.
     */
    public constructor(
        configuration: XmaxConfiguration,
        context: Context? = null,
    ) : this(
        configuration,
        context,
        ApiService(
            apiKey = configuration.apiKey,
            baseUrl = configuration.environment.apiBaseUrl,
        ),
    )

    /**
     * Creates an SDK client.
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
     * Creates a manager for uploading and downloading media files.
     *
     * Requires a nonempty API key and an Android context supplied when creating the client.
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
     * Fetches the remaining credits for the account owning this client's API key.
     *
     * Each call fetches the current server balance using the client's configured service environment.
     *
     * @return The remaining credits, including fractional credits.
     * @throws XmaxError If credentials are invalid, the request fails, or the response is invalid.
     * @throws kotlinx.coroutines.CancellationException If the request is cancelled.
     */
    public suspend fun getCreditBalance(): XmaxCreditBalance {
        configuration.validate()

        return apiService.get("/balance") { payload ->
            val value = (payload.opt("currentBalance") as? Number)?.toDouble()
            if (value == null || !value.isFinite() || value < 0) {
                throw XmaxError(
                    code = XmaxErrorCode.API_ERROR,
                    message = "Credit balance must be a finite, nonnegative number",
                )
            }

            XmaxCreditBalance(value)
        }
    }

    /**
     * Creates a manager for local media inputs and realtime generation.
     *
     * Requires an Android context supplied when creating the client. Each manager owns its media
     * and connection lifecycle; the client environment selects the realtime service endpoint.
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
     * Creates a service for media sizing queries.
     *
     * @param model The model whose input constraints apply; defaults to `RealtimeModel.X2_1`.
     * @return A media sizing service for the specified model.
     */
    @JvmOverloads
    public fun createMediaService(
        model: RealtimeModel = RealtimeModel.X2_1,
    ): MediaServicing = MediaService(model)
}
