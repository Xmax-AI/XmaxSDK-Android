//
//  InteractionCoordinateMapper.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.interaction

import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.VideoContentMode
import android.graphics.PointF
import android.graphics.RectF
import android.util.SizeF
import kotlin.math.max
import kotlin.math.min

/**
 * Maps rendering viewport coordinates to normalized coordinates on the fixed media baseline.
 */
internal object InteractionCoordinateMapper {

    /**
     * Resolves the media region from the processed aspect ratio and scaling mode.
     *
     * @param viewportSize The preview dimensions, in pixels.
     * @param resolvedMediaSize The fixed media dimensions after area limits and alignment.
     * @param contentMode Aspect-preserving fit or fill scaling.
     * @return The displayed video region, or null for invalid dimensions.
     */
    fun displayedFrame(
        viewportSize: SizeF,
        resolvedMediaSize: SizeF,
        contentMode: VideoContentMode,
    ): RectF? {
        if (!viewportSize.width.isFinite() || !viewportSize.height.isFinite() || !resolvedMediaSize.width.isFinite() || !resolvedMediaSize.height.isFinite()) return null

        if (viewportSize.width <= 0f || viewportSize.height <= 0f || resolvedMediaSize.width < 1f || resolvedMediaSize.height < 1f) return null
        val widthScale = viewportSize.width / resolvedMediaSize.width
        val heightScale = viewportSize.height / resolvedMediaSize.height
        val scale = if (contentMode == VideoContentMode.FILL) {
            max(
                widthScale,
                heightScale,
            )
        } else {
            min(
                widthScale,
                heightScale,
            )
        }
        if (!scale.isFinite() || scale <= 0f) return null

        val displayedWidth = resolvedMediaSize.width * scale
        val displayedHeight = resolvedMediaSize.height * scale
        val left = (viewportSize.width - displayedWidth) / 2f
        val top = (viewportSize.height - displayedHeight) / 2f
        return RectF(
            left,
            top,
            left + displayedWidth,
            top + displayedHeight,
        )
    }

    /**
     * Normalizes points using the complete media aspect ratio; FIT letterbox regions do not produce points.
     *
     * @param point The touch location in preview coordinates, in pixels.
     * @param viewportSize The preview dimensions, in pixels.
     * @param resolvedMediaSize The area-limited, aligned media dimensions defining normalized coordinates.
     * @param contentMode Aspect-preserving fit or fill scaling.
     * @return Media coordinates from 0 to 1, or null for invalid input or FIT letterboxing.
     */
    fun map(
        point: PointF,
        viewportSize: SizeF,
        resolvedMediaSize: SizeF,
        contentMode: VideoContentMode,
    ): RealtimePoint? {
        if (!point.x.isFinite() || !point.y.isFinite()) return null

        val displayedFrame = displayedFrame(
            viewportSize,
            resolvedMediaSize,
            contentMode,
        ) ?: return null
        if (contentMode == VideoContentMode.FIT && !displayedFrame.contains(
            point.x,
            point.y,
        )) {
            return null
        }
        val normalizedX = (point.x.toDouble() - displayedFrame.left) / displayedFrame.width()
        val normalizedY = (point.y.toDouble() - displayedFrame.top) / displayedFrame.height()
        return RealtimePoint(
            normalizedX.coerceIn(
                0.0,
                1.0,
            ),
            normalizedY.coerceIn(
                0.0,
                1.0,
            ),
        )
    }
}
