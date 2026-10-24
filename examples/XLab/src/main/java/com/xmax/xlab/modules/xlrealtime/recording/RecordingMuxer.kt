//
//  RecordingMuxer.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Merges independently encoded audio/video tracks using one recording origin and original track offsets.
 */
internal fun mergeRecordingTracks(output: File, video: RecordingTrack, audio: RecordingTrack, rotation: Int) {
    val extractors = mutableListOf<MediaExtractor>()
    var muxer: MediaMuxer? = null
    var started = false
    try {
        val tracks = listOf(video, audio)
        val origin = minOf(video.originUs, audio.originUs)
        val offsets = tracks.map { Math.subtractExact(it.originUs, origin) }
        offsets.forEach(::validateRecordingTime)
        val formats = tracks.map { track ->
            val extractor = MediaExtractor().also { extractors += it }
            extractor.setDataSource(track.file.absolutePath)
            check(extractor.trackCount == 1) { "录制中间文件轨道无效" }
            extractor.selectTrack(0)
            extractor.getTrackFormat(0).also { format ->
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    validateRecordingTime(Math.addExact(format.getLong(MediaFormat.KEY_DURATION), offsets[extractors.lastIndex]))
                }
            }
        }
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
        val indices = formats.map { muxer.addTrack(it) }
        val capacity = formats.maxOf { format ->
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        }.coerceAtLeast(8 * 1_024 * 1_024)
        val buffer = ByteBuffer.allocateDirect(capacity)
        val info = MediaCodec.BufferInfo()
        muxer.start()
        started = true
        while (true) {
            val next = extractors.indices.filter { extractors[it].sampleTime >= 0 }
                .minByOrNull { Math.addExact(extractors[it].sampleTime, offsets[it]) } ?: break
            val extractor = extractors[next]
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            check(size >= 0 && size <= buffer.capacity()) { "录制样本尺寸无效" }
            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            val timeUs = Math.addExact(extractor.sampleTime, offsets[next])
            validateRecordingTime(timeUs)
            info.set(0, size, timeUs, flags)
            muxer.writeSampleData(indices[next], buffer, info)
            extractor.advance()
        }
        formats.forEachIndexed { index, format ->
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                info.set(0, 0, Math.addExact(format.getLong(MediaFormat.KEY_DURATION), offsets[index]), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                muxer.writeSampleData(indices[index], ByteBuffer.allocate(0), info)
            }
        }
        muxer.stop()
        started = false
    } finally {
        extractors.forEach { it.release() }
        try {
            if (started) muxer?.stop()
        } finally { muxer?.release() }
    }
}

/**
 * Rejects invalid clock offsets and durations before they reach the native MP4 writer.
 */
internal fun validateRecordingTime(timeUs: Long) {
    check(timeUs in 0..86_400_000_000L) { "录制音视频时间线无效，请重新录制" }
}
