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
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Captures microphone PCM independently of RTC.
 */
internal interface MicrophoneCapture {

    /**
     * Whether the recorder has been started.
     */
    val isCapturing: Boolean

    /**
     * Checks that the selected audio input is still available.
     *
     * @param deviceId The Android audio input identifier.
     */
    suspend fun validateDevice(
        deviceId: String,
    )

    /**
     * Starts capture after the app has obtained microphone permission.
     *
     * @param deviceId The selected audio input ID, or null for the default Android input.
     * @param onFrame Receives 10 ms packets on the capture thread.
     * @param onError Receives a capture, delivery, or routing failure.
     */
    suspend fun start(
        deviceId: String?,
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
 *
 * @param context The application context used to look up connected audio inputs.
 */
internal class AudioRecordCapture(
    context: Context,
) : MicrophoneCapture {

    /**
     * Provides the currently available Android audio inputs.
     */
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * The recorder and read thread, serialized by the camera controller.
     */
    @Volatile private var active: Session? = null

    /**
     * Whether a recording session is open.
     */
    override val isCapturing: Boolean
        get() = active != null

    /**
     * Verifies that the selected input is currently attached without starting a recorder.
     *
     * @param deviceId The Android audio input identifier.
     * @throws XmaxError If the input is unavailable.
     */
    override suspend fun validateDevice(
        deviceId: String,
    ) {
        withContext(Dispatchers.IO) { requireInputDevice(deviceId) }
    }

    /**
     * Opens the recorder on an I/O thread and starts delivery of complete PCM packets.
     *
     * @param deviceId The selected audio input ID, or null for the default Android input.
     * @param onFrame The receiver of complete PCM packets.
     * @param onError The receiver of an unrecoverable audio failure.
     */
    @SuppressLint("MissingPermission")
    override suspend fun start(
        deviceId: String?,
        onFrame: (AudioFrame) -> Unit,
        onError: (XmaxError) -> Unit,
    ) {
        if (active != null) return

        // Commit ownership before returning to the caller's cancellable dispatcher.
        withContext(Dispatchers.IO + NonCancellable) {
            val device = deviceId?.let(::requireInputDevice)
            val recorder = createRecorder()
            val session = Session(recorder, device, onFrame, onError)
            try {
                if (device != null && !recorder.setPreferredDevice(device)) {
                    throw microphoneError("Android rejected audio input ${device.id}")
                }
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                session.verifyInput()
                session.worker.start()
                active = session
            } catch (error: Exception) {
                session.stopCheckingInput()
                recorder.release()
                if (error is XmaxError) throw error
                throw microphoneError("Could not start microphone capture", error)
            }
        }
    }

    /**
     * Resolves an enumerated ID to an audio input that is currently attached.
     *
     * @param deviceId The Android audio input identifier.
     * @return The matching Android input device.
     */
    private fun requireInputDevice(
        deviceId: String,
    ): AudioDeviceInfo = audioManager
        ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
        ?.firstOrNull { it.id.toString() == deviceId }
        ?: throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Audio input $deviceId is unavailable")

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
                session.stopCheckingInput()
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
     * @property device The explicitly selected input to verify, if present.
     * @property onFrame The receiver of complete PCM packets.
     * @property onError The receiver of an unrecoverable audio failure.
     */
    @Suppress("DEPRECATION")
    private class Session(
        val recorder: AudioRecord,
        private val device: AudioDeviceInfo?,
        private val onFrame: (AudioFrame) -> Unit,
        private val onError: (XmaxError) -> Unit,
    ) {

        /**
         * Whether frame delivery has stopped.
         */
        val stopping = AtomicBoolean(false)

        /**
         * Whether the recorder lost its explicitly selected input.
         */
        private val inputLost = AtomicBoolean(false)

        /**
         * Stops delivery if Android routes a running recorder away from the selected input.
         */
        private val routingListener = device?.let { input ->
            AudioRecord.OnRoutingChangedListener { record ->
                if (!stopping.get() && record.routedDevice?.id != input.id && inputLost.compareAndSet(false, true)) {
                    onError(microphoneError("Android stopped routing recording to audio input ${input.id}"))
                }
            }
        }

        /**
         * The worker draining the recorder's PCM buffers.
         */
        val worker = Thread(::read, "XmaxSDK-Microphone")

        /**
         * Waits for Android to apply routing before publishing any microphone audio.
         */
        suspend fun verifyInput() {
            val input = device ?: return
            val routed = withTimeoutOrNull(1_000L) {
                while (recorder.routedDevice?.id != input.id) delay(20L)
                true
            }
            if (routed != true) throw microphoneError("Android did not route recording to audio input ${input.id}")
            recorder.addOnRoutingChangedListener(checkNotNull(routingListener), Handler(Looper.getMainLooper()))
        }

        /**
         * Removes the explicit input routing monitor.
         */
        fun stopCheckingInput() {
            routingListener?.let(recorder::removeOnRoutingChangedListener)
        }

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
                while (!stopping.get() && !inputLost.get()) {
                    val count = recorder.read(samples, offset, samples.size - offset, AudioRecord.READ_BLOCKING)
                    if (stopping.get() || inputLost.get()) return
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
                if (!stopping.get() && !inputLost.get()) onError(microphoneError("Could not deliver microphone audio", error))
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
