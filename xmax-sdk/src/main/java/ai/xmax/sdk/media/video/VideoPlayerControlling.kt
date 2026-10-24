//
//  VideoPlayerControlling.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxVideoView
import android.net.Uri

/**
 * The file audio/video output capability using a shared timeline.
 */
internal interface VideoPlayerControlling {

    /**
     * Sets the decoder source, output dimensions, rotation, frame rate, and duration.
     *
     * @param uri A media URI your app can read.
     * @param outputWidth The output width, in pixels.
     * @param outputHeight The output height, in pixels.
     * @param rotation The rotation applied to the source image.
     * @param frameRate The video frame rate, in fps.
     * @param hasAudio Whether the input provides audio.
     * @param durationUs The media duration, in microseconds.
     */
    suspend fun configure(
        uri: Uri,
        outputWidth: Int,
        outputHeight: Int,
        rotation: VideoRotation,
        frameRate: Int,
        hasAudio: Boolean,
        durationUs: Long,
    )

    /**
     * Starts looped decoding and supplies media frames on the shared timeline.
     */
    suspend fun start()

    /**
     * Mutes or unmutes local preview without changing its stored volume.
     *
     * @param muted Whether audio is muted.
     */
    suspend fun setLocalAudioPreviewMuted(
        muted: Boolean,
    )

    /**
     * Sets local preview volume from 0 to 1 without changing audio submitted to RTC.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    suspend fun setLocalAudioVolume(
        volume: Float,
    )

    /**
     * Binds local media preview to a view with the requested scaling mode.
     *
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    fun attachPreview(
        view: XmaxVideoView,
        contentMode: VideoContentMode,
    )

    /**
     * Unbinds local media preview from a view.
     *
     * @param view The view to bind or unbind.
     */
    fun detachPreview(
        view: XmaxVideoView,
    )

    /**
     * Stops decoding and releases the current media resources.
     */
    suspend fun stop()
}
