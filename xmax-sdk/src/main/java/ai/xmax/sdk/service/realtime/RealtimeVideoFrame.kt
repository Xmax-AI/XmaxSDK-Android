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
 *
 * @property width The frame width, in pixels.
 * @property height The frame height, in pixels.
 * @property presentationTimeUs The presentation timestamp on the shared remote audio/video timeline, in microseconds.
 * @property durationUs The frame duration in microseconds, or null if RTC does not provide it.
 * @property rotationDegrees The clockwise rotation still required for display: 0, 90, 180, or 270 degrees.
 * @property y The Y pixel plane.
 * @property u The U pixel plane.
 * @property v The V pixel plane.
 */
public class RealtimeVideoFrame internal constructor(
    public val width: Int,
    public val height: Int,
    public val presentationTimeUs: Long,
    public val durationUs: Long?,
    public val rotationDegrees: Int,
    private val y: ByteArray,
    private val u: ByteArray,
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
