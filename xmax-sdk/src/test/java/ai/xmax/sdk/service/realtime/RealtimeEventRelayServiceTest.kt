//
//  RealtimeEventRelayServiceTest.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.realtime

import ai.xmax.sdk.XmaxEnvironment
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.service.network.ApiHttpRequest
import ai.xmax.sdk.service.network.ApiHttpResponse
import ai.xmax.sdk.service.network.ApiMethod
import ai.xmax.sdk.service.network.ApiService
import ai.xmax.sdk.service.network.ApiTransport
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeEventRelayServiceTest {

    @Test
    fun `relay uses the authenticated realtime endpoint and accepts duplicate receipts`() = runTest {
        for (environment in XmaxEnvironment.entries) {
            for (accepted in listOf(false, true)) {
                val requests = mutableListOf<ApiHttpRequest>()
                val service = service(environment, ApiTransport {
                    requests += it
                    response(JSONObject().put("xid", "command-1").put("accepted", accepted))
                })
                service.relay("session-1", "model-instance-1", command())

                val request = requests.single()
                val origin = if (environment == XmaxEnvironment.CHINA) "https://cloud.xmax.22duck.cn" else "https://api.xmax.ai"
                assertEquals("$origin/open/api/v1/client-event/relay", request.url.toString())
                assertEquals(ApiMethod.POST, request.method)
                assertEquals("relay-key", request.headers["X-Api-Key"])
                val body = JSONObject(String(request.body!!, Charsets.UTF_8))
                assertEquals(3, body.length())
                assertEquals("session-1", body.getString("sessionId"))
                assertEquals("model-instance-1", body.getString("modelId"))
                assertEquals(command().toString(), body.getJSONObject("data").toString())
            }
        }
    }

    @Test
    fun `relay rejects missing mismatched and incorrectly typed receipts without retries`() = runTest {
        val receipts = listOf(
            JSONObject(),
            JSONObject().put("xid", "wrong-command").put("accepted", true),
            JSONObject().put("xid", 123).put("accepted", true),
            JSONObject().put("xid", "command-1"),
            JSONObject().put("xid", "command-1").put("accepted", "false"),
            JSONObject().put("xid", "command-1").put("accepted", JSONObject.NULL),
        )
        for (receipt in receipts) {
            var count = 0
            val service = service(XmaxEnvironment.CHINA, ApiTransport {
                count++
                response(receipt)
            })
            val failure = runCatching { service.relay("session", "model", command()) }.exceptionOrNull()
            assertEquals(XmaxErrorCode.API_ERROR, (failure as XmaxError).code)
            assertEquals(1, count)
        }
    }

    @Test
    fun `relay preserves HTTP rejection and network failures without retries`() = runTest {
        for (networkFailure in listOf(false, true)) {
            var count = 0
            val service = service(XmaxEnvironment.GLOBAL, ApiTransport {
                count++
                if (networkFailure) throw IOException("offline")
                ApiHttpResponse(
                    503,
                    """{"success":false,"code":46015,"message":"relay unavailable"}""".toByteArray(),
                )
            })
            val error = runCatching { service.relay("session", "model", command()) }.exceptionOrNull() as XmaxError
            assertEquals(if (networkFailure) XmaxErrorCode.NETWORK_ERROR else XmaxErrorCode.API_ERROR, error.code)
            if (!networkFailure) {
                assertEquals(46015, error.apiCode)
                assertEquals(503, error.httpStatus)
            }
            assertEquals(1, count)
        }
    }

    @Test
    fun `command cancellation reaches the in flight HTTP transport`() = runTest {
        var cancelled = false
        val service = service(XmaxEnvironment.CHINA, ApiTransport {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        })
        val job = backgroundScope.launch { service.relay("session", "model", command()) }
        runCurrent()
        assertFalse(cancelled)
        job.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertTrue(job.isCancelled)
    }

    private fun service(
        environment: XmaxEnvironment,
        transport: ApiTransport,
    ) = RealtimeEventRelayService(ApiService(
        apiKey = " relay-key ",
        baseUrl = environment.realtimeApiBaseUrl,
        transport = transport,
    ))

    private fun response(data: JSONObject) = ApiHttpResponse(
        200,
        JSONObject().put("success", true).put("code", 200).put("data", data).toString().toByteArray(),
    )

    private fun command() = JSONObject()
        .put("event", "start")
        .put("xid", "command-1")
        .put("uid", "task-1")
        .put("params", JSONObject().put("prompt", "a cat"))
}
