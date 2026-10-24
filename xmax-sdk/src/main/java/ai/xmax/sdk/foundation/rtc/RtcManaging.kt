//
//  RtcManaging.kt
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
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoContentMode
import android.view.View

/**
 * The RTC engine lifecycle and room connection capabilities.
 */
internal interface RtcManaging {

    /**
     * Initializes the RTC engine without acquiring another lease on repeated calls.
     */
    suspend fun initialize()

    /**
     * Leaves the room and destroys the RTC engine; repeated calls are safe.
     */
    suspend fun destroy()

    /**
     * Applies encoding settings to the primary video stream.
     *
     * @param configuration Uplink dimensions, frame rate, bitrate, and encoding preference.
     */
    fun configureVideoEncoding(
        configuration: VideoEncodingConfiguration,
    )

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
     */
    fun useExternalVideoSource()

    /**
     * Selects external PCM as the primary audio source.
     */
    fun startExternalAudioSource()

    /**
     * Stops RTC external audio input.
     */
    fun stopExternalAudioSource()

    /**
     * Selects internal RTC audio input and starts microphone capture.
     */
    fun startAudioCapture()

    /**
     * Stops internal RTC microphone capture.
     */
    fun stopAudioCapture()

    /**
     * Starts internal RTC camera capture with the requested format.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @param frameRate The video frame rate, in fps.
     */
    fun startVideoCapture(
        width: Int,
        height: Int,
        frameRate: Int,
    )

    /**
     * Updates camera capture while retaining the lens and local preview binding.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @param frameRate The capture frame rate, in fps.
     */
    fun updateVideoCaptureFormat(
        width: Int,
        height: Int,
        frameRate: Int,
    )

    /**
     * Stops internal RTC camera capture.
     */
    fun stopVideoCapture()

    /**
     * Switches the camera used by internal RTC capture.
     *
     * @param position The front or rear camera to use.
     */
    fun switchCamera(
        position: CameraPosition,
    )

    /**
     * Binds local video to a rendering view.
     *
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    fun bindLocalVideo(
        view: View,
        contentMode: VideoContentMode,
    )

    /**
     * Unbinds the local rendering view.
     */
    fun unbindLocalVideo()

    /**
     * Binds a remote stream to its rendering view.
     *
     * @param stream The remote stream identified by room and user.
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    fun bindRemoteVideo(
        stream: RemoteStream,
        view: View,
        contentMode: VideoContentMode,
    )

    /**
     * Unbinds a remote stream's rendering view.
     *
     * @param stream The remote stream identified by room and user.
     */
    fun unbindRemoteVideo(
        stream: RemoteStream,
    )

    /**
     * Joins an RTC room and waits for join confirmation.
     *
     * @param configuration The room, user account, App ID, and room token.
     */
    suspend fun joinRoom(
        configuration: RoomJoinConfiguration,
    )

    /**
     * Leaves the active or pending room; repeated calls are safe.
     */
    suspend fun leaveRoom()

    /**
     * Publishes local video.
     */
    fun publishLocalVideo()

    /**
     * Unpublishes local video, safely returning if no room is active.
     */
    fun unpublishLocalVideo()

    /**
     * Publishes local audio.
     */
    fun publishLocalAudio()

    /**
     * Unpublishes local audio, safely returning if no room is active.
     */
    fun unpublishLocalAudio()

    /**
     * Updates remote video subscription.
     *
     * @param userId The room's business user identifier.
     * @param subscribe True to subscribe, false to unsubscribe.
     */
    fun subscribeRemoteVideo(
        userId: String,
        subscribe: Boolean,
    )

    /**
     * Updates remote audio subscription.
     *
     * @param userId The room's business user identifier.
     * @param subscribe True to subscribe, false to unsubscribe.
     */
    fun subscribeRemoteAudio(
        userId: String,
        subscribe: Boolean,
    )

    /**
     * Sets playback volume for a remote user's audio.
     *
     * @param volume The remote volume, from 0 to 100.
     * @param userId The room's business user identifier.
     */
    fun setRemoteAudioVolume(
        volume: Int,
        userId: String,
    )

    /**
     * Sends a message to the current RTC room.
     *
     * @param message The message to send or log.
     * @param replacingPendingMessage The coalescing key; retains only the latest unsent message for the same
     * key.
     */
    fun sendRoomMessage(
        message: String,
        replacingPendingMessage: String? = null,
    )

    /**
     * Discards coalescible signals that have not started sending.
     *
     * @param key The coalescing key to discard, or null for all coalescible signals.
     */
    fun discardPendingMessages(
        key: String? = null,
    )

    /**
     * Waits for queued room signals to be submitted without blocking the main thread.
     */
    suspend fun awaitPendingMessages() = Unit

    /**
     * Sets the RTC event listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setEventListener(
        listener: RtcEventListener?,
    )

    /**
     * Sets the camera preview readiness listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setCameraPreviewReadyListener(
        listener: RealtimeCameraPreviewReadyListener?,
    )

    /**
     * Registers continuous remote frames for the current generation; clearing it also stops the receiver's
     * remote rendering.
     *
     * @param stream The remote stream identified by room and user.
     * @param listener The event listener, or null to unregister it.
     */
    fun setRemoteVideoFrameListener(
        stream: RemoteStream,
        listener: RtcRemoteVideoSink?,
    )

    /**
     * Sets the RTC quality listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    fun setQualityListener(
        listener: RtcQualityListener?,
    )
}
