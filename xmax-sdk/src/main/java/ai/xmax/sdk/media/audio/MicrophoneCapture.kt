//
//  MicrophoneCapture.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.audio

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Captures microphone PCM independently of RTC.
 */
internal interface MicrophoneCapture {

    /**
     * Starts capture after the app has obtained microphone permission.
     *
     * @param onFrame Receives 10 ms packets on the capture thread.
     * @param onError Receives a read or delivery failure on the capture thread.
     */
    suspend fun start(
        onFrame: (AudioFrame) -> Unit,
        onError: (XmaxError) -> Unit,
    )

    /**
     * Stops capture and releases the recorder.
     */
    suspend fun stop()
}

/**
 * Uses AudioRecord to produce timestamped 48 kHz mono PCM16 packets.
 */
internal class AudioRecordCapture : MicrophoneCapture {

    /**
     * The recorder and read thread, serialized by the camera controller.
     */
    private var active: Session? = null

    /**
     * Opens the recorder on an I/O thread and starts delivery of complete PCM packets.
     */
    @SuppressLint("MissingPermission")
    override suspend fun start(
        onFrame: (AudioFrame) -> Unit,
        onError: (XmaxError) -> Unit,
    ) {
        if (active != null) return

        // Commit ownership before returning to the caller's cancellable dispatcher.
        withContext(Dispatchers.IO + NonCancellable) {
            val recorder = createRecorder()
            try {
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                val session = Session(recorder, onFrame, onError)
                session.worker.start()
                active = session
            } catch (error: Exception) {
                recorder.release()
                throw microphoneError("Could not start microphone capture", error)
            }
        }
    }

    /**
     * Selects a supported mono PCM recorder. Some devices only support 44.1 kHz input.
     */
    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord {
        val channels = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        var failure: Exception? = null
        for (rate in listOf(AudioFrame.sampleRate, 44_100)) {
            val minimum = AudioRecord.getMinBufferSize(rate, channels, encoding)
            if (minimum <= 0) continue

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    channels,
                    encoding,
                    maxOf(minimum, rate / 100 * Short.SIZE_BYTES * 4),
                )
            } catch (error: IllegalArgumentException) {
                failure = error
                continue
            }
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder

            recorder.release()
        }

        throw microphoneError("Mono PCM microphone capture is unavailable", failure)
    }

    /**
     * Unblocks the read thread and releases the recorder after delivery stops.
     */
    override suspend fun stop() {
        val session = active ?: return
        active = null

        withContext(Dispatchers.IO + NonCancellable) {
            session.stopping.set(true)
            try {
                if (session.recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) session.recorder.stop()
            } finally {
                try {
                    session.worker.join()
                } finally {
                    session.recorder.release()
                }
            }
        }
    }

    /**
     * Owns one recorder and its fixed-size PCM read loop.
     *
     * @property recorder The initialized recorder.
     * @property onFrame The receiver of complete PCM packets.
     * @property onError The receiver of an unrecoverable audio failure.
     */
    private class Session(
        val recorder: AudioRecord,
        private val onFrame: (AudioFrame) -> Unit,
        private val onError: (XmaxError) -> Unit,
    ) {

        /**
         * Whether frame delivery has stopped.
         */
        val stopping = AtomicBoolean(false)

        /**
         * The worker draining the recorder's PCM buffers.
         */
        val worker = Thread(::read, "XmaxSDK-Microphone")

        /**
         * Reads complete packets, using the system boot clock shared by video capture.
         */
        private fun read() {
            val rate = recorder.sampleRate
            val samples = ShortArray(rate / 100)
            val pcm = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val converter = if (rate == AudioFrame.sampleRate) null else ExternalAudioFrameConverter()
            val timestamp = AudioTimestamp()
            var sampleIndex = 0L
            var offset = 0
            var anchorUs: Long? = null

            try {
                while (!stopping.get()) {
                    val count = recorder.read(samples, offset, samples.size - offset, AudioRecord.READ_BLOCKING)
                    if (stopping.get()) return
                    check(count > 0) { "AudioRecord read failed ($count)" }

                    offset += count
                    if (offset < samples.size) continue

                    val elapsedUs = sampleIndex * 1_000_000L / rate
                    val firstUs = anchorUs ?: firstSampleUs(timestamp, samples.size).also { anchorUs = it }
                    pcm.clear()
                    pcm.asShortBuffer().put(samples)
                    val packetUs = firstUs + elapsedUs
                    if (converter == null) {
                        onFrame(AudioFrame(pcm.array(), packetUs))
                    } else {
                        converter.append(pcm.array(), packetUs, rate, AudioFrame.channelCount, onFrame)
                    }

                    offset = 0
                    sampleIndex += samples.size
                }
            } catch (error: Exception) {
                if (!stopping.get()) onError(microphoneError("Could not deliver microphone audio", error))
            }
        }

        /**
         * Resolves the first sample time, using the first packet's read completion on API 23.
         *
         * @param timestamp Storage for the hardware position and clock.
         * @param samples The number of samples in the first packet.
         */
        private fun firstSampleUs(
            timestamp: AudioTimestamp,
            samples: Int,
        ): Long {
            val timeUs = if (Build.VERSION.SDK_INT >= 24 &&
                recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
            ) {
                timestamp.nanoTime / 1_000L - timestamp.framePosition * 1_000_000L / recorder.sampleRate
            } else {
                SystemClock.elapsedRealtimeNanos() / 1_000L - samples * 1_000_000L / recorder.sampleRate
            }
            return timeUs.coerceAtLeast(0)
        }
    }
}

/**
 * Formats a microphone capture failure.
 *
 * @param message The failed audio operation.
 * @param cause The underlying recorder or delivery error, if available.
 */
private fun microphoneError(
    message: String,
    cause: Throwable? = null,
): XmaxError = XmaxError(XmaxErrorCode.MEDIA_ERROR, message, cause = cause)
