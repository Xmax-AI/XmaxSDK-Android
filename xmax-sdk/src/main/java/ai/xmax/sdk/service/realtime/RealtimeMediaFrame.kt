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
 */
public class RealtimeAudioFrame internal constructor(
    /**
     * The owned PCM bytes.
     */
    private val pcm: ByteArray,

    /**
     * The number of samples per second per channel.
     */
    public val sampleRate: Int,

    /**
     * The number of interleaved audio channels.
     */
    public val channels: Int,

    /**
     * The number of samples in each channel.
     */
    public val samplesPerChannel: Int,

    /**
     * The presentation timestamp on the shared remote audio/video timeline, in microseconds.
     */
    public val presentationTimeUs: Long,

    /**
     * The sample-derived duration, in microseconds.
     */
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
 */
public class RealtimeMediaFrame internal constructor(
    /**
     * The identity shared by audio and video for the current remote stream binding.
     */
    public val streamID: UUID,

    /**
     * Whether this track starts or resumes after an interruption.
     */
    public val isDiscontinuous: Boolean,

    /**
     * The audio or video packet carried by this event.
     */
    public val content: Content,
) {

    /**
     * An owned remote audio or video packet.
     */
    public sealed interface Content {

        /**
         * Remote audio before user mixing.
         */
        public data class Audio(
            /**
             * The owned PCM frame.
             */
            public val frame: RealtimeAudioFrame,
        ) : Content

        /**
         * Remote video ready for application processing.
         */
        public data class Video(
            /**
             * The owned I420 frame.
             */
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
