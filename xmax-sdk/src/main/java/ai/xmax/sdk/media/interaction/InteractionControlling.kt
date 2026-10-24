//
//  InteractionControlling.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.interaction

import ai.xmax.sdk.RealtimeVideoFormat

/**
 * The trajectory input lifecycle of a generation task.
 */
internal interface InteractionControlling {

    /**
     * Enables interaction submission for a generation task.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     */
    suspend fun startInteraction(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
    )

    /**
     * Stops interaction submission and discards pending trajectories.
     */
    suspend fun stopInteraction()

    /**
     * Submits a trajectory for the currently displayed image.
     *
     * @param frame The current trajectory and displayed image dimensions.
     */
    fun submitInteraction(
        frame: InteractionFrame,
    )
}
