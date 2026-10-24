//
//  VideoRenderRegistry.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.video

import ai.xmax.sdk.RealtimeVideoTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * A registry of video track rendering bindings.
 */
internal object VideoRenderRegistry {

    /**
     * Bindings between tracks and display components.
     */
    private val bindings = ConcurrentHashMap<RealtimeVideoTrack, VideoRenderBinding>()

    /**
     * Registers a track's display or interaction binding.
     *
     * @param track The video track to display or bind.
     * @param binding The track's display or interaction binding.
     */
    fun register(
        track: RealtimeVideoTrack,
        binding: VideoRenderBinding,
    ) {
        bindings[track] = binding
    }

    /**
     * Removes a track's display or interaction binding.
     *
     * @param track The video track to display or bind.
     */
    fun unregister(
        track: RealtimeVideoTrack,
    ) {
        bindings.remove(track)
    }

    /**
     * Returns a track's display or interaction binding.
     *
     * @param track The video track to display or bind.
     * @return The display binding, or null if unregistered.
     */
    fun binding(
        track: RealtimeVideoTrack,
    ): VideoRenderBinding? = bindings[track]
}
