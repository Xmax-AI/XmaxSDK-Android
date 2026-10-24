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
import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
    private var preview: Preview? = null

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
        val preview = if (source.providesPreview) null else Preview { input.reportError(it) }
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
     * Displays the latest submitted frame without queuing stale frames or blocking capture on the UI thread.
     */
    private class Preview(
        /**
         * The receiver of unrecoverable preview conversion or display failures.
         */
        private val onFailure: (Throwable) -> Unit,
    ) {

        /**
         * The scope converting and displaying submitted frames.
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * The conflated wake-up queue for the latest frame.
         */
        private val updates = Channel<Unit>(Channel.CONFLATED)

        /**
         * The lock protecting preview target and retained frame state.
         */
        private val lock = Any()

        /**
         * The latest copied frame available for preview.
         */
        private var frame: VideoFrame? = null

        /**
         * The weakly held display target.
         */
        private var view: WeakReference<XmaxVideoView>? = null

        /**
         * The aspect-preserving display mode.
         */
        private var mode = VideoContentMode.FILL

        /**
         * The revision rejecting deliveries prepared for an older view or binding.
         */
        private var revision = 0L

        init {
            scope.launch {
                for (ignored in updates) {
                    val target = synchronized(lock) {
                        val frame = frame ?: return@synchronized null
                        val view = view?.get() ?: return@synchronized null
                        Triple(
                            frame,
                            view,
                            revision,
                        )
                    } ?: continue
                    var bitmap: Bitmap? = null
                    var delivered = false
                    try {
                        val image = makeBitmap(target.first)
                        bitmap = image
                        withContext(Dispatchers.Main.immediate) {
                            if (synchronized(lock) { revision == target.third && view?.get() === target.second }) {
                                target.second.displayDecodedVideoBitmap(
                                    image,
                                    mode,
                                )
                                delivered = true
                            }
                        }
                    } catch (
                        cancelled: CancellationException,
                    ) {
                        throw cancelled
                    } catch (
                        error: Exception,
                    ) {
                        onFailure(error)
                        return@launch
                    } finally {
                        if (!delivered) bitmap?.recycle()
                    }
                }
            }
        }

        /**
         * Converts RGBA channels to Android colors with correct alpha handling.
         *
         * @param frame The processed, independently owned external frame.
         * @return A bitmap owned by preview until display transfers ownership to the view.
         */
        private fun makeBitmap(
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

        /**
         * Attaches submitted-frame preview and schedules the latest available frame.
         *
         * @param view The display target.
         * @param mode The aspect-preserving fit or fill mode.
         */
        fun attach(
            view: XmaxVideoView,
            mode: VideoContentMode,
        ) {
            synchronized(lock) {
                this.view = WeakReference(view)
                this.mode = mode
                revision++
            }
            view.prepareDecodedVideoPreview(mode)
            updates.trySend(Unit)
        }

        /**
         * Detaches a display target without ending capture or discarding the latest frame.
         *
         * @param view The target to detach.
         */
        fun detach(
            view: XmaxVideoView,
        ) {
            synchronized(lock) {
                if (this.view?.get() === view) {
                    this.view = null
                    revision++
                }
            }
            view.clearDecodedVideoPreview()
        }

        /**
         * Retains the latest independently owned frame for preview.
         *
         * @param frame The copied, processed external frame.
         */
        fun enqueue(
            frame: VideoFrame,
        ) {
            val attached = synchronized(lock) {
                this.frame = frame
                view?.get() != null
            }
            if (attached) updates.trySend(Unit)
        }

        /**
         * Cancels pending display work and releases the preview's frame and target.
         */
        fun close() {
            val previous = synchronized(lock) {
                revision++
                frame = null
                view?.get().also { view = null }
            }
            updates.close()
            scope.cancel()
            previous?.clearDecodedVideoPreview()
        }
    }
}
