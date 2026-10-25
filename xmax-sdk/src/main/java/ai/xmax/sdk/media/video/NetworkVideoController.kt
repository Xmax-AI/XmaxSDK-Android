//
//  NetworkVideoController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeReferenceVideo
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeMediaSource
import ai.xmax.sdk.RealtimeVideoSampleMethod
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.XmaxRealtimeVideoView
import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.ui.unit.IntSize
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A network video preview whose URL is independently read by the generation server.
 */
internal class NetworkVideoController(
    /**
     * The Android context for system services and media resources.
     */
    private val context: Context,

    /**
     * The service resolving media dimensions and model constraints.
     */
    private val mediaService: MediaServicing,
) {

    /**
     * The current network video track.
     */
    @Volatile var currentTrack: RealtimeVideoTrack? = null
        private set

    /**
     * The network video configuration read by the server.
     */
    @Volatile var reference: RealtimeReferenceVideo? = null
        private set

    /**
     * The completion callback for the network video generation task.
     */
    @Volatile var onFinish: (() -> Unit)? = null
        private set

    /**
     * The local network video preview player.
     */
    private var preview: NetworkVideoPreview? = null

    /**
     * Creates a server-read network video source with a muted local preview.
     *
     * @param uri A media URI your app can read.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param sampleMethod The sampling method used when the server reads the network video.
     * @param onFinish The completion callback for the network video task, or null to omit it.
     */
    suspend fun create(
        uri: Uri,
        videoFormat: RealtimeVideoFormat,
        sampleMethod: RealtimeVideoSampleMethod,
        onFinish: (() -> Unit)?,
    ): RealtimeMediaStream = withContext(Dispatchers.Main.immediate) {
        check(currentTrack == null)
        val path = validateNetworkVideoUrl(uri.toString())
        videoFormat.validate()
        val size = mediaService.resolveMediaSize(
            IntSize(
                videoFormat.width,
                videoFormat.height,
            ),
            RealtimeMediaSource.CAMERA,
        )
        val format = videoFormat.copy(
            width = size.width,
            height = size.height,
        ).also { it.validate() }
        val track = RealtimeVideoTrack(
            "network-video",
            format,
        )
        val player = NetworkVideoPreview(
            context,
            uri,
        )
        preview = player
        reference = RealtimeReferenceVideo(
            path,
            sampleMethod,
        )
        this@NetworkVideoController.onFinish = onFinish
        currentTrack = track
        VideoRenderRegistry.register(
            track,
            VideoRenderBinding(
                player::attach,
                player::detach,
            ),
        )
        RealtimeMediaStream(
            StreamID.LOCAL.value,
            track,
        )
    }

    /**
     * Stops network preview, unregisters its track, and clears the server reference.
     */
    suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        val track = currentTrack
        currentTrack = null
        reference = null
        onFinish = null
        cleanupResources(
            { track?.let { VideoRenderRegistry.binding(it)?.detach() } },
            { preview?.release() },
            { preview = null },
            { track?.let(VideoRenderRegistry::unregister) },
        )
    }
}

/**
 * Validates an HTTP or HTTPS video URL for server access.
 *
 * @param value The text to normalize, validate, or display.
 * @return The validated HTTP or HTTPS video URL.
 */
internal fun validateNetworkVideoUrl(
    value: String,
): String {
    val parsed = runCatching { URI(value) }.getOrNull()
    if (parsed == null || parsed.scheme?.lowercase() !in setOf(
        "http",
        "https",
    ) ||
        parsed.host.isNullOrBlank() || parsed.rawUserInfo != null
    ) {
        throw XmaxError(
            XmaxErrorCode.INVALID_CONFIGURATION,
            "Network video requires a valid HTTP or HTTPS URL",
        )
    }

    return value
}

/**
 * Player and Surface operations run serially on the main thread; preview failure does not end the independent
 * server task.
 */
