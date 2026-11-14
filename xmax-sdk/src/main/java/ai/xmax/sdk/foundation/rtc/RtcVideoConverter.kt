//
//  RtcVideoConverter.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoPixelFormat
import android.view.View
import io.agora.rtc2.Constants
import io.agora.rtc2.video.AgoraVideoFrame
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration

/**
 * Converts between SDK and Agora video types.
 *
 * Output buffers support synchronous submission only.
 */
internal object RtcVideoConverter {

    /**
     * Creates an RTC video canvas for a view, user, and scaling mode.
     *
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     * @param uid The numeric RTC user identifier; defaults to 0.
     * @return An RTC canvas with the requested view and scaling mode.
     */
    fun makeCanvas(
        view: View?,
        contentMode: VideoContentMode,
        uid: Int = 0,
    ): VideoCanvas =
        VideoCanvas(
            view,
            convertRenderMode(contentMode),
            uid,
        ).apply {
            mirrorMode = Constants.VIDEO_MIRROR_MODE_DISABLED
        }

    /**
     * Converts SDK fit or fill scaling to the RTC rendering mode.
     *
     * @param contentMode Aspect-preserving fit or fill scaling.
     * @return The RTC aspect-fit or aspect-fill mode.
     */
    fun convertRenderMode(
        contentMode: VideoContentMode,
    ): Int = when (contentMode) {
        VideoContentMode.FIT -> VideoCanvas.RENDER_MODE_FIT
        VideoContentMode.FILL -> VideoCanvas.RENDER_MODE_HIDDEN
    }

    /**
     * Supplies contiguous pixels and a millisecond timestamp for Agora array frames.
     *
     * @param frame The video frame to process, display, or submit.
     * @param seiData Optional video metadata; unused by the current Agora implementation.
     * @return A video frame matching RTC input requirements.
     */
    fun convertFrame(
        frame: VideoFrame,
        seiData: ByteArray? = null,
    ): AgoraVideoFrame {
        val width = frame.format.width
        val height = frame.format.height
        val pixel = frame.format.pixelFormat
        val expected = when (pixel) {
            VideoPixelFormat.I420 -> 3
            VideoPixelFormat.NV12, VideoPixelFormat.NV21 -> 2
            else -> 1
        }
        require(frame.planes.size == expected) { "Video frame requires $expected data planes" }

        return AgoraVideoFrame().apply {
            format = when (pixel) {
                VideoPixelFormat.I420 -> AgoraVideoFrame.FORMAT_I420
                VideoPixelFormat.NV12, VideoPixelFormat.NV21 -> AgoraVideoFrame.FORMAT_NV21
                VideoPixelFormat.BGRA -> AgoraVideoFrame.FORMAT_BGRA
                else -> AgoraVideoFrame.FORMAT_RGBA
            }
            buf = contiguousI420Bytes(frame) ?: packFrame(frame)
            stride = width
            this.height = height
            rotation = frame.rotation.degrees
            timeStamp = frame.timestampUs / 1_000L
            metadataBuffer = seiData
        }
    }

    /**
     * Returns the shared array when all three I420 planes already match Agora's packed layout.
     *
     * @param frame The frame with its plane count validated.
     * @return The shared array, or null if the frame needs packing.
     */
    private fun contiguousI420Bytes(
        frame: VideoFrame,
    ): ByteArray? {
        val (width, height, pixelFormat) = frame.format
        if (pixelFormat != VideoPixelFormat.I420 || width % 2 != 0 || height % 2 != 0) return null

        val (y, u, v) = frame.planes
        val bytes = y.backingBytes()
        val yLength = width.toLong() * height
        val chromaLength = yLength / 4
        if (yLength + 2 * chromaLength != bytes.size.toLong()) return null

        val uOffset = yLength.toInt()
        val vOffset = uOffset + chromaLength.toInt()
        return bytes.takeIf {
            y.byteOffset == 0 && u.byteOffset == uOffset && v.byteOffset == vOffset &&
                y.byteLength == uOffset && u.byteLength == vOffset - uOffset &&
                v.byteLength == bytes.size - vOffset &&
                y.stride == width && u.stride == width / 2 && v.stride == width / 2 &&
                u.backingBytes() === bytes && v.backingBytes() === bytes
        }
    }

