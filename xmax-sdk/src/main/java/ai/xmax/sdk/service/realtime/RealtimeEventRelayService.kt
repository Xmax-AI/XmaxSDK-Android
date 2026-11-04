//
//  RealtimeEventRelayService.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.realtime

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.service.network.ApiServicing
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/**
 * Submits generation controls through HTTP independently of RTC delivery and model acknowledgments.
 */
internal class RealtimeEventRelayService(
    /**
     * The authenticated realtime API service for the selected environment.
     */
    private val apiService: ApiServicing,
) {

    /**
     * Submits one complete command and accepts both new and previously accepted receipts.
     *
     * Cancellation stops the local request without withdrawing commands already accepted by the server.
     *
     * @param sessionId The generation session receiving the command.
     * @param modelId The assigned model instance returned as `modelUid`.
     * @param data The complete command, including its original `xid`.
     * @throws XmaxError If the request fails or its receipt is invalid.
     * @throws kotlinx.coroutines.CancellationException If the command is cancelled.
     */
    suspend fun relay(
        sessionId: String,
        modelId: String,
        data: JSONObject,
    ) {
        currentCoroutineContext().ensureActive()
        val startedAt = System.nanoTime()
        val response = apiService.post(
            path = "/client-event/relay",
            body = JSONObject()
                .put("sessionId", sessionId)
                .put("modelId", modelId)
                .put("data", data),
        )
        currentCoroutineContext().ensureActive()

        val xid = response.opt("xid") as? String
        if (xid == null || xid != data.opt("xid") || response.opt("accepted") !is Boolean) {
            throw XmaxError(
                code = XmaxErrorCode.API_ERROR,
                message = "Server returned invalid event relay response",
            )
        }

        XmaxLogger.room.info(message = {
            "HTTP 信令转发 (HTTP Signaling Relay)\n" +
                "├─ event: ${data.optString("event")}, xid: $xid\n" +
                "├─ sessionId: $sessionId, modelId: $modelId\n" +
                "└─ accepted: ${response.getBoolean("accepted")}, elapsed: ${(System.nanoTime() - startedAt) / 1_000_000} ms"
        })
    }
}
