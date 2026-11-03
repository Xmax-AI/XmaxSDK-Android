//
//  XmaxClientCreditBalanceTest.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.service.network.ApiHttpRequest
import ai.xmax.sdk.service.network.ApiHttpResponse
import ai.xmax.sdk.service.network.ApiMethod
import ai.xmax.sdk.service.network.ApiService
import ai.xmax.sdk.service.network.ApiTransport
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

public class XmaxClientCreditBalanceTest {

    @Test
    public fun `query uses API key and general service environment without Android context`() = runTest {
        val requests = mutableListOf<ApiHttpRequest>()
        val transport = ApiTransport { request ->
            requests += request
            response("""{"success":true,"code":200,"data":{"currentBalance":1250.75}}""")
        }

        for (environment in XmaxEnvironment.entries) {
            val client = makeClient(
                transport = transport,
                apiKey = "  balance-key\n",
                environment = environment,
            )

            assertEquals(
                1250.75,
                client.getCreditBalance().currentBalance,
                0.0,
            )
        }

        assertEquals(
            listOf(
                "https://cloud.xmax.22duck.cn/open/api/v1/balance",
                "https://api.xmax.cloud/open/api/v1/balance",
            ),
            requests.map { it.url.toString() },
        )
        requests.forEach { request ->
            assertEquals(
                ApiMethod.GET,
                request.method,
            )
            assertEquals(
                "balance-key",
                request.headers["X-Api-Key"],
            )
            assertNull(request.body)
        }
    }

    @Test
    public fun `zero and fractional credits are preserved`() = runTest {
        for (value in listOf(0.0, 0.25, 1_250.75)) {
            val client = makeClient(ApiTransport {
                response("""{"success":true,"data":{"currentBalance":$value}}""")
            })

            assertEquals(
                value,
                client.getCreditBalance().currentBalance,
                0.0,
            )
        }
    }

    @Test
    public fun `each invocation reads the latest server balance`() = runTest {
        var calls = 0
        val client = makeClient(ApiTransport {
            calls += 1
            val value = if (calls == 1) 100.0 else 25.5
            response("""{"success":true,"data":{"currentBalance":$value}}""")
        })

        val initial = client.getCreditBalance()
        val refreshed = client.getCreditBalance()

        assertEquals(
            100.0,
            initial.currentBalance,
            0.0,
        )
        assertEquals(
            25.5,
            refreshed.currentBalance,
            0.0,
        )
        assertEquals(
            2,
            calls,
        )
    }

    @Test
    public fun `empty API key fails before sending a request`() = runTest {
        var requestSent = false
        val client = makeClient(
            transport = ApiTransport {
                requestSent = true
                error("An empty API key must not reach the transport")
            },
            apiKey = " \n ",
        )

        val error = expectXmaxError { client.getCreditBalance() }

        assertEquals(
            XmaxErrorCode.INVALID_API_KEY,
            error.code,
        )
        assertTrue(!requestSent)
    }

    @Test
    public fun `missing malformed negative and nonfinite balances fail instead of becoming zero`() = runTest {
        val payloads = listOf(
            "null",
            "[]",
            "{}",
            """{"currentBalance":null}""",
            """{"currentBalance":true}""",
            """{"currentBalance":"1250.75"}""",
            """{"currentBalance":-1}""",
            """{"currentBalance":1e400}""",
            """{"currentBalance":NaN}""",
            """{"currentBalance":Infinity}""",
            """{"currentBalance":[]}""",
            """{"currentBalance":{}}""",
        )

        for (payload in payloads) {
            val client = makeClient(ApiTransport {
                response("""{"success":true,"data":$payload}""")
            })

            val error = expectXmaxError { client.getCreditBalance() }

            assertEquals(
                payload,
                XmaxErrorCode.API_ERROR,
                error.code,
            )
        }
    }

    @Test
    public fun `invalid response JSON remains an API error`() = runTest {
        val client = makeClient(ApiTransport { response("not-json") })

        val error = expectXmaxError { client.getCreditBalance() }

        assertEquals(
            XmaxErrorCode.API_ERROR,
            error.code,
        )
        assertEquals(
            200,
            error.httpStatus,
        )
    }

    @Test
    public fun `HTTP and business errors preserve server details`() = runTest {
        for (status in listOf(200, 401, 403, 500)) {
            val client = makeClient(ApiTransport {
                response(
                    body = """{"success":false,"code":46002,"message":"Invalid API key","data":null}""",
                    statusCode = status,
                )
            })

            val error = expectXmaxError { client.getCreditBalance() }

            assertEquals(
                XmaxErrorCode.API_ERROR,
                error.code,
            )
            assertEquals(
                "Invalid API key",
                error.message,
            )
            assertEquals(
                46002,
                error.apiCode,
            )
            assertEquals(
                status,
                error.httpStatus,
            )
        }
    }

    @Test
    public fun `transport failure remains a network error`() = runTest {
        val failure = IOException("offline")
        val client = makeClient(ApiTransport { throw failure })

        val error = expectXmaxError { client.getCreditBalance() }

        assertEquals(
            XmaxErrorCode.NETWORK_ERROR,
            error.code,
        )
        assertSame(
            failure,
            error.cause,
        )
    }

    @Test
    public fun `transport cancellation is preserved`() = runTest {
        val cancellation = CancellationException("cancelled")
        val client = makeClient(ApiTransport { throw cancellation })

        val error = try {
            client.getCreditBalance()
            throw AssertionError("Expected CancellationException")
        } catch (error: CancellationException) {
            error
        }

        assertSame(
            cancellation,
            error,
        )
    }

    @Test
    public fun `cancelling an in-flight query cancels transport without returning a balance`() = runTest {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val client = makeClient(ApiTransport {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                finished.complete(Unit)
            }
        })

        val query = async { client.getCreditBalance() }
        started.await()
        query.cancel()
        query.join()

        assertTrue(query.isCancelled)
        assertTrue(finished.isCompleted)
    }

    private fun makeClient(
        transport: ApiTransport,
        apiKey: String = "balance-key",
        environment: XmaxEnvironment = XmaxEnvironment.CHINA,
    ): XmaxClient {
        val configuration = XmaxConfiguration(
            apiKey = apiKey,
            environment = environment,
        )
        return XmaxClient(
            configuration = configuration,
            context = null,
            apiService = ApiService(
                apiKey = configuration.apiKey,
                baseUrl = configuration.environment.apiBaseUrl,
                transport = transport,
            ),
        )
    }

    private fun response(
        body: String,
        statusCode: Int = 200,
    ): ApiHttpResponse = ApiHttpResponse(
        statusCode = statusCode,
        body = body.toByteArray(Charsets.UTF_8),
    )

    private suspend fun expectXmaxError(
        block: suspend () -> Unit,
    ): XmaxError = try {
        block()
        throw AssertionError("Expected XmaxError")
    } catch (error: XmaxError) {
        error
    }
}
