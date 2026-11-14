//
//  NativeYuv420Converter.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.VideoRotation
import java.nio.ByteBuffer

/**
 * Native Android pixel conversion and resizing, with Kotlin fallback when unavailable.
 */
internal object NativeYuv420Converter {

    /**
     * Whether the native converter is available.
     */
    private val isAvailable = runCatching {
        System.loadLibrary("xmax_yuv")
        true
    }.getOrDefault(false)

    /**
     * Resizes tightly packed RGBA pixels with bilinear filtering.
     *
     * @param source The original RGBA pixels.
     * @param sourceWidth The original width, in pixels.
     * @param sourceHeight The original height, in pixels.
     * @param output The independently owned destination storage.
     * @param width The output width, in pixels.
     * @param height The output height, in pixels.
     * @return True on success, false when Kotlin fallback is needed.
     */
    fun resizeRgba(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        output: ByteArray,
        width: Int,
        height: Int,
    ): Boolean = isAvailable && runCatching {
        resizeRgbaNative(
            source,
            sourceWidth,
            sourceHeight,
            output,
            width,
            height,
        )
    }.getOrDefault(false)

    /**
     * Copies and filters RGBA pixels into destination storage.
     *
     * @param source The original RGBA pixels.
     * @param sourceWidth The original width, in pixels.
     * @param sourceHeight The original height, in pixels.
     * @param output The destination RGBA storage.
     * @param width The output width, in pixels.
     * @param height The output height, in pixels.
     * @return True if the arrays contain complete, valid images and resizing succeeds.
     */
    private external fun resizeRgbaNative(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        output: ByteArray,
        width: Int,
        height: Int,
    ): Boolean

    /**
     * Crops, rotates, and scales YUV420 natively, returning false when fallback is needed.
     *
     * @param source The source dimensions, crop region, and pixel planes.
     * @param outputWidth The output width, in pixels.
     * @param outputHeight The output height, in pixels.
     * @param rotation The rotation applied to the source image.
     * @param output The output array containing Y, U, and V consecutively.
     * @param mirrored Whether to mirror the output horizontally after rotation.
     * @return True on conversion success, false if unavailable or failed.
     */
    fun convert(
        source: Yuv420VideoFrameConverter.Source,
        outputWidth: Int,
        outputHeight: Int,
        rotation: VideoRotation,
        output: ByteArray,
        mirrored: Boolean = false,
    ): Boolean {
        if (!isAvailable || source.planes.any { !it.buffer.isDirect }) return false

        val yPlane = source.planes[0]
        val uPlane = source.planes[1]
        val vPlane = source.planes[2]
        return runCatching {
            convertNative(
                yBuffer = yPlane.buffer,
                yOffset = yPlane.baseOffset,
                yRowStride = yPlane.rowStride,
                yPixelStride = yPlane.pixelStride,
                uBuffer = uPlane.buffer,
                uOffset = uPlane.baseOffset,
                uRowStride = uPlane.rowStride,
                uPixelStride = uPlane.pixelStride,
                vBuffer = vPlane.buffer,
                vOffset = vPlane.baseOffset,
                vRowStride = vPlane.rowStride,
                vPixelStride = vPlane.pixelStride,
                sourceWidth = source.width,
                sourceHeight = source.height,
                cropLeft = source.cropLeft,
                cropTop = source.cropTop,
                cropWidth = source.cropWidth,
                cropHeight = source.cropHeight,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                rotationDegrees = rotation.degrees,
                mirrored = mirrored,
                output = output,
            )
        }.getOrDefault(false)
    }

