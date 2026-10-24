//
//  MediaServicing.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Size

/**
 * The sizing rules for local media, model canvases, and downlink output.
 */
public interface MediaServicing {

    /**
     * The realtime model defining these media constraints.
     */
    public val model: RealtimeModel

    /**
     * Resolves media within the maximum pixel area without enlargement, using four-pixel alignment and
     * preserving exact aspect ratio where possible.
     *
     * @param size The requested camera dimensions or the source's orientation-corrected pixel dimensions.
     * @param source The source kind; cameras must match configured resolution buckets.
     * @return Media dimensions satisfying area, aspect-ratio, and four-pixel alignment constraints.
     * @throws XmaxError If dimensions are invalid, a camera misses its buckets, or aligned output is below
     * the model's minimum input area.
     */
    public fun resolveMediaSize(
        size: IntSize,
        source: RealtimeMediaSource,
    ): IntSize

    /**
     * Selects the closest model canvas aspect ratio without changing local media format.
     *
     * @param size The media display dimensions, in pixels.
     * @return The matching model bucket, or an area-limited, aligned canvas when buckets are absent.
     * @throws XmaxError If the media width or height is not positive.
     */
    public fun resolveGenerationSize(
        size: IntSize,
    ): IntSize

    /**
     * Resolves downlink dimensions within the bounds without enlarging media, preserving exact aspect ratio
     * where possible.
     *
     * @param sourceSize The fixed media baseline dimensions, in pixels.
     * @param maximumSize The downlink bounds, which may include fractional pixels.
     * @return Dimensions within the bounds with both sides divisible by four.
     * @throws XmaxError If dimensions are invalid or the bounds cannot fit 4 by 4 pixels.
     */
    public fun resolveTargetSize(
        sourceSize: IntSize,
        maximumSize: Size,
    ): IntSize
}
