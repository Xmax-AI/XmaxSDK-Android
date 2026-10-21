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
 * 一次从渲染视口采集到的交互输入。
 */
internal data class InteractionFrame(
    /**
     * 渲染视口中的触控点集合，单位为像素。
     */
    val points: List<PointF>,

    /**
     * 预览视图的尺寸，单位为像素。
     */
    val viewportSize: SizeF,

    /**
     * 视频等比适配或等比裁切的显示方式。
     */
    val contentMode: VideoContentMode,
)
