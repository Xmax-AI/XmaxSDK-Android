//
//  InteractionFrame.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.interaction

import ai.xmax.sdk.VideoContentMode
import android.graphics.PointF
import android.util.SizeF

/**
 * An interaction sampled from a video viewport.
 *
 * @property points Touch points in viewport coordinates, in pixels.
 * @property viewportSize The preview viewport dimensions, in pixels.
 * @property contentMode Aspect-preserving fit or fill scaling.
 */
internal data class InteractionFrame(
    val points: List<PointF>,
    val viewportSize: SizeF,
    val contentMode: VideoContentMode,
)
