//
//  RealtimeModel.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * SDK 支持的实时生成模型；[id] 是创建服务端会话时使用的模型标识。
 */
public enum class RealtimeModel(
    /**
     * 请求服务端时使用的模型标识。
     */
    public val id: String
) {

    /**
     * X2.1 实时生成模型。
     */
    X2_1("x2.1"),

    ;

    /**
     * 界面展示名称，与创建会话时使用的 [id] 分开维护。
     */
    public val displayName: String
        get() = when (this) {
            X2_1 -> "X2.1"
        }

    /**
     * 模型支持的视频规格；摄像头宽高须精确匹配，帧率为推荐默认值。
     * 本地图片和视频保留媒体输入比例，SDK 从桶中选择比例最接近的画布，由服务端等比适配。
     * 空列表表示不限制固定尺寸，生成画布按像素面积上下限和模型对齐规则计算。
     */
    public val supportedVideoFormats: List<RealtimeVideoFormat>
        get() = listOf(
            RealtimeVideoFormat(width = 1_920, height = 1_080, fps = defaultFrameRate),
            RealtimeVideoFormat(width = 1_080, height = 1_920, fps = defaultFrameRate),
            RealtimeVideoFormat(width = 1_408, height = 1_408, fps = defaultFrameRate),
            RealtimeVideoFormat(width = 1_152, height = 1_536, fps = defaultFrameRate),
            RealtimeVideoFormat(width = 1_536, height = 1_152, fps = defaultFrameRate),
        )

    /**
     * 生成画布的最小总像素面积；仅在分辨率桶为空时参与计算，不放大小尺寸媒体帧。
     */
    public val minimumInputPixels: Int
        get() = 600_000

    /**
     * 本地媒体帧的最大总像素面积；分辨率桶为空时也用于限制生成画布。
     */
    public val maximumInputPixels: Int
        get() = 2_100_000

    /**
     * 输入宽度和高度分别需要对齐的像素倍数；仅在分辨率桶为空时参与尺寸计算。
     */
    public val inputSizeAlignment: Int
        get() = 32

    /**
     * 默认媒体输入规格。
     *
     * 未指定视频规格时使用的默认帧率。
     */
    public val defaultFrameRate: Int
        get() = 30

    /**
     * 摄像头采集使用的默认视频规格。
     */
    public val defaultCameraVideoFormat: RealtimeVideoFormat
        get() = RealtimeVideoFormat(
            width = 1_080,
            height = 1_920,
            fps = defaultFrameRate,
        )
}

/**
 * 创建实时 Manager 所需的业务配置。
 */
public data class RealtimeConfiguration(
    /**
     * 新建实时会话使用的模型；服务区域由客户端环境决定。
     */
    public val model: RealtimeModel = RealtimeModel.X2_1,
)
