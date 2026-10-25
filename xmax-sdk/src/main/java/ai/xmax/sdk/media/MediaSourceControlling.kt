//
//  MediaSourceControlling.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxRealtimeVideoView
import android.net.Uri

/**
 * The local video preparation, looped playback, and preview capabilities.
 */
internal interface MediaSourceControlling {

    /**
     * Whether the current input provides audio.
     */
    val hasAudio: Boolean

    /**
     * Reads video metadata and prepares local input with the original aspect ratio.
     *
     * @param uri A media URI your app can read.
     * @return The resolved configuration for media input and decoding.
     */
    suspend fun prepare(
        uri: Uri,
    ): MediaSourceConfiguration

    /**
     * Starts the prepared video, looping decoded audio and video frames.
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
        view: XmaxRealtimeVideoView,
        contentMode: VideoContentMode,
    )

    /**
     * Unbinds local media preview from a view.
     *
     * @param view The view to bind or unbind.
     */
    fun detachPreview(
        view: XmaxRealtimeVideoView,
    )

    /**
     * Stops local video output and releases media resources.
     */
    suspend fun stop()
}
