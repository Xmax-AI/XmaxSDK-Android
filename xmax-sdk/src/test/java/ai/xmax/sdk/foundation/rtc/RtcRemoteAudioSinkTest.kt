package ai.xmax.sdk.foundation.rtc

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class RtcRemoteAudioSinkTest {
    @Test fun `selected publisher PCM is copied while unrelated channels and users are ignored`() {
        val frames = mutableListOf<RtcRemoteAudioFrame>()
        val sink = RtcRemoteAudioSink { channel, uid ->
            if (channel == "room" && uid == 42) RtcRemoteAudioFrameListener { frames += it } else null
        }
        val borrowed = ByteBuffer.wrap(ByteArray(964) { it.toByte() }).apply { position(4) }
        assertTrue(sink.onPlaybackAudioFrameBeforeMixing("other", 42, 0, 480, 2, 1, 48_000, borrowed, 1_000, 0, 0, 0))
        assertTrue(sink.onPlaybackAudioFrameBeforeMixing("room", 7, 0, 480, 2, 1, 48_000, borrowed, 1_000, 0, 0, 0))
        assertTrue(frames.isEmpty())
        assertTrue(sink.onPlaybackAudioFrameBeforeMixing("room", 42, 0, 480, 2, 1, 48_000, borrowed, 1_000, 0, 0, 0))
        assertEquals(4, borrowed.position())
        assertEquals(960, frames.single().data.size)
        assertEquals(4.toByte(), frames.single().data[0])
        borrowed.put(4, 0)
        assertEquals(4.toByte(), frames.single().data[0])
        assertEquals(1_000L, frames.single().renderTimeMs)
        assertEquals(0x0008, sink.observedAudioFramePosition)
    }

    @Test fun `malformed layout emits an invalidation packet without reading beyond native storage`() {
        val frames = mutableListOf<RtcRemoteAudioFrame>()
        val sink = RtcRemoteAudioSink { _, _ -> RtcRemoteAudioFrameListener { frames += it } }
        sink.onPlaybackAudioFrameBeforeMixing("room", 42, 0, 480, 2, 1, 48_000, ByteBuffer.allocate(2), 1_000, 0, 0, 0)
        assertTrue(frames.single().data.isEmpty())
    }
}
