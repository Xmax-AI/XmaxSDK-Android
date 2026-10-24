//
//  ImageController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.image

import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import android.graphics.Bitmap
import android.net.Uri

/**
 * Coordinates image decoding, repeated frame delivery, and preview.
 */
internal class ImageController(
    /**
     * The manager for RTC capture, publication, subscription, and rendering.
     */
    private val rtcManager: RtcManaging,

    /**
     * The component decoding images and supplying repeated frames.
     */
    private val imageSourceController: ImageSourceControlling,
) {

    /**
     * The lock protecting image track state.
     */
    private val stateLock = Any()

    /**
     * The active image track.
     */
    private var activeTrack: RealtimeVideoTrack? = null

    /**
     * The local video track representing the current image.
     */
    val currentTrack: RealtimeVideoTrack?
        get() = synchronized(stateLock) { activeTrack }

    /**
     * Creates a continuous video frame stream from encoded image data.
     *
     * The stream retains the media aspect ratio and uses the model's default frame rate. Dimensions
     * respect model pixel-area limits and four-pixel alignment; the generation canvas is selected
     * separately.
     *
     * @param imageData Encoded image file bytes, not raw pixels.
     */
    suspend fun createLocalImageStream(
        imageData: ByteArray,
    ): RealtimeMediaStream = createStream {
        imageSourceController.prepare(imageData)
    }

    /**
     * Creates a continuous video frame stream from an Android bitmap.
     *
     * The stream retains the media aspect ratio and uses the model's default frame rate. Dimensions
     * respect model pixel-area limits and four-pixel alignment; the generation canvas is selected
     * separately.
     *
     * @param bitmap The Android image to process or display.
     */
    suspend fun createLocalImageStream(
        bitmap: Bitmap,
    ): RealtimeMediaStream = createStream {
        imageSourceController.prepare(bitmap)
    }

    /**
     * Creates a continuous video frame stream from a local image URI.
     *
     * Your app must hold read permission. The stream retains the media aspect ratio and uses the
     * model's default frame rate. Dimensions respect model pixel-area limits and four-pixel
     * alignment; the generation canvas is selected separately.
     *
     * @param uri A media URI your app can read.
     */
    suspend fun createLocalImageStream(
        uri: Uri,
    ): RealtimeMediaStream = createStream {
        imageSourceController.prepare(uri)
    }

    /**
     * Stops the local image stream.
     *
     * Other input sources are unaffected.
     */
    suspend fun stopLocalImageStream() {
        val track = synchronized(stateLock) {
            activeTrack.also { activeTrack = null }
        }

        cleanupResources(
            { imageSourceController.stop() },
            { track?.let { VideoRenderRegistry.binding(it)?.detach() } },
            { track?.let(VideoRenderRegistry::unregister) },
        )
    }

    /**
     * Prepares image input and registers a track for preview and generation.
     *
     * @param prepare The suspending function resolving input format and image pixels.
     */
    private suspend fun createStream(
        prepare: suspend () -> Pair<RealtimeVideoFormat, VideoFrame>,
    ): RealtimeMediaStream {
        if (currentTrack != null) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Stop the current local image stream before creating another one",
            )
        }

        var track: RealtimeVideoTrack? = null

        try {
            val source = prepare()
            val localTrack = RealtimeVideoTrack(
                id = LOCAL_VIDEO_TRACK_ID,
                videoFormat = source.first,
            )
            track = localTrack
            rtcManager.useExternalVideoSource()
            VideoRenderRegistry.register(
                localTrack,
                VideoRenderBinding(source.second),
            )
            imageSourceController.start()
            synchronized(stateLock) { activeTrack = localTrack }
            return RealtimeMediaStream(
                StreamID.LOCAL.value,
                localTrack,
            )
        } catch (
            error: Throwable,
        ) {
            cleanupAfterFailure(
                error,
                { imageSourceController.stop() },
                { track?.let(VideoRenderRegistry::unregister) },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * The local image track identifier.
     */
    private companion object {

        /**
         * The video track identifier for local image output.
         */
        const val LOCAL_VIDEO_TRACK_ID = "video0"
    }
}
