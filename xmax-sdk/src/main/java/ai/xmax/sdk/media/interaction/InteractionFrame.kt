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
 */
internal data class InteractionFrame(
    /**
     * Touch points in viewport coordinates, in pixels.
     */
    val points: List<PointF>,

    /**
     * The preview viewport dimensions, in pixels.
     */
    val viewportSize: SizeF,

    /**
     * Aspect-preserving fit or fill scaling.
     */
    val contentMode: VideoContentMode,
)
