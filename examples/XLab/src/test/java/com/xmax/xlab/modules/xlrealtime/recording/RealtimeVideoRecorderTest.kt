//
//  RealtimeVideoRecorderTest.kt
//  XmaxSDK
//  Repo: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime.recording

import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeVideoRecorderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `muxer rejects mismatched native clock offsets before entering media writer`() {
        assertTrue(runCatching { validateRecordingTime(1_792_449_676_491_000L) }.isFailure)
        assertTrue(runCatching { validateRecordingTime(-1) }.isFailure)
        validateRecordingTime(0)
        validateRecordingTime(6_714_700)
    }

    @Test fun `audio and video are encoded separately and merged with original shared origins`() = runTest {
        val video = Encoder()
        val audio = AudioEncoder()
        val id = java.util.UUID.randomUUID()
        var merged = false
        val recorder = RealtimeVideoRecorder(
            temporary.root, StandardTestDispatcher(testScheduler),
            audioEncoderFactory = { _, _ -> audio },
            mergeTracks = { _, videoTrack, audioTrack, rotation ->
                assertEquals(1_100_000L, videoTrack.originUs)
                assertEquals(1_000_000L, audioTrack.originUs)
                assertEquals(0, rotation)
                assertTrue(videoTrack.file.exists())
                assertTrue(audioTrack.file.exists())
                merged = true
            },
            encoderFactory = { _, _ -> video },
        )
        recorder.append(RecordingPacket.Audio(id, audioFrame(1_000_000)))
        recorder.append(RecordingPacket.Video(id, frame(1_100_000)))
        recorder.append(RecordingPacket.Audio(id, audioFrame(1_030_000)))
        recorder.stop()
        runCurrent()
        val output = recorder.result.await()
        assertTrue(merged)
        assertEquals(listOf(1_000_000L, 1_030_000L), audio.timestamps)
        assertEquals(1, audio.finishes)
        assertEquals(1, audio.closes)
        assertEquals(listOf(output), temporary.root.listFiles()!!.toList())
    }

    @Test fun `changing stream identity or audio format fails without exporting partial files`() = runTest {
        val first = java.util.UUID.randomUUID()
        val video = Encoder()
        val recorder = RealtimeVideoRecorder(temporary.root, StandardTestDispatcher(testScheduler)) { _, _ -> video }
        recorder.append(RecordingPacket.Video(first, frame(1)))
        recorder.append(RecordingPacket.Video(java.util.UUID.randomUUID(), frame(2)))
        recorder.stop()
        runCurrent()
        assertTrue(runCatching { recorder.result.await() }.isFailure)
        assertTrue(temporary.root.listFiles()!!.isEmpty())

        val audio = AudioEncoder()
        val second = RealtimeVideoRecorder(temporary.root, StandardTestDispatcher(testScheduler),
            audioEncoderFactory = { _, _ -> audio }, encoderFactory = { _, _ -> Encoder() })
        second.append(RecordingPacket.Audio(first, audioFrame(1_000_000)))
        second.append(RecordingPacket.Audio(first, audioFrame(1_020_000).copy(format = RecordingAudioFormat(44_100, 1))))
        second.stop()
        runCurrent()
        assertTrue(runCatching { second.result.await() }.isFailure)
        assertEquals(1, audio.closes)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `recording overload fails rather than silently losing audio or compressing time`() = runTest {
        val recorder = RealtimeVideoRecorder(temporary.root, StandardTestDispatcher(testScheduler)) { _, _ -> Encoder() }
        repeat(257) { recorder.append(frame(it.toLong())) }
        runCurrent()
        assertTrue(runCatching { recorder.result.await() }.exceptionOrNull()!!.message!!.contains("处理速度不足"))
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `audio timeline retains forward gaps and uses sample counts without drift`() {
        val timeline = RecordingAudioTimeline(RecordingAudioFormat(44_100, 1))
        repeat(441) { index ->
            assertEquals(0L, timeline.missingSamples(1_000_000L + index * 100_000_000L / 44_100))
            timeline.next(100)
        }
        assertEquals(1_000_000L, timeline.endTimeUs)
        assertEquals(441L, timeline.missingSamples(2_010_000))
        timeline.next(441)
        assertEquals(1_010_000L, timeline.endTimeUs)
        assertTrue(runCatching { timeline.missingSamples(2_000_000) }.isFailure)
    }

    private class AudioEncoder : RecordingAudioEncoder {
        val timestamps = mutableListOf<Long>()
        var finishes = 0
        var closes = 0
        override fun append(frame: RecordingAudioFrame) { timestamps += frame.presentationTimeUs }
        override fun finish() { finishes++ }
        override fun close() { closes++ }
    }

    private fun audioFrame(time: Long) = RecordingAudioFrame(RecordingAudioFormat(48_000, 1), 480, time, 10_000, ByteBuffer.allocate(960))

    @Test fun `slow encoding preserves ordered pending frames and stop drains them exactly once`() = runTest {
        val encoder = Encoder()
        lateinit var recorder: RealtimeVideoRecorder
        encoder.onAppend = { frame ->
            if (frame.presentationTimeUs == 1L) {
                (2L..100L).forEach { recorder.append(frame(it)) }
                recorder.stop()
                recorder.stop()
                recorder.append(frame(101))
            }
        }
        recorder = RealtimeVideoRecorder(temporary.root, StandardTestDispatcher(testScheduler)) { _, _ -> encoder }
        recorder.append(frame(1))
        runCurrent()
        val file = recorder.result.await()
        assertEquals((1L..100L).toList(), encoder.timestamps)
        assertEquals(1, encoder.finishes)
        assertEquals(1, encoder.closes)
        assertTrue(file.exists())
    }

    @Test fun `stopping before first frame creates no output and late frames are ignored`() = runTest {
        var created = 0
        val recorder = RealtimeVideoRecorder(temporary.root, StandardTestDispatcher(testScheduler)) { _, _ -> created++; Encoder() }
        recorder.stop()
        recorder.append(frame(1))
        runCurrent()
        assertTrue(runCatching { recorder.result.await() }.exceptionOrNull()!!.message!!.contains("尚未录制到"))
        assertEquals(0, created)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `format changes fail recording close encoder and remove incomplete output`() = runTest {
        val encoder = Encoder()
        val recorder = RealtimeVideoRecorder(temporary.root, StandardTestDispatcher(testScheduler)) { _, _ -> encoder }
        recorder.append(frame(1))
        runCurrent()
        recorder.append(frame(2).copy(rotationDegrees = 90))
        runCurrent()
        assertTrue(runCatching { recorder.result.await() }.isFailure)
        assertEquals(1, encoder.closes)
        assertEquals(0, encoder.finishes)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun `encoder setup and finalization failures remove temporary files and permit independent restart`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val setupFailure = IllegalStateException("setup failed")
        val first = RealtimeVideoRecorder(temporary.root, dispatcher) { _, _ -> throw setupFailure }
        first.append(frame(1)); first.stop(); runCurrent()
        val failure = runCatching { first.result.await() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(setupFailure.message, failure?.message)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
        val encoder = Encoder().apply { onFinish = { error("EOS failed") } }
        val second = RealtimeVideoRecorder(temporary.root, dispatcher) { _, _ -> encoder }
        first.append(frame(20))
        second.append(frame(2)); second.stop(); runCurrent()
        assertTrue(runCatching { second.result.await() }.isFailure)
        assertEquals(listOf(2L), encoder.timestamps)
        assertEquals(1, encoder.closes)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    private class Encoder : RecordingEncoder {
        val timestamps = mutableListOf<Long>()
        var finishes = 0
        var closes = 0
        var onAppend: (RecordingFrame) -> Unit = {}
        var onFinish: () -> Unit = {}
        override fun append(frame: RecordingFrame) { timestamps += frame.presentationTimeUs; onAppend(frame) }
        override fun finish() { finishes++; onFinish() }
        override fun close() { closes++ }
    }

    private fun frame(timestamp: Long) = RecordingFrame(4, 2, 0, timestamp, null,
        ByteBuffer.allocate(8), ByteBuffer.allocate(2), ByteBuffer.allocate(2))
}