    /**
     * Produces contiguous Agora pixels from frames that require format conversion or row packing.
     *
     * @param frame The frame with its plane count validated.
     * @return The packed pixel array.
     */
    private fun packFrame(
        frame: VideoFrame,
    ): ByteArray {
        val (width, height, pixel) = frame.format
        val expected = frame.planes.size
        val packed = pixel in setOf(
            VideoPixelFormat.RGBA,
            VideoPixelFormat.BGRA,
            VideoPixelFormat.ARGB,
        )
        val rows = if (packed) listOf(height) else listOf(
            height,
            height / 2,
            height / 2,
        ).take(expected)
        val columns = if (packed) listOf(width * 4) else if (expected == 3) listOf(
            width,
            width / 2,
            width / 2,
        ) else listOf(
            width,
            width,
        )
        val size = rows.indices.sumOf { rows[it] * columns[it] }
        return if (packed && pixel != VideoPixelFormat.ARGB && frame.planes[0].stride == width * 4 && frame.planes[0].byteLength == size) {
            frame.planes[0].selectedBytesView()
        } else {
            ByteArray(size).also { output ->
                var offset = 0
                frame.planes.forEachIndexed { index, plane ->
                    val source = plane.byteBuffer()
                    require(plane.stride >= columns[index])
                    require((rows[index] - 1L) * plane.stride + columns[index] <= source.remaining())
                    repeat(rows[index]) { row ->
                        source.position(row * plane.stride)
                        source.get(
                            output,
                            offset,
                            columns[index],
                        )
                        offset += columns[index]
                    }
                }
                if (pixel == VideoPixelFormat.ARGB) {
                    for (i in output.indices step 4) {
                        val alpha = output[i]
                        output[i] = output[i + 1]
                        output[i + 1] = output[i + 2]
                        output[i + 2] = output[i + 3]
                        output[i + 3] = alpha
                    }
                }

                if (pixel == VideoPixelFormat.NV12) {
                    for (i in width * height until output.size step 2) {
                        val u = output[i]
                        output[i] = output[i + 1]
                        output[i + 1] = u
                    }
                }
            }
        }
    }

    /**
     * Maps the SDK encoder configuration to Agora without changing source orientation.
     *
     * @param configuration Uplink dimensions, frame rate, bitrate, and encoding preference.
     * @return The RTC encoder configuration.
     */
    fun makeEncoderConfiguration(
        configuration: VideoEncodingConfiguration,
    ): VideoEncoderConfiguration =
        VideoEncoderConfiguration().apply {
            dimensions = VideoEncoderConfiguration.VideoDimensions(
                configuration.width,
                configuration.height,
            )
            frameRate = configuration.frameRate
            bitrate = configuration.maximumBitrate
            minBitrate = configuration.minimumBitrate
            orientationMode = if (configuration.width > configuration.height) {
                VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_LANDSCAPE
            } else {
                VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT
            }
            mirrorMode = VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_DISABLED
            degradationPrefer = when (configuration.encoderPreference) {
                VideoEncodingConfiguration.EncoderPreference.AUTO -> VideoEncoderConfiguration.DEGRADATION_PREFERENCE.MAINTAIN_AUTO
                VideoEncodingConfiguration.EncoderPreference.MAINTAIN_FRAMERATE -> VideoEncoderConfiguration.DEGRADATION_PREFERENCE.MAINTAIN_FRAMERATE
                VideoEncodingConfiguration.EncoderPreference.MAINTAIN_QUALITY -> VideoEncoderConfiguration.DEGRADATION_PREFERENCE.MAINTAIN_QUALITY
            }
        }
}
