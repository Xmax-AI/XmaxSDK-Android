//
//  RealtimeMediaFrame.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import java.nio.ByteBuffer
import java.util.UUID

/**
 * Owned, interleaved signed PCM16 little-endian audio from the remote publisher before user mixing.
 *
 * The data remains valid after the callback returns and is independent of playback volume.
 *
 * @property pcm The owned PCM bytes.
 * @property sampleRate The number of samples per second per channel.
 * @property channels The number of interleaved audio channels.
 * @property samplesPerChannel The number of samples in each channel.
 * @property presentationTimeUs The presentation timestamp on the shared remote audio/video timeline, in microseconds.
 * @property durationUs The sample-derived duration, in microseconds.
 */
public class RealtimeAudioFrame internal constructor(
    private val pcm: ByteArray,
    public val sampleRate: Int,
    public val channels: Int,
    public val samplesPerChannel: Int,
    public val presentationTimeUs: Long,
    public val durationUs: Long,
) {

    /**
     * An independent read-only view of the owned PCM bytes.
     */
    public val data: ByteBuffer get() = ByteBuffer.wrap(pcm).asReadOnlyBuffer()
}

/**
 * One audio or video packet on a shared remote stream timeline.
 *
 * Events arrive serially in delivery order, not sorted by presentation time across tracks. Recording
 * should use one origin for both tracks. A new stream identifier starts a new timeline; discontinuity
 * marks the first packet of each track, format/clock changes, or output discarded under backpressure.
 *
 * @property streamID The identity shared by audio and video for the current remote stream binding.
 * @property isDiscontinuous Whether this track starts or resumes after an interruption.
 * @property content The audio or video packet carried by this event.
 */
public class RealtimeMediaFrame internal constructor(
    public val streamID: UUID,
    public val isDiscontinuous: Boolean,
    public val content: Content,
) {

    /**
     * An owned remote audio or video packet.
     */
    public sealed interface Content {

        /**
         * Remote audio before user mixing.
         *
         * @property frame The owned PCM frame.
         */
        public data class Audio(
            public val frame: RealtimeAudioFrame,
        ) : Content

        /**
         * Remote video ready for application processing.
         *
         * @property frame The owned I420 frame.
         */
        public data class Video(
            public val frame: RealtimeVideoFrame,
        ) : Content
    }

    /**
     * The packet presentation timestamp, in microseconds.
     */
    public val presentationTimeUs: Long get() = when (val packet = content) {
        is Content.Audio -> packet.frame.presentationTimeUs
        is Content.Video -> packet.frame.presentationTimeUs
    }

    /**
     * The packet duration in microseconds, or null when video duration is unavailable.
     */
    public val durationUs: Long? get() = when (val packet = content) {
        is Content.Audio -> packet.frame.durationUs
        is Content.Video -> packet.frame.durationUs
    }
}

/**
 * Receives owned remote audio/video packets serially on a bounded SDK background queue.
 *
 * Copy or enqueue work promptly: slow consumers can lose packets, reported by the next track packet's
 * discontinuity flag. Removing a listener discards queued events; an executing callback may finish.
 */
public fun interface RealtimeMediaFrameListener {

    /**
     * Receives a packet without changing remote playback or subscription.
     *
     * @param frame The owned audio or video packet and its stream timeline metadata.
     */
    public fun onFrame(
        frame: RealtimeMediaFrame,
    )
}
