//
//  ExternalVideoFrameSink.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.external

import ai.xmax.sdk.*
import ai.xmax.sdk.media.audio.ExternalAudioFrameConverter
import ai.xmax.sdk.media.video.NativeYuv420Converter

/**
 * Accepts original external pixels and PCM for one binding, isolating their storage and failure state.
 *
 * Invalidation waits for synchronous submissions and rejects all subsequent frames and errors.
 *
 * @param hasAudio Whether this binding accepts external PCM audio.
 */
internal class ExternalVideoFrameSink(
    /**
     * The original pixel dimensions declared by the source.
     */
    private val sourceVideoFormat: RealtimeVideoFormat,

    /**
     * The processed format shared by local preview and generation.
     */
    override val videoFormat: RealtimeVideoFormat,

    hasAudio: Boolean,

    /**
     * The synchronous receiver of copied, resized video frames.
     */
    private val frameListener: (VideoFrame) -> Unit,

    /**
     * The synchronous receiver of converted audio packets.
     */
    private val audioListener: (AudioFrame) -> Unit,

    /**
     * The receiver of the binding's first unrecoverable failure.
     */
    private val errorListener: (XmaxError) -> Unit,
) : RealtimeExternalFrameSink {

    /**
     * The lock serializing frame processing, failure, and invalidation.
     */
    private val lock = Any()

    /**
     * Whether this binding still accepts submissions.
     */
    private var active = true

    /**
     * The last accepted video timestamp, in microseconds.
     */
    private var lastVideoUs = -1L

    /**
     * The audio converter for sources declaring audio, released on invalidation.
     */
    private var audioConverter = if (hasAudio) ExternalAudioFrameConverter() else null

    /**
     * The first media failure, or null if the binding has not failed.
     */
    @Volatile var failure: XmaxError? = null
        private set

    init {
        require(sourceVideoFormat.width > 0 && sourceVideoFormat.height > 0)
        require(sourceVideoFormat.width.toLong() * sourceVideoFormat.height <= Int.MAX_VALUE / 4) {
            "External RGBA source dimensions exceed byte-array capacity"
        }
    }

    /**
     * Whether this binding accepts frames, including while generation is disconnected.
     */
    override val isRequestingFrames: Boolean
        get() = synchronized(lock) { active }

    /**
     * Copies and resizes an original RGBA frame for preview and uplink delivery.
     *
     * @param rgba Orientation-corrected pixels matching the source's original dimensions.
     * @param timestampUs The nonnegative, strictly increasing timestamp, in microseconds.
     */
    override fun pushVideo(
        rgba: ByteArray,
        timestampUs: Long,
    ) = submit {
        if (timestampUs < 0 || timestampUs <= lastVideoUs) return@submit

        require(rgba.size.toLong() == sourceVideoFormat.width.toLong() * sourceVideoFormat.height * 4) {
            "External RGBA pixels must match source.videoFormat dimensions"
        }
        val pixels = resize(rgba)
        lastVideoUs = timestampUs
        frameListener(
            VideoFrame(
                VideoFormat(
                    videoFormat.width,
                    videoFormat.height,
                    VideoPixelFormat.RGBA,
                ),
                timestampUs,
                listOf(
                    VideoFramePlane(
                        pixels,
                        videoFormat.width * 4,
                        copyData = false,
                    ),
                ),
            ),
        )
    }

    /**
     * Converts a PCM buffer into copied RTC audio packets.
     *
     * @param pcm Complete, interleaved PCM16 little-endian samples.
     * @param timestampUs The first sample's timestamp on the video clock, in microseconds.
     * @param sampleRate The input sample rate, in Hz.
     * @param channelCount The input channel count.
     */
    override fun pushAudio(
        pcm: ByteArray,
        timestampUs: Long,
        sampleRate: Int,
        channelCount: Int,
    ) = submit {
        audioConverter?.append(
            pcm,
            timestampUs,
            sampleRate,
            channelCount,
            audioListener,
        )
    }

    /**
     * Invalidates the binding and reports its first unrecoverable source failure.
     *
     * @param error The external capture, decoding, or delivery failure.
     */
    override fun reportError(
        error: Throwable,
    ) {
        synchronized(lock) {
            if (!active) return

            errorListener(fail(error))
        }
    }

    /**
     * Waits for in-flight submissions and rejects subsequent frames and errors.
     */
    fun invalidate() = synchronized(lock) {
        active = false
        audioConverter = null
    }

    /**
     * Delivers an active submission and makes processing failures terminal before releasing the lock.
     *
     * @param action The synchronous conversion and delivery operation.
     */
    private fun submit(
        action: () -> Unit,
    ) {
        synchronized(lock) {
            if (!active) return

            try {
                action()
            } catch (
                error: Exception,
            ) {
                errorListener(fail(error))
            }
        }
    }

    /**
     * Records the terminal media failure while holding the submission lock.
     *
     * @param error The source or frame-processing failure.
     * @return The media error delivered once to the owner.
     */
    private fun fail(
        error: Throwable,
    ): XmaxError {
        active = false
        audioConverter = null
        return XmaxError(
            XmaxErrorCode.MEDIA_ERROR,
            "External media source failed",
            cause = error,
        ).also { failure = it }
    }

    /**
     * Copies original RGBA pixels with bilinear resizing when processed dimensions differ.
     *
     * @param rgba The caller-owned original pixels.
     * @return Independently owned, tightly packed output pixels.
     */
    private fun resize(
        rgba: ByteArray,
    ): ByteArray {
        val sourceWidth = sourceVideoFormat.width
        val sourceHeight = sourceVideoFormat.height
        val width = videoFormat.width
        val height = videoFormat.height
        if (width == sourceWidth && height == sourceHeight) return rgba.copyOf()

        val output = ByteArray(width * height * 4)
        if (NativeYuv420Converter.resizeRgba(
            rgba,
            sourceWidth,
            sourceHeight,
            output,
            width,
            height,
        )) return output

        for (row in 0 until height) {
            val y = ((row + 0.5) * sourceHeight / height - 0.5).coerceIn(
                0.0,
                (sourceHeight - 1).toDouble(),
            )
            val top = y.toInt()
            val bottom = minOf(
                top + 1,
                sourceHeight - 1,
            )
            val fy = y - top
            for (column in 0 until width) {
                val x = ((column + 0.5) * sourceWidth / width - 0.5).coerceIn(
                    0.0,
                    (sourceWidth - 1).toDouble(),
                )
                val left = x.toInt()
                val right = minOf(
                    left + 1,
                    sourceWidth - 1,
                )
                val fx = x - left
                repeat(4) { channel ->
                    val a = rgba[(top * sourceWidth + left) * 4 + channel].toInt() and 255
                    val b = rgba[(top * sourceWidth + right) * 4 + channel].toInt() and 255
                    val c = rgba[(bottom * sourceWidth + left) * 4 + channel].toInt() and 255
                    val d = rgba[(bottom * sourceWidth + right) * 4 + channel].toInt() and 255
                    val upper = a + (b - a) * fx
                    val lower = c + (d - c) * fx
                    output[(row * width + column) * 4 + channel] = (upper + (lower - upper) * fy + 0.5).toInt().toByte()
                }
            }
        }
        return output
    }
}
