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
 * The native Android YUV converter, with Kotlin fallback when the library is unavailable.
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
     * Crops, rotates, and scales YUV420 natively, returning false when fallback is needed.
     *
     * @param source The source dimensions, crop region, and pixel planes.
     * @param outputWidth The output width, in pixels.
     * @param outputHeight The output height, in pixels.
     * @param rotation The rotation applied to the source image.
     * @param y The Y pixel plane.
     * @param u The U pixel plane.
     * @param v The V pixel plane.
     * @return True on conversion success, false if unavailable or failed.
     */
    fun convert(
        source: Yuv420VideoFrameConverter.Source,
        outputWidth: Int,
        outputHeight: Int,
        rotation: VideoRotation,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
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
                outputY = y,
                outputU = u,
                outputV = v,
            )
        }.getOrDefault(false)
    }

    /**
     * Converts I420 pixels into the caller's ARGB array.
     *
     * @param y The Y pixel plane.
     * @param yStride The Y-plane row stride, in bytes.
     * @param u The U pixel plane.
     * @param uStride The U-plane row stride, in bytes.
     * @param v The V pixel plane.
     * @param vStride The V-plane row stride, in bytes.
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @param pixels The output ARGB pixel array.
     * @return True on conversion success, false if unavailable or failed.
     */
    fun convertI420ToArgb(
        y: ByteArray,
        yStride: Int,
        u: ByteArray,
        uStride: Int,
        v: ByteArray,
        vStride: Int,
        width: Int,
        height: Int,
        pixels: IntArray,
    ): Boolean = isAvailable && runCatching {
        convertI420ToArgbNative(
            y = y,
            yStride = yStride,
            u = u,
            uStride = uStride,
            v = v,
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
     * @param outputY The output Y luma array.
     * @param outputU The output U chroma array.
     * @param outputV The output V chroma array.
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
        outputY: ByteArray,
        outputU: ByteArray,
        outputV: ByteArray,
    ): Boolean

    /**
     * Converts I420 pixels into an ARGB array using the native converter.
     *
     * @param y The Y pixel plane.
     * @param yStride The Y-plane row stride, in bytes.
     * @param u The U pixel plane.
     * @param uStride The U-plane row stride, in bytes.
     * @param v The V pixel plane.
     * @param vStride The V-plane row stride, in bytes.
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @param pixels The output ARGB pixel array.
     * @return True if conversion succeeds.
     */
    private external fun convertI420ToArgbNative(
        y: ByteArray,
        yStride: Int,
        u: ByteArray,
        uStride: Int,
        v: ByteArray,
        vStride: Int,
        width: Int,
        height: Int,
        pixels: IntArray,
    ): Boolean

}
