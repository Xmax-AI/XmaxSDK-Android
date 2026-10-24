//
//  AgoraRemoteVideoFrame.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.RealtimeVideoFrame
import io.agora.base.VideoFrame
import java.nio.ByteBuffer

/**
 * Borrows an Agora frame, copying I420 only when pixel access is requested; native VideoCanvas owns display.
 */
internal class AgoraRemoteVideoFrame(
    /**
     * The video frame to process, display, or submit.
     */
    private val frame: VideoFrame,
) : RtcRemoteVideoFrame {

    /**
     * The display dimensions after rotation.
     */
    override val displayWidth: Int get() = frame.rotatedWidth

    /**
     * The rotated display height, in pixels.
     */
    override val displayHeight: Int get() = frame.rotatedHeight

    /**
     * The original frame buffer dimensions.
     */
    override val width: Int get() = frame.buffer.width

    /**
     * The original buffer height, in pixels.
     */
    override val height: Int get() = frame.buffer.height

    /**
     * Copies remote pixels and metadata so callers can retain the frame after the callback returns.
     *
     * @return A remote frame with independently owned pixels.
     */
    override fun copy(): RealtimeVideoFrame {
        val i420 = frame.buffer.toI420()

        try {
            return RealtimeVideoFrame(
                width = width,
                height = height,
                presentationTimeUs = frame.timestampNs / 1_000L,
                durationUs = null,
                rotationDegrees = frame.rotation,
                y = copyPlane(
                    i420.dataY,
                    i420.strideY,
                    width,
                    height,
                ),
                u = copyPlane(
                    i420.dataU,
                    i420.strideU,
                    (width + 1) / 2,
                    (height + 1) / 2,
                ),
                v = copyPlane(
                    i420.dataV,
                    i420.strideV,
                    (width + 1) / 2,
                    (height + 1) / 2,
                ),
            )
        } finally {
            i420.release()
        }
    }

    /**
     * Copies valid pixel rows and columns, omitting row padding.
     *
     * @param buffer The buffer containing the data to process.
     * @param stride The byte distance between consecutive row starts.
     * @param columns The valid sample count in each row.
     * @param rows The number of valid rows.
     * @return An array containing the valid bytes.
     */
    private fun copyPlane(
        buffer: ByteBuffer,
        stride: Int,
        columns: Int,
        rows: Int,
    ): ByteArray {
        val source = buffer.duplicate()
        require(stride >= columns && (rows - 1L) * stride + columns <= source.remaining())
        val start = source.position()
        return ByteArray(columns * rows).also { bytes ->
            repeat(rows) { row ->
                source.position(start + row * stride)
                source.get(
                    bytes,
                    row * columns,
                    columns,
                )
            }
        }
    }
}
