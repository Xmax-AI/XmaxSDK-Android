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
 * Consecutive sample thresholds and adaptation intervals, in milliseconds.
 */
internal data class QualityPolicy(
    /**
     * The consecutive poor samples required to downgrade; defaults to 3.
     */
    val poorSamples: Int = 3,

    /**
     * The consecutive good samples required to upgrade; defaults to 5.
     */
    val goodSamples: Int = 5,

    /**
     * The post-adjustment stabilization interval in milliseconds; defaults to 4,000.
     */
    val stabilizationMs: Long = 4_000,

    /**
     * The upgrade probe duration in milliseconds; defaults to 20,000.
     */
    val probeMs: Long = 20_000,

    /**
     * The upgrade retry cooldown in milliseconds; defaults to 60,000.
     */
    val upgradeCooldownMs: Long = 60_000,

    /**
     * The maximum valid interval between samples in milliseconds; defaults to 5,000.
     */
    val sampleTimeoutMs: Long = 5_000,
)
