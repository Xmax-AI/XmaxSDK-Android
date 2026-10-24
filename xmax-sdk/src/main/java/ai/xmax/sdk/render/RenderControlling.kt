//
//  RenderControlling.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render

import androidx.compose.ui.unit.IntSize
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.media.interaction.InteractionFrame

/**
 * The remote video capabilities exposed by Render to Core.
 */
internal interface RenderControlling {

    /**
     * Sets the subscribed remote stream; null unbinds it.
     *
     * @param stream The remote stream identified by room and user.
     */
    fun setRemoteStream(
        stream: RemoteStream?,
    )

    /**
     * Registers remote video display and trajectory capabilities for a track.
     *
     * @param track The video track to display or bind.
     * @param resolvedMediaSize The fixed, resolved local media dimensions used for interaction coordinates.
     * @param interactionListener The interaction frame callback.
     */
    fun registerRemoteTrack(
        track: RealtimeVideoTrack,
        resolvedMediaSize: IntSize,
        interactionListener: (InteractionFrame) -> Unit,
    )

    /**
     * Waits for a displayable remote frame, throwing if the stream expires or the wait times out.
     */
    suspend fun waitUntilRemoteFrameReady()

    /**
     * Unregisters a remote track and releases its display resources.
     *
     * @param track The video track to display or bind.
     */
    suspend fun resetRemoteTrack(
        track: RealtimeVideoTrack?,
    )
}
