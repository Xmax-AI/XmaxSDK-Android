//
//  RealtimeMediaStream.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import java.util.concurrent.atomic.AtomicReference

/**
 * A realtime stream containing local input or remote generation.
 */
public class RealtimeMediaStream internal constructor(
    /**
     * The media stream identifier.
     */
    public val id: String,

    /**
     * The stream's video track.
     */
    public val videoTrack: RealtimeVideoTrack?,
)

/**
 * A realtime video track with dynamic metadata.
 *
 * @param videoFormat The initial format, omitted when unknown.
 * @param position The initial lens position of a camera track.
 */
public class RealtimeVideoTrack internal constructor(
    /**
     * The identifier used for rendering and trajectory bindings.
     */
    public val id: String,

    videoFormat: RealtimeVideoFormat? = null,
    position: CameraPosition? = null,
) {

    /**
     * The current format and camera position snapshot.
     */
    private val metadata = AtomicReference(
        Metadata(
            videoFormat,
            position,
        ),
    )

    /**
     * The track's current video format.
     */
    public val videoFormat: RealtimeVideoFormat?
        get() = metadata.get().videoFormat

    /**
     * The local camera track's current camera position, or null for a noncamera track.
     */
    public val position: CameraPosition?
        get() = metadata.get().position

    /**
     * Updates the track's exposed format snapshot.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     */
    internal fun updateVideoFormat(
        videoFormat: RealtimeVideoFormat,
    ) {
        metadata.updateAndGet { it.copy(videoFormat = videoFormat) }
    }

    /**
     * Updates the track's camera position.
     *
     * @param position The front or rear camera to use.
     */
    internal fun updatePosition(
        position: CameraPosition,
    ) {
        metadata.updateAndGet { it.copy(position = position) }
    }

    /**
     * An immutable snapshot of current video format and camera position.
     */
    private data class Metadata(
        /**
         * The track's current video format.
         */
        val videoFormat: RealtimeVideoFormat?,

        /**
         * The local camera track's current camera position, or null for a noncamera track.
         */
        val position: CameraPosition?,
    )
}

/**
 * Internal camera preview readiness; public readiness is represented by RealtimeConnectionState.READY.
 */
internal fun interface RealtimeCameraPreviewReadyListener {

    /**
     * Reports that the current camera preview can be displayed.
     */
    fun onCameraPreviewReady()
}
