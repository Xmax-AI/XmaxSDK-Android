//
//  ExternalVideoFrameSinkTest.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.external

import ai.xmax.sdk.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class ExternalVideoFrameSinkTest {

    @Test
    fun `original pixels are resized bilinearly and isolated before returning`() {
        val frames = mutableListOf<VideoFrame>()
        val sink = sink(RealtimeVideoFormat(4, 4, 24), RealtimeVideoFormat(2, 2, 24), video = frames::add)
        val bytes = ByteArray(64) { index -> ((index / 4) * 10 + index % 4).toByte() }
        sink.pushVideo(bytes, 0)
        bytes.fill(0)
        val frame = frames.single()
        assertEquals(2, frame.format.width)
        assertEquals(2, frame.format.height)
        assertEquals(0L, frame.timestampUs)
        assertArrayEquals(byteArrayOf(25, 26, 27, 28, 45, 46, 47, 48, 105, 106, 107, 108, 125, 126, 127, -128), frame.planes.single().data)
    }

    @Test
    fun `upscaling clamps image edges and does not flip or reorder channels`() {
        val frames = mutableListOf<VideoFrame>()
        val sink = sink(RealtimeVideoFormat(2, 2, 30), RealtimeVideoFormat(4, 4, 30), video = frames::add)
        val bytes = ByteArray(16) { it.toByte() }
        sink.pushVideo(bytes, 1)
        val output = frames.single().planes.single().data
        assertArrayEquals(bytes.copyOfRange(0, 4), output.copyOfRange(0, 4))
        assertArrayEquals(bytes.copyOfRange(12, 16), output.copyOfRange(60, 64))
    }

    @Test
    fun `duplicate out of order and negative video timestamps are ignored`() {
        val frames = mutableListOf<VideoFrame>()
        val errors = mutableListOf<XmaxError>()
        val sink = sink(video = frames::add, errors = errors::add)
        sink.pushVideo(byteArrayOf(), -1)
        sink.pushVideo(ByteArray(16), 10)
        sink.pushVideo(byteArrayOf(), 10)
        sink.pushVideo(byteArrayOf(), 9)
        sink.pushVideo(ByteArray(16), 11)
        assertEquals(listOf(10L, 11L), frames.map { it.timestampUs })
        assertTrue(errors.isEmpty())
        assertTrue(sink.isRequestingFrames)
    }

    @Test
    fun `wrong dimensions immediately invalidate the binding and report only one media error`() {
        val frames = mutableListOf<VideoFrame>()
        val errors = mutableListOf<XmaxError>()
        val sink = sink(video = frames::add, errors = errors::add)
        sink.pushVideo(ByteArray(12), 1)
        assertFalse(sink.isRequestingFrames)
        sink.pushVideo(ByteArray(16), 2)
        sink.pushAudio(ByteArray(960), 2)
        sink.reportError(IllegalStateException("second failure"))
        assertTrue(frames.isEmpty())
        assertEquals(1, errors.size)
        assertEquals(XmaxErrorCode.MEDIA_ERROR, errors.single().code)
        assertSame(errors.single(), sink.failure)
    }

    @Test
    fun `source failure and delivery failure invalidate before reporting`() {
        for (deliveryFailure in listOf(false, true)) {
            var reports = 0
            lateinit var sink: ExternalVideoFrameSink
            sink = sink(
                video = { if (deliveryFailure) error("delivery failed") },
                errors = {
                    assertFalse(sink.isRequestingFrames)
                    reports++
                },
            )
            if (deliveryFailure) sink.pushVideo(ByteArray(16), 0) else sink.reportError(IllegalStateException("capture failed"))
            sink.reportError(IllegalStateException("late failure"))
            assertEquals(1, reports)
        }
    }

    @Test
    fun `PCM metadata is converted and a format change terminates the binding`() {
        val frames = mutableListOf<AudioFrame>()
        val errors = mutableListOf<XmaxError>()
        val sink = sink(audio = frames::add, errors = errors::add)
        sink.pushAudio(ByteArray(960), 0)
        assertEquals(1, frames.size)
        sink.pushAudio(ByteArray(1764), 10000, 44100, 2)
        assertFalse(sink.isRequestingFrames)
        assertEquals(1, errors.size)

        val converted = sink(audio = frames::add)
        converted.pushAudio(ByteArray(4410 * 4), 0, 44100, 2)
        assertEquals(10, frames.size)
        assertTrue(frames.all { it.data.size == 960 })
    }

    @Test
    fun `video only source ignores audio without invalidating video`() {
        val sink = sink(hasAudio = false, audio = { fail("Unexpected audio") }, errors = { fail("Unexpected error") })
        sink.pushAudio(byteArrayOf(), 0, -1, -1)
        assertTrue(sink.isRequestingFrames)
    }

    @Test
    fun `invalidation waits for in flight frame delivery and prevents later callbacks`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidating = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        var deliveries = 0
        val sink = sink(video = {
            deliveries++
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }, errors = { fail("Unexpected error") })
        try {
            val submitting = executor.submit { sink.pushVideo(ByteArray(16), 0) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val stopping = executor.submit {
                invalidating.countDown()
                sink.invalidate()
            }
            assertTrue(invalidating.await(3, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { stopping.get(50, TimeUnit.MILLISECONDS) }
            release.countDown()
            submitting.get(3, TimeUnit.SECONDS)
            stopping.get(3, TimeUnit.SECONDS)
            sink.pushVideo(ByteArray(16), 1)
            sink.reportError(IllegalStateException("late"))
            assertEquals(1, deliveries)
            assertFalse(sink.isRequestingFrames)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun sink(
        original: RealtimeVideoFormat = RealtimeVideoFormat(2, 2, 30),
        output: RealtimeVideoFormat = original,
        hasAudio: Boolean = true,
        video: (VideoFrame) -> Unit = {},
        audio: (AudioFrame) -> Unit = {},
        errors: (XmaxError) -> Unit = { throw AssertionError(it) },
    ) = ExternalVideoFrameSink(original, output, hasAudio, video, audio, errors)
}
