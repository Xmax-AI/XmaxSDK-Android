//
//  CameraDevice.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * A camera reported by Android's camera service.
 *
 * @property id The Camera2 device identifier.
 * @property name A generic display name based on the camera's reported facing.
 * @property position The front or rear position, or null when the camera has no such position.
 * @property isExternal Whether Android reports this camera as external.
 */
public data class CameraDevice internal constructor(
    public val id: String,
    public val name: String,
    public val position: CameraPosition?,
    public val isExternal: Boolean,
)
