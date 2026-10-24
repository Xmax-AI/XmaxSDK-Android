//
//  LocalAudioPreviewPlayer.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.audio

import ai.xmax.sdk.AudioFrame
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Plays decoded 48 kHz mono PCM for local video preview.
 */
internal class LocalAudioPreviewPlayer {

    /**
     * The local PCM playback resources.
     */
    private var audioTrack: AudioTrack? = null

    /**
     * The local preview mute state.
     */
    private var muted = false

    /**
     * The local audio preview volume, from 0 to 1.
     */
    private var volume = DEFAULT_VOLUME

    /**
     * Starts the local preview audio queue.
     */
    @Synchronized
    fun start() {
        if (audioTrack != null) return

        val minimumBufferSize = AudioTrack.getMinBufferSize(
            AudioFrame.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(AudioFrame.sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            maxOf(
                minimumBufferSize,
                AudioFrame.samplesPerFrame * Short.SIZE_BYTES * 4,
            ),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Failed to initialize local audio preview")
        }

        track.setVolume(if (muted) 0f else volume)
        track.play()
        audioTrack = track
    }

    /**
     * Queues a PCM frame for local preview playback.
     *
     * @param frame The PCM audio frame to play or submit.
     */
    @Synchronized
    fun enqueue(
        frame: AudioFrame,
    ) {
        val data = frame.dataBytes()
        audioTrack?.write(
            data,
            0,
            data.size,
            AudioTrack.WRITE_NON_BLOCKING,
        )
    }

    /**
     * Mutes or unmutes local audio preview.
     *
     * @param muted Whether audio is muted.
     */
    @Synchronized
    fun setMuted(
        muted: Boolean,
    ) {
        this.muted = muted
        audioTrack?.setVolume(if (muted) 0f else volume)
    }

    /**
     * Sets local audio preview volume.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    @Synchronized
    fun setVolume(
        volume: Float,
    ) {
        this.volume = volume.coerceIn(
            0f,
            1f,
        )
        audioTrack?.setVolume(if (muted) 0f else this.volume)
    }

    /**
     * Stops local audio preview and releases audio output resources.
     */
    @Synchronized
    fun stop() {
        val track = audioTrack ?: return
        audioTrack = null
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        track.release()
    }

    /**
     * The default local preview audio configuration.
     */
    private companion object {

        /**
         * The default local preview volume, from 0 to 1.
         */
        const val DEFAULT_VOLUME = 0.45f
    }
}
