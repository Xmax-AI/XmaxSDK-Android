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

import ai.xmax.sdk.stream.quality.QualityConfiguration
import ai.xmax.sdk.stream.quality.QualityChange

/**
 * The transport interface used by Core.
 */
internal interface StreamControlling {

    /**
     * The remote playback volume retained across subscriptions and disconnection.
     */
    val remoteAudioVolume: Float

    /**
     * The current connection quality configuration, or null before preparation or after reset.
     */
    val qualityConfiguration: QualityConfiguration?

    /**
     * Prepares initial transport quality from the media baseline and model history.
     *
     * @param baseline The fixed, resolved local media format.
     * @param minimumInputPixels The minimum uplink pixel area.
     * @param publishLocalMedia Whether the connection publishes local video.
     * @return The initial directional transport configuration.
     */
    fun prepareQuality(
        baseline: RealtimeVideoFormat,
        minimumInputPixels: Int = 0,
        publishLocalMedia: Boolean = true,
    ): QualityConfiguration

    /**
     * Proposes quality levels without committing them.
     *
     * @param quality The directional network quality.
     * @param sampledAt The sample time on the monotonic millisecond clock.
     * @return The proposed directional adjustments.
     */
    fun proposeQuality(
        quality: RealtimeNetworkQuality,
        sampledAt: Long,
    ): QualityChange

    /**
     * Commits an adjustment result, persisting successful levels only.
     *
     * @param downlink Whether the adjustment affects returned video.
     * @param level The attempted quality level.
     * @param success Whether the configuration was applied successfully.
     */
    fun completeQuality(
        downlink: Boolean,
        level: Int,
        success: Boolean,
    )

    /**
     * Clears consecutive samples without discarding committed levels.
     */
    fun resetQualitySamples()

    /**
     * Persists an initially applied level without starting an adaptation cooldown.
     *
     * @param downlink Whether the initial level affects returned video.
     */
    fun completeInitialQuality(
        downlink: Boolean,
    )

    /**
     * Releases connection quality state while retaining history and public listeners.
     */
    fun resetQuality()

    /**
     * Sets the internal adaptation listener independently of your app's network listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setAdaptiveQualityListener(
        listener: ((RealtimeNetworkQuality) -> Unit)?,
    )

    /**
     * Sets the uplink video statistics listener.
     *
     * Callbacks run on the main thread. The listener remains registered after disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setUplinkVideoStatisticsListener(
        listener: UplinkVideoStatisticsListener?,
    )

    /**
     * Sets the downlink video statistics listener.
     *
     * Callbacks run on the main thread. The listener remains registered after disconnection.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setDownlinkVideoStatisticsListener(
        listener: DownlinkVideoStatisticsListener?,
    )

    /**
     * Sets the realtime video encoder configuration.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     */
    fun setVideoEncoderConfig(
        videoFormat: RealtimeVideoFormat,
    )

    /**
     * Sets the network quality listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setNetworkQualityListener(
        listener: RealtimeNetworkQualityListener?,
    )

    /**
     * Sets the device performance alarm listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setPerformanceAlarmListener(
        listener: RealtimePerformanceAlarmListener?,
    )

    /**
     * Sets remote generated audio volume.
     *
     * The volume is retained for future subscriptions.
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
     * Subscribes to generated audio after remote video is ready.
     */
    fun activateRemoteAudio()

    /**
     * Sends an active task's condition update.
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
     * Stops generation and clears its result stream.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     */
    suspend fun stopGeneration(
        taskId: String,
    )

    /**
     * Sends a generation task's trajectory.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param points Normalized media trajectory points from 0 to 1.
     */
    suspend fun sendTracks(
        taskId: String,
        points: List<RealtimePoint>,
    )
}
