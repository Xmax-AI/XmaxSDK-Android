//
//  RealtimeQualityLevel.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * 实时传输档位；L1 质量最高，L5 最节省带宽，与网络质量评级无关。
 */
public enum class RealtimeQualityLevel(
    /**
     * 档位编号，范围为 1 到 5。
     */
    public val rawValue: Int,
) {

    /**
     * 保留媒体基准尺寸。
     */
    L1(1),

    /**
     * 目标宽高为媒体基准的 80%。
     */
    L2(2),

    /**
     * 目标宽高为媒体基准的 65%。
     */
    L3(3),

    /**
     * 目标宽高为媒体基准的 50%。
     */
    L4(4),

    /**
     * 与 L4 使用相同尺寸，并进一步降低上行帧率。
     */
    L5(5),
}
