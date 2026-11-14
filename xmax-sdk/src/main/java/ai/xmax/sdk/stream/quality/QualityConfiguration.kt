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
 * The media baseline and directional quality levels for a connection.
 *
 * @property baseline The resolved local media format, unchanged by transmission adjustments.
 * @property uplinkLevel The uplink quality level, or null for server-read video without local publication.
 * @property downlinkLevel The downlink quality level.
 * @property minimumInputPixels The minimum uplink pixel area.
 */
internal data class QualityConfiguration(
    val baseline: RealtimeVideoFormat,
    val uplinkLevel: Int?,
    val downlinkLevel: Int,
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
 * The directional quality proposals for one sample; null leaves a direction unchanged.
 *
 * @property uplink The proposed uplink adjustment.
 * @property downlink The proposed downlink adjustment.
 */
internal data class QualityChange(
    val uplink: QualityDirection.Change?,
    val downlink: QualityDirection.Change?,
)
