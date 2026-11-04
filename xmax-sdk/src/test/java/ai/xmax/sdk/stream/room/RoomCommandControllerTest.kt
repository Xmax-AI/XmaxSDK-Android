//
//  RoomCommandControllerTest.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.room

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.XmaxLoggerOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
public class RoomCommandControllerTest {

    @Test
    public fun `missing receipt retries the frozen payload six times without blocking the caller`() = runTest {
        val messages = mutableListOf<String>()
        val controller = RoomCommandController(
            sendMessage = { messages += it; CompletableDeferred(Unit) },
            scope = backgroundScope,
        )
        command(controller).await()
        runCurrent()
        assertEquals(1, messages.size)

        var previous = 0L
        for ((offset, count) in listOf(300L to 2, 1_000L to 3, 3_000L to 4, 3_300L to 5, 4_000L to 6)) {
            advanceTimeBy(offset - previous)
            runCurrent()
            assertEquals(count, messages.size)
            previous = offset
        }
        assertEquals(1, messages.toSet().size)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(6, messages.size)

        command(controller, kind = RoomCommandController.Kind.CHANGE_CONDITION).await()
        assertEquals(7, messages.size)
        controller.close()
    }

    @Test
    public fun `only a matching receipt with positive safe count stops retries`() = runTest {
        val messages = mutableListOf<String>()
        val controller = RoomCommandController(
            sendMessage = { messages += it; CompletableDeferred(Unit) },
            scope = backgroundScope,
        )
        command(controller).await()
        runCurrent()
        val xid = JSONObject(messages.single()).getString("xid")
        controller.acknowledge("unknown", 1)
        controller.acknowledge(xid, 0)
        controller.acknowledge(xid, -1)
        controller.acknowledge(xid, Long.MAX_VALUE)
        advanceTimeBy(300)
        runCurrent()
        assertEquals(2, messages.size)

        controller.acknowledge(xid, 2)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, messages.size)
        controller.close()
    }

    @Test
    public fun `early receipt stops retries but does not complete or cancel original send`() = runTest {
        val original = CompletableDeferred<Unit>()
        var xid = ""
        val controller = RoomCommandController(
            sendMessage = { xid = JSONObject(it).getString("xid"); original },
            scope = backgroundScope,
        )
        val sending = command(controller)
        controller.acknowledge(xid, 1)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(sending.isCompleted)
        assertFalse(original.isCancelled)

        original.complete(Unit)
        runCurrent()
        sending.await()
        controller.close()
    }

    @Test
    public fun `receipt deadline leaves unresolved original send intact`() = runTest {
        val attempts = mutableListOf<CompletableDeferred<Unit>>()
        val controller = RoomCommandController(
            sendMessage = { CompletableDeferred<Unit>().also { attempts += it } },
            scope = backgroundScope,
        )
        val sending = command(controller)
        runCurrent()
        advanceTimeBy(6_001)
        runCurrent()
        assertEquals(6, attempts.size)
        assertFalse(sending.isCompleted)
        assertFalse(attempts.first().isCancelled)
        assertTrue(attempts.drop(1).all { it.isCancelled })

        attempts.first().complete(Unit)
        runCurrent()
        sending.await()
        controller.close()
    }

    @Test
    public fun `newer condition replaces queued condition and retains start ordering`() = runTest {
        val messages = mutableListOf<String>()
        val original = CompletableDeferred<Unit>()
        val controller = RoomCommandController(
            sendMessage = { messages += it; if (messages.size == 1) original else CompletableDeferred(Unit) },
            scope = backgroundScope,
        )
        val start = command(controller)
        val old = command(controller, RoomCommandController.Kind.CHANGE_CONDITION, prompt = "old")
        val latest = command(controller, RoomCommandController.Kind.CHANGE_CONDITION, prompt = "latest")
        runCurrent()
        assertTrue(old.isCancelled)
        assertEquals(1, messages.size)

        original.complete(Unit)
        runCurrent()
        start.await()
        latest.await()
        assertEquals(2, messages.size)
        assertTrue(messages.last().contains("latest"))
        controller.close()
    }

    @Test
    public fun `replacement start cancels previous task attempts and uses a new identifier`() = runTest {
        val messages = mutableListOf<String>()
        val controller = RoomCommandController(
            sendMessage = { messages += it; CompletableDeferred(Unit) },
            scope = backgroundScope,
        )
        command(controller).await()
        runCurrent()
        command(controller, RoomCommandController.Kind.CHANGE_TARGET_SIZE).await()
        runCurrent()
        command(controller, taskId = "new-task").await()
        runCurrent()
        advanceTimeBy(301)
        runCurrent()
        assertEquals(4, messages.size)
        assertEquals("new-task", JSONObject(messages.last()).getString("uid"))
        assertNotEquals(
            JSONObject(messages.first()).getString("xid"),
            JSONObject(messages.last()).getString("xid"),
        )

        controller.cancel("new-task")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(4, messages.size)
        controller.close()
    }

    @Test
    public fun `initial failure propagates and cancels dependent queued updates`() = runTest {
        val original = CompletableDeferred<Unit>()
        var count = 0
        val controller = RoomCommandController(
            sendMessage = { count += 1; original },
            scope = backgroundScope,
        )
        val sending = command(controller)
        val update = command(controller, RoomCommandController.Kind.CHANGE_CONDITION)
        val failure = XmaxError(XmaxErrorCode.RTC_ERROR, "send failed")
        original.completeExceptionally(failure)
        runCurrent()

        assertSame(failure, runCatching { sending.await() }.exceptionOrNull())
        assertTrue(update.isCancelled)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, count)
        controller.close()
    }

    @Test
    public fun `retry failure does not fail an already sent command or later commands`() = runTest {
        var count = 0
        var fail = false
        val controller = RoomCommandController(
            sendMessage = {
                count += 1
                if (fail) throw XmaxError(XmaxErrorCode.RTC_ERROR, "retry failed")
                CompletableDeferred(Unit)
            },
            scope = backgroundScope,
        )
        val sending = command(controller)
        sending.await()
        runCurrent()
        fail = true
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(3, count)
        sending.await()

        fail = false
        command(controller, RoomCommandController.Kind.CHANGE_CONDITION).await()
        assertEquals(4, count)
        controller.close()
    }

    @Test
    public fun `first successful redundant attempt resolves caller and receipt cancels original`() = runTest {
        val original = CompletableDeferred<Unit>()
        var count = 0
        var xid = ""
        val controller = RoomCommandController(
            sendMessage = {
                count += 1
                xid = JSONObject(it).getString("xid")
                if (count == 1) original else CompletableDeferred(Unit)
            },
            scope = backgroundScope,
        )
        val sending = command(controller)
        runCurrent()
        advanceTimeBy(301)
        runCurrent()
        sending.await()
        assertFalse(original.isCancelled)

        controller.acknowledge(xid, 1)
        assertTrue(original.isCancelled)
        controller.close()
    }

    @Test
    public fun `caller cancellation discards original and scheduled attempts`() = runTest {
        val original = CompletableDeferred<Unit>()
        var count = 0
        val controller = RoomCommandController(
            sendMessage = { count += 1; original },
            scope = backgroundScope,
        )
        val sending = command(controller)
        sending.cancel()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(original.isCancelled)
        assertEquals(1, count)
        controller.close()
    }

    @Test
    public fun `stale configuration cancels attempts before first successful send`() = runTest {
        var current = true
        var count = 0
        var xid = ""
        val original = CompletableDeferred<Unit>()
        val controller = RoomCommandController(
            sendMessage = { count += 1; xid = JSONObject(it).getString("xid"); original },
            scope = backgroundScope,
        )
        val sending = controller.send(
            kind = RoomCommandController.Kind.CHANGE_TARGET_SIZE,
            taskId = "task",
            createMessage = { """{"xid":"$it"}""" },
            ensureCurrent = { if (!current) throw kotlinx.coroutines.CancellationException("stale") },
        )
        runCurrent()
        current = false
        controller.acknowledge(xid, 1)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, count)
        assertTrue(sending.isCancelled)
        assertTrue(original.isCancelled)
        controller.close()
    }

    @Test
    public fun `retries survive the caller operation after successful submission`() = runTest {
        var current = true
        var count = 0
        var relays = 0
        val controller = RoomCommandController(
            sendMessage = { count += 1; CompletableDeferred(Unit) },
            scope = backgroundScope,
            relayMessage = { relays++ },
        )
        controller.send(
            kind = RoomCommandController.Kind.CHANGE_TARGET_SIZE,
            taskId = "task",
            createMessage = { """{"xid":"$it"}""" },
            ensureCurrent = { if (!current) throw kotlinx.coroutines.CancellationException("operation ended") },
        ).await()
        current = false
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(3, count)
        assertEquals(1, relays)
        controller.close()
    }

    @Test
    public fun `close cancels original and rejects late submissions`() = runTest {
        val original = CompletableDeferred<Unit>()
        val controller = RoomCommandController(
            sendMessage = { original },
            scope = backgroundScope,
        )
        val sending = command(controller)
        controller.close()
        assertTrue(sending.isCancelled)
        assertTrue(original.isCancelled)
        assertTrue(runCatching { command(controller) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test
    public fun `each control relays its frozen payload once while RTC retries six times`() = runTest {
        for (kind in RoomCommandController.Kind.entries) {
            val messages = mutableListOf<String>()
            val relays = mutableListOf<String>()
            val controller = RoomCommandController(
                sendMessage = { messages += it; CompletableDeferred(Unit) },
                scope = backgroundScope,
                relayMessage = { relays += it },
            )

            command(controller, kind).await()
            runCurrent()
            advanceTimeBy(6_001)
            runCurrent()

            assertEquals(6, messages.size)
            assertEquals(listOf(messages.first()), relays)
            assertTrue(messages.all { it == relays.single() })
            controller.close()
        }
    }

    @Test
    public fun `ack and retry deadline retain HTTP cancellation across replacement and close`() = runTest {
        for (acknowledged in listOf(false, true)) {
            val messages = mutableListOf<String>()
            val relays = mutableListOf<Job>()
            val controller = RoomCommandController(
                sendMessage = { messages += it; CompletableDeferred(Unit) },
                scope = backgroundScope,
                relayMessage = {
                    relays += currentCoroutineContext().job
                    awaitCancellation()
                },
            )
            command(controller, RoomCommandController.Kind.CHANGE_CONDITION).await()
            runCurrent()
            if (acknowledged) {
                controller.acknowledge(JSONObject(messages.first()).getString("xid"), 1)
            } else {
                advanceTimeBy(6_001)
                runCurrent()
            }
            assertTrue(relays.single().isActive)

            command(controller, RoomCommandController.Kind.CHANGE_CONDITION, prompt = "new").await()
            runCurrent()
            assertTrue(relays[0].isCancelled)
            assertTrue(relays[1].isActive)

            command(controller, RoomCommandController.Kind.CHANGE_TARGET_SIZE).await()
            runCurrent()
            assertTrue(relays[1].isActive)
            assertTrue(relays[2].isActive)

            command(controller, taskId = "new-task").await()
            runCurrent()
            assertTrue(relays.take(3).all { it.isCancelled })
            assertTrue(relays.last().isActive)
            controller.close()
            runCurrent()
            assertTrue(relays.all { it.isCancelled })
        }
    }

    @Test
    public fun `HTTP success does not complete an unresolved RTC send or stop retries`() = runTest {
        val original = CompletableDeferred<Unit>()
        val messages = mutableListOf<String>()
        var relays = 0
        val controller = RoomCommandController(
            sendMessage = { messages += it; if (messages.size == 1) original else CompletableDeferred() },
            scope = backgroundScope,
            relayMessage = { relays++ },
        )
        val sending = command(controller)
        runCurrent()
        assertEquals(1, relays)
        assertFalse(sending.isCompleted)

        advanceTimeBy(301)
        runCurrent()
        assertEquals(2, messages.size)
        controller.acknowledge(JSONObject(messages.first()).getString("xid"), 1)
        assertFalse(sending.isCompleted)
        original.complete(Unit)
        runCurrent()
        sending.await()
        controller.close()
    }

    @Test
    public fun `HTTP failure warns once and preserves RTC sends and retries`() = runTest {
        val logs = mutableListOf<String>()
        XmaxLogger.configure(XmaxLoggerOption.business)
        XmaxLogger.setSinkForTesting { _, _, message -> logs += message }
        try {
            var sends = 0
            var relays = 0
            val controller = RoomCommandController(
                sendMessage = { sends++; CompletableDeferred(Unit) },
                scope = backgroundScope,
                relayMessage = {
                    relays++
                    throw XmaxError(XmaxErrorCode.API_ERROR, "relay unavailable", apiCode = 46015, httpStatus = 503)
                },
            )
            command(controller).await()
            runCurrent()
            advanceTimeBy(6_001)
            runCurrent()
            assertEquals(6, sends)
            assertEquals(1, relays)
            val warnings = logs.filter { it.contains("HTTP Signaling Relay Failed") }
            assertEquals(1, warnings.size)
            assertTrue(warnings.single().contains("httpStatus: 503"))
            controller.close()
        } finally {
            XmaxLogger.configure(XmaxLoggerOption.none)
            XmaxLogger.setSinkForTesting(null)
        }
    }

    @Test
    public fun `initial RTC failure cancels the independent HTTP request`() = runTest {
        val original = CompletableDeferred<Unit>()
        var relay: Job? = null
        val controller = RoomCommandController(
            sendMessage = { original },
            scope = backgroundScope,
            relayMessage = {
                relay = currentCoroutineContext().job
                awaitCancellation()
            },
        )
        val sending = command(controller)
        runCurrent()
        assertTrue(checkNotNull(relay).isActive)
        val failure = XmaxError(XmaxErrorCode.RTC_ERROR, "send failed")
        original.completeExceptionally(failure)
        runCurrent()
        assertSame(failure, runCatching { sending.await() }.exceptionOrNull())
        assertTrue(checkNotNull(relay).isCancelled)
        controller.close()
    }

    @Test
    public fun `synchronous receipt retains the HTTP request until task cleanup`() = runTest {
        lateinit var controller: RoomCommandController
        var relay: Job? = null
        controller = RoomCommandController(
            sendMessage = {
                controller.acknowledge(JSONObject(it).getString("xid"), 1)
                CompletableDeferred(Unit)
            },
            scope = backgroundScope,
            relayMessage = {
                relay = currentCoroutineContext().job
                awaitCancellation()
            },
        )
        command(controller).await()
        runCurrent()
        assertTrue(checkNotNull(relay).isActive)
        controller.cancel("task")
        runCurrent()
        assertTrue(checkNotNull(relay).isCancelled)
        controller.close()
    }

    @Test
    public fun `HTTP lifecycle cancellation does not log a failure`() = runTest {
        val logs = mutableListOf<String>()
        XmaxLogger.configure(XmaxLoggerOption.business)
        XmaxLogger.setSinkForTesting { _, _, message -> logs += message }
        try {
            val controller = RoomCommandController(
                sendMessage = { CompletableDeferred(Unit) },
                scope = backgroundScope,
                relayMessage = { awaitCancellation() },
            )
            command(controller).await()
            runCurrent()
            controller.close()
            runCurrent()
            assertFalse(logs.any { it.contains("HTTP Signaling Relay Failed") })
        } finally {
            XmaxLogger.configure(XmaxLoggerOption.none)
            XmaxLogger.setSinkForTesting(null)
        }
    }

    private fun command(
        controller: RoomCommandController,
        kind: RoomCommandController.Kind = RoomCommandController.Kind.START,
        taskId: String = "task",
        prompt: String = "prompt",
    ): Deferred<Unit> = controller.send(
        kind = kind,
        taskId = taskId,
        createMessage = { xid ->
            JSONObject()
                .put("event", kind.value)
                .put("uid", taskId)
                .put("xid", xid)
                .put("prompt", prompt)
                .toString()
        },
    )
}
