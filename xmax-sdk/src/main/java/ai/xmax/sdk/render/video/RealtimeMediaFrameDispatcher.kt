//
//  RealtimeMediaFrameDispatcher.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.video

import ai.xmax.sdk.RealtimeMediaFrame
import ai.xmax.sdk.RealtimeMediaFrameListener
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.foundation.rtc.RtcRemoteAudioFrame
import ai.xmax.sdk.foundation.rtc.RtcRemoteVideoFrame
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Serializes remote audio/video output with bounded retention and generation-safe delivery.
 *
 * @param dispatcher The background callback dispatcher.
 */
internal class RealtimeMediaFrameDispatcher(
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),

    /**
     * The retained packet limit, including an executing callback.
     */
    private val maximumPackets: Int = 256,

    /**
     * The retained payload byte limit, including an executing callback.
     */
    private val maximumBytes: Long = 32L * 1_024 * 1_024,
) {

    // Delivery synchronization.

    /**
     * The background scope delivering serialized media events.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * The synchronization gate for output state.
     */
    private val lock = Any()

    // Output registration.

    /**
     * The application's current remote media receiver.
     */
    private var listener: RealtimeMediaFrameListener? = null

    /**
     * The current remote stream binding identity.
     */
    private var generation: Any? = null

    /**
     * The identity shared by output audio and video packets.
     */
    private var streamID: UUID? = null

    /**
     * The registration revision rejecting obsolete queued callbacks.
     */
    private var version = 0L

    // Per-track timeline and continuity.

    /**
     * The sample-derived audio presentation timeline.
     */
    private val audioTimeline = RemoteAudioTimeline()

    /**
     * Whether the next audio event starts a discontinuous segment.
     */
    private var audioDiscontinuous = true

    /**
     * Whether the next video event starts a discontinuous segment.
     */
    private var videoDiscontinuous = true

    /**
     * The last accepted video presentation timestamp.
     */
    private var videoTimeUs: Long? = null

    /**
     * The last accepted video dimensions and rotation.
     */
    private var videoFormat: List<Int>? = null

    // Bounded retention.

    /**
     * The owned media events awaiting serialized delivery.
     */
    private val pending = ArrayDeque<Pending>()

    /**
     * The number of retained packets, including the executing callback.
     */
    private var retainedPackets = 0

    /**
     * The retained payload byte count, including the executing callback.
     */
    private var retainedBytes = 0L

    /**
     * Whether a serial event drain is active.
     */
    private var draining = false

    /**
     * Whether application media output is requested.
     */
    val hasListener: Boolean get() = synchronized(lock) { listener != null }

    /**
     * Replaces the application listener and invalidates queued callbacks.
     *
     * @param listener The media listener, or null to unregister it.
     */
    fun setListener(
        listener: RealtimeMediaFrameListener?,
    ) = synchronized(lock) {
        this.listener = listener
        resetTimeline()
    }

    /**
     * Starts a fresh shared timeline for a remote stream binding.
     *
     * @param generation The current binding identity, or null to stop output.
     */
    fun setGeneration(
        generation: Any?,
    ) = synchronized(lock) {
        this.generation = generation
        streamID = generation?.let { UUID.randomUUID() }
        resetTimeline()
    }

    /**
     * Invalidates queued events and starts fresh per-track continuity.
     */
    private fun resetTimeline() {
        version++
        audioTimeline.reset()
        audioDiscontinuous = true
        videoDiscontinuous = true
        videoTimeUs = null
        videoFormat = null
    }

    /**
     * Copies and queues video while its native pixels are valid.
     *
     * @param generation The owning remote stream binding.
     * @param frame The borrowed RTC video frame.
     */
    fun dispatch(
        generation: Any,
        frame: RtcRemoteVideoFrame,
    ): Unit = synchronized(lock) {
        if (this.generation !== generation || listener == null) return

        val bytes = frame.width.toLong() * frame.height +
            2L * ((frame.width.toLong() + 1) / 2) * ((frame.height.toLong() + 1) / 2)
        if (!canRetain(bytes)) {
            videoDiscontinuous = true
            return
        }

        val copy = frame.copy()
        if (copy.presentationTimeUs < 0) {
            videoDiscontinuous = true
            return
        }
        val format = listOf(
            copy.width,
            copy.height,
            copy.rotationDegrees,
        )
        val discontinuous = videoDiscontinuous || videoFormat != format ||
            videoTimeUs?.let { copy.presentationTimeUs <= it } == true
        videoTimeUs = copy.presentationTimeUs
        videoFormat = format
        val event = RealtimeMediaFrame(
            streamID = checkNotNull(streamID),
            isDiscontinuous = discontinuous,
            content = RealtimeMediaFrame.Content.Video(copy),
        )
        videoDiscontinuous = !enqueue(
            event,
            bytes,
        )
    }

    /**
     * Queues audio on the same stream timeline as video.
     *
     * @param generation The owning remote stream binding.
     * @param frame The owned RTC audio packet.
     */
    fun dispatchAudio(
        generation: Any,
        frame: RtcRemoteAudioFrame,
    ): Unit = synchronized(lock) {
        if (this.generation !== generation || listener == null) return

        val resolved = audioTimeline.resolve(frame)
        if (resolved == null) {
            audioDiscontinuous = true
            return
        }

        val event = RealtimeMediaFrame(
            streamID = checkNotNull(streamID),
            isDiscontinuous = audioDiscontinuous || resolved.second,
            content = RealtimeMediaFrame.Content.Audio(resolved.first),
        )
        audioDiscontinuous = !enqueue(
            event,
            frame.data.size.toLong(),
        )
    }

    /**
     * Reports whether a packet fits within retained output limits.
     *
     * @param bytes The proposed packet payload size.
     * @return Whether both the packet and byte limits allow the packet.
     */
    private fun canRetain(
        bytes: Long,
    ): Boolean = bytes >= 0 && retainedPackets < maximumPackets && bytes <= maximumBytes - retainedBytes

    /**
     * Retains an event and starts delivery when no drain is active.
     *
     * @param event The owned output event.
     * @param bytes The retained payload size.
     * @return Whether the event was retained.
     */
    private fun enqueue(
        event: RealtimeMediaFrame,
        bytes: Long,
    ): Boolean {
        if (!canRetain(bytes)) return false

        pending.addLast(
            Pending(
                version,
                event,
                bytes,
            ),
        )
        retainedPackets++
        retainedBytes += bytes
        if (!draining) {
            draining = true
            scope.launch { drain() }
        }
        return true
    }

    /**
     * Delivers current events in FIFO order and releases retained payload accounting.
     */
    private fun drain() {
        while (true) {
            val delivery = synchronized(lock) {
                val next = pending.removeFirstOrNull()
                if (next == null) {
                    draining = false
                    return
                }
                next to listener.takeIf { next.version == version && next.event.streamID == streamID }
            }
            try {
                delivery.second?.onFrame(delivery.first.event)
            } catch (
                error: Throwable,
            ) {
                XmaxLogger.render.warn(message = { "Remote media frame listener failed: ${error.message}" })
            } finally {
                synchronized(lock) {
                    retainedPackets--
                    retainedBytes -= delivery.first.bytes
                }
            }
        }
    }

    /**
     * An event retained for one listener revision.
     */
    private data class Pending(
        /**
         * The listener revision owning this event.
         */
        val version: Long,

        /**
         * The owned audio or video event.
         */
        val event: RealtimeMediaFrame,

        /**
         * The retained payload size.
         */
        val bytes: Long,
    )
}
