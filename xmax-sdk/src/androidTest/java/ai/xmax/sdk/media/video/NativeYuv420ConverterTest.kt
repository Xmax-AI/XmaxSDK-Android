//
//  NativeYuv420ConverterTest.kt
//  XmaxSDK
//  Repo: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.VideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoFramePlane
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.VideoRotation
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class NativeYuv420ConverterTest {
    @Test
    public fun nativeRgbaResizePreservesChannelsOrientationAndBilinearFiltering() {
        val source = ByteArray(64) { index -> ((index / 4) * 10 + index % 4).toByte() }
        val output = ByteArray(16)
        assertTrue(NativeYuv420Converter.resizeRgba(source, 4, 4, output, 2, 2))
        assertArrayEquals(
            byteArrayOf(25, 26, 27, 28, 45, 46, 47, 48, 105, 106, 107, 108, 125, 126, 127, -128),
            output,
        )
        assertArrayEquals(ByteArray(64) { index -> ((index / 4) * 10 + index % 4).toByte() }, source)
    }

    @Test
    public fun nativeConverterCopiesPlanarRowsWithoutPadding() {
        val source = Yuv420VideoFrameConverter.Source(
            width = 4,
            height = 4,
            cropLeft = 0,
            cropTop = 0,
            cropWidth = 4,
            cropHeight = 4,
            planes = listOf(
                directPlane(
                    ByteArray(16) { (it + 1).toByte() },
                    rowStride = 4,
                    pixelStride = 1,
                ),
                directPlane(
                    byteArrayOf(0, 21, 22, 0, 23, 24),
                    rowStride = 3,
                    pixelStride = 1,
                    offset = 1,
                ),
                directPlane(
                    byteArrayOf(0, 0, 31, 32, 0, 0, 33, 34),
                    rowStride = 4,
                    pixelStride = 1,
                    offset = 2,
                ),
            ),
        )
        val output = ByteArray(24)

        assertTrue(
            NativeYuv420Converter.convert(
                source = source,
                outputWidth = 4,
                outputHeight = 4,
                rotation = VideoRotation.ROTATION_0,
                output = output,
            ),
        )
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 21, 22, 23, 24, 31, 32, 33, 34),
            output,
        )
    }

    @Test
    public fun nativeConverterSplitsInterleavedChromaWithOffsetAndPadding() {
        val interleaved = ByteBuffer.allocateDirect(10)
        for (uvOrder in listOf(true, false)) {
            val uOffset = if (uvOrder) 0 else 1
            val vOffset = 1 - uOffset
            for ((index, rowOffset) in listOf(0, 2, 6, 8).withIndex()) {
                interleaved.put(rowOffset + uOffset, (21 + index).toByte())
                interleaved.put(rowOffset + vOffset, (31 + index).toByte())
            }
            val source = Yuv420VideoFrameConverter.Source(
                width = 4,
                height = 4,
                cropLeft = 0,
                cropTop = 0,
                cropWidth = 4,
                cropHeight = 4,
                planes = listOf(
                    directPlane(
                        byteArrayOf(0, 1, 2, 3, 4, 0, 5, 6, 7, 8, 0, 9, 10, 11, 12, 0, 13, 14, 15, 16),
                        rowStride = 5,
                        pixelStride = 1,
                        offset = 1,
                    ),
                    Yuv420VideoFrameConverter.Plane(
                        buffer = interleaved.duplicate().apply { limit(9 + uOffset) }.slice(),
                        rowStride = 6,
                        pixelStride = 2,
                        baseOffset = uOffset,
                    ),
                    Yuv420VideoFrameConverter.Plane(
                        buffer = interleaved.duplicate().apply { limit(9 + vOffset) }.slice(),
                        rowStride = 6,
                        pixelStride = 2,
                        baseOffset = vOffset,
                    ),
                ),
            )
            val output = ByteArray(24)

            assertTrue(
                NativeYuv420Converter.convert(
                    source = source,
                    outputWidth = 4,
                    outputHeight = 4,
                    rotation = VideoRotation.ROTATION_0,
                    output = output,
                ),
            )
            assertArrayEquals(
                byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 21, 22, 23, 24, 31, 32, 33, 34),
                output,
            )
        }
    }

    @Test
    public fun nativeConverterRotatesFlexibleChromaIntoPortraitI420() {
        val source = Yuv420VideoFrameConverter.Source(
            width = 4,
            height = 2,
            cropLeft = 0,
            cropTop = 0,
            cropWidth = 4,
            cropHeight = 2,
            planes = listOf(
                directPlane(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 4, 1),
                directPlane(byteArrayOf(10, 99, 11, 0), 4, 2),
                directPlane(byteArrayOf(20, 88, 21, 0), 4, 2),
            ),
        )
        val output = ByteArray(12)

        assertTrue(
            NativeYuv420Converter.convert(
                source = source,
                outputWidth = 2,
                outputHeight = 4,
                rotation = VideoRotation.ROTATION_90,
                output = output,
            ),
        )
        assertArrayEquals(byteArrayOf(5, 1, 6, 2, 7, 3, 8, 4, 10, 11, 20, 21), output)

        assertTrue(
            NativeYuv420Converter.convert(
                source = source,
                outputWidth = 2,
                outputHeight = 4,
                rotation = VideoRotation.ROTATION_90,
                output = output,
                mirrored = true,
            ),
        )
        assertArrayEquals(byteArrayOf(1, 5, 2, 6, 3, 7, 4, 8, 10, 11, 20, 21), output)
    }

    @Test
    public fun nativePreviewConverterProducesOpaqueBlackAndWhitePixels() {
        val pixels = IntArray(2)

        assertTrue(
            NativeYuv420Converter.convertI420ToArgb(
                y = byteArrayOf(16, 235.toByte()),
                yOffset = 0,
                yStride = 2,
                u = byteArrayOf(128.toByte()),
                uOffset = 0,
                uStride = 1,
                v = byteArrayOf(128.toByte()),
                vOffset = 0,
                vStride = 1,
                width = 2,
                height = 1,
                pixels = pixels,
            ),
        )
        assertEquals(0xFF000000.toInt(), pixels[0])
        assertEquals(0xFFFFFFFF.toInt(), pixels[1])
    }

    @Test
    public fun previewReadsChromaFromTheSharedI420Array() {
        val bytes = byteArrayOf(82, 82, 82, 82, 90, 240.toByte())
        val frame = VideoFrame(
            format = VideoFormat(2, 2, VideoPixelFormat.I420),
            timestampUs = 0,
            planes = listOf(
                VideoFramePlane(bytes, stride = 2, byteLength = 4, copyData = false),
                VideoFramePlane(bytes, stride = 1, byteOffset = 4, byteLength = 1, copyData = false),
                VideoFramePlane(bytes, stride = 1, byteOffset = 5, byteLength = 1, copyData = false),
            ),
        )

        val bitmap = Yuv420VideoFrameConverter.makePreviewBitmap(frame)
        try {
            assertEquals(0xFFFF0100.toInt(), bitmap.getPixel(1, 1))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    public fun previewReusesPixelArrayWhileEarlierBitmapKeepsItsPixels() {
        val format = VideoFormat(
            2,
            2,
            VideoPixelFormat.I420,
        )
        val chroma = VideoFramePlane(
            byteArrayOf(128.toByte()),
            stride = 1,
        )
        val pixels = IntArray(4)
        val frames = listOf(16, 235).map { luma ->
            VideoFrame(
                format = format,
                timestampUs = 0,
                planes = listOf(
                    VideoFramePlane(
                        ByteArray(4) { luma.toByte() },
                        stride = 2,
                    ),
                    chroma,
                    chroma,
                ),
            )
        }
        val black = Yuv420VideoFrameConverter.makePreviewBitmap(
            frame = frames[0],
            reusablePixels = pixels,
        )
        try {
            assertArrayEquals(IntArray(4) { 0xFF000000.toInt() }, pixels)
            val white = Yuv420VideoFrameConverter.makePreviewBitmap(
                frame = frames[1],
                reusablePixels = pixels,
            )
            try {
                assertArrayEquals(IntArray(4) { 0xFFFFFFFF.toInt() }, pixels)
                assertEquals(0xFF000000.toInt(), black.getPixel(1, 1))
                assertEquals(0xFFFFFFFF.toInt(), white.getPixel(1, 1))
            } finally {
                white.recycle()
            }
        } finally {
            black.recycle()
        }
    }

    @Test
    public fun nativeCropScaleAndRotationMatchesKotlinFallback() {
        val width = 8
        val height = 6
        val y = ByteArray(width * height) { it.toByte() }
        val u = ByteArray(width * height / 2) { (it + 64).toByte() }
        val v = ByteArray(width * height / 2) { (it + 96).toByte() }
        val nativeSource = source(width, height, y, u, v, direct = true)
        val fallbackSource = source(width, height, y, u, v, direct = false)

        VideoRotation.entries.forEach { rotation ->
            val nativeFrame = Yuv420VideoFrameConverter.convert(
                source = nativeSource,
                outputWidth = 4,
                outputHeight = 6,
                rotation = rotation,
                timestampUs = 0,
            )
            val fallbackFrame = Yuv420VideoFrameConverter.convert(
                source = fallbackSource,
                outputWidth = 4,
                outputHeight = 6,
                rotation = rotation,
                timestampUs = 0,
            )

            nativeFrame.planes.zip(fallbackFrame.planes).forEach { (native, fallback) ->
                assertArrayEquals(fallback.selectedBytes(), native.selectedBytes())
            }
        }
    }

    private fun directPlane(
        bytes: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        offset: Int = 0,
    ): Yuv420VideoFrameConverter.Plane = Yuv420VideoFrameConverter.Plane(
        buffer = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        },
        rowStride = rowStride,
        pixelStride = pixelStride,
        baseOffset = offset,
    )

    private fun source(
        width: Int,
        height: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        direct: Boolean,
    ): Yuv420VideoFrameConverter.Source = Yuv420VideoFrameConverter.Source(
        width = width,
        height = height,
        cropLeft = 2,
        cropTop = 0,
        cropWidth = 6,
        cropHeight = 6,
        planes = listOf(
            plane(y, width, 1, direct),
            plane(u, width, 2, direct),
            plane(v, width, 2, direct),
        ),
    )

    private fun plane(
        bytes: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        direct: Boolean,
    ): Yuv420VideoFrameConverter.Plane = if (direct) {
        directPlane(bytes, rowStride, pixelStride)
    } else {
        Yuv420VideoFrameConverter.Plane(
            buffer = ByteBuffer.wrap(bytes),
            rowStride = rowStride,
            pixelStride = pixelStride,
        )
    }

}
