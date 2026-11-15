//
//  RealtimeCamera.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * The camera input used to create a local camera stream.
 */
public sealed interface RealtimeCamera {

    /**
     * Uses the first camera matching the requested position and supporting YUV capture.
     *
     * @property position The front or rear camera position.
     */
    public data class Position(
        public val position: CameraPosition,
    ) : RealtimeCamera

    /**
     * Uses a specific Android camera, including external devices reported by Camera2.
     *
     * @property id The identifier returned by [MediaDevices.listCameraDevices].
     */
    public data class Device(
        public val id: String,
    ) : RealtimeCamera
}
