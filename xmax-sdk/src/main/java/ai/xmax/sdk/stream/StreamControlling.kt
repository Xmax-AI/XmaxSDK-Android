//
//  StreamControlling.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.UplinkVideoStatisticsListener
import ai.xmax.sdk.DownlinkVideoStatisticsListener
import ai.xmax.sdk.RealtimePerformanceAlarmListener
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import kotlinx.coroutines.Deferred

/**
 * The transport capabilities exposed to Core.
 */
internal interface StreamControlling {

    /**
     * Receives internal adaptation samples without occupying the public network listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setAdaptiveQualityListener(
        listener: ((RealtimeNetworkQuality) -> Unit)?,
    )

    /**
     * Sets the main-thread uplink statistics listener; null unregisters it. The listener survives
     * disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setUplinkVideoStatisticsListener(
        listener: UplinkVideoStatisticsListener?,
    )

    /**
     * Sets the main-thread downlink statistics listener; null unregisters it. The listener survives
     * disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setDownlinkVideoStatisticsListener(
        listener: DownlinkVideoStatisticsListener?,
    )

    /**
     * Updates uplink dimensions, frame rate, and encoder bitrate.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     */
    fun setVideoEncoderConfig(
        videoFormat: RealtimeVideoFormat,
    )

    /**
     * Sets the RTC network quality listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setNetworkQualityListener(
        listener: RealtimeNetworkQualityListener?,
    )

    /**
     * Sets the RTC performance alarm listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setPerformanceAlarmListener(
        listener: RealtimePerformanceAlarmListener?,
    )

    /**
     * Sets result audio volume from 0 to 1 and retains it for future subscriptions.
     *
     * @param volume The playback volume, from 0 to 1.
     */
    fun setRemoteAudioVolume(
        volume: Float,
    )

    /**
     * Joins RTC, publishes media according to its source, and enables appropriate uplink statistics.
     *
     * @param connection The server-provided RTC room, user, credentials, and result publisher.
     * @param includeLocalAudio Whether to publish local audio alongside video.
     * @param publishLocalMedia Whether to publish local media; false for server-read network video, true by
     * default.
     * @param ensureActive Checks operation validity and throws on cancellation.
     */
    suspend fun connect(
        connection: RealtimeSessionConnection,
        includeLocalAudio: Boolean,
        publishLocalMedia: Boolean = true,
        ensureActive: () -> Unit,
    )

    /**
     * Clears local publication, remote subscriptions, and generation waits, then leaves the room.
     */
    suspend fun disconnect()

    /**
     * Submits local video to RTC while generation requests input frames.
     *
     * @param frame The video frame to process, display, or submit.
     */
    fun pushLocalVideoFrame(
        frame: VideoFrame,
    )

    /**
     * Submits audio to RTC while local audio is published.
     *
     * @param frame The PCM audio frame to play or submit.
     */
    fun pushLocalAudioFrame(
        frame: AudioFrame,
    )

    /**
     * Starts generation and waits for result confirmation; output dimensions default to the generation
     * format, and uplink bitrate fields do not control downlink.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param context The generation prompt and reference media.
     * @param faceRestoration Whether to enable face restoration through `face_replace`; defaults to false.
     * @param targetVideoFormat The downlink output format.
     * @param paddingContain Whether the server fits input and removes padding from output; defaults to false.
     * @return An awaitable result completed when cleanup finishes.
     */
    suspend fun beginGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean = false,
        targetVideoFormat: RealtimeVideoFormat? = null,
        paddingContain: Boolean = false,
    ): Deferred<Unit>

    /**
     * Updates downlink dimensions and bitrate, estimating bitrate at a fixed 24 fps.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param targetVideoFormat The downlink output format.
     * @param ensureActive Checks operation validity and throws on cancellation.
     */
    suspend fun updateDownlinkVideoResolution(
        taskId: String,
        targetVideoFormat: RealtimeVideoFormat,
        ensureActive: () -> Unit,
    )

    /**
     * Enables network video completion notification after both the server completion event and first frame.
     *
     * @param onFinish The completion callback for the network video task, or null to omit it.
     */
    fun activateNetworkVideoCompletion(
        onFinish: (() -> Unit)?,
    )

    /**
     * Subscribes to result audio and applies remote playback volume.
     */
    fun activateRemoteAudio()

    /**
     * Updates the current task's conditions without creating another server session.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param context The generation prompt and reference media.
     * @param faceRestoration Whether to enable face restoration through `face_replace`; defaults to false.
     * @param paddingContain Whether the server fits input and removes padding from output; defaults to false.
     */
    suspend fun updateGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean = false,
        paddingContain: Boolean = false,
    )

    /**
     * Sends a stop signal for the generation task.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     */
    suspend fun stopGeneration(
        taskId: String,
    )

    /**
     * Submits media-normalized trajectories for the current task.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param points Normalized media trajectory points from 0 to 1.
     */
    suspend fun sendTracks(
        taskId: String,
        points: List<RealtimePoint>,
    )
}
