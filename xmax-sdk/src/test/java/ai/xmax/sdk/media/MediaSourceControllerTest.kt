//
//  MediaSourceControllerTest.kt
//  XmaxSDK
//  Repo: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxVideoView
import ai.xmax.sdk.foundation.media.MediaFileMetadata
import ai.xmax.sdk.foundation.media.MediaFileMetadataManaging
import ai.xmax.sdk.media.video.VideoPlayerControlling
import ai.xmax.sdk.service.media.MediaService
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test

class MediaSourceControllerTest {
    private val service = MediaService()
    private val controller = MediaSourceController(
        object : MediaFileMetadataManaging {
            override suspend fun readMetadata(uri: Uri): MediaFileMetadata = error("unused")
        },
        service,
        PlayerStub,
    )

    @Test
    fun `video derives dimensions from media and respects the model area limit`() {
        for ((width, height) in listOf(1920 to 1080, 1080 to 1920, 1280 to 720, 1000 to 500, 800 to 800)) {
            val result = controller.resolveVideoFormat(metadata(width, height))
            assertEquals(RealtimeVideoFormat(width, height, service.model.defaultFrameRate), result)
        }
        assertEquals(RealtimeVideoFormat(1920, 1080, 30), controller.resolveVideoFormat(metadata(3840, 2160)))
        assertEquals(RealtimeVideoFormat(1080, 1920, 30), controller.resolveVideoFormat(metadata(3840, 2160, VideoRotation.ROTATION_90)))
    }

    @Test
    fun `rotation determines display dimensions and odd dimensions align down to four pixels`() {
        for (rotation in listOf(VideoRotation.ROTATION_90, VideoRotation.ROTATION_270)) {
            assertEquals(RealtimeVideoFormat(1080, 1920, 30), controller.resolveVideoFormat(metadata(1920, 1080, rotation)))
        }
        assertEquals(RealtimeVideoFormat(1920, 1080, 30),
            controller.resolveVideoFormat(metadata(1920, 1080, VideoRotation.ROTATION_180)))
        assertEquals(RealtimeVideoFormat(1920, 1080, 30), controller.resolveVideoFormat(metadata(1921, 1081)))
    }

    @Test
    fun `video rejects invalid media dimensions before playback`() {
        for ((width, height) in listOf(0 to 1080, -1 to 1080, 1080 to 0, 1080 to -1)) {
            assertThrows(XmaxError::class.java) { controller.resolveVideoFormat(metadata(width, height)) }
        }
    }

    @Test
    fun `small video metadata resolves to an upscaled oriented media format`() {
        for ((input, expected) in listOf(
            (1 to 1) to RealtimeVideoFormat(448, 448, 30),
            (2 to 2) to RealtimeVideoFormat(448, 448, 30),
            (3 to 3) to RealtimeVideoFormat(448, 448, 30),
            (200 to 200) to RealtimeVideoFormat(448, 448, 30),
            (256 to 480) to RealtimeVideoFormat(352, 660, 30),
            (640 to 480) to RealtimeVideoFormat(640, 480, 30),
            (512 to 960) to RealtimeVideoFormat(512, 960, 30),
        )) {
            assertEquals(expected, controller.resolveVideoFormat(metadata(input.first, input.second)))
            assertEquals(expected.copy(width = expected.height, height = expected.width),
                controller.resolveVideoFormat(metadata(input.first, input.second, VideoRotation.ROTATION_90)))
        }
    }

    private fun metadata(width: Int, height: Int, rotation: VideoRotation = VideoRotation.ROTATION_0) =
        MediaFileMetadata(width, height, rotation, durationUs = 1_000_000, hasAudio = false)

    private object PlayerStub : VideoPlayerControlling {
        override suspend fun configure(uri: Uri, metadata: MediaFileMetadata, videoFormat: RealtimeVideoFormat) = Unit
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun setLocalAudioPreviewMuted(muted: Boolean) = Unit
        override suspend fun setLocalAudioVolume(volume: Float) = Unit
        override fun attachPreview(view: XmaxVideoView, contentMode: VideoContentMode) = Unit
        override fun detachPreview(view: XmaxVideoView) = Unit
    }
}
