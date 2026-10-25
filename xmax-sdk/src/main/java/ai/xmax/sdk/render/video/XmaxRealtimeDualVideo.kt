//
//  XmaxRealtimeDualVideo.kt
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
 * A Compose view that overlays local and remote video tracks and switches between them.
 * Retains local preview until the first remote frame fades in, returning to preview when the remote track is
 * cleared.
 * Recomposition updates parameters; leaving composition unbinds tracks without starting, stopping, or
 * releasing media streams.
 *
 * @param localTrack The local input track, or null to clear preview.
 * @param remoteTrack The remote generation track, or null to show local preview.
 * @param modifier Compose sizing, layout, and other modifiers.
 * @param videoContentMode The aspect-preserving scaling applied to both tracks; defaults to fill and crop.
 * @param remoteVideoFadeInDuration The remote fade-in duration, in seconds; zero, negative, or nonfinite
 * values disable animation. Changes apply to the next fade-in.
 * @param isInteractionEnabled Whether to enable trajectory interaction once remote video is visible.
 * @param trajectoryRenderer The remote trajectory effect, or null for the built-in renderer.
 */
@Composable
public fun XmaxRealtimeDualVideo(
    localTrack: RealtimeVideoTrack?,
    remoteTrack: RealtimeVideoTrack?,
    modifier: Modifier = Modifier,
    videoContentMode: VideoContentMode = VideoContentMode.FILL,
    remoteVideoFadeInDuration: Double = 0.3,
    isInteractionEnabled: Boolean = true,
    trajectoryRenderer: TrajectoryEffectRendering? = null,
) {
    AndroidView(
        factory = { context -> XmaxRealtimeDualVideoView(context) },
        modifier = modifier,
        update = { view ->
            view.videoContentMode = videoContentMode
            view.remoteVideoFadeInDuration = remoteVideoFadeInDuration
            view.isInteractionEnabled = isInteractionEnabled
            if (view.trajectoryRenderer !== trajectoryRenderer) {
                view.trajectoryRenderer = trajectoryRenderer
            }
            view.localTrack = localTrack
            view.remoteTrack = remoteTrack
        },
        onRelease = { view ->
            view.isInteractionEnabled = false
            view.remoteTrack = null
            view.localTrack = null
            view.trajectoryRenderer = null
        },
    )
}
