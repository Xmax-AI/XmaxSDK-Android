//
//  QualityControllerTest.kt
//  XmaxSDK
//  Repo: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.UplinkVideoStatistics
import ai.xmax.sdk.DownlinkVideoStatistics
import ai.xmax.sdk.RealtimeNetworkQualityLevel
import ai.xmax.sdk.RealtimePerformanceAlarm
import ai.xmax.sdk.RealtimePerformanceStatus
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.foundation.rtc.RtcQualityLevel
import ai.xmax.sdk.stream.room.RtcManagingStub
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
public class QualityControllerTest {

    @Test
    public fun `enabled adaptation starts both directions at L2 without history`() = runTest {
        val controller = QualityController(
            RtcManagingStub(),
            callbackScope = this,
            now = { testScheduler.currentTime },
        )
        val baseline = RealtimeVideoFormat(1024, 1920, 30)
        val initial = controller.prepareQuality(
            baseline,
            isAdaptiveQualityEnabled = true,
        )

        assertEquals(2, initial.uplinkLevel)
        assertEquals(2, initial.downlinkLevel)
        assertEquals(RealtimeVideoFormat(896, 1680, 24), initial.uplink)
        assertEquals(baseline, initial.baseline)
    }

    @Test
    public fun `expired history restarts both directions at L2`() = runTest {
        var time = 1_000L
        val history = QualityHistory(now = { time })
        history.write(downlink = false, level = 1)
        history.write(downlink = true, level = 4)
        val controller = QualityController(
            RtcManagingStub(),
            callbackScope = this,
            history = history,
            now = { testScheduler.currentTime },
        )
        val baseline = RealtimeVideoFormat(1024, 1920, 30)
        val initial = controller.prepareQuality(baseline)
        assertEquals(1, initial.uplinkLevel)
        assertEquals(4, initial.downlinkLevel)

        controller.resetQuality()
        time += 3_600_000L
        val reconnected = controller.prepareQuality(baseline)
        assertEquals(2, reconnected.uplinkLevel)
        assertEquals(2, reconnected.downlinkLevel)
    }

    @Test
    public fun `disabled adaptation starts at L1 and keeps manual changes out of history`() = runTest {
        var reads = 0
        var writes = 0
        val history = QualityHistory(
            readValue = { direction ->
                reads++
                QualityHistory.Entry(if (direction == "uplink") 2 else 4, 1_000L)
            },
            writeValue = { _, _ -> writes++ },
            now = { 1_000L },
        )
        val controller = QualityController(
            RtcManagingStub(),
            callbackScope = this,
            history = history,
            now = { testScheduler.currentTime },
        )
        val baseline = RealtimeVideoFormat(1024, 1920, 30)
        val initial = controller.prepareQuality(baseline, isAdaptiveQualityEnabled = false)
        assertEquals(1, initial.uplinkLevel)
        assertEquals(1, initial.downlinkLevel)
        controller.completeInitialQuality(downlink = false)
        controller.completeInitialQuality(downlink = true)

        controller.completeQuality(downlink = false, level = 3, success = true)
        controller.completeQuality(downlink = true, level = 5, success = true)
        assertEquals(3, controller.qualityConfiguration?.uplinkLevel)
        assertEquals(5, controller.qualityConfiguration?.downlinkLevel)
        repeat(10) {
            assertEquals(QualityChange(null, null), controller.proposeQuality(
                RealtimeNetworkQuality(RealtimeNetworkQualityLevel.BAD, RealtimeNetworkQualityLevel.BAD),
                testScheduler.currentTime,
            ))
            testScheduler.advanceTimeBy(1_000)
        }
        assertEquals(0, reads)
        assertEquals(0, writes)

        controller.resetQuality()
        val reconnected = controller.prepareQuality(baseline, isAdaptiveQualityEnabled = false)
        assertEquals(1, reconnected.uplinkLevel)
        assertEquals(1, reconnected.downlinkLevel)
        assertEquals(0, reads)
        assertEquals(0, writes)

        controller.resetQuality()
        val automatic = controller.prepareQuality(baseline, isAdaptiveQualityEnabled = true)
        assertEquals(2, automatic.uplinkLevel)
        assertEquals(4, automatic.downlinkLevel)
        assertEquals(2, reads)
        assertEquals(0, writes)
    }

