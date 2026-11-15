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
 *
 * @property id The media stream identifier.
 * @property videoTrack The stream's video track.
 */
public class RealtimeMediaStream internal constructor(
    public val id: String,
    public val videoTrack: RealtimeVideoTrack?,
)

/**
 * A realtime video track with dynamic metadata.
 *
 * @property id The identifier used for rendering and trajectory bindings.
 * @param videoFormat The initial format, omitted when unknown.
 * @param position The initial lens position of a camera track.
 */
public class RealtimeVideoTrack internal constructor(
    public val id: String,
    videoFormat: RealtimeVideoFormat? = null,
    position: CameraPosition? = null,
) {

    /**
     * The current format and camera metadata snapshot.
     */
    private val metadata = AtomicReference(
        Metadata(
            videoFormat,
            position,
            null,
        ),
    )

    /**
     * The track's current video format.
     */
    public val videoFormat: RealtimeVideoFormat?
        get() = metadata.get().videoFormat

    /**
     * The local camera track's front or rear position, when available.
     */
    public val position: CameraPosition?
        get() = metadata.get().position

    /**
     * The active camera device for a local camera track, if available.
     */
    public val cameraDevice: CameraDevice?
        get() = metadata.get().cameraDevice

    /**
     * Updates the track's exposed format snapshot.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     */
    internal fun updateVideoFormat(
        videoFormat: RealtimeVideoFormat,
    ) {
        updateMetadata { it.copy(videoFormat = videoFormat) }
    }

    /**
     * Updates the device and lens position together after capture starts.
     *
     * @param device The device capturing video for this track.
     */
    internal fun updateCameraDevice(
        device: CameraDevice,
    ) {
        updateMetadata { it.copy(position = device.position, cameraDevice = device) }
    }

    /**
     * Applies a metadata update without losing a concurrent change to another field.
     *
     * @param transform The update to apply to the latest snapshot.
     */
    private inline fun updateMetadata(transform: (Metadata) -> Metadata) {
        do {
            val current = metadata.get()
            val updated = transform(current)
        } while (!metadata.compareAndSet(current, updated))
    }

    /**
     * An immutable snapshot of current video format and camera position.
     *
     * @property videoFormat The track's current video format.
     * @property position The local camera track's front or rear position, when available.
     * @property cameraDevice The active camera device, when available.
     */
    private data class Metadata(
        val videoFormat: RealtimeVideoFormat?,
        val position: CameraPosition?,
        val cameraDevice: CameraDevice?,
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
