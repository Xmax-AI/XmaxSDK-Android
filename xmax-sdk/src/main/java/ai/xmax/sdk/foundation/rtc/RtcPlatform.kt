//
//  RtcPlatform.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import android.view.View

/**
 * The engine capabilities abstracted from the RTC vendor.
 */
internal interface RtcPlatform {

    /**
     * Applies encoding settings to the primary video stream.
     *
     * @param configuration Uplink dimensions, frame rate, bitrate, and encoding preference.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun configureVideoEncoding(
        configuration: VideoEncodingConfiguration,
    ): Int

    /**
     * Submits one external video frame with optional SEI metadata.
     *
     * @param frame The video frame to process, display, or submit.
     * @param seiData Optional video metadata; unused by the current Agora implementation.
     */
    fun pushExternalVideoFrame(
        frame: VideoFrame,
        seiData: ByteArray?,
    )

    /**
     * Submits one external 10 ms PCM audio frame.
     *
     * @param frame The PCM audio frame to play or submit.
     */
    fun pushExternalAudioFrame(
        frame: AudioFrame,
    )

    /**
     * Selects external frames as the primary video source.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun useExternalVideoSource(): Int

    /**
     * Selects external PCM as the primary audio source.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun startExternalAudioSource(): Int

    /**
     * Stops RTC external audio input.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun stopExternalAudioSource(): Int

    /**
     * Selects internal RTC audio input and starts microphone capture.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun startAudioCapture(): Int

    /**
     * Stops internal RTC microphone capture.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun stopAudioCapture(): Int

    /**
     * Starts internal RTC camera capture with the requested format.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @param frameRate The video frame rate, in fps.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun startVideoCapture(
        width: Int,
        height: Int,
        frameRate: Int,
    ): Int

    /**
     * Updates camera capture while retaining the lens and local preview binding.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @param frameRate The capture frame rate, in fps.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun updateVideoCaptureFormat(
        width: Int,
        height: Int,
        frameRate: Int,
    ): Int

    /**
     * Stops internal RTC camera capture.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun stopVideoCapture(): Int

    /**
     * Switches the camera used by internal RTC capture.
     *
     * @param position The front or rear camera to use.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun switchCamera(
        position: CameraPosition,
    ): Int

    /**
     * Binds local video to a rendering view.
     *
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun bindLocalVideo(
        view: View,
        contentMode: VideoContentMode,
    ): Int

    /**
     * Unbinds the local rendering view.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun unbindLocalVideo(): Int

    /**
     * Binds a remote user's video to a view with the requested scaling mode.
     *
     * @param userId The room's business user identifier.
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun bindRemoteVideo(
        userId: String,
        view: View,
        contentMode: VideoContentMode,
    ): Int

    /**
     * Unbinds a remote user's video view.
     *
     * @param userId The room's business user identifier.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun unbindRemoteVideo(
        userId: String,
    ): Int

    /**
     * Replaces the camera preview readiness listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setCameraPreviewReadyListener(
        listener: (() -> Unit)?,
    )

    /**
     * Replaces the frame listener for a remote stream.
     *
     * @param streamId The remote media stream or data channel identifier.
     * @param listener The event listener, or null to unregister it.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun setRemoteVideoFrameListener(
        streamId: String,
        listener: RtcRemoteVideoSink?,
    ): Int

    /**
     * Sets playback volume for a remote user's audio.
     *
     * @param streamId The remote media stream or data channel identifier.
     * @param volume The remote volume, from 0 to 100.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun setRemoteAudioVolume(
        streamId: String,
        volume: Int,
    ): Int

    /**
     * Sets the RTC event listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setEventListener(
        listener: RtcEventListener?,
    )

    /**
     * Sets the RTC quality listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setQualityListener(
        listener: RtcQualityListener?,
    )

    /**
     * Creates the operations for joining a specified RTC room.
     *
     * @param roomId The RTC room identifier.
     * @return The room's RTC operations, or null if creation is unavailable.
     */
    fun createRoom(
        roomId: String,
    ): RtcRoom?
}

/**
 * The room capabilities abstracted from the RTC vendor.
 */
internal interface RtcRoom {

    /**
     * Replaces the RTC room or engine event listener.
     *
     * @param listener The event listener.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun setEventListener(
        listener: (
            roomId: String,
            joined: Boolean,
            reason: String?,
        ) -> Unit,
    ): Int

    /**
     * Joins the RTC room with a business user account.
     *
     * @param configuration The room, user account, App ID, and room token.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun join(
        configuration: RoomJoinConfiguration,
    ): Int

    /**
     * Leaves the current RTC room.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun leave(): Int

    /**
     * Updates local video publication according to the publish flag.
     *
     * @param publish True to publish media, false to unpublish it.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun publishLocalVideo(
        publish: Boolean,
    ): Int

    /**
     * Updates local audio publication according to the publish flag.
     *
     * @param publish True to publish media, false to unpublish it.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun publishLocalAudio(
        publish: Boolean,
    ): Int

    /**
     * Updates remote video subscription.
     *
     * @param userId The room's business user identifier.
     * @param subscribe True to subscribe, false to unsubscribe.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun subscribeRemoteVideo(
        userId: String,
        subscribe: Boolean,
    ): Int

    /**
     * Updates remote audio subscription.
     *
     * @param userId The room's business user identifier.
     * @param subscribe True to subscribe, false to unsubscribe.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    fun subscribeRemoteAudio(
        userId: String,
        subscribe: Boolean,
    ): Int

    /**
     * Resolves the stream identifier used to subscribe to and render a remote user.
     *
     * @param userId The room's business user identifier.
     * @return The stream identifier for remote subscription and rendering.
     */
    fun resolveRemoteStreamId(
        userId: String,
    ): String

    /**
     * Sends a message to the current RTC room.
     *
     * @param message The message to send or log.
     * @param replacingPendingMessage The coalescing key; retains only the latest unsent message for the same
     * key.
     * @return The RTC message identifier or error code.
     */
    fun sendRoomMessage(
        message: String,
        replacingPendingMessage: String? = null,
    ): Long

    /**
     * Discards coalescible signals that have not started sending.
     *
     * @param key The coalescing key to discard, or null for all coalescible signals.
     */
    fun discardPendingMessages(
        key: String? = null,
    )

    /**
     * Waits for queued signals to reach RTC, throwing if the room leaves or sending fails.
     */
    suspend fun awaitPendingMessages() = Unit

    /**
     * Destroys the room and releases listeners and asynchronous tasks.
     */
    fun destroy()
}
