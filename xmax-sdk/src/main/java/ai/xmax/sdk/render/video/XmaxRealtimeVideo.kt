//
//  XmaxRealtimeVideo.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A Compose view displaying a single local or remote video track through [XmaxRealtimeVideoView].
 * Recomposition updates parameters; leaving composition unbinds tracks without starting, stopping, or
 * releasing media streams.
 *
 * @param track The video track to display, or null to clear it.
 * @param modifier Compose sizing, layout, and other modifiers.
 * @param videoContentMode The aspect-preserving scaling mode; defaults to fill and crop.
 * @param isInteractionEnabled Whether the remote track accepts trajectory interaction.
 * @param trajectoryRenderer The custom trajectory effect, or null for the built-in renderer.
 */
@Composable
public fun XmaxRealtimeVideo(
    track: RealtimeVideoTrack?,
    modifier: Modifier = Modifier,
    videoContentMode: VideoContentMode = VideoContentMode.FILL,
    isInteractionEnabled: Boolean = true,
    trajectoryRenderer: TrajectoryEffectRendering? = null,
) {
    AndroidView(
        factory = { context -> XmaxRealtimeVideoView(context) },
        modifier = modifier,
        update = { view ->
            view.videoContentMode = videoContentMode
            view.isInteractionEnabled = isInteractionEnabled
            if (view.trajectoryRenderer !== trajectoryRenderer) {
                view.trajectoryRenderer = trajectoryRenderer
            }
            view.track = track
        },
        onRelease = { view ->
            view.isInteractionEnabled = false
            view.track = null
            view.trajectoryRenderer = null
        },
    )
}
