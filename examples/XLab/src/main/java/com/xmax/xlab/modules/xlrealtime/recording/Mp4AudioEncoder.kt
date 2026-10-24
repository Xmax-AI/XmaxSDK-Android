//
//  Mp4AudioEncoder.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes remote PCM as AAC, preserving missing audio intervals as silence rather than compressing time.
 */
internal class Mp4AudioEncoder(file: File, private val format: RecordingAudioFormat) : RecordingAudioEncoder {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var codecStarted = false
    private var muxerStarted = false
    private var trackIndex = -1
    private var outputSamples = 0
    private val info = MediaCodec.BufferInfo()
    private val timeline = RecordingAudioTimeline(format)

    init {
        try {
            val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, format.sampleRate, format.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec!!.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec!!.start()
            codecStarted = true
        } catch (error: Throwable) {
            runCatching { close() }.onFailure { if (it !== error) error.addSuppressed(it) }
            throw error
        }
    }

    override fun append(frame: RecordingAudioFrame) {
        check(frame.format == format) { "录制音频格式发生变化" }
        val deadline = deadline()
        val missingSamples = timeline.missingSamples(frame.presentationTimeUs)
        var missing = missingSamples
        while (missing > 0) {
            checkDeadline(deadline)
            val count = minOf(missing, 4_096).toInt()
            write(ByteBuffer.allocate(count * format.channels * 2))
            missing -= count
        }
        write(frame.data.duplicate())
    }

    private fun write(data: ByteBuffer) {
        val encoder = checkNotNull(codec)
        val bytesPerSample = format.channels * 2
        val deadline = deadline()
        while (data.hasRemaining()) {
            val index = awaitInput(deadline)
            val input = checkNotNull(encoder.getInputBuffer(index)).apply { clear() }
            val count = minOf(input.remaining(), data.remaining()) / bytesPerSample * bytesPerSample
            check(count > 0) { "音频编码器输入缓冲区不足" }
            val source = data.slice().apply { limit(count) }
            input.put(source)
            data.position(data.position() + count)
            encoder.queueInputBuffer(index, 0, count, timeline.next(count / bytesPerSample), 0)
            check(!drain(false)) { "音频编码提前结束" }
        }
    }

    override fun finish() {
        val deadline = deadline()
        val encoder = checkNotNull(codec)
        encoder.queueInputBuffer(awaitInput(deadline), 0, 0, timeline.endTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        while (!drain(true)) checkDeadline(deadline)
        check(muxerStarted && outputSamples > 0) { "没有可保存的音频" }
        muxer!!.stop()
        muxerStarted = false
    }

    private fun awaitInput(deadline: Long): Int {
        while (true) {
            checkDeadline(deadline)
            val index = codec!!.dequeueInputBuffer(10_000)
            if (index >= 0) return index
            check(!drain(false)) { "音频编码提前结束" }
        }
    }

    private fun drain(wait: Boolean): Boolean {
        val encoder = checkNotNull(codec)
        while (true) {
            when (val index = encoder.dequeueOutputBuffer(info, if (wait) 10_000 else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "音频编码输出格式发生变化" }
                    trackIndex = muxer!!.addTrack(encoder.outputFormat)
                    muxer!!.start()
                    muxerStarted = true
                }
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted)
                            val data = checkNotNull(encoder.getOutputBuffer(index)).apply {
                                position(info.offset)
                                limit(info.offset + info.size)
                            }
                            muxer!!.writeSampleData(trackIndex, data, info)
                            outputSamples++
                        }
                    } finally { encoder.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    override fun close() {
        val encoder = codec.also { codec = null }
        val writer = muxer.also { muxer = null }
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            runCatching(action).onFailure { error ->
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        if (encoder != null) {
            if (codecStarted) release { encoder.stop() }
            release { encoder.release() }
        }
        if (writer != null) {
            if (muxerStarted) release { writer.stop() }
            release { writer.release() }
        }
        codecStarted = false
        muxerStarted = false
        failure?.let { throw it }
    }

    private fun deadline() = System.nanoTime() + 5_000_000_000L

    private fun checkDeadline(deadline: Long) {
        check(System.nanoTime() < deadline) { "音频编码超时，请重试" }
    }
}

/**
 * Uses cumulative sample counts to preserve PCM duration and forward gaps without rounding drift.
 */
internal class RecordingAudioTimeline(private val format: RecordingAudioFormat) {
    private var originUs: Long? = null
    private var samples = 0L
    val endTimeUs get() = samples * 1_000_000 / format.sampleRate

    fun missingSamples(presentationTimeUs: Long): Long {
        val origin = originUs ?: presentationTimeUs.also { originUs = it }
        val delta = Math.subtractExact(presentationTimeUs, origin)
        check(delta >= 0 && delta <= (Long.MAX_VALUE - 500_000) / format.sampleRate) { "音频时间线无效" }
        val requestedSamples = (delta * format.sampleRate + 500_000) / 1_000_000
        check(requestedSamples >= samples) { "音频时间线发生重叠" }
        return requestedSamples - samples
    }

    fun next(count: Int): Long = endTimeUs.also { samples = Math.addExact(samples, count.toLong()) }
}