    @Test
    public fun `disabled adaptation preserves public quality performance and video statistics`() = runTest {
        val rtc = RtcManagingStub()
        val controller = QualityController(rtc, callbackScope = this)
        val network = mutableListOf<RealtimeNetworkQuality>()
        val performance = mutableListOf<RealtimePerformanceAlarm>()
        val uplink = mutableListOf<UplinkVideoStatistics?>()
        val downlink = mutableListOf<DownlinkVideoStatistics?>()
        controller.prepareQuality(RealtimeVideoFormat(1024, 1920, 30), isAdaptiveQualityEnabled = false)
        controller.setNetworkQualityListener(network::add)
        controller.setPerformanceAlarmListener(performance::add)
        controller.setUplinkVideoStatisticsListener(uplink::add)
        controller.setDownlinkVideoStatisticsListener(downlink::add)
        controller.setUplinkVideoStatisticsEnabled(true)
        controller.setDownlinkVideoStatisticsUser("bot")

        rtc.emitNetworkQuality(RtcQualityLevel.BAD, RtcQualityLevel.GOOD)
        rtc.emitPerformanceAlarm(true, 540, 960, 15)
        val sent = UplinkVideoStatistics(width = 1024, height = 1920)
        val received = DownlinkVideoStatistics(userId = "bot", width = 1024, height = 1920)
        controller.onUplinkVideoStatistics(sent)
        controller.onDownlinkVideoStatistics(received)
        runCurrent()

        assertEquals(listOf(RealtimeNetworkQuality(RealtimeNetworkQualityLevel.BAD, RealtimeNetworkQualityLevel.GOOD)), network)
        assertEquals(RealtimePerformanceStatus.LIMITED, performance.single().status)
        assertEquals(sent, uplink.last())
        assertEquals(received, downlink.last())
    }

    @Test
    public fun `quality reset discards queued callbacks while retaining public registrations`() = runTest {
        val rtc = RtcManagingStub()
        val controller = QualityController(rtc, callbackScope = this)
        var networkCount = 0
        var performanceCount = 0
        controller.setNetworkQualityListener { networkCount++ }
        controller.setPerformanceAlarmListener { performanceCount++ }
        rtc.emitNetworkQuality(RtcQualityLevel.BAD, RtcQualityLevel.BAD)
        rtc.emitPerformanceAlarm(true, 540, 960, 15)
        controller.resetQuality()
        runCurrent()
        assertEquals(0, networkCount)
        assertEquals(0, performanceCount)

        rtc.emitNetworkQuality(RtcQualityLevel.GOOD, RtcQualityLevel.GOOD)
        rtc.emitPerformanceAlarm(false, 0, 0, 0)
        runCurrent()
        assertEquals(1, networkCount)
        assertEquals(1, performanceCount)
    }

    @Test
    public fun `quality policies restore history and commit only successful adjustments`() = runTest {
        val history = QualityHistory(now = { 1_000L })
        history.write(downlink = false, level = 2)
        history.write(downlink = true, level = 4)
        val controller = QualityController(
            RtcManagingStub(),
            callbackScope = this,
            history = history,
            now = { testScheduler.currentTime },
        )
        val baseline = RealtimeVideoFormat(1024, 1920, 30)
        val initial = controller.prepareQuality(baseline)
        assertEquals(2, initial.uplinkLevel)
        assertEquals(4, initial.downlinkLevel)
        assertEquals(baseline, initial.baseline)
        controller.completeInitialQuality(downlink = false)
        controller.completeInitialQuality(downlink = true)

        val poor = RealtimeNetworkQuality(
            RealtimeNetworkQualityLevel.BAD,
            RealtimeNetworkQualityLevel.BAD,
        )
        repeat(2) {
            assertEquals(QualityChange(null, null), controller.proposeQuality(poor, testScheduler.currentTime))
            testScheduler.advanceTimeBy(1_000)
        }
        val proposed = controller.proposeQuality(poor, testScheduler.currentTime)
        assertEquals(3, proposed.uplink?.to)
        assertEquals(5, proposed.downlink?.to)
        assertEquals(initial, controller.qualityConfiguration)

        controller.completeQuality(downlink = false, level = 3, success = false)
        controller.completeQuality(downlink = true, level = 5, success = true)
        assertEquals(2, controller.qualityConfiguration?.uplinkLevel)
        assertEquals(5, controller.qualityConfiguration?.downlinkLevel)
        assertEquals(2, history.read(downlink = false))
        assertEquals(5, history.read(downlink = true))
    }

