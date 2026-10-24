//
//  MediaServiceTest.kt
//  XmaxSDK
//  Repo: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.media

import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.RealtimeMediaSource
import androidx.compose.ui.geometry.Size
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class MediaServiceTest {
    @Test
    fun `generation canvas selects nearest aspect without changing media input`() {
        RealtimeModel.entries.forEach { model ->
            val service = MediaService(model)
            for (size in listOf(IntSize(1920, 1080), IntSize(1280, 720), IntSize(3840, 2160))) {
                assertEquals(IntSize(1920, 1024), service.resolveGenerationSize(size))
                assertEquals(IntSize(1024, 1920), service.resolveGenerationSize(IntSize(size.height, size.width)))
            }
            assertEquals(IntSize(1920, 1024), service.resolveGenerationSize(IntSize(640, 480)))
            assertEquals(IntSize(1024, 1920), service.resolveGenerationSize(IntSize(480, 640)))
            model.supportedVideoFormats.map { IntSize(it.width, it.height) }.forEach { assertEquals(it, service.resolveGenerationSize(it)) }
            assertTrue(service.resolveGenerationSize(IntSize(640, 640)) in model.supportedVideoFormats.map { IntSize(it.width, it.height) })
            assertThrows(XmaxError::class.java) { service.resolveGenerationSize(IntSize(0, 1080)) }
        }
    }

    @Test fun `camera preserves only the declared buckets`() {
        RealtimeModel.entries.forEach { model ->
            val service = MediaService(model)
            model.supportedVideoFormats.map { IntSize(it.width, it.height) }.forEach { assertEquals(it, service.resolveMediaSize(it, RealtimeMediaSource.CAMERA)) }
            val format = model.defaultCameraVideoFormat
            assertEquals(IntSize(format.width, format.height), service.resolveMediaSize(IntSize(format.width, format.height), RealtimeMediaSource.CAMERA))
            listOf(IntSize(832, 1472), IntSize(1920, 1080), IntSize(1080, 1920), IntSize(512, 960),
                IntSize(2048, 3840), IntSize(1120, 1120), IntSize(1408, 1408), IntSize(0, 1920)).forEach { size ->
                val error = assertThrows(XmaxError::class.java) { service.resolveMediaSize(size, RealtimeMediaSource.CAMERA) }
                assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
            }
        }
    }

    @Test
    fun `non camera media respects area bounds without requiring a model bucket`() {
        val cases = listOf(
            IntSize(3840, 2160) to IntSize(1920, 1080),
            IntSize(2160, 3840) to IntSize(1080, 1920),
            IntSize(8000, 6000) to IntSize(1664, 1248),
            IntSize(6000, 8000) to IntSize(1248, 1664),
            IntSize(1280, 1707) to IntSize(1252, 1672),
            IntSize(1707, 1280) to IntSize(1672, 1252),
            IntSize(1000, 500) to IntSize(1000, 500),
            IntSize(1004, 600) to IntSize(1004, 600),
            IntSize(1001, 1000) to IntSize(1000, 1000),
            IntSize(1001, 600) to IntSize(1000, 600),
        )
        for (model in RealtimeModel.entries) {
            val service = MediaService(model)
            for (source in listOf(RealtimeMediaSource.IMAGE, RealtimeMediaSource.VIDEO, RealtimeMediaSource.EXTERNAL)) {
                for ((input, expected) in cases) {
                    val actual = service.resolveMediaSize(input, source)
                    assertEquals(expected, actual)
                    assertTrue(actual.width.toLong() * actual.height <= model.maximumInputPixels)
                    assertTrue(actual.width.toLong() * actual.height >= model.minimumInputPixels)
                    assertEquals(0, actual.width % 4)
                    assertEquals(0, actual.height % 4)
                    assertTrue(actual.width <= input.width && actual.height <= input.height)
                    assertEquals(actual, service.resolveTargetSize(actual, Size(actual.width.toFloat(), actual.height.toFloat())))
                }
            }
        }
    }

    @Test
    fun `small media is upscaled to the input minimum for every non camera source`() {
        val cases = listOf(
            IntSize(400, 752) to IntSize(400, 752),
            IntSize(752, 400) to IntSize(752, 400),
            IntSize(512, 960) to IntSize(512, 960),
            IntSize(640, 480) to IntSize(640, 480),
            IntSize(640, 640) to IntSize(640, 640),
            IntSize(256, 480) to IntSize(352, 660),
            IntSize(200, 200) to IntSize(448, 448),
            IntSize(1, 1) to IntSize(448, 448),
        )
        for (model in RealtimeModel.entries) {
            val service = MediaService(model)
            for (source in listOf(RealtimeMediaSource.IMAGE, RealtimeMediaSource.VIDEO, RealtimeMediaSource.EXTERNAL)) {
                for ((original, expected) in cases) {
                    val resolved = service.resolveMediaSize(original, source)
                    assertEquals(expected, resolved)
                    assertEquals(0, resolved.width % 4)
                    assertEquals(0, resolved.height % 4)
                    assertTrue(resolved.width.toLong() * resolved.height in model.minimumInputPixels.toLong()..model.maximumInputPixels.toLong())
                    assertEquals(original.width.toLong() * resolved.height, original.height.toLong() * resolved.width)
                }
                assertEquals(IntSize(1000, 500), service.resolveMediaSize(IntSize(1001, 500), source))
                assertEquals(IntSize(512, 1000), service.resolveMediaSize(IntSize(512, 1000), source))
            }
        }
    }

    @Test
    fun `alignment preserves area bounds near the minimum when exact aspect cannot fit`() {
        for (model in RealtimeModel.entries) {
            val service = MediaService(model)
            for (source in listOf(RealtimeMediaSource.IMAGE, RealtimeMediaSource.VIDEO, RealtimeMediaSource.EXTERNAL)) {
                for (original in listOf(IntSize(1001, 199), IntSize(999, 200), IntSize(1000, 200))) {
                    for (size in listOf(original, IntSize(original.height, original.width))) {
                        val resolved = service.resolveMediaSize(size, source)
                        assertTrue(resolved.width.toLong() * resolved.height in model.minimumInputPixels.toLong()..model.maximumInputPixels.toLong())
                        assertEquals(0, resolved.width % 4)
                        assertEquals(0, resolved.height % 4)
                        val ratioError = kotlin.math.abs(resolved.width.toDouble() * size.height / (resolved.height.toDouble() * size.width) - 1)
                        assertTrue(ratioError < 0.01)
                    }
                }
            }
        }
    }

    @Test
    fun `extreme aspects and alignment boundaries remain within model area limits`() {
        val cases = listOf(IntSize(1, 200001), IntSize(447, 448), IntSize(999, 501),
            IntSize(1449, 1450), IntSize(65535, 65537), IntSize(Int.MAX_VALUE, 4))
        for (model in RealtimeModel.entries) {
            val service = MediaService(model)
            for (original in cases) {
                for (size in listOf(original, IntSize(original.height, original.width))) {
                    val resolved = service.resolveMediaSize(size, RealtimeMediaSource.EXTERNAL)
                    assertEquals(0, resolved.width % 4)
                    assertEquals(0, resolved.height % 4)
                    assertTrue(resolved.width.toLong() * resolved.height in model.minimumInputPixels.toLong()..model.maximumInputPixels.toLong())
                }
            }
        }
    }

    @Test
    fun `upscaled media does not impose the input minimum on returned dimensions`() {
        val service = MediaService()
        val media = service.resolveMediaSize(IntSize(200, 200), RealtimeMediaSource.VIDEO)
        val target = service.resolveTargetSize(media, Size(media.width / 2f, media.height / 2f))
        assertEquals(IntSize(448, 448), media)
        assertEquals(IntSize(224, 224), target)
        assertTrue(target.width.toLong() * target.height < service.model.minimumInputPixels)
    }

    @Test
    fun `target preserves exact aspect within bounds without upscaling`() {
        val service = MediaService()
        assertEquals(IntSize(720, 1280), service.resolveTargetSize(IntSize(720, 1280), Size(800f, 1600f)))
        assertEquals(IntSize(432, 768), service.resolveTargetSize(IntSize(720, 1280), Size(600f, 800f)))
        assertEquals(IntSize(800, 1500), service.resolveTargetSize(IntSize(1024, 1920), Size(819.2f, 1536f)))
        assertEquals(IntSize(640, 1200), service.resolveTargetSize(IntSize(1024, 1920), Size(665.6f, 1248f)))
        assertEquals(IntSize(800, 600), service.resolveTargetSize(IntSize(802, 600), Size(802f, 600f)))
    }

    @Test
    fun `invalid input and sub alignment target dimensions fail before media allocation`() {
        val service = MediaService()
        for (edge in listOf(-1, 0)) {
            for (size in listOf(IntSize(edge, 600), IntSize(600, edge))) {
                for (source in RealtimeMediaSource.entries) {
                    assertThrows(XmaxError::class.java) { service.resolveMediaSize(size, source) }
                }
            }
        }
        for (edge in listOf(-1, 0, 1, 2, 3)) {
            for (size in listOf(IntSize(edge, 600), IntSize(600, edge))) {
                assertThrows(XmaxError::class.java) { service.resolveTargetSize(size, Size(720f, 1280f)) }
            }
        }
        for (edge in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f, 3f)) {
            for (bounds in listOf(Size(edge, 1280f), Size(720f, edge))) {
                assertThrows(XmaxError::class.java) { service.resolveTargetSize(IntSize(720, 1280), bounds) }
            }
        }
    }
}
