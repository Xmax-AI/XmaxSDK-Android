//
//  TrajectoryRegistry.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.trajectory

import ai.xmax.sdk.RealtimeVideoTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * A registry of remote track interaction bindings.
 */
internal object TrajectoryRegistry {

    /**
     * Bindings between tracks and display components.
     */
    private val bindings = ConcurrentHashMap<RealtimeVideoTrack, TrajectoryBinding>()

    /**
     * Registers a track's display or interaction binding.
     *
     * @param track The video track to display or bind.
     * @param binding The track's display or interaction binding.
     */
    fun register(
        track: RealtimeVideoTrack,
        binding: TrajectoryBinding,
    ) {
        bindings.put(
            track,
            binding,
        )?.invalidate()
    }

    /**
     * Removes a track's display or interaction binding.
     *
     * @param track The video track to display or bind.
     */
    fun unregister(
        track: RealtimeVideoTrack,
    ) {
        bindings.remove(track)?.invalidate()
    }

    /**
     * Returns a track's display or interaction binding.
     *
     * @param track The video track to display or bind.
     * @return The trajectory binding, or null if unregistered.
     */
    fun binding(
        track: RealtimeVideoTrack,
    ): TrajectoryBinding? = bindings[track]
}