    @Test
    public fun `preparing unchanged media retains uplink stabilization until disconnection`() = runTest {
        val controller = QualityController(
            RtcManagingStub(),
            callbackScope = this,
            history = QualityHistory(now = { 1_000L }),
            now = { testScheduler.currentTime },
        )
        val baseline = RealtimeVideoFormat(1024, 1920, 30)
        val poor = RealtimeNetworkQuality(
            RealtimeNetworkQualityLevel.BAD,
            RealtimeNetworkQualityLevel.BAD,
        )
        controller.prepareQuality(baseline)
        controller.completeQuality(downlink = false, level = 2, success = true)
        controller.prepareQuality(baseline)
        repeat(4) {
            assertNull(controller.proposeQuality(poor, testScheduler.currentTime).uplink)
            testScheduler.advanceTimeBy(1_000)
        }
        controller.proposeQuality(poor, testScheduler.currentTime)
        testScheduler.advanceTimeBy(1_000)
        controller.proposeQuality(poor, testScheduler.currentTime)
        testScheduler.advanceTimeBy(1_000)
        assertEquals(3, controller.proposeQuality(poor, testScheduler.currentTime).uplink?.to)

        controller.resetQuality()
        controller.prepareQuality(baseline)
        repeat(2) {
            assertNull(controller.proposeQuality(poor, testScheduler.currentTime).uplink)
            testScheduler.advanceTimeBy(1_000)
        }
        assertEquals(3, controller.proposeQuality(poor, testScheduler.currentTime).uplink?.to)
    }

    @Test
    public fun `quality reset retains history and public listeners for the next connection`() = runTest {
        val rtc = RtcManagingStub()
        val history = QualityHistory(now = { 1_000L })
        val controller = QualityController(rtc, callbackScope = this, history = history)
        val received = mutableListOf<RealtimeNetworkQuality>()
        controller.setNetworkQualityListener(received::add)
        controller.prepareQuality(RealtimeVideoFormat(1024, 1920, 30))
        controller.completeQuality(downlink = false, level = 3, success = true)
        controller.resetQuality()
        assertNull(controller.qualityConfiguration)
        assertEquals(QualityChange(null, null), controller.proposeQuality(
            RealtimeNetworkQuality(RealtimeNetworkQualityLevel.BAD, RealtimeNetworkQualityLevel.BAD),
            0,
        ))

        val restored = controller.prepareQuality(RealtimeVideoFormat(1024, 1920, 30))
        assertEquals(3, restored.uplinkLevel)
        rtc.emitNetworkQuality(RtcQualityLevel.GOOD, RtcQualityLevel.GOOD)
        runCurrent()
        assertEquals(1, received.size)
    }

    @Test
    public fun `server read video prepares only a downlink quality policy`() = runTest {
        val controller = QualityController(
            RtcManagingStub(),
            callbackScope = this,
            now = { testScheduler.currentTime },
        )
        val prepared = controller.prepareQuality(
            RealtimeVideoFormat(1920, 1080, 30),
            publishLocalMedia = false,
        )
        assertNull(prepared.uplinkLevel)
        assertNull(prepared.uplink)
        val poor = RealtimeNetworkQuality(
            RealtimeNetworkQualityLevel.BAD,
            RealtimeNetworkQualityLevel.BAD,
        )
        controller.proposeQuality(poor, 0)
        testScheduler.advanceTimeBy(1_000)
        controller.proposeQuality(poor, 1_000)
        testScheduler.advanceTimeBy(1_000)
        val proposed = controller.proposeQuality(poor, 2_000)
        assertNull(proposed.uplink)
        assertEquals(3, proposed.downlink?.to)
    }

    @Test
    public fun `internal adaptation samples survive removal of public quality listener`() = runTest {
        val rtc = RtcManagingStub()
        val controller = QualityController(rtc, callbackScope = this)
        val internal = mutableListOf<RealtimeNetworkQuality>()
        val external = mutableListOf<RealtimeNetworkQuality>()
        controller.setAdaptiveQualityListener(internal::add)
        controller.setNetworkQualityListener(external::add)
        rtc.emitNetworkQuality(RtcQualityLevel.BAD, RtcQualityLevel.GOOD)
        runCurrent()
        controller.setNetworkQualityListener(null)
        rtc.emitNetworkQuality(RtcQualityLevel.VERY_BAD, RtcQualityLevel.EXCELLENT)
        runCurrent()
        assertEquals(2, internal.size)
        assertEquals(1, external.size)
        controller.setAdaptiveQualityListener(null)
        rtc.emitNetworkQuality(RtcQualityLevel.BAD, RtcQualityLevel.BAD)
        runCurrent()
        assertEquals(2, internal.size)
    }

    @Test
    public fun `statistics are delivered only for published local video and selected remote user`() = runTest {
        val controller = QualityController(RtcManagingStub(), callbackScope = this)
        val local = mutableListOf<UplinkVideoStatistics?>()
        val remote = mutableListOf<DownlinkVideoStatistics?>()
        controller.setUplinkVideoStatisticsListener(local::add)
        controller.setDownlinkVideoStatisticsListener(remote::add)
        controller.onUplinkVideoStatistics(UplinkVideoStatistics(bitrateKbps = 999))
        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("other"))
        runCurrent()
        assertEquals(0, local.size)
        assertEquals(0, remote.size)

