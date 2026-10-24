//
//  VideoController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.media.MediaSourceControlling
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import android.net.Uri

/**
 * Coordinates local playback, RTC external media input, and preview resources.
 */
internal class VideoController(
    /**
     * The manager for RTC capture, publication, subscription, and rendering.
     */
    private val rtcManager: RtcManaging,

    /**
     * The component resolving media input and driving frame output.
     */
    private val mediaSourceController: MediaSourceControlling,
) {

    /**
     * The lock protecting the local video track.
     */
    private val stateLock = Any()

    /**
     * The active video track.
     */
    private var activeTrack: RealtimeVideoTrack? = null

    /**
     * The media track representing the current local video.
     */
    val currentTrack: RealtimeVideoTrack?
        get() = synchronized(stateLock) { activeTrack }

    /**
     * Whether the current video track includes audio.
     */
    val hasAudio: Boolean
        get() = currentTrack != null && mediaSourceController.hasAudio

    /**
     * Creates a local video stream from a URI, including its audio track when present.
     * Uses orientation-corrected media dimensions, limited by model pixel area and aligned to four pixels,
     * with the model's default frame rate.
     * The SDK selects a separate generation canvas; the server fits the media and removes padding from
     * output.
     *
     * @param uri A media URI your app can read.
     */
    suspend fun createLocalVideoStream(
        uri: Uri,
    ): RealtimeMediaStream {
        if (currentTrack != null) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Stop the current local video stream before creating another one",
            )
        }

        var track: RealtimeVideoTrack? = null
        var externalAudioStarted = false

        try {
            val configuration = mediaSourceController.prepare(uri)
            val localTrack = RealtimeVideoTrack(
                id = LOCAL_VIDEO_TRACK_ID,
                videoFormat = configuration.videoFormat,
            )
            track = localTrack
            rtcManager.useExternalVideoSource()
            if (configuration.hasAudio) {
                rtcManager.startExternalAudioSource()
                externalAudioStarted = true
            }

            VideoRenderRegistry.register(
                localTrack,
                VideoRenderBinding(
                    attachHandler = mediaSourceController::attachPreview,
                    detachHandler = mediaSourceController::detachPreview,
                ),
            )
            mediaSourceController.start()

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
                { mediaSourceController.stop() },
                { if (externalAudioStarted) rtcManager.stopExternalAudioSource() },
                { track?.let(VideoRenderRegistry::unregister) },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * Mutes or unmutes local preview without changing its stored volume.
     *
     * @param muted Whether audio is muted.
     */
    suspend fun setLocalAudioPreviewMuted(
        muted: Boolean,
    ) {
        if (currentTrack != null) mediaSourceController.setLocalAudioPreviewMuted(muted)
    }

    /**
     * Sets local preview volume from 0 to 1 without changing audio submitted to RTC.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    suspend fun setLocalAudioVolume(
        volume: Float,
    ) {
        mediaSourceController.setLocalAudioVolume(volume)
    }

    /**
     * Stops the current video source and releases decoding and preview resources; does nothing for other
     * source types.
     */
    suspend fun stopLocalVideoStream() {
        val state = synchronized(stateLock) {
            val track = activeTrack
            val hasAudio = track != null && mediaSourceController.hasAudio
            activeTrack = null
            track to hasAudio
        }

        cleanupResources(
            { mediaSourceController.stop() },
            { if (state.second) rtcManager.stopExternalAudioSource() },
            { state.first?.let { VideoRenderRegistry.binding(it)?.detach() } },
            { state.first?.let(VideoRenderRegistry::unregister) },
        )
    }

    /**
     * The local video track identifier.
     */
    private companion object {

        /**
         * The video track identifier for a local file.
         */
        const val LOCAL_VIDEO_TRACK_ID = "video0"
    }
}
