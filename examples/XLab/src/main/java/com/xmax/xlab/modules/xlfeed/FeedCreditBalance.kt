//
//  FeedCreditBalance.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlfeed

import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal data class CreditBalanceState(
    val balance: Double? = null,
    val isLoading: Boolean = false,
    val refreshFailed: Boolean = false,
)

internal suspend fun pollCreditBalance(
    initialBalance: Double?,
    fetchBalance: suspend () -> Double,
    onUpdate: (CreditBalanceState) -> Unit,
) {
    var balance = initialBalance
    while (true) {
        currentCoroutineContext().ensureActive()
        onUpdate(CreditBalanceState(balance = balance, isLoading = true))

        val state = try {
            val fetched = fetchBalance()
            currentCoroutineContext().ensureActive()
            balance = fetched
            CreditBalanceState(balance = balance)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            CreditBalanceState(balance = balance, refreshFailed = true)
        }

        onUpdate(state)
        delay(5_000L)
    }
}

internal fun formatCreditBalance(
    balance: Double?,
): String = balance?.let {
    NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = 6
    }.format(it)
} ?: "—"
