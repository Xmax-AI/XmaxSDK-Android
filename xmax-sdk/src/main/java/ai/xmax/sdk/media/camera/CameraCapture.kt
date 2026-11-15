//
//  CameraCapture.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.camera

import ai.xmax.sdk.CameraDevice
import ai.xmax.sdk.RealtimeCamera
import ai.xmax.sdk.RealtimeCameraRotation
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxError

/**
 * Owns one Android camera and delivers SDK video frames independently of RTC.
 */
internal interface CameraCapture {

    /**
     * Opens the camera and waits for a valid frame. An existing capture is replaced.
     *
     * @param format The desired output dimensions and frame rate.
     * @param selection The camera position or explicit device identifier.
     * @param rotation The clockwise correction relative to automatic orientation.
     * @param onFrame Receives an independently owned video frame on the capture thread.
     * @param onError Receives a capture failure after startup completes.
     * @return The camera that produced the first frame.
     */
    suspend fun start(
        format: RealtimeVideoFormat,
        selection: RealtimeCamera,
        rotation: RealtimeCameraRotation,
        onFrame: (VideoFrame) -> Unit,
        onError: (XmaxError) -> Unit,
    ): CameraDevice

    /**
     * Applies a correction before converting the next frame.
     *
     * @param rotation The clockwise correction relative to automatic orientation.
     * @param onApplied Runs on the frame thread after the transform changes, before delivering more frames.
     */
    suspend fun setRotation(
        rotation: RealtimeCameraRotation,
        onApplied: () -> Unit,
    )

    /**
     * Releases the current capture and waits for the camera to close.
     */
    suspend fun stop()
}
