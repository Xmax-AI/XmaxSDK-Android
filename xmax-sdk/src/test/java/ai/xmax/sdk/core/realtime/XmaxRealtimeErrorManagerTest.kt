//
//  XmaxRealtimeErrorManagerTest.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.XmaxRealtimeLifecycleManager.TerminationScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XmaxRealtimeErrorManagerTest {
    @Test
    fun `disconnect invalidates queued connection failures while retaining media failure validity`() = runTest {
        val manager = XmaxRealtimeErrorManager(StandardTestDispatcher(testScheduler))
        val failures = mutableListOf<TerminationScope>()
        manager.setFailureHandler { _, target, current -> if (current()) failures += target }
        val error = XmaxError(XmaxErrorCode.RTC_ERROR, "Late failure")
        manager.forward(error, TerminationScope.CONNECTION)
        manager.forward(error, TerminationScope.ALL)
        manager.invalidatePendingFailures(TerminationScope.CONNECTION)
        runCurrent()
        assertEquals(listOf(TerminationScope.ALL), failures)
    }

    @Test
    fun `closed runtimes and full cleanup reject queued failures`() = runTest {
        val manager = XmaxRealtimeErrorManager(StandardTestDispatcher(testScheduler))
        var failures = 0
        var currentRuntime = true
        manager.setFailureHandler { _, _, current -> if (current()) failures++ }
        val error = XmaxError(XmaxErrorCode.MEDIA_ERROR, "Late media failure")
        manager.forward(error, TerminationScope.ALL, isCurrent = { currentRuntime })
        currentRuntime = false
        runCurrent()
        assertEquals(0, failures)
        manager.forward(error, TerminationScope.ALL)
        manager.forward(error, TerminationScope.CONNECTION)
        manager.invalidatePendingFailures()
        runCurrent()
        assertEquals(0, failures)
    }

    @Test
    fun `generation cancellation keeps connection failures active and cancellation is diagnostic only`() = runTest {
        val manager = XmaxRealtimeErrorManager(StandardTestDispatcher(testScheduler))
        val failures = mutableListOf<XmaxError>()
        manager.setFailureHandler { error, _, current -> if (current()) failures += error }
        val connectionError = XmaxError(XmaxErrorCode.RTC_ERROR, "Current connection failed")
        manager.forward(connectionError, TerminationScope.CONNECTION)
        manager.invalidatePendingFailures(TerminationScope.GENERATION)
        manager.forward(XmaxError(XmaxErrorCode.CANCELLED, "Superseded"), TerminationScope.ALL)
        runCurrent()
        assertEquals(listOf(connectionError), failures)
    }
}
