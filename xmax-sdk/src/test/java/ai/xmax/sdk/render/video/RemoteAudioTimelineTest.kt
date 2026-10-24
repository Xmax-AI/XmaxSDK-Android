package ai.xmax.sdk.render.video

import ai.xmax.sdk.foundation.rtc.RtcRemoteAudioFrame
import org.junit.Assert.*
import org.junit.Test

class RemoteAudioTimelineTest {
    @Test fun `repeated native timestamps use cumulative sample cadence without rounding drift`() {
        val timeline = RemoteAudioTimeline()
        var lastEnd = 0L
        repeat(441) { index ->
            val frame = timeline.resolve(packet(rate = 44_100, count = 100, time = 1_000 + index * 100_000L / 44_100))!!
            if (index > 0) assertEquals(lastEnd, frame.first.presentationTimeUs)
            assertEquals(index == 0, frame.second)
            lastEnd = frame.first.presentationTimeUs + frame.first.durationUs
        }
        assertEquals(2_000_000L, lastEnd)
    }

    @Test fun `clock jumps invalid layouts and format changes reanchor continuity`() {
        val timeline = RemoteAudioTimeline()
        assertTrue(timeline.resolve(packet())!!.second)
        assertFalse(timeline.resolve(packet(time = 1_000))!!.second)
        val jump = timeline.resolve(packet(time = 2_000))!!
        assertTrue(jump.second)
        assertEquals(2_000_000L, jump.first.presentationTimeUs)
        assertNull(timeline.resolve(packet().copy(data = byteArrayOf())))
        assertTrue(timeline.resolve(packet(time = 2_010))!!.second)
        assertTrue(timeline.resolve(packet(rate = 44_100, time = 2_020))!!.second)
        timeline.reset()
        assertTrue(timeline.resolve(packet())!!.second)
    }

    @Test fun `owned PCM views are read only and retain independent positions`() {
        val frame = RemoteAudioTimeline().resolve(packet())!!.first
        val view = frame.data
        view.get()
        assertEquals(0, frame.data.position())
        assertTrue(view.isReadOnly)
        assertEquals(10_000L, frame.durationUs)
    }

    private fun packet(rate: Int = 48_000, count: Int = 480, time: Long = 1_000) =
        RtcRemoteAudioFrame(ByteArray(count * 2), rate, 1, count, time)
}
