//
//  RenderControllingStub.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.media.interaction.InteractionFrame
import ai.xmax.sdk.render.RenderControlling
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Deferred

/**
 * Supplies a controllable first-frame gate without Android view resources.
 */
internal class RenderControllingStub(
    private val firstFrame: Deferred<Unit>? = null,
) : RenderControlling {
    override fun setRemoteStream(stream: RemoteStream?) = Unit
    override fun registerRemoteTrack(track: RealtimeVideoTrack, resolvedMediaSize: IntSize, interactionListener: (InteractionFrame) -> Unit) = Unit
    override suspend fun waitUntilRemoteFrameReady() { firstFrame?.await() }
    override suspend fun resetRemoteTrack(track: RealtimeVideoTrack?) = Unit
}
