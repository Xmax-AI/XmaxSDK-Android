//
//  RealtimeVideoRecorder.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime.recording

import ai.xmax.sdk.RealtimeMediaFrame
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel

internal interface VideoRecording {
    val result: Deferred<File>
    fun append(frame: RealtimeMediaFrame)
    fun stop()
}

/**
 * Records owned remote media with bounded retention and a shared audio/video origin.
 * Separate track writers allow video-only recordings without waiting for an absent audio track.
 */
internal class RealtimeVideoRecorder(
    directory: File,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    audioEncoderFactory: (File, RecordingAudioFormat) -> RecordingAudioEncoder = ::Mp4AudioEncoder,
    mergeTracks: (File, RecordingTrack, RecordingTrack, Int) -> Unit = ::mergeRecordingTracks,
    encoderFactory: (File, RecordingFormat) -> RecordingEncoder = ::Mp4VideoEncoder,
) : VideoRecording {
    private val frames = Channel<RecordingPacket>(Channel.UNLIMITED)
    private val gate = Any()
    private var stopped = false
    private var retainedPackets = 0
    private var retainedBytes = 0L
    private var inputFailure: Throwable? = null
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    override val result: Deferred<File> = scope.async {
        var videoFile: File? = null
        var audioFile: File? = null
        var outputFile: File? = null
        var videoEncoder: RecordingEncoder? = null
        var audioEncoder: RecordingAudioEncoder? = null
        var videoFormat: RecordingFormat? = null
        var audioFormat: RecordingAudioFormat? = null
        var streamID: UUID? = null
        var videoOrigin: Long? = null
        var audioOrigin: Long? = null
        var lastVideoTime: Long? = null
        var audioEnd: Long? = null
        try {
            for (packet in frames) {
                try {
                    synchronized(gate) { inputFailure }?.let { throw it }
                    check(streamID == null || streamID == packet.streamID) { "生成流已变化，请重新录制" }
                    streamID = packet.streamID
                    check(directory.isDirectory || directory.mkdirs()) { "无法创建录制目录" }
                    when (packet) {
                        is RecordingPacket.Video -> {
                            val frame = packet.frame
                            if (videoEncoder == null) {
                                videoFile = File.createTempFile("xlab-video-", ".mp4", directory)
                                videoFormat = frame.format
                                videoOrigin = frame.presentationTimeUs
                                videoEncoder = encoderFactory(videoFile, frame.format)
                            }
                            check(frame.format == videoFormat) { "录制期间视频尺寸或方向发生变化，请重新录制" }
                            check(lastVideoTime?.let { frame.presentationTimeUs > it } != false) { "视频时间线发生回退，请重新录制" }
                            videoEncoder.append(frame)
                            lastVideoTime = frame.presentationTimeUs
                        }
                        is RecordingPacket.Audio -> {
                            val frame = packet.frame
                            if (audioEncoder == null) {
                                audioFile = File.createTempFile("xlab-audio-", ".m4a", directory)
                                audioFormat = frame.format
                                audioOrigin = frame.presentationTimeUs
                                audioEncoder = audioEncoderFactory(audioFile, frame.format)
                            }
                            check(frame.format == audioFormat) { "录制期间音频格式发生变化，请重新录制" }
                            check(audioEnd?.let { frame.presentationTimeUs >= it } != false) { "音频时间线发生回退，请重新录制" }
                            audioEncoder.append(frame)
                            audioEnd = Math.addExact(frame.presentationTimeUs, frame.durationUs)
                        }
                    }
                } finally {
                    synchronized(gate) {
                        retainedPackets--
                        retainedBytes -= packet.byteCount
                    }
                }
            }
            synchronized(gate) { inputFailure }?.let { throw it }
            val writer = checkNotNull(videoEncoder) { "尚未录制到生成画面，请稍后重试" }
            writer.finish()
            writer.close()
            videoEncoder = null
            audioEncoder?.finish()
            audioEncoder?.close()
            audioEncoder = null

            if (audioFile != null) {
                outputFile = File.createTempFile("xlab-recording-", ".mp4", directory)
                mergeTracks(
                    outputFile,
                    RecordingTrack(checkNotNull(videoFile), checkNotNull(videoOrigin)),
                    RecordingTrack(audioFile, checkNotNull(audioOrigin)),
                    checkNotNull(videoFormat).rotationDegrees,
                )
                outputFile
            } else {
                checkNotNull(videoFile).also { videoFile = null }
            }
        } catch (error: Throwable) {
            runCatching { videoEncoder?.close() }.onFailure { if (it !== error) error.addSuppressed(it) }
            runCatching { audioEncoder?.close() }.onFailure { if (it !== error) error.addSuppressed(it) }
            outputFile?.delete()
            throw error
        } finally {
            synchronized(gate) { stopped = true }
            frames.cancel()
            videoFile?.delete()
            audioFile?.delete()
        }
    }.also { it.invokeOnCompletion { scope.cancel() } }

    override fun append(frame: RealtimeMediaFrame) {
        append(when (val content = frame.content) {
            is RealtimeMediaFrame.Content.Video -> RecordingPacket.Video(frame.streamID, RecordingFrame.from(content.frame))
            is RealtimeMediaFrame.Content.Audio -> RecordingPacket.Audio(frame.streamID, RecordingAudioFrame.from(content.frame))
        })
    }

    internal fun append(frame: RecordingFrame) = append(RecordingPacket.Video(UUID(0, 0), frame))

    internal fun append(packet: RecordingPacket): Unit = synchronized(gate) {
        if (stopped) return
        if (retainedPackets >= 256 || packet.byteCount > 32L * 1_024 * 1_024 - retainedBytes) {
            inputFailure = IllegalStateException("录制处理速度不足，请缩短录制或降低分辨率")
            stopped = true
            frames.close()
            return
        }
        retainedPackets++
        retainedBytes += packet.byteCount
        frames.trySend(packet)
        Unit
    }

    override fun stop() = synchronized(gate) {
        stopped = true
        frames.close()
        Unit
    }
}

internal interface RecordingEncoder : AutoCloseable {
    fun append(frame: RecordingFrame)
    fun finish()
}

internal interface RecordingAudioEncoder : AutoCloseable {
    fun append(frame: RecordingAudioFrame)
    fun finish()
}
