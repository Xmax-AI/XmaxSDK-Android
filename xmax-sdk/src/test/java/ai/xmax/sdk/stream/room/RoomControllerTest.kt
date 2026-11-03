//
//  RoomControllerTest.kt
//  XmaxSDK
//  Repo: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.room

import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RoomJoinConfiguration
import ai.xmax.sdk.foundation.rtc.RemoteStream
import kotlinx.coroutines.test.advanceTimeBy
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
public class RoomControllerTest {
    @Test
    public fun `join forwards connection and leave releases room once`() = runTest {
        val rtcManager = RtcManagingStub()
        val heartbeat = RoomHeartbeat(
            rtcManager,
            sleeper = { awaitCancellation() },
            scope = this,
        )
        val controller = RoomController(rtcManager, heartbeat)

        controller.join(connection()) {}
        controller.leave()
        controller.leave()

        assertEquals(
            listOf(
                RtcManagingCall.JoinRoom(
                    RoomJoinConfiguration("room-id", "user-id", "room-token"),
                ),
                RtcManagingCall.LeaveRoom,
            ),
            rtcManager.calls.filterNot { it is RtcManagingCall.SendRoomMessage },
        )
    }

    @Test
    public fun `join rejects another room until leave`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = RoomController(
            rtcManager,
            RoomHeartbeat(rtcManager, sleeper = { awaitCancellation() }, scope = this),
        )
        controller.join(connection()) {}

