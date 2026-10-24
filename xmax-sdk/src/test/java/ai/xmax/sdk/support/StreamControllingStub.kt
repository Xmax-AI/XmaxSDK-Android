//
//  StreamControllingStub.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.stream.StreamControlling
import ai.xmax.sdk.stream.quality.QualityChange
import ai.xmax.sdk.stream.quality.QualityConfiguration
import ai.xmax.sdk.stream.quality.QualityController
import ai.xmax.sdk.stream.quality.QualityHistory
import ai.xmax.sdk.stream.room.RtcManagingStub

/**
 * Supplies real quality policies for transport fakes without requiring an RTC engine.
 */
internal abstract class StreamControllingStub(
    history: QualityHistory = QualityHistory(),
    now: () -> Long = { System.nanoTime() / 1_000_000 },
) : StreamControlling {
    override val remoteAudioVolume: Float get() = 1f
    private val quality = QualityController(RtcManagingStub(), history = history, now = now)

    override val qualityConfiguration: QualityConfiguration? get() = quality.qualityConfiguration
    override fun prepareQuality(baseline: RealtimeVideoFormat, minimumInputPixels: Int, publishLocalMedia: Boolean) =
        quality.prepareQuality(baseline, minimumInputPixels, publishLocalMedia)
    override fun proposeQuality(quality: RealtimeNetworkQuality, sampledAt: Long): QualityChange =
        this.quality.proposeQuality(quality, sampledAt)
    override fun completeQuality(downlink: Boolean, level: Int, success: Boolean) =
        quality.completeQuality(downlink, level, success)
    override fun completeInitialQuality(downlink: Boolean) = quality.completeInitialQuality(downlink)
    override fun resetQualitySamples() = quality.resetQualitySamples()
    override fun resetQuality() = quality.resetQuality()
}
