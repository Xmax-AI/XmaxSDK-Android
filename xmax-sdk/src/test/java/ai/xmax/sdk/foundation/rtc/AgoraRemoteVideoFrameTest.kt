//
//  AgoraRemoteVideoFrameTest.kt
//  XmaxSDK
//  Repo: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import io.agora.base.VideoFrame
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class AgoraRemoteVideoFrameTest {
    @Test fun `unset render start time preserves video output on the shared audio clock`() {
        var releases = 0
        lateinit var buffer: VideoFrame.I420Buffer
        buffer = Proxy.newProxyInstance(VideoFrame.I420Buffer::class.java.classLoader,
            arrayOf(VideoFrame.I420Buffer::class.java)) { _, method, _ ->
            when (method.name) {
                "getWidth" -> 4
                "getHeight" -> 2
                "getStrideY" -> 5
                "getStrideU", "getStrideV" -> 3
                "getDataY" -> ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 99, 5, 6, 7, 8))
                "getDataU" -> ByteBuffer.wrap(byteArrayOf(9, 10))
                "getDataV" -> ByteBuffer.wrap(byteArrayOf(11, 12))
                "toI420" -> buffer
                "release" -> { releases++; null }
                "retain" -> null
                else -> error("Unexpected call: ${method.name}")
            }
        } as VideoFrame.I420Buffer
        val renderTimeMs = 1_792_444_616_400L
        val nativeTimeNs = 401_009_113_000_000L
        val offsetUs = renderTimeMs * 1_000L - nativeTimeNs / 1_000L
        val native = VideoFrame(buffer, 90, nativeTimeNs, null, null, 0, 1f, 0, 0)
        val copy = AgoraRemoteVideoFrame(native, offsetUs).copy()
        assertEquals(4, copy.width)
        assertEquals(2, copy.height)
        assertEquals(90, copy.rotationDegrees)
        assertEquals(renderTimeMs * 1_000, copy.presentationTimeUs)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), copy.yData.bytes())
        assertArrayEquals(byteArrayOf(9, 10), copy.uData.bytes())
        assertArrayEquals(byteArrayOf(11, 12), copy.vData.bytes())
        assertEquals(1, releases)

        val audio = ai.xmax.sdk.render.video.RemoteAudioTimeline().resolve(
            RtcRemoteAudioFrame(ByteArray(960), 48_000, 1, 480, renderTimeMs),
        )!!.first
        assertEquals(audio.presentationTimeUs, copy.presentationTimeUs)

        val later = VideoFrame(buffer, 90, nativeTimeNs + 41_666_000L, null, null, 0, 1f, 0, 0)
        val laterCopy = AgoraRemoteVideoFrame(later, offsetUs).copy()
        assertEquals(41_666L, laterCopy.presentationTimeUs - copy.presentationTimeUs)

        val dispatcher = ai.xmax.sdk.render.video.RealtimeMediaFrameDispatcher(kotlinx.coroutines.Dispatchers.Unconfined)
        val generation = Any()
        val events = mutableListOf<ai.xmax.sdk.RealtimeMediaFrame>()
        dispatcher.setGeneration(generation)
        dispatcher.setListener { events += it }
        dispatcher.dispatch(generation, AgoraRemoteVideoFrame(native, offsetUs))
        assertTrue(events.single().content is ai.xmax.sdk.RealtimeMediaFrame.Content.Video)
        assertEquals(audio.presentationTimeUs, events.single().presentationTimeUs)
    }

    private fun ByteBuffer.bytes() = ByteArray(remaining()).also { get(it) }
}
