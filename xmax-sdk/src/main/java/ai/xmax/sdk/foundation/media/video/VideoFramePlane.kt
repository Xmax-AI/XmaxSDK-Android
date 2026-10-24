//
//  VideoFramePlane.kt
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
 * A plane of video pixel data.
 * Construction validates byte ranges and row stride, throwing [XmaxError] for invalid configuration.
 *
 * @param data The bytes containing this plane's valid range.
 * @param byteLength The valid byte count, or null to use the remaining bytes after the offset.
 * @param copyData Whether to isolate pixels from later input mutations by copying them; defaults to true.
 */
internal class VideoFramePlane(
    data: ByteArray,

    /**
     * The byte distance between consecutive row starts.
     */
    val stride: Int,

    /**
     * The start offset of valid bytes; defaults to 0.
     */
    val byteOffset: Int = 0,

    byteLength: Int? = null,
    copyData: Boolean = true,
) {

    /**
     * The underlying bytes owned by this plane.
     */
    private val bytes: ByteArray = if (copyData) data.copyOf() else data

    /**
     * A copy of the plane's pixels.
     */
    val data: ByteArray
        get() = bytes.copyOf()

    /**
     * The valid byte count in this plane.
     */
    val byteLength: Int = byteLength ?: data.size - byteOffset

    /**
     * The direct buffer used by the native converter.
     */
    private val directBytes: ByteBuffer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ByteBuffer.allocateDirect(this.byteLength).apply {
            put(
                bytes,
                byteOffset,
                this@VideoFramePlane.byteLength,
            )
            flip()
        }
    }

    init {
        if (
            data.isEmpty() ||
            byteOffset < 0 ||
            byteOffset >= data.size ||
            this.byteLength <= 0 ||
            this.byteLength > data.size - byteOffset
        ) {
            throw invalidRangeError()
        }

        if (stride <= 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Video frame plane stride must be a positive integer",
            )
        }
    }

    /**
     * Returns a buffer restricted to this plane's valid byte range.
     *
     * @return A buffer covering the valid bytes.
     */
    internal fun byteBuffer(): ByteBuffer = directBytes.duplicate().apply {
        position(0)
        limit(byteLength)
    }

    /**
     * Copies the buffer's current valid byte range.
     *
     * @return An array containing the valid bytes.
     */
    internal fun selectedBytes(): ByteArray = bytes.copyOfRange(
        fromIndex = byteOffset,
        toIndex = byteOffset + byteLength,
    )

    /**
     * Returns pixels for synchronous, read-only platform conversion; callers must not modify the array.
     *
     * @return An array containing the valid bytes.
     */
    internal fun selectedBytesView(): ByteArray = if (
        byteOffset == 0 && byteLength == bytes.size
    ) {
        bytes
    } else {
        selectedBytes()
    }

    /**
     * Compares objects by value.
     *
     * @param other The object to compare or combine.
     */
    override fun equals(
        other: Any?,
    ): Boolean =
        this === other ||
            other is VideoFramePlane &&
            stride == other.stride &&
            byteOffset == other.byteOffset &&
            byteLength == other.byteLength &&
            bytes.contentEquals(other.bytes)

    /**
     * Computes a hash consistent with value equality.
     */
    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + stride
        result = 31 * result + byteOffset
        result = 31 * result + byteLength
        return result
    }

    /**
     * The pixel range validation helpers.
     */
    private companion object {

        /**
         * Creates an error for an invalid pixel-plane byte range.
         */
        fun invalidRangeError(): XmaxError = XmaxError(
            code = XmaxErrorCode.INVALID_CONFIGURATION,
            message = "Video frame plane range is invalid",
        )
    }
}