        val error = expectXmaxError {
            controller.join(connection()) {}
        }

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
        assertEquals(
            "Leave the current RTC room before joining another one",
            error.message,
        )
        controller.leave()
    }

    @Test
    public fun `join failure rolls back room resources`() = runTest {
        val expected = XmaxError(XmaxErrorCode.RTC_ERROR, "join failed")
        val rtcManager = RtcManagingStub(joinRoomError = expected)
        val controller = RoomController(
            rtcManager,
            RoomHeartbeat(rtcManager, sleeper = { awaitCancellation() }, scope = this),
        )

        val error = expectXmaxError {
            controller.join(connection()) {}
        }

        assertTrue(error === expected)
        assertEquals(RtcManagingCall.LeaveRoom, rtcManager.calls.last())
    }

    @Test
    public fun `inactive join after async boundary preserves cancellation and leaves room`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = RoomController(
            rtcManager,
            RoomHeartbeat(rtcManager, sleeper = { awaitCancellation() }, scope = this),
        )
        var invocationCount = 0
        val expected = CancellationException("connection replaced")

        val error = runCatching {
            controller.join(connection()) {
                invocationCount += 1
                if (invocationCount == 2) throw expected
            }
        }.exceptionOrNull()

        assertSame(expected, error)
        assertEquals(2, invocationCount)
        assertEquals(RtcManagingCall.LeaveRoom, rtcManager.calls.last())
    }

    @Test
    public fun `generation signals use joined user and room protocol`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = RoomController(
            rtcManager,
            RoomHeartbeat(
                rtcManager,
                sleeper = { awaitCancellation() },
                scope = this,
            ),
        )
        controller.join(connection()) {}
        val format = RealtimeVideoFormat(720, 1280, 24)

        controller.startGeneration("task-id", format, RealtimeContext("first"))
        controller.changeGenerationCondition(
            "task-id",
            format,
            RealtimeContext("second"),
        )
        controller.sendTracks("task-id", listOf(RealtimePoint(0.1, 0.2)))
        controller.stopGeneration("task-id")

        assertEquals(
            listOf("start", "change_condition", "tracks", "stop"),
            rtcManager.roomMessages.map { JSONObject(it).getString("event") },
        )
        assertEquals(listOf(null, null, "tracks:task-id", null), rtcManager.replacementKeys)
        assertEquals(listOf(null, null, null), rtcManager.discardedKeys)
        assertTrue(
            rtcManager.roomMessages.all {
                JSONObject(it).getString("user_id") == "user-id"
            },
        )
        controller.leave()
    }

    @Test
    public fun `generation signal requires joined room`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = RoomController(
            rtcManager,
            RoomHeartbeat(rtcManager, sleeper = { awaitCancellation() }, scope = this),
        )

        val error = expectXmaxError {
            controller.startGeneration(
                "task-id",
                RealtimeVideoFormat(720, 1280, 24),
                RealtimeContext("prompt"),
            )
        }

        assertEquals(XmaxErrorCode.RTC_ERROR, error.code)
        assertEquals("RTC room is not joined", error.message)
        assertTrue(rtcManager.roomMessages.isEmpty())
    }

    @Test
    public fun `empty optional signals are ignored`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = RoomController(
            rtcManager,
            RoomHeartbeat(rtcManager, sleeper = { awaitCancellation() }, scope = this),
        )
        controller.join(connection()) {}

        controller.stopGeneration("")
        controller.sendTracks("task-id", emptyList())
        runCurrent()

        assertTrue(rtcManager.roomMessages.isEmpty())
        controller.leave()
    }

    @Test
    public fun `room signal log identifies and pretty prints event`() {
        val controller = RoomController(RtcManagingStub())

        val message = controller.formatSignalLog(
            JSONObject()
                .put("event", "start")
                .put("user_id", "user-id")
                .toString(),
        )

        assertTrue(message.contains("Outbound Room Signaling"))
        assertTrue(message.contains("类型：start"))
        assertTrue(message.contains("\"user_id\""))
    }

    private fun connection(): RealtimeSessionConnection = RealtimeSessionConnection(
        roomId = "room-id",
        userId = "user-id",
        token = "room-token",
        botName = "bot-user",
    )

    @Test
    public fun `receipts reject invalid senders recipients counts and stale room callbacks`() = runTest {
        val rtc = RtcManagingStub()
        val controller = RoomController(
            rtc,
            RoomHeartbeat(rtc, sleeper = { awaitCancellation() }, scope = backgroundScope),
            commandScope = backgroundScope,
        )
        controller.join(connection()) {}
        controller.startGeneration("task", RealtimeVideoFormat(720, 1280, 24), RealtimeContext("test")).await()
        runCurrent()
        val oldListener = rtc.roomMessageListener!!
        val xid = JSONObject(rtc.roomMessages.single()).getString("xid")

        for ((room, sender, recipient, count) in listOf(
            listOf("room-id", "intruder", "user-id", "1"),
            listOf("room-id", "user-id", "user-id", "1"),
            listOf("other-room", "bot-user", "user-id", "1"),
            listOf("room-id", "bot-user", "other-user", "1"),
            listOf("room-id", "bot-user", "user-id", "0"),
            listOf("room-id", "bot-user", "user-id", "1.5"),
            listOf("room-id", "bot-user", "user-id", "\"1\""),
            listOf("room-id", "bot-user", "user-id", "9007199254740992"),
        )) {
            oldListener(
                RemoteStream(room, sender),
                """{"event":"xid_ack","xid":"$xid","user_id":"$recipient","count":$count}""",
            )
        }
        advanceTimeBy(301)
        runCurrent()
        assertEquals(2, rtc.roomMessages.size)

        val receipt = JSONObject()
            .put("event", "xid_ack")
            .put("xid", xid)
            .put("count", 1)
            .put("unused", "x".repeat(2_000))
            .toString()
        val fragments = RoomMessageCodec().encode(receipt)
        assertTrue(fragments.size > 1)
        fragments.reversed().forEach {
            oldListener(RemoteStream("room-id", "bot-user"), it)
        }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, rtc.roomMessages.size)

        controller.leave()
        assertEquals(null, rtc.roomMessageListener)
        controller.join(connection()) {}
        controller.startGeneration("new-task", RealtimeVideoFormat(720, 1280, 24), RealtimeContext("test")).await()
        runCurrent()
        val newXid = JSONObject(rtc.roomMessages.last()).getString("xid")
        oldListener(
            RemoteStream("room-id", "bot-user"),
            """{"event":"xid_ack","xid":"$newXid","count":1}""",
        )
        rtc.roomMessageListener!!(
            RemoteStream("room-id", "bot-user"),
            """{"event":"xid_ack","xid":"$xid","count":1}""",
        )
        advanceTimeBy(301)
        runCurrent()
        assertEquals(4, rtc.roomMessages.size)

        controller.leave()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(4, rtc.roomMessages.size)
    }

    private suspend fun expectXmaxError(block: suspend () -> Unit): XmaxError = try {
        block()
        throw AssertionError("Expected XmaxError")
    } catch (error: XmaxError) {
        error
    }
}
