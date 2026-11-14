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
import ai.xmax.sdk.render.video.LocalVideoFramePreview
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Binds an external video source to local preview and RTC.
 *
 * @property rtc The RTC operations manager.
 * @property mediaService The service resolving media dimensions and model constraints.
 * @property videoListener The local video frame callback.
 * @property audioListener The local audio frame callback.
 * @property errorListener The media failure callback.
 */
internal class ExternalVideoController(
    private val rtc: RtcManaging,
    private val mediaService: MediaServicing,
    private val videoListener: (VideoFrame) -> Unit,
    private val audioListener: (AudioFrame) -> Unit,
    private val errorListener: (XmaxError) -> Unit,
) {

    /**
     * The active external source.
     */
    private var source: RealtimeExternalVideoSource? = null

    /**
     * The frame receiver for the active external source.
     */
    private var sink: ExternalVideoFrameSink? = null

    /**
     * The submitted-frame preview, omitted when the source supplies native preview.
     */
    private var preview: LocalVideoFramePreview? = null

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
     * @param videoFormat Desired processed dimensions, frame rate, and encoding settings; null uses
     * the source format. Submitted pixels always retain the source's original dimensions.
     * @return The local stream containing the processed preview and generation track.
     */
    suspend fun create(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream = withContext(Dispatchers.Main.immediate) {
        check(this@ExternalVideoController.source == null)
        val original = source.videoFormat.also(RealtimeVideoFormat::validate)
        require(original.width.toLong() * original.height <= Int.MAX_VALUE / 4) {
            "External RGBA source dimensions exceed byte-array capacity"
        }
        val requested = videoFormat ?: original
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
        val hasAudio = source.hasAudio
        lateinit var input: ExternalVideoFrameSink
        val preview = if (source.providesPreview) null else LocalVideoFramePreview(
            makeBitmap = ::makePreviewBitmap,
            onFailure = { input.reportError(it) },
        )
        input = ExternalVideoFrameSink(
            sourceVideoFormat = original,
            videoFormat = format,
            hasAudio = hasAudio,
            frameListener = { frame ->
                preview?.enqueue(frame)
                videoListener(frame)
            },
            audioListener = audioListener,
            errorListener = errorListener,
        )
        this@ExternalVideoController.source = source
        this@ExternalVideoController.preview = preview
        sink = input

        try {
            rtc.useExternalVideoSource()
            this@ExternalVideoController.hasAudio = hasAudio
            if (hasAudio) rtc.startExternalAudioSource()

            VideoRenderRegistry.register(
                track,
                VideoRenderBinding(
                    attachHandler = { view, mode ->
                        if (preview != null) preview.attach(
                            view,
                            mode,
                        ) else source.attachPreview(
                            view,
                            mode,
                        )
                    },
                    detachHandler = { view ->
                        if (preview != null) preview.detach(view) else source.detachPreview(view)
                    },
                ),
            )

            currentTrack = track
            source.setPreviewAudio(
                volume,
                muted,
            )

            source.start(input)
            input.failure?.let { throw it }
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
        sink?.invalidate()
        sink = null
        val previousSource = source
        val previousTrack = currentTrack
        val hadAudio = hasAudio
        val previousPreview = preview
        source = null
        currentTrack = null
        hasAudio = false
        muted = false
        preview = null
        cleanupResources(
            { previousPreview?.close() },
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
     * Converts RGBA channels to Android colors with correct alpha handling.
     *
     * @param frame The processed, independently owned external frame.
     * @return A bitmap owned by preview until display transfers ownership to the view.
     */
    private fun makePreviewBitmap(
        frame: VideoFrame,
    ): Bitmap {
        val bytes = frame.planes.single().selectedBytesView()
        val pixels = IntArray(frame.format.width * frame.format.height) { index ->
            val offset = index * 4
            val red = bytes[offset].toInt() and 255
            val green = bytes[offset + 1].toInt() and 255
            val blue = bytes[offset + 2].toInt() and 255
            val alpha = bytes[offset + 3].toInt() and 255
            (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return Bitmap.createBitmap(
            pixels,
            frame.format.width,
            frame.format.height,
            Bitmap.Config.ARGB_8888,
        )
    }
}
