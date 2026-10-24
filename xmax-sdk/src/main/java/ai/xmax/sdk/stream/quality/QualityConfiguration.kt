//
//  QualityConfiguration.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeVideoFormat

/**
 * The fixed media baseline and successfully applied directional quality levels for a connection.
 */
internal data class QualityConfiguration(
    /**
     * The resolved local media format, unchanged by transmission adjustments.
     */
    val baseline: RealtimeVideoFormat,

    /**
     * The uplink quality level, or null for server-read video without local publication.
     */
    val uplinkLevel: Int?,

    /**
     * The downlink quality level.
     */
    val downlinkLevel: Int,

    /**
     * The minimum uplink pixel area.
     */
    val minimumInputPixels: Int,
) {

    /**
     * The uplink encoder format, or null when no local video is published.
     */
    val uplink: RealtimeVideoFormat?
        get() = uplinkLevel?.let {
            QualityVideoFormat.resolve(
                baseline,
                it,
                downlink = false,
                minimumInputPixels = minimumInputPixels,
            )
        }
}

/**
 * Proposed directional quality adjustments; null leaves that direction unchanged.
 */
internal data class QualityChange(
    /**
     * The proposed uplink adjustment.
     */
    val uplink: QualityDirection.Change?,

    /**
     * The proposed downlink adjustment.
     */
    val downlink: QualityDirection.Change?,
)
