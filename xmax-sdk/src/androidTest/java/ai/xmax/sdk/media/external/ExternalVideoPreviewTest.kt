//
//  ExternalVideoPreviewTest.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.external

import ai.xmax.sdk.*
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.render.video.RealtimeVideoTestActivity
import ai.xmax.sdk.service.media.MediaService
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.widget.ImageView
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalVideoPreviewTest {

    @Test
    fun submittedFramesProvidePreviewWithoutAConnectionAndClearOnStop() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rtc = Proxy.newProxyInstance(RtcManaging::class.java.classLoader, arrayOf(RtcManaging::class.java)) { _, _, _ ->
            null
        } as RtcManaging
        val media = object : MediaServicing by MediaService() {
            override fun resolveMediaSize(size: IntSize, source: RealtimeMediaSource) = size
        }
        val errors = mutableListOf<XmaxError>()
        val controller = ExternalVideoController(rtc, media, {}, {}, errors::add)
        val source = Source()
        val stream = controller.create(source)
        val sink = checkNotNull(source.sink)
        val original = ByteArray(16) { index -> if (index % 4 == 0 || index % 4 == 3) 255.toByte() else 0 }
        sink.pushVideo(original, 0)
        original.fill(0)
        val scenario = ActivityScenario.launch<RealtimeVideoTestActivity>(
            Intent(instrumentation.targetContext, RealtimeVideoTestActivity::class.java),
        )
        lateinit var view: XmaxVideoView
        try {
            scenario.onActivity { activity ->
                view = XmaxVideoView(activity).apply { track = stream.videoTrack }
                activity.setContentView(view)
            }
            awaitColor(scenario, { view }, Color.RED)
            assertTrue(sink.isRequestingFrames)

            sink.pushVideo(ByteArray(16) { index -> if (index % 4 >= 2) 255.toByte() else 0 }, 1)
            awaitColor(scenario, { view }, Color.BLUE)

            controller.stop()
            assertFalse(sink.isRequestingFrames)
            sink.pushVideo(ByteArray(16), 2)
            scenario.onActivity { assertNull((view.getChildAt(0) as ImageView).drawable) }
            assertTrue(errors.isEmpty())
        } finally {
            controller.stop()
            scenario.close()
        }
    }

    private fun awaitColor(
        scenario: ActivityScenario<RealtimeVideoTestActivity>,
        view: () -> XmaxVideoView,
        expected: Int,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (SystemClock.elapsedRealtime() < deadline) {
            var color: Int? = null
            scenario.onActivity {
                val drawable = (view().getChildAt(0) as ImageView).drawable as? BitmapDrawable
                color = drawable?.bitmap?.getPixel(0, 0)
            }
            if (color == expected) return

            SystemClock.sleep(10)
        }
        fail("External preview did not display the expected RGBA color")
    }

    private class Source : RealtimeExternalVideoSource {
        override val videoFormat = RealtimeVideoFormat(2, 2, 30)
        var sink: RealtimeExternalFrameSink? = null

        override suspend fun start(sink: RealtimeExternalFrameSink) {
            this.sink = sink
        }

        override suspend fun stop() {
            sink = null
        }
    }
}