        controller.setUplinkVideoStatisticsEnabled(true)
        controller.setDownlinkVideoStatisticsUser("bot")
        controller.onUplinkVideoStatistics(UplinkVideoStatistics(bitrateKbps = 1234))
        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("other"))
        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("bot", renderFrameRate = 24))
        runCurrent()
        assertEquals(listOf(null, UplinkVideoStatistics(bitrateKbps = 1234)), local)
        assertEquals(listOf(null, DownlinkVideoStatistics("bot", renderFrameRate = 24)), remote)
    }

    @Test
    public fun `disconnect and remote replacement invalidate queued statistics`() = runTest {
        val controller = QualityController(RtcManagingStub(), callbackScope = this)
        val local = mutableListOf<UplinkVideoStatistics?>()
        val remote = mutableListOf<DownlinkVideoStatistics?>()
        controller.setUplinkVideoStatisticsListener(local::add)
        controller.setDownlinkVideoStatisticsListener(remote::add)
        controller.setUplinkVideoStatisticsEnabled(true)
        controller.setDownlinkVideoStatisticsUser("old")
        controller.onUplinkVideoStatistics(UplinkVideoStatistics(bitrateKbps = 1234))
        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("old"))
        controller.setUplinkVideoStatisticsEnabled(false)
        controller.setDownlinkVideoStatisticsUser("new")
        controller.onUplinkVideoStatistics(UplinkVideoStatistics(bitrateKbps = 999))
        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("old"))
        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("new", renderFrameRate = 30))
        runCurrent()
        assertEquals(listOf<UplinkVideoStatistics?>(null), local)
        assertEquals(listOf(null, DownlinkVideoStatistics("new", renderFrameRate = 30)), remote)

        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("new", renderFrameRate = 15))
        controller.setDownlinkVideoStatisticsListener(null)
        runCurrent()
        assertEquals(2, remote.size)
    }

    @Test
    public fun `network quality maps every RTC level`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = QualityController(rtcManager, callbackScope = this)
        val received = mutableListOf<RealtimeNetworkQuality>()
        controller.setNetworkQualityListener(received::add)

        RtcQualityLevel.entries.forEach { level ->
            rtcManager.emitNetworkQuality(level, level)
        }
        runCurrent()

        assertEquals(
            RealtimeNetworkQualityLevel.entries,
            received.map(RealtimeNetworkQuality::uplink),
        )
        assertEquals(
            received.map(RealtimeNetworkQuality::uplink),
            received.map(RealtimeNetworkQuality::downlink),
        )
    }

    @Test
    public fun `performance alarm maps limited state and suggested format`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = QualityController(rtcManager, callbackScope = this)
        var received: RealtimePerformanceAlarm? = null
        controller.setPerformanceAlarmListener { received = it }

        rtcManager.emitPerformanceAlarm(
            limited = true,
            suggestedWidth = 540,
            suggestedHeight = 960,
            suggestedFrameRate = 15,
        )
        runCurrent()

        assertEquals(
            RealtimePerformanceAlarm(
                status = RealtimePerformanceStatus.LIMITED,
                suggestedVideoFormat = RealtimeVideoFormat(540, 960, 15),
            ),
            received,
        )
    }

    @Test
    public fun `performance recovery rejects invalid suggestion`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = QualityController(rtcManager, callbackScope = this)
        var received: RealtimePerformanceAlarm? = null
        controller.setPerformanceAlarmListener { received = it }

        rtcManager.emitPerformanceAlarm(
            limited = false,
            suggestedWidth = 0,
            suggestedHeight = 960,
            suggestedFrameRate = 15,
        )
        runCurrent()

        assertEquals(RealtimePerformanceStatus.RECOVERED, received?.status)
        assertNull(received?.suggestedVideoFormat)
    }

    @Test
    public fun `cleared listeners ignore later events`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = QualityController(rtcManager, callbackScope = this)
        var networkCallbacks = 0
        var performanceCallbacks = 0
        controller.setNetworkQualityListener { networkCallbacks += 1 }
        controller.setPerformanceAlarmListener { performanceCallbacks += 1 }

        rtcManager.emitNetworkQuality(RtcQualityLevel.GOOD, RtcQualityLevel.POOR)
        rtcManager.emitPerformanceAlarm(true, 540, 960, 15)
        controller.setNetworkQualityListener(null)
        controller.setPerformanceAlarmListener(null)
        runCurrent()

        assertEquals(0, networkCallbacks)
        assertEquals(0, performanceCallbacks)
    }
}
