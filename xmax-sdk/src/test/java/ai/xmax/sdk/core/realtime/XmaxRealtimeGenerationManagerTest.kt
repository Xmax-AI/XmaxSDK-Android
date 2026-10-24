//
//  XmaxRealtimeGenerationManagerTest.kt
//  XmaxSDK
//  Repo: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import ai.xmax.sdk.media.interaction.InteractionControlling
import ai.xmax.sdk.media.interaction.InteractionFrame
import ai.xmax.sdk.stream.StreamControlling
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import kotlinx.coroutines.cancelAndJoin
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class XmaxRealtimeGenerationManagerTest {
    @Test
    fun `generation becomes ready only after confirmation first frame and audio activation`() = runTest {
        val stream = GenerationStreamStub()
        val interaction = GenerationInteractionStub()
        val firstFrame = CompletableDeferred<Unit>()
        val manager = XmaxRealtimeGenerationManager(interaction, stream, RenderControllingStub(firstFrame)) { "task" }
        val start = async { manager.start(RealtimeVideoFormat(1280, 720, 30), RealtimeContext("first")) {} }
        runCurrent()
        assertFalse(start.isCompleted)
        assertFalse(stream.audioActivated)
        stream.confirmation.complete(Unit)
        runCurrent()
        assertEquals("task", interaction.startedTaskId)
        assertFalse(start.isCompleted)
        assertFalse(stream.audioActivated)
        assertNull(manager.cachedContext)
        firstFrame.complete(Unit)
        assertEquals("task", start.await())
        assertTrue(stream.audioActivated)
        assertEquals("first", manager.cachedContext!!.prompt)
    }

    @Test
    fun `cancelling first frame wait stops the task without publishing cached conditions or audio`() = runTest {
        val stream = GenerationStreamStub()
        val firstFrame = CompletableDeferred<Unit>()
        val manager = XmaxRealtimeGenerationManager(GenerationInteractionStub(), stream, RenderControllingStub(firstFrame)) { "task" }
        val start = async { manager.start(RealtimeVideoFormat(1280, 720, 30), RealtimeContext("first")) {} }
        runCurrent()
        stream.confirmation.complete(Unit)
        runCurrent()
        start.cancelAndJoin()
        assertEquals(listOf("task"), stream.stoppedTasks)
        assertNull(manager.cachedContext)
        assertFalse(stream.audioActivated)
    }

    @Test
    fun `start waits for stream confirmation and caches context`() = runTest {
        val stream = GenerationStreamStub()
        val interaction = GenerationInteractionStub()
        val manager = XmaxRealtimeGenerationManager(interaction, stream, RenderControllingStub()) { "task-fixed" }
        val format = RealtimeVideoFormat(704, 1280, 24)
        val context = RealtimeContext("first")

        val start = async { manager.start(format, context) {} }
        runCurrent()
        assertEquals("task-fixed", stream.startedTaskId)
        stream.confirmation.complete(Unit)
        assertEquals("task-fixed", start.await())
        assertEquals("task-fixed", interaction.startedTaskId)

        val restarted = async { manager.start(format, context = null) {} }
        runCurrent()
        stream.confirmation.complete(Unit)
        assertEquals("task-fixed", restarted.await())
    }

    @Test
    fun `first generation requires context`() = runTest {
        val manager = XmaxRealtimeGenerationManager(
            GenerationInteractionStub(),
            GenerationStreamStub(),
            RenderControllingStub(),
        ) { "task-fixed" }

        val error = try {
            manager.start(RealtimeVideoFormat(704, 1280, 24), null) {}
            throw AssertionError("Expected XmaxError")
        } catch (error: XmaxError) {
            error
        }

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
    }

    @Test
    fun `task ID uses compact Base64URL token and Android suffix`() {
        val first = XmaxRealtimeGenerationManager.createTaskId()
        val second = XmaxRealtimeGenerationManager.createTaskId()
        val token = first.removePrefix("task-").removeSuffix("?os=android")

        assertTrue(first.startsWith("task-"))
        assertTrue(first.endsWith("?os=android"))
        assertEquals(38, first.length)
        assertNotEquals(first, second)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{22}")))
    }
}

private class GenerationInteractionStub : InteractionControlling {
    var startedTaskId: String? = null

    override suspend fun startInteraction(taskId: String, videoFormat: RealtimeVideoFormat) {
        startedTaskId = taskId
    }

    override suspend fun stopInteraction() = Unit
    override fun submitInteraction(frame: InteractionFrame) = Unit
}

private class GenerationStreamStub : StreamControllingStub() {
    var startedTaskId: String? = null
    var audioActivated = false
    val stoppedTasks = mutableListOf<String>()
    var confirmation = CompletableDeferred<Unit>()

    override fun setVideoEncoderConfig(videoFormat: RealtimeVideoFormat) = Unit
    override fun setAdaptiveQualityListener(listener: ((RealtimeNetworkQuality) -> Unit)?) = Unit
    override fun setUplinkVideoStatisticsListener(listener: UplinkVideoStatisticsListener?) = Unit
    override fun setDownlinkVideoStatisticsListener(listener: DownlinkVideoStatisticsListener?) = Unit
    override fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) = Unit
    override fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) = Unit
    override fun setRemoteAudioVolume(volume: Float) = Unit
    override suspend fun connect(
        connection: RealtimeSessionConnection,
        includeLocalAudio: Boolean,
        publishLocalMedia: Boolean,
        ensureActive: () -> Unit,
    ) = Unit
    override suspend fun disconnect() = Unit
    override fun pushLocalVideoFrame(frame: VideoFrame) = Unit
    override fun pushLocalAudioFrame(frame: AudioFrame) = Unit
    override suspend fun updateDownlinkVideoResolution(
        taskId: String,
        targetVideoFormat: RealtimeVideoFormat,
        ensureActive: () -> Unit,
    ) { ensureActive() }

    override suspend fun beginGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean,
        targetVideoFormat: RealtimeVideoFormat?,
        paddingContain: Boolean,
    ): Deferred<Unit> {
        startedTaskId = taskId
        if (confirmation.isCompleted) confirmation = CompletableDeferred()
        return confirmation
    }
    override fun activateNetworkVideoCompletion(onFinish: (() -> Unit)?) = Unit
    override fun activateRemoteAudio() { audioActivated = true }
    override suspend fun updateGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean,
        paddingContain: Boolean,
    ) = Unit
    override suspend fun stopGeneration(taskId: String) { stoppedTasks += taskId }
    override suspend fun sendTracks(taskId: String, points: List<RealtimePoint>) = Unit
}
