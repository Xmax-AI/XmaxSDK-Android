//
//  MediaSourceController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeMediaSource
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxRealtimeVideoView
import ai.xmax.sdk.foundation.media.MediaFileMetadata
import ai.xmax.sdk.foundation.media.MediaFileMetadataManaging
import ai.xmax.sdk.media.video.VideoPlayerControlling
import ai.xmax.sdk.MediaServicing
import android.net.Uri
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates local video metadata, output format, and platform playback.
 */
internal class MediaSourceController(
    /**
     * The media metadata reader.
     */
    private val metadataManager: MediaFileMetadataManaging,

    /**
     * The service resolving media dimensions and model constraints.
     */
    private val mediaService: MediaServicing,

    /**
     * The component owning local decoding and playback timing.
     */
    private val playerController: VideoPlayerControlling,
) : MediaSourceControlling {

    /**
     * The mutex serializing media preparation and playback.
     */
    private val operationMutex = Mutex()

    /**
     * The lock protecting prepared media and output state.
     */
    private val stateLock = Any()

    /**
     * The prepared media configuration.
     */
    private var preparedMedia: PreparedMedia? = null

    /**
     * Whether media frame output is running.
     */
    private var isRunning = false

    /**
     * Whether the prepared media contains audio.
     */
    override val hasAudio: Boolean
        get() = synchronized(stateLock) { preparedMedia?.configuration?.hasAudio == true }

    /**
     * Reads video metadata and prepares local input with the original aspect ratio.
     *
     * @param uri A media URI your app can read.
     * @return The resolved configuration for media input and decoding.
     */
    override suspend fun prepare(
        uri: Uri,
    ): MediaSourceConfiguration = operationMutex.withLock {
        if (synchronized(stateLock) { preparedMedia != null }) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Stop the current media source before preparing another file",
            )
        }

        try {
            val metadata = metadataManager.readMetadata(uri)
            val resolvedFormat = resolveVideoFormat(metadata)

            playerController.configure(
                uri = uri,
                metadata = metadata,
                videoFormat = resolvedFormat,
            )

            val configuration = MediaSourceConfiguration(
                resolvedFormat,
                metadata.hasAudio,
            )
            synchronized(stateLock) {
                preparedMedia = PreparedMedia(
                    metadata,
                    configuration,
                )
            }
            configuration
        } catch (
            error: Throwable,
        ) {
            throw XmaxError.from(error)
        }
    }

    /**
     * Starts the prepared video, looping decoded audio and video frames.
     */
    override suspend fun start() = operationMutex.withLock {
        synchronized(stateLock) {
            if (preparedMedia == null) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Prepare the media file before starting it",
                )
            }

            if (isRunning) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Local media source is already active",
                )
            }

            isRunning = true
        }

        try {
            playerController.start()
        } catch (
            error: Throwable,
        ) {
            synchronized(stateLock) { isRunning = false }
            cleanupAfterFailure(
                error,
                { playerController.stop() },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * Mutes or unmutes local preview without changing its stored volume.
     *
     * @param muted Whether audio is muted.
     */
    override suspend fun setLocalAudioPreviewMuted(
        muted: Boolean,
    ) {
        if (hasAudio) playerController.setLocalAudioPreviewMuted(muted)
    }

    /**
     * Sets local preview volume from 0 to 1 without changing audio submitted to RTC.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    override suspend fun setLocalAudioVolume(
        volume: Float,
    ) {
        playerController.setLocalAudioVolume(volume)
    }

    /**
     * Binds local media preview to a view with the requested scaling mode.
     *
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    override fun attachPreview(
        view: XmaxRealtimeVideoView,
        contentMode: VideoContentMode,
    ) {
        playerController.attachPreview(
            view,
            contentMode,
        )
    }

    /**
     * Unbinds local media preview from a view.
     *
     * @param view The view to bind or unbind.
     */
    override fun detachPreview(
        view: XmaxRealtimeVideoView,
    ) {
        playerController.detachPreview(view)
    }

    /**
     * Stops local video output and clears prepared media metadata.
     */
    override suspend fun stop() = operationMutex.withLock {
        synchronized(stateLock) {
            isRunning = false
            preparedMedia = null
        }
        playerController.stop()
    }

    /**
     * Resolves aspect-preserving input dimensions from orientation-corrected media metadata.
     *
     * @param metadata The original dimensions, orientation, duration, and audio metadata.
     * @return The resolved video dimensions, frame rate, and encoding settings.
     */
    internal fun resolveVideoFormat(
        metadata: MediaFileMetadata,
    ): RealtimeVideoFormat {
        val swapsDimensions = metadata.rotation == VideoRotation.ROTATION_90 ||
            metadata.rotation == VideoRotation.ROTATION_270
        val displaySize = if (swapsDimensions) {
            IntSize(
                metadata.height,
                metadata.width,
            )
        } else {
            IntSize(
                metadata.width,
                metadata.height,
            )
        }
        val resolvedMediaSize = mediaService.resolveMediaSize(
            displaySize,
            RealtimeMediaSource.VIDEO,
        )

        return RealtimeVideoFormat(
            resolvedMediaSize.width,
            resolvedMediaSize.height,
            mediaService.model.defaultFrameRate,
        )
    }

    /**
     * The parsed media metadata and final playback configuration.
     */
    private data class PreparedMedia(
        /**
         * The original dimensions, orientation, duration, and audio metadata.
         */
        val metadata: MediaFileMetadata,

        /**
         * The media input dimensions, frame rate, rotation, and audio configuration.
         */
        val configuration: MediaSourceConfiguration,
    )
}
