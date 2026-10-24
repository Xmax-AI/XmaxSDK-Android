//
//  Mp4VideoEncoderTest.kt
//  XmaxSDK
//  Repo: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime.recording

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 MediaCodec/MediaMuxer 验证；只在测试结束时删除本测试创建的文件和相册条目。 */
@RunWith(AndroidJUnit4::class)
class Mp4VideoEncoderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun incompatibleAudioVideoClocksAreRejectedBeforeNativeMuxerStarts() {
        val video = encodeClip()
        val output = File.createTempFile("xlab-invalid-clock-test-", ".mp4", context.cacheDir)
        try {
            val failure = runCatching {
                mergeRecordingTracks(output, RecordingTrack(video, 41_819_000_000L),
                    RecordingTrack(video, 1_792_491_495_491_000L), 90)
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("时间线无效"))
            assertEquals(0L, output.length())
        } finally {
            video.delete()
            output.delete()
        }
    }

    @Test fun aacAndH264TracksPreserveSharedOriginAndAudioGaps() {
        val video = encodeClip()
        val audio = File.createTempFile("xlab-audio-test-", ".m4a", context.cacheDir)
        val output = File.createTempFile("xlab-av-test-", ".mp4", context.cacheDir)
        try {
            val format = RecordingAudioFormat(48_000, 1)
            Mp4AudioEncoder(audio, format).use { encoder ->
                repeat(20) { index ->
                    val time = 8_900_000L + index * 10_000L + if (index >= 10) 50_000L else 0
                    val pcm = ByteBuffer.allocate(960).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    repeat(480) { sample -> pcm.putShort((kotlin.math.sin(sample * 0.1) * 4_000).toInt().toShort()) }
                    pcm.flip()
                    encoder.append(RecordingAudioFrame(format, 480, time, 10_000, pcm))
                }
                encoder.finish()
            }
            mergeRecordingTracks(output, RecordingTrack(video, 9_000_000), RecordingTrack(audio, 8_900_000), 90)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.absolutePath)
                assertEquals(2, extractor.trackCount)
                repeat(2) { track ->
                    val mediaFormat = extractor.getTrackFormat(track)
                    val mime = mediaFormat.getString(MediaFormat.KEY_MIME)!!
                    extractor.selectTrack(track)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    val first = extractor.sampleTime
                    assertTrue(first >= 0)
                    if (mime.startsWith("video/")) {
                        assertEquals(100_000L, first)
                    } else {
                        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, mime)
                        assertEquals(48_000, mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                        assertEquals(1, mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                        var end = first
                        while (extractor.sampleTime >= 0) {
                            end = extractor.sampleTime
                            extractor.advance()
                        }
                        assertTrue(end >= 230_000L)
                    }
                    extractor.unselectTrack(track)
                }
            } finally { extractor.release() }
        } finally {
            video.delete()
            audio.delete()
            output.delete()
        }
    }

    @Test fun finalI420FramesProduceDecodableSilentMp4WithNormalizedTimestampsAndRotation() {
        val file = encodeClip()
        try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertEquals(1, extractor.trackCount)
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                extractor.selectTrack(0)
                var count = 0
                var previous = -1L
                while (extractor.sampleTime >= 0) {
                    val time = extractor.sampleTime
                    if (count == 0) assertEquals(0L, time)
                    assertTrue(time > previous)
                    previous = time
                    count++
                    extractor.advance()
                }
                assertEquals(6, count)
            } finally { extractor.release() }
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(file.absolutePath)
                assertEquals("90", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
                assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 200)
                val bitmap = checkNotNull(metadata.getFrameAtTime(0))
                try {
                    val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    assertTrue(Color.red(color) > Color.green(color) + 80)
                    assertTrue(Color.red(color) > Color.blue(color) + 80)
                } finally { bitmap.recycle() }
            } finally { metadata.release() }
        } finally { file.delete() }
    }

    @Test fun completedVideoIsPublishedAsReadableMediaStoreVideo() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val file = encodeClip()
        var uri: android.net.Uri? = null
        try {
            val saved = RecordingVideoStore(context).save(file)
            uri = saved
            context.contentResolver.query(saved,
                arrayOf(MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.MIME_TYPE), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals("video/mp4", cursor.getString(1))
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, saved, null)
                assertEquals(1, extractor.trackCount)
            } finally { extractor.release() }
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            file.delete()
        }
    }

    private fun encodeClip(): File {
        val file = File.createTempFile("xlab-recording-test-", ".mp4", context.cacheDir)
        try {
            Mp4VideoEncoder(file, RecordingFormat(320, 240, 90)).use { encoder ->
                repeat(6) { index ->
                    encoder.append(RecordingFrame(320, 240, 90, 9_000_000L + index * 40_000L, 40_000L,
                        ByteBuffer.wrap(ByteArray(320 * 240) { 81 }),
                        ByteBuffer.wrap(ByteArray(160 * 120) { 90 }),
                        ByteBuffer.wrap(ByteArray(160 * 120) { 240.toByte() })))
                }
                encoder.finish()
            }
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }
}
