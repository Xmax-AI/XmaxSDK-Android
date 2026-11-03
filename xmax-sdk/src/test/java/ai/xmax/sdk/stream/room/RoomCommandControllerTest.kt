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
        val controller = RoomCommandController(
            sendMessage = { count += 1; CompletableDeferred(Unit) },
            scope = backgroundScope,
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
