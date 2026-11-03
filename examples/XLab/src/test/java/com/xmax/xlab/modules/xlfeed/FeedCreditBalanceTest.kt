//
//  FeedCreditBalanceTest.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlfeed

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedCreditBalanceTest {

    @Test
    fun `polls immediately then every five seconds and stops on cancellation`() = runTest {
        val requests = mutableListOf<Long>()
        val states = mutableListOf<CreditBalanceState>()
        val job = backgroundScope.launch {
            pollCreditBalance(
                initialBalance = null,
                fetchBalance = { requests += testScheduler.currentTime; 12.5 },
                onUpdate = states::add,
            )
        }

        runCurrent()
        assertEquals(listOf(0L), requests)
        assertEquals(CreditBalanceState(isLoading = true), states.first())
        assertEquals(CreditBalanceState(balance = 12.5), states.last())
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, requests.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 5_000L), requests)

        job.cancelAndJoin()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, requests.size)
    }

    @Test
    fun `slow requests never overlap`() = runTest {
        val response = CompletableDeferred<Double>()
        var requests = 0
        val job = backgroundScope.launch {
            pollCreditBalance(
                initialBalance = null,
                fetchBalance = { requests += 1; response.await() },
                onUpdate = {},
            )
        }

        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(1, requests)
        response.complete(100.0)
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, requests)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, requests)
        job.cancelAndJoin()
    }

    @Test
    fun `failed refresh retains last balance and next poll recovers`() = runTest {
        var requests = 0
        val states = mutableListOf<CreditBalanceState>()
        val job = backgroundScope.launch {
            pollCreditBalance(
                initialBalance = 10.5,
                fetchBalance = {
                    requests += 1
                    if (requests == 1) throw IOException("offline")
                    8.25
                },
                onUpdate = states::add,
            )
        }

        runCurrent()
        assertEquals(CreditBalanceState(balance = 10.5, refreshFailed = true), states.last())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(CreditBalanceState(balance = 8.25), states.last())
        job.cancelAndJoin()
    }

    @Test
    fun `cancelled poll ignores a late response`() = runTest {
        val response = CompletableDeferred<Double>()
        val states = mutableListOf<CreditBalanceState>()
        val job = backgroundScope.launch {
            pollCreditBalance(
                initialBalance = null,
                fetchBalance = { withContext(NonCancellable) { response.await() } },
                onUpdate = states::add,
            )
        }

        runCurrent()
        job.cancel()
        response.complete(999.0)
        runCurrent()
        job.join()
        assertTrue(states.all { it.balance == null && !it.refreshFailed })
        assertEquals(1, states.size)
    }

    @Test
    fun `balance formatting distinguishes unavailable zero and fractional credits`() {
        assertEquals("—", formatCreditBalance(null))
        assertEquals("0", formatCreditBalance(0.0))
        assertEquals("0.25", formatCreditBalance(0.25))
        assertEquals("1,250.75", formatCreditBalance(1_250.75))
        assertEquals("12", formatCreditBalance(12.0))
    }
}
