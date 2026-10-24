//
//  XmaxMedia3Source.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.media3

import ai.xmax.sdk.*
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Media3 adapter supporting SDR, normal-speed playback, and PCM16 audio; your app creates and releases the
 * player.
 * Pass renderersFactory when creating ExoPlayer, then call attachPlayer. A player can bind only one video
 * output.
 * SDK stop and close only detach frame delivery. Finally release ExoPlayer before calling release to free GL
 * resources.
 *
 * @param context The Android context used for system services and media resources.
 */
@UnstableApi
@MainThread
public class XmaxMedia3Source(
    context: Context,
) : RealtimeExternalVideoSource {

    /**
     * The current external frame sink.
     */
    @Volatile private var sink: RealtimeExternalFrameSink? = null

    /**
     * The bridge connecting decoder output, local preview, and SDK frame input.
     */
    private val bridge = DecoderSurfaceBridge { sink }

    /**
     * The Media3 player decoding and playing external media.
     */
    private var player: ExoPlayer? = null

    /**
     * The local preview texture view.
     */
    private var preview: TextureView? = null

    /**
     * The container hosting local preview.
     */
    private var previewHost: FrameLayout? = null

    /**
     * The local audio preview volume.
     */
    private var volume = 0.45f

    /**
     * Whether local audio preview is muted.
     */
    private var muted = false

    /**
     * The latest playback failure.
     */
    private var playbackFailure: PlaybackException? = null

    /**
     * A main-thread callback indicating the first local preview frame, allowing the app to hide its video
     * cover.
     */
    public var onPreviewFrame: (() -> Unit)? = null

    /**
     * The external input video format.
     */
    override val videoFormat: RealtimeVideoFormat
        get() {
            val size = checkNotNull(player).videoSize
            require(size.width > 0 && size.height > 0) { "Prepare ExoPlayer before binding it to XmaxSDK" }
            // Reports source display dimensions; XmaxSDK resolves generation dimensions from app configuration and model constraints.
            val width = kotlin.math.round(size.width * size.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
            return RealtimeVideoFormat(
                width,
                size.height,
                30,
            )
        }

    /**
     * Whether the selected media tracks include audio.
     */
    override val hasAudio: Boolean get() = player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_AUDIO) == true

    /**
     * The player's audio/video renderer factory.
     */
    public val renderersFactory: DefaultRenderersFactory = object : DefaultRenderersFactory(context.applicationContext) {

        /**
         * Creates an audio sink supporting both local playback and external PCM output.
         *
         * @param context The Android context used for system services and media resources.
         * @param enableFloatOutput Whether floating-point audio output is allowed.
         * @param enableAudioOutputPlaybackParams Whether audio output may apply playback-speed parameters.
         * @return A local playback sink supporting PCM frame delivery.
         */
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean,
        ): AudioSink =
            FrameAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(false).build()) { sink }
    }

    /**
     * The listener for player readiness, completion, and errors.
     */
    private val listener = object : Player.Listener {

        /**
         * Updates preparation or playback state from player state.
         *
         * @param playbackState The current Media3 playback state.
         */
        override fun onPlaybackStateChanged(
            playbackState: Int,
        ) {
            if (playbackState == Player.STATE_READY) playbackFailure = null
        }

        /**
         * Reports player failures to the external source consumer.
         *
         * @param error The operation failure or SDK error.
         */
        override fun onPlayerError(
            error: PlaybackException,
        ) {
            playbackFailure = error
            sink?.reportError(error)
        }

        /**
         * Updates display dimensions used by preview and frame output.
         *
         * @param videoSize The rotated display dimensions.
         */
        override fun onVideoSizeChanged(
            videoSize: VideoSize,
        ) {
            bridge.videoSize(
                (videoSize.width * videoSize.pixelWidthHeightRatio).toInt(),
                videoSize.height,
            )
        }
    }

    /**
     * Attaches a player to this external source.
     *
     * @param player The local preview and decoding player.
     */
    public suspend fun attachPlayer(
        player: ExoPlayer,
    ): Unit = withContext(Dispatchers.Main.immediate) {
        check(this@XmaxMedia3Source.player == null)
        val surface = bridge.surface()
        this@XmaxMedia3Source.player = player
        player.addListener(listener)
        player.setVideoSurface(surface)
        player.volume = if (muted) 0f else volume
    }

    /**
     * Binds frame delivery before returning, without waiting for generation to finish.
     * Your app rotates, crops, and scales frames to sink.videoFormat; delivery must not wait for
     * startGeneration to return.
     * Report source completion or unrecoverable failure through sink.reportError. The SDK may call stop even
     * after start fails.
     *
     * @param sink The SDK receiver for external media frames.
     */
    override suspend fun start(
        sink: RealtimeExternalFrameSink,
    ) {
        check(this.sink == null)
        bridge.failure?.let { throw it }
        playbackFailure?.let { throw it }
        this.sink = sink
    }

    /**
     * Stops frame delivery and clears the sink idempotently without closing your app's camera or player.
     */
    override suspend fun stop() { sink = null }

    /**
     * Attaches player preview to the SDK video container.
     *
     * @param container The Android container hosting local preview.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    override fun attachPreview(
        container: FrameLayout,
        contentMode: VideoContentMode,
    ) {
        previewHost?.let(::detachPreview)
        val view = TextureView(container.context)
        preview = view; previewHost = container
        var reported = false
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {

            /**
             * Binds video output once the preview texture is available.
             *
             * @param texture The texture receiving preview output.
             * @param width The frame width, in pixels.
             * @param height The frame height, in pixels.
             */
            override fun onSurfaceTextureAvailable(
                texture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                bridge.preview(
                    texture,
                    width,
                    height,
                    contentMode,
                )
            }

            /**
             * Updates video layout after preview dimensions change.
             *
             * @param texture The texture receiving preview output.
             * @param width The frame width, in pixels.
             * @param height The frame height, in pixels.
             */
            override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                bridge.preview(
                    texture,
                    width,
                    height,
                    contentMode,
                )
            }

            /**
             * Detaches a destroyed texture and determines who releases it.
             *
             * @param texture The texture receiving preview output.
             * @return False; the texture bridge releases the texture after detaching EGL.
             */
            override fun onSurfaceTextureDestroyed(
                texture: SurfaceTexture,
            ): Boolean {
                bridge.destroyPreview(texture)
                return false
            }

            /**
             * Receives a texture content update.
             *
             * @param texture The texture receiving preview output.
             */
            override fun onSurfaceTextureUpdated(
                texture: SurfaceTexture,
            ) {
                if (!reported && preview === view) {
                    reported = true
                    onPreviewFrame?.invoke()
                }
            }
        }
        container.addView(
            view,
            FrameLayout.LayoutParams(
                -1,
                -1,
            ),
        )
    }

    /**
     * Removes player preview from its container without stopping playback.
     *
     * @param container The Android container hosting local preview.
     */
    override fun detachPreview(
        container: FrameLayout,
    ) {
        if (previewHost !== container) return
        preview?.let(container::removeView)
        preview = null; previewHost = null
    }

    /**
     * Sets local monitoring volume and mute state without changing PCM supplied to XmaxSDK.
     *
     * @param volume The playback volume, from 0 to 1.
     * @param muted Whether audio is muted.
     */
    override fun setPreviewAudio(
        volume: Float,
        muted: Boolean,
    ) {
        this.volume = volume; this.muted = muted
        player?.volume = if (muted) 0f else volume
    }

    /**
     * Unbinds the source, player preview, and decoder texture.
     */
    public suspend fun release(): Unit = withContext(Dispatchers.Main.immediate) {
        sink = null
        onPreviewFrame = null
        previewHost?.let(::detachPreview)
        player?.removeListener(listener)
        player = null
        bridge.release()
    }

    /**
     * Processing metrics without media content.
     *
     * @return Processing statistics without media content.
     */
    public fun diagnostics(): String =
        "rendered=${bridge.renderedFrames}, captured=${bridge.capturedFrames}, maxCaptureMs=${bridge.maximumCaptureMs}, error=${bridge.failure?.javaClass?.simpleName}"
}
