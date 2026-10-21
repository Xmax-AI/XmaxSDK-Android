//
//  QualityPolicy.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.quality

/**
 * 自动调档的连续样本阈值和时间窗口，时间单位为毫秒。
 */
internal data class QualityPolicy(
    /**
     * 触发降档所需的连续差网络样本数；默认值为 `3`。
     */
    val poorSamples: Int = 3,

    /**
     * 触发升档所需的连续好网络样本数；默认值为 `5`。
     */
    val goodSamples: Int = 5,

    /**
     * 调档后的稳定等待时间，单位为毫秒；默认值为 `4_000`。
     */
    val stabilizationMs: Long = 4_000,

    /**
     * 升档后的探测时间，单位为毫秒；默认值为 `20_000`。
     */
    val probeMs: Long = 20_000,

    /**
     * 再次尝试升档前的冷却时间，单位为毫秒；默认值为 `60_000`。
     */
    val upgradeCooldownMs: Long = 60_000,

    /**
     * 网络样本的最大有效间隔，单位为毫秒；默认值为 `5_000`。
     */
    val sampleTimeoutMs: Long = 5_000,
)
