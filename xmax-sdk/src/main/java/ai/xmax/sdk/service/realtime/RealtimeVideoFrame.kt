//
//  RealtimeVideoFrame.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import java.nio.ByteBuffer

/**
 * A processed frame from the realtime video pipeline.
 *
 * Pixels are owned by XmaxSDK and stored as tightly packed I420 Y, U, and V planes, independently
 * of the native RTC frame. Your app may retain the frame for asynchronous encoding without
 * releasing it. Each data accessor returns an independent read-only view; changing its position
 * does not affect other consumers.
 */
public class RealtimeVideoFrame internal constructor(
    /**
     * The frame width, in pixels.
     */
    public val width: Int,

    /**
     * The frame height, in pixels.
     */
    public val height: Int,

    /**
     * The presentation timestamp on the shared remote audio/video timeline, in microseconds.
     */
    public val presentationTimeUs: Long,

    /**
     * The frame duration in microseconds, or null if RTC does not provide it.
     */
    public val durationUs: Long?,

    /**
     * The clockwise rotation still required for display: 0, 90, 180, or 270 degrees.
     */
    public val rotationDegrees: Int,

    /**
     * The Y pixel plane.
     */
    private val y: ByteArray,

    /**
     * The U pixel plane.
     */
    private val u: ByteArray,

    /**
     * The V pixel plane.
     */
    private val v: ByteArray,
) {

    /**
     * The Y-plane row stride, in bytes.
     */
    public val yStride: Int get() = width

    /**
     * The U-plane row stride, in bytes.
     */
    public val uStride: Int get() = (width + 1) / 2

    /**
     * The V-plane row stride, in bytes.
     */
    public val vStride: Int get() = uStride

    /**
     * The I420 pixel data owned by this remote frame.
     */
    public val yData: ByteBuffer get() = ByteBuffer.wrap(y).asReadOnlyBuffer()

    /**
     * The read-only U chroma plane.
     */
    public val uData: ByteBuffer get() = ByteBuffer.wrap(u).asReadOnlyBuffer()

    /**
     * The read-only V chroma plane.
     */
    public val vData: ByteBuffer get() = ByteBuffer.wrap(v).asReadOnlyBuffer()
}
