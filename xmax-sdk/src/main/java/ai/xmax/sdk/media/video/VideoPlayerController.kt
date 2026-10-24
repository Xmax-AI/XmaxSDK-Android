//
//  VideoPlayerController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.XmaxVideoView
import ai.xmax.sdk.media.MediaAudioFrameListener
import ai.xmax.sdk.media.MediaVideoFrameListener
import ai.xmax.sdk.media.audio.LocalAudioPreviewPlayer
import ai.xmax.sdk.media.audio.PCMFramePacketizer
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Decodes looped local audio and video on a shared timeline using Android media APIs.
 *
 * @param context The Android context used for system services and media resources.
 */
internal class VideoPlayerController(
    context: Context,

    /**
     * The decoded video frame listener.
     */
    private val videoFrameListener: MediaVideoFrameListener,

    /**
     * The decoded audio frame listener.
     */
    private val audioFrameListener: MediaAudioFrameListener,

    /**
     * The media failure callback.
     */
    private val errorListener: (XmaxError) -> Unit,

    /**
     * The scope running continuous playback decoding.
     */
    private val playbackScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default,
    ),
) : VideoPlayerControlling {

    /**
     * The Android context for reading media.
     */
    private val applicationContext = context.applicationContext

    /**
     * The mutex serializing playback decoding operations.
     */
    private val operationMutex = Mutex()

    /**
     * The lock protecting player and decoder state.
     */
    private val stateLock = Any()

    /**
     * The local audio preview player.
     */
    private val audioPreviewPlayer = LocalAudioPreviewPlayer()

    /**
     * The dispatcher delivering decoded images to local preview.
     */
    private val previewDispatcher = DecodedVideoPreviewDispatcher()

    /**
     * The current playback configuration.
     */
    private var configuration: PlaybackConfiguration? = null

    /**
     * The coroutine driving looped audio and video decoding.
     */
    private var playbackJob: Job? = null

    /**
     * Sets the decoder source, output dimensions, rotation, frame rate, and duration.
     *
     * @param uri A media URI your app can read.
     * @param outputWidth The output width, in pixels.
     * @param outputHeight The output height, in pixels.
     * @param rotation The rotation applied to the source image.
     * @param frameRate The video frame rate, in fps.
     * @param hasAudio Whether the input provides audio.
     * @param durationUs The media duration, in microseconds.
     */
    override suspend fun configure(
        uri: Uri,
        outputWidth: Int,
        outputHeight: Int,
        rotation: VideoRotation,
        frameRate: Int,
        hasAudio: Boolean,
        durationUs: Long,
    ) = operationMutex.withLock {
        if (outputWidth <= 0 || outputHeight <= 0 ||
            outputWidth % 2 != 0 || outputHeight % 2 != 0 ||
            frameRate <= 0 || durationUs <= 0L ||
            synchronized(stateLock) { configuration != null || playbackJob != null }
        ) {
            throw mediaError("Video player configuration is invalid")
        }
        applicationContext.contentResolver.openAssetFileDescriptor(
            uri,
            "r",
        )?.use { Unit }
            ?: throw mediaError("The media file cannot be opened")
        synchronized(stateLock) {
            configuration = PlaybackConfiguration(
                uri = uri,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                rotation = rotation,
                frameRate = frameRate,
                hasAudio = hasAudio,
                durationUs = durationUs,
            )
        }
    }

    /**
     * Starts looped decoding and supplies media frames on the shared timeline.
     */
    override suspend fun start() = operationMutex.withLock {
        val resolvedConfiguration = synchronized(stateLock) {
            configuration?.takeIf { playbackJob == null }
        } ?: throw mediaError("Configure the video player before starting it")
        if (resolvedConfiguration.hasAudio) audioPreviewPlayer.start()
        val timeline = MediaPlaybackTimeline(resolvedConfiguration.durationUs)
        val job = playbackScope.launch {
            try {
                coroutineScope {
                    val jobs = mutableListOf(
                        launch {
                            produceVideoFrames(
                                resolvedConfiguration,
                                timeline,
                            )
                        },
                    )
                    if (resolvedConfiguration.hasAudio) {
                        jobs += launch {
                            produceAudioFrames(
                                resolvedConfiguration,
                                timeline,
                            )
                        }
                    }
                    jobs.joinAll()
                }
            } catch (
                _: CancellationException,
            ) {
                Unit
            } catch (
                error: Throwable,
            ) {
                XmaxLogger.media.error(
                    message = {
                        "本地视频播放停止 (Local Video Playback Stopped)\n" +
                            "└─ ${XmaxLogger.localized(
                                "原因：",
                                "Reason: ",
                            )}${playbackErrorDescription(error)}"
                    },
                )
                errorListener(
                    mediaError(
                        "Local video playback failed",
                        error,
                    ),
                )
            }
        }
        synchronized(stateLock) { playbackJob = job }
    }

    /**
     * Mutes or unmutes local preview without changing its stored volume.
     *
     * @param muted Whether audio is muted.
     */
    override suspend fun setLocalAudioPreviewMuted(
        muted: Boolean,
    ) {
        audioPreviewPlayer.setMuted(muted)
    }

    /**
     * Sets local preview volume from 0 to 1 without changing audio submitted to RTC.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    override suspend fun setLocalAudioVolume(
        volume: Float,
    ) {
        audioPreviewPlayer.setVolume(volume)
    }

    /**
     * Binds local media preview to a view with the requested scaling mode.
     *
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    override fun attachPreview(
        view: XmaxVideoView,
        contentMode: VideoContentMode,
    ) {
        previewDispatcher.attach(
            view,
            contentMode,
        )
    }

    /**
     * Unbinds local media preview from a view.
     *
     * @param view The view to bind or unbind.
     */
    override fun detachPreview(
        view: XmaxVideoView,
    ) {
        previewDispatcher.detach(view)
    }

    /**
     * Stops decoding and audio preview, discarding pending media frames.
     */
    override suspend fun stop() = operationMutex.withLock {
        val job = synchronized(stateLock) {
            playbackJob.also { playbackJob = null }
        }
        cleanupResources(
            { job?.cancel(); job?.join() },
            { audioPreviewPlayer.stop() },
            { previewDispatcher.clear() },
            { synchronized(stateLock) { configuration = null } },
        )
    }

    /**
     * Continuously decodes and submits video frames on the playback timeline.
     *
     * @param configuration The local media decoding and playback configuration.
     * @param timeline The shared timeline scheduling audio and video output.
     */
    private suspend fun produceVideoFrames(
        configuration: PlaybackConfiguration,
        timeline: MediaPlaybackTimeline,
    ) {
        var loopIndex = 0
        while (true) {
            coroutineContext.ensureActive()
            if (!decodeVideoCycle(
                configuration,
                timeline,
                loopIndex,
            )) {
                throw mediaError("The media file produced no video frames")
            }
            loopIndex += 1
            sleepUntil(
                timeline.target(
                    loopIndex,
                    0L,
                ),
            )
        }
    }

    /**
     * Decodes one video loop and submits frames on the timeline.
     *
     * @param configuration The local media decoding and playback configuration.
     * @param timeline The shared timeline scheduling audio and video output.
     * @param loopIndex The zero-based playback loop number.
     * @return True if this loop output any video frames.
     */
    private suspend fun decodeVideoCycle(
        configuration: PlaybackConfiguration,
        timeline: MediaPlaybackTimeline,
        loopIndex: Int,
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var decodingFailure: Throwable? = null
        var yieldedFrame = false
        try {
            extractor.setDataSource(
                applicationContext,
                configuration.uri,
                null,
            )
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw mediaError("The media file does not contain a video track")
            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: throw mediaError("The media video track has no MIME type")
            sourceFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(
                sourceFormat,
                null,
                null,
                0,
            )
            codec.start()
            val firstPresentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
            val frameIntervalUs = MICROSECONDS_PER_SECOND / configuration.frameRate
            val frameIntervalNanoseconds = frameIntervalUs * NANOSECONDS_PER_MICROSECOND
            val bufferInfo = MediaCodec.BufferInfo()
            val pendingFrames = ArrayDeque<ScheduledVideoFrame>(VIDEO_DECODE_BUFFER_SIZE)
            var inputEnded = false
            var outputEnded = false
            var lastYieldedTimeUs = Long.MIN_VALUE

            while (!outputEnded) {
                coroutineContext.ensureActive()
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(0L)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: throw mediaError("The video decoder input buffer is unavailable")
                        val sampleSize = extractor.readSampleData(
                            input,
                            0,
                        )
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(
                    bufferInfo,
                    CODEC_TIMEOUT_US,
                )) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    -> Unit
                    else -> if (outputIndex >= 0) {
                        var frame: VideoFrame? = null
                        val relativeTimeUs = (
                            bufferInfo.presentationTimeUs - firstPresentationTimeUs
                            ).coerceAtLeast(0L)
                        val hasFrameIntervalElapsed = lastYieldedTimeUs == Long.MIN_VALUE ||
                            relativeTimeUs - lastYieldedTimeUs >= frameIntervalUs * 3 / 4
                        val target = timeline.target(
                            loopIndex,
                            relativeTimeUs,
                        )
                        val isOnTime = SystemClock.elapsedRealtimeNanos() - target <=
                            frameIntervalNanoseconds
                        if (bufferInfo.size > 0 && hasFrameIntervalElapsed && isOnTime) {
                            val image = codec.getOutputImage(outputIndex)
                                ?: throw mediaError("The video decoder did not provide a YUV image")
                            frame = try {
                                Yuv420VideoFrameConverter.convert(
                                    image = image,
                                    outputWidth = configuration.outputWidth,
                                    outputHeight = configuration.outputHeight,
                                    rotation = configuration.rotation,
                                    timestampUs = target / NANOSECONDS_PER_MICROSECOND,
                                )
                            } finally {
                                image.close()
                            }
                            lastYieldedTimeUs = relativeTimeUs
                            yieldedFrame = true
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(
                            outputIndex,
                            false,
                        )
                        if (frame != null) {
                            pendingFrames.addLast(
                                ScheduledVideoFrame(
                                    frame,
                                    target,
                                ),
                            )
                            if (pendingFrames.size >= VIDEO_DECODE_BUFFER_SIZE) {
                                emitVideoFrame(pendingFrames.removeFirst())
                            }
                        }
                    }
                }
            }
            while (pendingFrames.isNotEmpty()) {
                emitVideoFrame(pendingFrames.removeFirst())
            }
            return yieldedFrame
        } catch (
            error: Throwable,
        ) {
            decodingFailure = error
            throw error
        } finally {
            val failure = decodingFailure
            if (failure == null) {
                cleanupResources(
                    { codec?.stop() },
                    { codec?.release() },
                    { extractor.release() },
                )
            } else {
                cleanupAfterFailure(
                    failure,
                    { codec?.stop() },
                    { codec?.release() },
                    { extractor.release() },
                )
            }
        }
    }

    /**
     * Continuously decodes and submits audio on the playback timeline.
     *
     * @param configuration The local media decoding and playback configuration.
     * @param timeline The shared timeline scheduling audio and video output.
     */
    private suspend fun produceAudioFrames(
        configuration: PlaybackConfiguration,
        timeline: MediaPlaybackTimeline,
    ) {
        var loopIndex = 0
        while (true) {
            coroutineContext.ensureActive()
            decodeAudioCycle(
                configuration,
                timeline,
                loopIndex,
            )
            loopIndex += 1
        }
    }

    /**
     * Submits a video frame at its deadline and updates local preview.
     *
     * @param scheduledFrame The video frame and its output deadline.
     */
    private suspend fun emitVideoFrame(
        scheduledFrame: ScheduledVideoFrame,
    ) {
        sleepUntil(scheduledFrame.targetNanoseconds)
        previewDispatcher.enqueue(scheduledFrame.frame)
        videoFrameListener(scheduledFrame.frame)
    }

    /**
     * Decodes one audio loop into fixed-size PCM packets.
     *
     * @param configuration The local media decoding and playback configuration.
     * @param timeline The shared timeline scheduling audio and video output.
     * @param loopIndex The zero-based playback loop number.
     */
    private suspend fun decodeAudioCycle(
        configuration: PlaybackConfiguration,
        timeline: MediaPlaybackTimeline,
        loopIndex: Int,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var decodingFailure: Throwable? = null
        try {
            extractor.setDataSource(
                applicationContext,
                configuration.uri,
                null,
            )
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw mediaError("The media file does not contain an audio track")
            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: throw mediaError("The media audio track has no MIME type")
            sourceFormat.setInteger(
                MediaFormat.KEY_PCM_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(
                sourceFormat,
                null,
                null,
                0,
            )
            codec.start()
            val firstPresentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
            val packetizer = PCMFramePacketizer(timeline.cycleSampleCount)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var outputSampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannelCount = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var outputEncoding = AudioFormat.ENCODING_PCM_16BIT

            while (!outputEnded) {
                coroutineContext.ensureActive()
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: throw mediaError("The audio decoder input buffer is unavailable")
                        val sampleSize = extractor.readSampleData(
                            input,
                            0,
                        )
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(
                    bufferInfo,
                    CODEC_TIMEOUT_US,
                )) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        outputEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    -> Unit
                    else -> if (outputIndex >= 0) {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (bufferInfo.size > 0 && output != null) {
                            val bytes = output.selectedBytes(
                                bufferInfo.offset,
                                bufferInfo.size,
                            )
                            val samples = convertToRtcSamples(
                                bytes = bytes,
                                encoding = outputEncoding,
                                channelCount = outputChannelCount,
                                sampleRate = outputSampleRate,
                            )
                            val relativePresentationUs =
                                (bufferInfo.presentationTimeUs - firstPresentationTimeUs)
                                    .coerceAtLeast(0L)
                            val sampleOffset = (
                                relativePresentationUs * AudioFrame.sampleRate /
                                    MICROSECONDS_PER_SECOND
                                ).toInt()
                            packetizer.append(
                                samples,
                                sampleOffset,
                            )
                            emitAvailableAudioFrames(
                                packetizer,
                                timeline,
                                loopIndex,
                            )
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(
                            outputIndex,
                            false,
                        )
                    }
                }
            }
            packetizer.finishWithSilence()
            emitAvailableAudioFrames(
                packetizer,
                timeline,
                loopIndex,
            )
        } catch (
            error: Throwable,
        ) {
            decodingFailure = error
            throw error
        } finally {
            val failure = decodingFailure
            if (failure == null) {
                cleanupResources(
                    { codec?.stop() },
                    { codec?.release() },
                    { extractor.release() },
                )
            } else {
                cleanupAfterFailure(
                    failure,
                    { codec?.stop() },
                    { codec?.release() },
                    { extractor.release() },
                )
            }
        }
    }

    /**
     * Submits complete buffered PCM packets.
     *
     * @param packetizer The packetizer grouping PCM samples into fixed-size frames.
     * @param timeline The shared timeline scheduling audio and video output.
     * @param loopIndex The zero-based playback loop number.
     */
    private suspend fun emitAvailableAudioFrames(
        packetizer: PCMFramePacketizer,
        timeline: MediaPlaybackTimeline,
        loopIndex: Int,
    ) {
        while (true) {
            val packet = packetizer.nextFrame() ?: return
            val target = timeline.target(
                loopIndex,
                packet.sampleOffset,
            )
            val now = SystemClock.elapsedRealtimeNanos()
            if (now < target) {
                sleepUntil(target)
            } else if (now - target > MAXIMUM_AUDIO_LATENESS_NANOSECONDS) {
                continue
            }
            val frame = AudioFrame(
                data = packet.data,
                timestampUs = target / NANOSECONDS_PER_MICROSECOND,
            )
            audioPreviewPlayer.enqueue(frame)
            audioFrameListener(frame)
        }
    }

    /**
     * Converts decoded audio to the sample rate, channels, and PCM format required by RTC.
     *
     * @param bytes The bytes to decode or transfer.
     * @param encoding The Android audio sample encoding.
     * @param channelCount The number of audio channels.
     * @param sampleRate The sample rate, in Hz.
     * @return PCM16 data matching RTC audio input.
     */
    private fun convertToRtcSamples(
        bytes: ByteArray,
        encoding: Int,
        channelCount: Int,
        sampleRate: Int,
    ): ShortArray {
        if (channelCount <= 0 || sampleRate <= 0) {
            throw mediaError("The decoded audio format is invalid")
        }
        val interleaved = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                ShortArray(bytes.size / Short.SIZE_BYTES) { buffer.short }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                ShortArray(bytes.size / Float.SIZE_BYTES) {
                    (buffer.float.coerceIn(
                        -1f,
                        1f,
                    ) * Short.MAX_VALUE).roundToInt().toShort()
                }
            }
            else -> throw mediaError("The decoded audio PCM format is unsupported")
        }
        val sourceFrameCount = interleaved.size / channelCount
        if (sourceFrameCount == 0) return ShortArray(0)
        val mono = ShortArray(sourceFrameCount) { frameIndex ->
            var sum = 0L
            repeat(channelCount) { channelIndex ->
                sum += interleaved[frameIndex * channelCount + channelIndex]
            }
            (sum / channelCount).toShort()
        }
        if (sampleRate == AudioFrame.sampleRate) return mono
        val outputCount = (sourceFrameCount.toLong() * AudioFrame.sampleRate / sampleRate)
            .toInt().coerceAtLeast(1)
        return ShortArray(outputCount) { outputIndex ->
            val sourcePosition = outputIndex.toDouble() * sampleRate / AudioFrame.sampleRate
            val leftIndex = sourcePosition.toInt().coerceAtMost(mono.lastIndex)
            val rightIndex = (leftIndex + 1).coerceAtMost(mono.lastIndex)
            val fraction = sourcePosition - leftIndex
            (mono[leftIndex] + (mono[rightIndex] - mono[leftIndex]) * fraction)
                .roundToInt()
                .coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt(),
                )
                .toShort()
        }
    }

    /**
     * Suspends until a monotonic deadline, supporting coroutine cancellation.
     *
     * @param targetNanoseconds The monotonic deadline, in nanoseconds.
     */
    private suspend fun sleepUntil(
        targetNanoseconds: Long,
    ) {
        while (true) {
            val remaining = targetNanoseconds - SystemClock.elapsedRealtimeNanos()
            if (remaining <= 0L) return
            delay((remaining / NANOSECONDS_PER_MILLISECOND).coerceAtLeast(1L))
        }
    }

    /**
     * Copies the buffer's current valid byte range.
     *
     * @param offset The valid data's starting offset.
     * @param size The valid byte count.
     * @return An array containing the valid bytes.
     */
    private fun ByteBuffer.selectedBytes(
        offset: Int,
        size: Int,
    ): ByteArray =
        duplicate().apply {
            position(offset)
            limit(offset + size)
        }.let { selected ->
            ByteArray(size).also(selected::get)
        }

    /**
     * Creates a media error with the processing failure description.
     *
     * @param message The message to send or log.
     * @param cause The underlying exception that caused this error.
     */
    private fun mediaError(
        message: String,
        cause: Throwable? = null,
    ): XmaxError =
        XmaxError(
            XmaxErrorCode.MEDIA_ERROR,
            message,
            cause = cause,
        )

    /**
     * Formats a playback failure description.
     *
     * @param error The operation failure or SDK error.
     * @return Text describing the playback failure.
     */
    private fun playbackErrorDescription(
        error: Throwable,
    ): String =
        generateSequence(error) { it.cause }
            .map { cause ->
                val code = (cause as? XmaxError)?.code?.let { "[$it] " }.orEmpty()
                code + (cause.message?.trim()?.takeIf(String::isNotEmpty)
                    ?: cause.javaClass.simpleName)
            }
            .distinct()
            .joinToString(" <- ")

    /**
     * Local decoding, rotation, frame output, and playback loop settings.
     */
    private data class PlaybackConfiguration(
        /**
         * A media URI accessible to your app.
         */
        val uri: Uri,

        /**
         * The output width, in pixels.
         */
        val outputWidth: Int,

        /**
         * The output height, in pixels.
         */
        val outputHeight: Int,

        /**
         * The rotation applied to the source image.
         */
        val rotation: VideoRotation,

        /**
         * The video frame rate, in fps.
         */
        val frameRate: Int,

        /**
         * Whether the input provides audio.
         */
        val hasAudio: Boolean,

        /**
         * The media duration, in microseconds.
         */
        val durationUs: Long,
    )

    /**
     * A video frame and its playback submission deadline.
     */
    private data class ScheduledVideoFrame(
        /**
         * The video frame to process, display, or submit.
         */
        val frame: VideoFrame,

        /**
         * The monotonic deadline, in nanoseconds.
         */
        val targetNanoseconds: Long,
    )

    /**
     * A preview view, scaling mode, and binding revision.
     */
    private data class PreviewTarget(
        /**
         * The view to bind or unbind.
         */
        val view: XmaxVideoView?,

        /**
         * Aspect-preserving fit or fill scaling.
         */
        val contentMode: VideoContentMode,

        /**
         * The identifier associating a request or event with its generation task.
         */
        val generation: Long,
    )

    /**
     * Delivers the latest decoded frame to preview without accumulating stale frames behind a slow display.
     */
    private inner class DecodedVideoPreviewDispatcher {

        /**
         * The latest frame awaiting display.
         */
        private val pendingFrame = AtomicReference<VideoFrame?>(null)

        /**
         * Whether a main-thread preview delivery is scheduled.
         */
        private val deliveryScheduled = AtomicBoolean(false)

        /**
         * The lock protecting preview bindings.
         */
        private val previewLock = Any()

        /**
         * The active preview view.
         */
        private var view: WeakReference<XmaxVideoView>? = null

        /**
         * The local preview fit or fill mode.
         */
        private var contentMode = VideoContentMode.FILL

        /**
         * The binding revision used to discard stale preview frames.
         */
        private var generation = 0L

        /**
         * Binds decoded local video preview to a view.
         *
         * @param view The view to bind or unbind.
         * @param contentMode Aspect-preserving fit or fill scaling.
         */
        fun attach(
            view: XmaxVideoView,
            contentMode: VideoContentMode,
        ) {
            synchronized(previewLock) {
                this.view = WeakReference(view)
                this.contentMode = contentMode
                generation += 1L
            }
            view.prepareDecodedVideoPreview(contentMode)
        }

        /**
         * Unbinds decoded local preview from a view.
         *
         * @param view The view to bind or unbind.
         */
        fun detach(
            view: XmaxVideoView,
        ) {
            synchronized(previewLock) {
                if (this.view?.get() === view) {
                    this.view = null
                    generation += 1L
                }
            }
            view.clearDecodedVideoPreview()
        }

        /**
         * Replaces the pending preview frame with the latest decoded frame.
         *
         * @param frame The video frame to process, display, or submit.
         */
        suspend fun enqueue(
            frame: VideoFrame,
        ) {
            if (synchronized(previewLock) { view?.get() == null }) return
            pendingFrame.set(frame)
            scheduleDelivery(CoroutineScope(currentCoroutineContext()))
        }

        /**
         * Clears pending preview frames and detaches the display target.
         */
        fun clear() {
            pendingFrame.set(null)
            val currentView = synchronized(previewLock) {
                generation += 1L
                view?.get().also { view = null }
            }
            currentView?.clearDecodedVideoPreview()
        }

        /**
         * Schedules preview delivery without starting duplicate dispatch tasks.
         *
         * @param owner The scope running asynchronous delivery.
         */
        private fun scheduleDelivery(
            owner: CoroutineScope,
        ) {
            if (owner.isActive && deliveryScheduled.compareAndSet(
                false,
                true,
            )) {
                owner.launch { deliverLatestFrames() }
            }
        }

        /**
         * Delivers the latest preview frame, skipping stale queued frames.
         */
        private suspend fun deliverLatestFrames() {
            try {
                while (true) {
                    val frame = pendingFrame.getAndSet(null) ?: return
                    val preview = synchronized(previewLock) {
                        PreviewTarget(
                            view?.get(),
                            contentMode,
                            generation,
                        )
                    }
                    if (preview.view == null) continue
                    val bitmap = try {
                        Yuv420VideoFrameConverter.makePreviewBitmap(frame)
                    } catch (
                        error: Throwable,
                    ) {
                        XmaxLogger.media.warn(
                            message = {
                                "本地视频预览帧转换失败 (Failed to Convert Local Video Preview)\n" +
                                    "└─ ${XmaxLogger.localized(
                                        "原因：",
                                        "Reason: ",
                                    )}${playbackErrorDescription(error)}"
                            },
                        )
                        continue
                    }
                    var delivered = false
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            val remainsAttached = synchronized(previewLock) {
                                generation == preview.generation && view?.get() === preview.view
                            }
                            if (remainsAttached) {
                                preview.view.displayDecodedVideoBitmap(
                                    bitmap,
                                    preview.contentMode,
                                )
                                delivered = true
                            }
                        }
                    } finally {
                        if (!delivered) bitmap.recycle()
                    }
                }
            } finally {
                deliveryScheduled.set(false)
                if (pendingFrame.get() != null) scheduleDelivery(CoroutineScope(currentCoroutineContext()))
            }
        }
    }

    /**
     * Audio/video playback timing and buffering limits.
     */
    private companion object {

        /**
         * The decoder input or output buffer timeout, in microseconds.
         */
        const val CODEC_TIMEOUT_US = 10_000L

        /**
         * The number of microseconds per second.
         */
        const val MICROSECONDS_PER_SECOND = 1_000_000L

        /**
         * The number of nanoseconds per microsecond.
         */
        const val NANOSECONDS_PER_MICROSECOND = 1_000L

        /**
         * The number of nanoseconds per millisecond.
         */
        const val NANOSECONDS_PER_MILLISECOND = 1_000_000L

        /**
         * The maximum permitted audio lateness, in nanoseconds.
         */
        const val MAXIMUM_AUDIO_LATENESS_NANOSECONDS = 30_000_000L

        /**
         * The maximum buffered frames between video decoding and output.
         */
        const val VIDEO_DECODE_BUFFER_SIZE = 2
    }
}
