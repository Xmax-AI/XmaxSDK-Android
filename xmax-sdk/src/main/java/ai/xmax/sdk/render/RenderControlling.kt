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
import ai.xmax.sdk.RealtimeVideoFrameListener
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.media.interaction.InteractionFrame

/**
 * The rendering interface used by Core.
 */
internal interface RenderControlling {

    /**
     * Sets serialized remote video output without changing playback.
     *
     * @param listener The output listener, or null to unregister it.
     */
    fun setRemoteVideoFrameListener(
        listener: RealtimeVideoFrameListener?,
    )

    /**
     * Sets the subscribed remote stream; null unbinds it.
     *
     * @param stream The remote stream identified by room and user.
     */
    fun setRemoteStream(
        stream: RemoteStream?,
    )

    /**
     * Registers remote rendering and trajectory interactions.
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
     * Waits for the first remote frame ready for display.
     *
     * An expired stream or timeout ends the wait with an error.
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
