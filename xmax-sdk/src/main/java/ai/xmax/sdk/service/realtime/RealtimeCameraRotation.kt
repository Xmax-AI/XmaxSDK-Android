//
//  RealtimeCameraRotation.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * A clockwise correction relative to the camera's automatically oriented output.
 *
 * @property degrees The correction angle in degrees.
 */
public enum class RealtimeCameraRotation(
    public val degrees: Int,
) {

    /**
     * Uses the automatically determined orientation.
     */
    ROTATION_0(0),

    /**
     * Rotates the automatically oriented picture 90 degrees clockwise.
     */
    ROTATION_90(90),

    /**
     * Rotates the automatically oriented picture 180 degrees.
     */
    ROTATION_180(180),

    /**
     * Rotates the automatically oriented picture 270 degrees clockwise.
     */
    ROTATION_270(270),
}

/**
 * Resolves the sensor-frame transform. Mirroring after rotation reverses the apparent direction.
 *
 * @param orientation The automatically determined rotation of the sensor frame.
 * @param mirrored Whether the final image is mirrored horizontally.
 * @return The rotation to apply before mirroring.
 */
internal fun RealtimeCameraRotation.resolveFrameRotation(
    orientation: VideoRotation,
    mirrored: Boolean,
): VideoRotation {
    val correction = if (mirrored) -degrees else degrees
    val angle = (orientation.degrees + correction + 360) % 360
    return VideoRotation.entries[angle / 90]
}