    /**
     * Converts I420 pixels into the caller's ARGB array.
     *
     * @param y The array containing the Y pixel plane.
     * @param yOffset The first byte of the Y plane in its array.
     * @param yStride The Y-plane row stride, in bytes.
     * @param u The array containing the U pixel plane.
     * @param uOffset The first byte of the U plane in its array.
     * @param uStride The U-plane row stride, in bytes.
     * @param v The array containing the V pixel plane.
     * @param vOffset The first byte of the V plane in its array.
     * @param vStride The V-plane row stride, in bytes.
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @param pixels The output ARGB pixel array.
     * @return True on conversion success, false if unavailable or failed.
     */
    fun convertI420ToArgb(
        y: ByteArray,
        yOffset: Int,
        yStride: Int,
        u: ByteArray,
        uOffset: Int,
        uStride: Int,
        v: ByteArray,
        vOffset: Int,
        vStride: Int,
        width: Int,
        height: Int,
        pixels: IntArray,
    ): Boolean = isAvailable && runCatching {
        convertI420ToArgbNative(
            y = y,
            yOffset = yOffset,
            yStride = yStride,
            u = u,
            uOffset = uOffset,
            uStride = uStride,
            v = v,
            vOffset = vOffset,
            vStride = vStride,
            width = width,
            height = height,
            pixels = pixels,
        )
    }.getOrDefault(false)

    /**
     * Converts YUV420 pixels using the crop region, rotation, and output dimensions.
     *
     * @param yBuffer The direct Y luma buffer.
     * @param yOffset The valid Y-plane byte offset.
     * @param yRowStride The Y-plane row stride, in bytes.
     * @param yPixelStride The Y-plane pixel stride, in bytes.
     * @param uBuffer The direct U chroma buffer.
     * @param uOffset The valid U-plane byte offset.
     * @param uRowStride The U-plane row stride, in bytes.
     * @param uPixelStride The U-plane pixel stride, in bytes.
     * @param vBuffer The direct V chroma buffer.
     * @param vOffset The valid V-plane byte offset.
     * @param vRowStride The V-plane row stride, in bytes.
     * @param vPixelStride The V-plane pixel stride, in bytes.
     * @param sourceWidth The source width, in pixels.
     * @param sourceHeight The source height, in pixels.
     * @param cropLeft The left crop edge, in pixels.
     * @param cropTop The top crop edge, in pixels.
     * @param cropWidth The crop width, in pixels.
     * @param cropHeight The crop height, in pixels.
     * @param outputWidth The output width, in pixels.
     * @param outputHeight The output height, in pixels.
     * @param rotationDegrees The clockwise rotation angle, in degrees.
     * @param mirrored Whether to mirror the output horizontally after rotation.
     * @param output The output array containing Y, U, and V consecutively.
     * @return True if conversion succeeds.
     */
    private external fun convertNative(
        yBuffer: ByteBuffer,
        yOffset: Int,
        yRowStride: Int,
        yPixelStride: Int,
        uBuffer: ByteBuffer,
        uOffset: Int,
        uRowStride: Int,
        uPixelStride: Int,
        vBuffer: ByteBuffer,
        vOffset: Int,
        vRowStride: Int,
        vPixelStride: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        rotationDegrees: Int,
        mirrored: Boolean,
        output: ByteArray,
    ): Boolean

    /**
     * Converts I420 pixels into an ARGB array using the native converter.
     *
     * @param y The array containing the Y pixel plane.
     * @param yOffset The first byte of the Y plane in its array.
     * @param yStride The Y-plane row stride, in bytes.
     * @param u The array containing the U pixel plane.
     * @param uOffset The first byte of the U plane in its array.
     * @param uStride The U-plane row stride, in bytes.
     * @param v The array containing the V pixel plane.
     * @param vOffset The first byte of the V plane in its array.
     * @param vStride The V-plane row stride, in bytes.
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @param pixels The output ARGB pixel array.
     * @return True if conversion succeeds.
     */
    private external fun convertI420ToArgbNative(
        y: ByteArray,
        yOffset: Int,
        yStride: Int,
        u: ByteArray,
        uOffset: Int,
        uStride: Int,
        v: ByteArray,
        vOffset: Int,
        vStride: Int,
        width: Int,
        height: Int,
        pixels: IntArray,
    ): Boolean

}
