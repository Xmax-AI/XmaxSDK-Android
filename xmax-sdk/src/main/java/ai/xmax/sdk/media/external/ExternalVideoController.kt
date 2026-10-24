//
//  ExternalVideoController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.external

import ai.xmax.sdk.*
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Binds an external video source to local preview and RTC.
 */
internal class ExternalVideoController(
    /**
     * The RTC operations manager.
     */
    private val rtc: RtcManaging,

    /**
     * The service resolving media dimensions and model constraints.
     */
    private val mediaService: MediaServicing,

    /**
     * The function checking whether generation currently requests external frames.
     */
    private val requestingFrames: () -> Boolean,

    /**
     * The local video frame callback.
     */
    private val videoListener: (VideoFrame) -> Unit,

    /**
     * The local audio frame callback.
     */
    private val audioListener: (AudioFrame) -> Unit,

    /**
     * The media failure callback.
     */
    private val errorListener: (XmaxError) -> Unit,
) {

    /**
     * The lock protecting external input state.
     */
    private val lock = Any()

    /**
     * The active external source.
     */
    private var source: RealtimeExternalVideoSource? = null

    /**
     * The frame receiver for the active external source.
     */
    private var sink: Sink? = null

    /**
     * The local video track of the external source.
     */
    @Volatile var currentTrack: RealtimeVideoTrack? = null
        private set

    /**
     * Whether the current external source supplies audio frames.
     */
    @Volatile var hasAudio = false
        private set

    /**
     * The external source's local preview volume.
     */
    private var volume = 0.45f

    /**
     * Whether the external source's local audio preview is muted.
     */
    private var muted = false

    /**
     * Binds an external source and creates its local media stream.
     *
     * @param source The external media source whose lifecycle your app owns.
     * @param videoFormat Input dimensions, frame rate, and optional uplink encoding settings; null uses
     * source or parsed media defaults.
     */
    suspend fun create(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream = withContext(Dispatchers.Main.immediate) {
        check(this@ExternalVideoController.source == null)
        val requested = videoFormat ?: source.videoFormat
        requested.validate()
        val size = mediaService.resolveMediaSize(
            IntSize(
                requested.width,
                requested.height,
            ),
            RealtimeMediaSource.EXTERNAL,
        )
        val format = requested.copy(
            width = size.width,
            height = size.height,
        ).also(RealtimeVideoFormat::validate)
        val track = RealtimeVideoTrack(
            "external0",
            format,
        )
        val input = Sink(
            format,
            source.hasAudio,
        )
        this@ExternalVideoController.source = source
        synchronized(lock) { sink = input }

        try {
            rtc.useExternalVideoSource()
            hasAudio = source.hasAudio
            if (hasAudio) rtc.startExternalAudioSource()

            VideoRenderRegistry.register(
                track,
                VideoRenderBinding(
                    attachHandler = { view, mode -> source.attachPreview(
                        view,
                        mode,
                    ) },
                    detachHandler = { view -> source.detachPreview(view) },
                ),
            )

            currentTrack = track
            source.setPreviewAudio(
                volume,
                muted,
            )

            source.start(input)
            RealtimeMediaStream(
                StreamID.LOCAL.value,
                track,
            )
        } catch (
            error: Throwable,
        ) {
            cleanupAfterFailure(
                error,
                { stop() },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * Updates the external source's preview volume or mute state.
     *
     * @param volume The playback volume, from 0 to 1.
     * @param muted Whether audio is muted.
     */
    suspend fun setAudio(
        volume: Float? = null,
        muted: Boolean? = null,
    ): Unit = withContext(Dispatchers.Main.immediate) {
        volume?.let { this@ExternalVideoController.volume = it }
        muted?.let { this@ExternalVideoController.muted = it }
        source?.setPreviewAudio(
            this@ExternalVideoController.volume,
            this@ExternalVideoController.muted,
        )
    }

    /**
     * Stops frame delivery and clears the sink idempotently without closing your app's camera or player.
     */
    suspend fun stop(): Unit = withContext(Dispatchers.Main.immediate) {
        synchronized(lock) { sink = null }
        val previousSource = source
        val previousTrack = currentTrack
        val hadAudio = hasAudio
        source = null
        currentTrack = null
        hasAudio = false
        muted = false
        cleanupResources(
            { previousSource?.setPreviewAudio(
                volume,
                false,
            ) },
            { previousSource?.stop() },
            { previousTrack?.let { VideoRenderRegistry.binding(it)?.detach() } },
            { previousTrack?.let(VideoRenderRegistry::unregister) },
            { if (hadAudio) rtc.stopExternalAudioSource() },
        )
    }

    /**
     * A receiver for frames and failures from the current external input.
     */
    private inner class Sink(
        /**
         * The video dimensions, frame rate, and uplink encoding settings.
         */
        override val videoFormat: RealtimeVideoFormat,

        /**
         * Whether external audio is supplied to RTC.
         */
        private val audioEnabled: Boolean,
    ) : RealtimeExternalFrameSink {

        /**
         * The last accepted video timestamp, in microseconds.
         */
        private var lastVideoUs = -1L

        /**
         * The last accepted audio timestamp, in microseconds.
         */
        private var lastAudioUs = -1L

        /**
         * Whether the current task requests frames from this source.
         */
        override val isRequestingFrames: Boolean
            get() = synchronized(lock) { sink === this && requestingFrames() }

        /**
         * Submits an externally captured RGBA video frame.
         *
         * @param rgba Tightly packed RGBA pixels matching the current input dimensions.
         * @param timestampUs The monotonic frame timestamp, in microseconds.
         */
        override fun pushVideo(
            rgba: ByteArray,
            timestampUs: Long,
        ) = submit {
            if (timestampUs <= lastVideoUs) return@submit

            require(rgba.size.toLong() == videoFormat.width.toLong() * videoFormat.height * 4)
            lastVideoUs = timestampUs
            videoListener(
                VideoFrame(
                    VideoFormat(
                        videoFormat.width,
                        videoFormat.height,
                        VideoPixelFormat.RGBA,
                    ),
                    timestampUs,
                    listOf(
                        VideoFramePlane(
                            rgba,
                            videoFormat.width * 4,
                        ),
                    ),
                ),
            )
        }

        /**
         * Submits externally captured PCM audio.
         *
         * @param pcm 48 kHz mono PCM16 little-endian audio bytes.
         * @param timestampUs The monotonic frame timestamp, in microseconds.
         */
        override fun pushAudio(
            pcm: ByteArray,
            timestampUs: Long,
        ) = submit {
            if (!audioEnabled || timestampUs <= lastAudioUs) return@submit

            require(pcm.size == 960)
            lastAudioUs = timestampUs
            audioListener(
                AudioFrame(
                    pcm,
                    timestampUs,
                ),
            )
        }

        /**
         * Reports a failure in the current media input or generation task.
         *
         * @param error The operation failure or SDK error.
         */
        override fun reportError(
            error: Throwable,
        ) {
            if (synchronized(lock) { sink === this }) {
                errorListener(
                    XmaxError(
                        XmaxErrorCode.MEDIA_ERROR,
                        "External media source failed",
                        cause = error,
                    ),
                )
            }
        }

        /**
         * Submits events only while the external source is active, forwarding failures to the error callback.
         *
         * @param action The operation to perform.
         */
        private fun submit(
            action: () -> Unit,
        ) {
            try {
                synchronized(lock) {
                    if (sink !== this || !requestingFrames()) return

                    action()
                }
            } catch (
                error: Exception,
            ) { reportError(error) }
        }
    }
}
