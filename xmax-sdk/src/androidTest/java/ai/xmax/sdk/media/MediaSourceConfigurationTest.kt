//
//  MediaSourceConfigurationTest.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxRealtimeVideoView
import ai.xmax.sdk.foundation.media.MediaFileMetadata
import ai.xmax.sdk.foundation.media.MediaFileMetadataManaging
import ai.xmax.sdk.media.video.VideoPlayerControlling
import ai.xmax.sdk.service.media.MediaService
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSourceConfigurationTest {

    @Test
    fun playerReceivesOriginalMetadataAndResolvedOutputFormat() = runBlocking {
        for (rotation in VideoRotation.entries) {
            val metadata = MediaFileMetadata(
                width = 3840,
                height = 2160,
                rotation = rotation,
                durationUs = 3_456_789,
                hasAudio = true,
            )
            val player = PlayerStub()
            val source = source(metadata, player)
            val uri = Uri.parse("content://xmax.test/video")
            val prepared = source.prepare(uri)
            val expectedFormat = when (rotation) {
                VideoRotation.ROTATION_90, VideoRotation.ROTATION_270 -> RealtimeVideoFormat(1080, 1920, 30)
                else -> RealtimeVideoFormat(1920, 1080, 30)
            }

            assertSame(uri, player.uri)
            assertSame(metadata, player.metadata)
            assertEquals(expectedFormat, player.videoFormat)
            assertEquals(expectedFormat, prepared.videoFormat)
            assertTrue(source.hasAudio)

            source.start()
            assertEquals(1, player.starts)
            source.stop()
            assertEquals(1, player.stops)
            assertFalse(source.hasAudio)
        }
    }

    @Test
    fun failedPlayerConfigurationCanBeRetriedWithoutChangingMetadata() = runBlocking {
        val metadata = MediaFileMetadata(
            width = 1920,
            height = 1080,
            rotation = VideoRotation.ROTATION_0,
            durationUs = 1_000_000,
            hasAudio = false,
        )
        val player = PlayerStub().apply { failConfiguration = true }
        val source = source(metadata, player)
        val uri = Uri.parse("content://xmax.test/video")
        val failure = runCatching { source.prepare(uri) }.exceptionOrNull()
        assertEquals(XmaxErrorCode.MEDIA_ERROR, (failure as? XmaxError)?.code)
        assertFalse(source.hasAudio)

        player.failConfiguration = false
        val prepared = source.prepare(uri)
        assertSame(metadata, player.metadata)
        assertEquals(RealtimeVideoFormat(1920, 1080, 30), prepared.videoFormat)
        assertFalse(source.hasAudio)
        source.stop()
    }

    private fun source(
        metadata: MediaFileMetadata,
        player: VideoPlayerControlling,
    ) = MediaSourceController(
        metadataManager = object : MediaFileMetadataManaging {
            override suspend fun readMetadata(uri: Uri) = metadata
        },
        mediaService = MediaService(),
        playerController = player,
    )

    private class PlayerStub : VideoPlayerControlling {
        var uri: Uri? = null
        var metadata: MediaFileMetadata? = null
        var videoFormat: RealtimeVideoFormat? = null
        var failConfiguration = false
        var starts = 0
        var stops = 0

        override suspend fun configure(
            uri: Uri,
            metadata: MediaFileMetadata,
            videoFormat: RealtimeVideoFormat,
        ) {
            if (failConfiguration) throw XmaxError(XmaxErrorCode.MEDIA_ERROR, "Configuration rejected")
            this.uri = uri
            this.metadata = metadata
            this.videoFormat = videoFormat
        }

        override suspend fun start() { starts++ }
        override suspend fun stop() { stops++ }
        override suspend fun setLocalAudioPreviewMuted(muted: Boolean) = Unit
        override suspend fun setLocalAudioVolume(volume: Float) = Unit
        override fun attachPreview(view: XmaxRealtimeVideoView, contentMode: VideoContentMode) = Unit
        override fun detachPreview(view: XmaxRealtimeVideoView) = Unit
    }
}
