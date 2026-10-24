//
//  VideoFrame.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import java.util.UUID

/**
 * A platform-neutral video frame with pixels and timing metadata.
 *
 * @param planes The pixel planes in the format's required order.
 */
internal class VideoFrame(
    /**
     * The video dimensions and pixel arrangement.
     */
    val format: VideoFormat,

    /**
     * The monotonic frame timestamp, in microseconds.
     */
    val timestampUs: Long,

    planes: List<VideoFramePlane>,

    /**
     * The rotation applied to the source image; defaults to `VideoRotation.ROTATION_0`.
     */
    val rotation: VideoRotation = VideoRotation.ROTATION_0,

    /**
     * The identifier of reusable pixel storage, or null without one.
     */
    val bufferReuseId: UUID? = null,
) {

    /**
     * The dimensions and pixels of an image or video.
     */
    val planes: List<VideoFramePlane> = planes.toList()

    init {
        if (timestampUs < 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Video frame timestamp must be non-negative",
            )
        }

        if (planes.isEmpty()) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Video frame must contain at least one plane",
            )
        }
    }

    /**
     * Reuses pixel storage with updated per-frame metadata.
     *
     * @param timestampUs The monotonic frame timestamp, in microseconds.
     * @param rotation The source rotation; defaults to `this.rotation`.
     * @return A frame with the requested pixel format, dimensions, and orientation.
     */
    fun updating(
        timestampUs: Long,
        rotation: VideoRotation = this.rotation,
    ): VideoFrame = VideoFrame(
        format = format,
        timestampUs = timestampUs,
        planes = planes,
        rotation = rotation,
        bufferReuseId = bufferReuseId,
    )

    /**
     * Compares objects by value.
     *
     * @param other The object to compare or combine.
     */
    override fun equals(
        other: Any?,
    ): Boolean =
        this === other ||
            other is VideoFrame &&
            format == other.format &&
            timestampUs == other.timestampUs &&
            planes == other.planes &&
            rotation == other.rotation &&
            bufferReuseId == other.bufferReuseId

    /**
     * Computes a hash consistent with value equality.
     */
    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + planes.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + (bufferReuseId?.hashCode() ?: 0)
        return result
    }
}
