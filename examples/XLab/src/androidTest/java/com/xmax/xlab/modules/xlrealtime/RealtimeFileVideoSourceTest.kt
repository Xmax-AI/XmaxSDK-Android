//
//  RealtimeFileVideoSourceTest.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime

import ai.xmax.sdk.RealtimeExternalFrameSink
import ai.xmax.sdk.RealtimeVideoFormat
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xmax.xlab.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies actual Media3 decoding and external video/PCM delivery without a generation session.
 */
@RunWith(AndroidJUnit4::class)
class RealtimeFileVideoSourceTest {

    @Test
    fun selectedVideoSuppliesOriginalRgbaAndPcmWhileOnlyPreviewing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.realtime_debug_video}")
        val source = RealtimeFileVideoSource.make(context, uri)
        val sink = Sink(source.videoFormat)
        try {
            assertTrue(source.hasAudio)
            assertTrue(source.providesPreview)
            withContext(Dispatchers.Main.immediate) { source.start(sink) }
            assertTrue("No decoded video reached the external sink", sink.video.await(10, TimeUnit.SECONDS))
            assertTrue("No decoded PCM reached the external sink", sink.audio.await(10, TimeUnit.SECONDS))
            sink.failure.get()?.let { throw AssertionError("External decoding failed", it) }
            assertTrue(sink.videoFrames.get() > 0)
            assertTrue(sink.audioPackets.get() > 0)

            sink.active = false
            withContext(Dispatchers.Main.immediate) {
                source.stop()
                source.stop()
            }
        } finally {
            sink.active = false
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                source.stop()
                source.release()
                source.release()
            }
        }
    }

    private class Sink(override val videoFormat: RealtimeVideoFormat) : RealtimeExternalFrameSink {
        @Volatile var active = true
        override val isRequestingFrames: Boolean get() = active
        val video = CountDownLatch(1)
        val audio = CountDownLatch(1)
        val videoFrames = AtomicInteger()
        val audioPackets = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        private val lastVideoUs = AtomicLong(-1)
        private val lastAudioUs = AtomicLong(-1)

        override fun pushVideo(rgba: ByteArray, timestampUs: Long) {
            if (!active) return

            assertEquals(videoFormat.width * videoFormat.height * 4, rgba.size)
            assertTrue(timestampUs > lastVideoUs.getAndSet(timestampUs))
            videoFrames.incrementAndGet()
            video.countDown()
        }

        override fun pushAudio(pcm: ByteArray, timestampUs: Long, sampleRate: Int, channelCount: Int) {
            if (!active) return

            assertTrue(sampleRate > 0 && channelCount > 0)
            assertTrue(pcm.isNotEmpty() && pcm.size % (channelCount * 2) == 0)
            assertTrue(timestampUs > lastAudioUs.getAndSet(timestampUs))
            audioPackets.incrementAndGet()
            audio.countDown()
        }

        override fun reportError(error: Throwable) {
            failure.compareAndSet(null, error)
            video.countDown()
            audio.countDown()
        }
    }
}
