//
//  RealtimeMediaFrameDispatcherTest.kt
//  XmaxSDK
//  Repo: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.video

import ai.xmax.sdk.RealtimeVideoFrame
import ai.xmax.sdk.RealtimeMediaFrame
import ai.xmax.sdk.foundation.rtc.RtcRemoteAudioFrame
import ai.xmax.sdk.foundation.rtc.RtcRemoteVideoFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeMediaFrameDispatcherTest {
    @Test
    fun `listener replacement generation change and clear discard queued pixels`() = runTest {
        val dispatcher = RealtimeMediaFrameDispatcher(StandardTestDispatcher(testScheduler))
        val generation = Any()
        val nextGeneration = Any()
        val received = mutableListOf<Long>()
        dispatcher.setGeneration(generation)
        val frame = Frame(1)
        dispatcher.dispatch(generation, frame)
        assertEquals(0, frame.copies)
        dispatcher.setListener { received += it.presentationTimeUs }
        dispatcher.dispatch(generation, frame)
        dispatcher.setListener { received += it.presentationTimeUs + 10 }
        runCurrent()
        assertTrue(received.isEmpty())
        dispatcher.dispatch(generation, frame)
        dispatcher.setGeneration(nextGeneration)
        dispatcher.dispatch(generation, frame)
        assertEquals(2, frame.copies)
        runCurrent()
        assertTrue(received.isEmpty())
        dispatcher.dispatch(nextGeneration, Frame(2))
        runCurrent()
        assertEquals(listOf(12L), received)
        dispatcher.dispatch(nextGeneration, Frame(3))
        dispatcher.setListener(null)
        runCurrent()
        assertEquals(listOf(12L), received)
    }

    @Test
    fun `slow listeners preserve FIFO order and listener exceptions do not kill delivery`() = runTest {
        val dispatcher = RealtimeMediaFrameDispatcher(StandardTestDispatcher(testScheduler))
        val generation = Any()
        val received = mutableListOf<Long>()
        dispatcher.setGeneration(generation)
        dispatcher.setListener {
            received += it.presentationTimeUs
            if (it.presentationTimeUs == 1L) {
                repeat(100) { index -> dispatcher.dispatch(generation, Frame(index + 2L)) }
                throw AssertionError("consumer failed")
            }
        }
        dispatcher.dispatch(generation, Frame(1))
        runCurrent()
        assertEquals((1L..101L).toList(), received)
    }

    @Test
    fun `audio and video share identity and native timeline but retain delivery order`() = runTest {
        val dispatcher = RealtimeMediaFrameDispatcher(StandardTestDispatcher(testScheduler))
        val generation = Any()
        val events = mutableListOf<RealtimeMediaFrame>()
        dispatcher.setGeneration(generation)
        dispatcher.setListener { events += it }
        dispatcher.dispatchAudio(generation, RtcRemoteAudioFrame(ByteArray(960), 48_000, 1, 480, 1_000))
        dispatcher.dispatch(generation, Frame(1_005_000))
        dispatcher.dispatchAudio(generation, RtcRemoteAudioFrame(ByteArray(960), 48_000, 1, 480, 1_000))
        runCurrent()
        assertEquals(listOf(1_000_000L, 1_005_000L, 1_010_000L), events.map { it.presentationTimeUs })
        assertEquals(1, events.map { it.streamID }.toSet().size)
        assertEquals(listOf(true, true, false), events.map { it.isDiscontinuous })
        val previous = events.first().streamID
        dispatcher.setGeneration(generation)
        dispatcher.dispatch(generation, Frame(2_000_000))
        runCurrent()
        assertNotEquals(previous, events.last().streamID)
    }

    @Test
    fun `overflow accounts for running callbacks and marks only the discarded track discontinuous`() = runTest {
        val dispatcher = RealtimeMediaFrameDispatcher(StandardTestDispatcher(testScheduler), maximumPackets = 2)
        val generation = Any()
        val events = mutableListOf<RealtimeMediaFrame>()
        dispatcher.setGeneration(generation)
        dispatcher.setListener { frame ->
            events += frame
            if (frame.presentationTimeUs == 1L) {
                dispatcher.dispatch(generation, Frame(2))
                dispatcher.dispatchAudio(generation, RtcRemoteAudioFrame(ByteArray(960), 48_000, 1, 480, 1_000))
            }
        }
        dispatcher.dispatch(generation, Frame(1))
        runCurrent()
        dispatcher.dispatchAudio(generation, RtcRemoteAudioFrame(ByteArray(960), 48_000, 1, 480, 1_010))
        dispatcher.dispatch(generation, Frame(3))
        runCurrent()
        assertEquals(listOf(1L, 2L, 1_010_000L, 3L), events.map { it.presentationTimeUs })
        assertEquals(listOf(true, false, true, false), events.map { it.isDiscontinuous })
    }

    @Test
    fun `byte limit skips pixel copies and marks resumed video discontinuous`() = runTest {
        val dispatcher = RealtimeMediaFrameDispatcher(StandardTestDispatcher(testScheduler), maximumBytes = 6)
        val generation = Any()
        val events = mutableListOf<RealtimeMediaFrame>()
        dispatcher.setGeneration(generation)
        dispatcher.setListener { events += it }
        dispatcher.dispatch(generation, Frame(1))
        val discarded = Frame(2)
        dispatcher.dispatch(generation, discarded)
        assertEquals(0, discarded.copies)
        runCurrent()
        dispatcher.dispatch(generation, Frame(3))
        runCurrent()
        assertEquals(listOf(true, true), events.map { it.isDiscontinuous })
    }

    @Test
    fun `production dispatcher runs off caller thread and never overlaps callbacks`() {
        val dispatcher = RealtimeMediaFrameDispatcher()
        val generation = Any()
        val caller = Thread.currentThread()
        val firstEntered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val error = AtomicReference<Throwable?>()
        dispatcher.setGeneration(generation)
        dispatcher.setListener {
            try {
                assertNotSame(caller, Thread.currentThread())
                maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                if (it.presentationTimeUs == 1L) {
                    firstEntered.countDown()
                    check(unblock.await(5, TimeUnit.SECONDS))
                }
            } catch (failure: Throwable) { error.set(failure) }
            finally { active.decrementAndGet(); completed.countDown() }
        }
        try {
            dispatcher.dispatch(generation, Frame(1))
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            repeat(100) { dispatcher.dispatch(generation, Frame(it + 2L)) }
        } finally { unblock.countDown() }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        dispatcher.setListener(null)
        error.get()?.let { throw it }
        assertEquals(1, maximum.get())
    }

    private class Frame(private val timestamp: Long) : RtcRemoteVideoFrame {
        var copies = 0
        override val width = 2
        override val height = 2
        override fun copy(): RealtimeVideoFrame {
            copies++
            return RealtimeVideoFrame(2, 2, timestamp, null, 0, ByteArray(4), ByteArray(1), ByteArray(1))
        }
    }
}