private class NetworkVideoPreview(
    /**
     * The Android context for system services and media resources.
     */
    private val context: Context,

    /**
     * A media URI accessible to your app.
     */
    private val uri: Uri,
) {

    /**
     * The local preview player.
     */
    private var player: MediaPlayer? = null

    /**
     * The Surface receiving player video output.
     */
    private var surface: Surface? = null

    /**
     * The view hosting local network video preview.
     */
    private var view: XmaxRealtimeVideoView? = null

    /**
     * The texture view receiving player output.
     */
    private var texture: TextureView? = null

    /**
     * The preview scaling mode.
     */
    private var contentMode = VideoContentMode.FIT

    /**
     * Whether the network player is prepared.
     */
    private var prepared = false

    /**
     * Whether network video playback has ended.
     */
    private var completed = false

    /**
     * The video display dimensions, in pixels.
     */
    private var videoWidth = 0

    /**
     * The original network video height, in pixels.
     */
    private var videoHeight = 0

    /**
     * Binds network video preview to a view with the requested scaling mode.
     *
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    fun attach(
        view: XmaxRealtimeVideoView,
        contentMode: VideoContentMode,
    ) {
        this.view = view
        this.contentMode = contentMode
        val texture = view.prepareNetworkVideoRendering()
        this.texture = texture
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {

            /**
             * Binds video output once the preview texture is available.
             *
             * @param value The preview SurfaceTexture.
             * @param width The frame width, in pixels.
             * @param height The frame height, in pixels.
             */
            override fun onSurfaceTextureAvailable(
                value: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                if (this@NetworkVideoPreview.texture !== texture) return

                bindSurface(value)
                updateTransform()
            }

            /**
             * Updates video layout after preview dimensions change.
             *
             * @param value The preview SurfaceTexture.
             * @param width The frame width, in pixels.
             * @param height The frame height, in pixels.
             */
            override fun onSurfaceTextureSizeChanged(
                value: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                updateTransform()
            }

            /**
             * Detaches a destroyed texture and determines who releases it.
             *
             * @param value The preview SurfaceTexture.
             * @return Whether TextureView should release the texture.
             */
            override fun onSurfaceTextureDestroyed(
                value: SurfaceTexture,
            ): Boolean {
                if (this@NetworkVideoPreview.texture === texture) detachSurface()
                return true
            }

            /**
             * Receives a texture content update.
             *
             * @param value The preview SurfaceTexture.
             */
            override fun onSurfaceTextureUpdated(
                value: SurfaceTexture,
            ) = Unit
        }
        texture.surfaceTexture?.let(::bindSurface)
    }

    /**
     * Unbinds network video preview from a view.
     *
     * @param view The view to bind or unbind.
     */
    fun detach(
        view: XmaxRealtimeVideoView,
    ) {
        if (this.view !== view) return

        detachSurface()
        texture?.surfaceTextureListener = null
        texture = null
        view.invalidateVideoPresentation()
        this.view = null
    }

    /**
     * Binds player output to a preview texture.
     *
     * @param value The preview SurfaceTexture.
     */
    private fun bindSurface(
        value: SurfaceTexture,
    ) {
        runCatching {
            surface?.release()
            surface = Surface(value)
            val existing = player
            if (existing != null) {
                existing.setSurface(surface)
                resumePreview(existing)
            } else {
                val created = MediaPlayer()
                player = created
                created.setVolume(
                    0f,
                    0f,
                )
                created.isLooping = false
                created.setSurface(surface)
                created.setOnPreparedListener {
                    if (player === it) {
                        prepared = true
                        videoWidth = it.videoWidth
                        videoHeight = it.videoHeight
                        updateTransform()
                        resumePreview(it)
                    }
                }
                created.setOnVideoSizeChangedListener { _, width, height ->
                    videoWidth = width
                    videoHeight = height
                    updateTransform()
                }
                created.setOnCompletionListener { if (player === it) completed = true }
                created.setOnErrorListener { _, what, extra ->
                    previewFailed(IllegalStateException("Network preview error: $what/$extra"))
                    true
                }
                created.setDataSource(
                    context,
                    uri,
                )
                created.prepareAsync()
            }
        }.onFailure(::previewFailed)
    }

    /**
     * Resumes local video preview playback.
     *
     * @param player The local preview and decoding player.
     */
    private fun resumePreview(
        player: MediaPlayer,
    ) {
        if (!prepared || surface == null) return

        if (completed) {
            // Restore the final frame when rebinding a view without replaying the entire video.
            player.seekTo(
                (player.duration - 1).coerceAtLeast(0).toLong(),
                MediaPlayer.SEEK_CLOSEST,
            )
        } else {
            player.start()
        }
    }

    /**
     * Updates preview transforms from video dimensions and scaling mode.
     */
    private fun updateTransform() {
        val target = texture ?: return
        if (target.width <= 0 || target.height <= 0 || videoWidth <= 0 || videoHeight <= 0) return

        val widthRatio = target.width.toFloat() / videoWidth
        val heightRatio = target.height.toFloat() / videoHeight
        val scale = if (contentMode == VideoContentMode.FIT) minOf(
            widthRatio,
            heightRatio,
        ) else maxOf(
            widthRatio,
            heightRatio,
        )
        target.setTransform(
            Matrix().apply {
                setScale(
                    videoWidth * scale / target.width,
                    videoHeight * scale / target.height,
                    target.width / 2f,
                    target.height / 2f,
                )
            },
        )
    }

    /**
     * Detaches the player from its preview Surface.
     */
    private fun detachSurface() {
        player?.let {
            if (prepared && !completed) it.pause()
            it.setSurface(null)
        }
        surface?.release()
        surface = null
    }

    /**
     * Reports local preview playback failure.
     *
     * @param error The operation failure or SDK error.
     */
    private fun previewFailed(
        error: Throwable,
    ) {
        player?.release()
        player = null
        prepared = false
        XmaxLogger.realtime.warn(message = { "Network video preview failed: ${error.message}" })
    }

    /**
     * Releases the network player and local preview resources.
     */
    fun release() {
        view?.let(::detach)
        player?.release()
        player = null
        prepared = false
    }
}
