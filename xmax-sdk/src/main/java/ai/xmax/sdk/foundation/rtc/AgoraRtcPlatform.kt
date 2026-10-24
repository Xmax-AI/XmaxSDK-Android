//
//  AgoraRtcPlatform.kt
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
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.stream.room.RoomMessageCodec
import android.content.Context
import android.view.View
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.DataStreamConfig
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcConnection
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.UserInfo
import io.agora.rtc2.audio.AudioTrackConfig
import io.agora.rtc2.video.CameraCapturerConfiguration
import io.agora.rtc2.video.IVideoFrameObserver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Creates an Agora engine with the configured App ID, allowing local preview before session creation.
 *
 * @param context The Android context used for system services and media resources.
 * @param appId The RTC App ID, not an App Certificate or room token.
 * @return The RTC platform engine, or null for an empty App ID.
 */
internal fun createAgoraRtcEngine(
    context: Context,
    appId: String,
): RtcPlatform? =
    appId.trim().takeIf(String::isNotEmpty)?.let { AgoraRtcPlatform(
        context,
        it,
    ) }

/**
 * Releases the shared Agora engine and its resources.
 */
internal fun destroyAgoraRtcEngine() = RtcEngine.destroy()

/**
 * Checks video availability from enabled video and capture state without the mute flag.
 *
 * @param state The current state snapshot.
 * @return True if the remote video can be subscribed to.
 */
internal fun isAgoraVideoPublished(
    state: Int,
): Boolean =
    state and Constants.USER_STATE_MUTE_VIDEO == 0 &&
        state and Constants.USER_STATE_ENABLE_VIDEO != 0 &&
        state and Constants.USER_STATE_ENABLE_LOCAL_VIDEO != 0

/**
 * An Agora adapter for RTC engine operations.
 *
 * @param context The Android context used for system services and media resources.
 */
private class AgoraRtcPlatform(
    context: Context,

    /**
     * The RTC App ID, not an App Certificate or room token.
     */
    private val appId: String,
) : RtcPlatform {

    /**
     * The RTC event listener.
     */
    private val listener = AtomicReference<RtcEventListener?>()

    /**
     * The network quality, performance, and media statistics listener.
     */
    private val qualityListener = AtomicReference<RtcQualityListener?>()

    /**
     * The local camera preview readiness listener.
     */
    private val previewListener = AtomicReference<(() -> Unit)?>()

    /**
     * The active RTC room.
     */
    private val activeRoom = AtomicReference<AgoraRoom?>()

    /**
     * Remote frame receivers and their fixed rendering-clock offsets, keyed by user identifier.
     */
    private val frameSinks = ConcurrentHashMap<String, Pair<RtcRemoteVideoSink, Long>>()

    /**
     * Remote PCM receivers keyed by publisher identifier.
     */
    private val audioSinks = ConcurrentHashMap<String, RtcRemoteAudioFrameListener>()

    /**
     * Whether external video input is enabled.
     */
    private var externalVideo = false

    /**
     * Whether external audio input is enabled.
     */
    private var externalAudio = false

    /**
     * The Agora-assigned external audio track identifier.
     */
    private var audioTrackId = -1

    /**
     * The camera position used for capture.
     */
    private val awaitingCameraFrame = AtomicBoolean(false)

    /**
     * The active front or rear camera.
     */
    private var cameraPosition = CameraPosition.FRONT

    /**
     * The camera capture dimensions and frame rate.
     */
    private var captureFormat = CameraCapturerConfiguration.CaptureFormat(
        1080,
        1920,
        30,
    )

    /**
     * Whether camera capture has started.
     */
    private var cameraStarted = false

    /**
     * The configured uplink encoding settings.
     */
    private var videoEncodingConfiguration: VideoEncodingConfiguration? = null

    /**
     * The underlying Agora RTC engine.
     */
    private val engine: RtcEngineEx

    init {
        engine = RtcEngine.create(
            RtcEngineConfig().apply {
                mContext = context.applicationContext
                mAppId = this@AgoraRtcPlatform.appId
                mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                mEventHandler = object : IRtcEngineEventHandler() {

                    /**
                     * Reports the first frame from the local source for preview readiness.
                     *
                     * @param source The RTC capture source identifier.
                     * @param width The frame width, in pixels.
                     * @param height The frame height, in pixels.
                     * @param elapsed The RTC-reported elapsed time, in milliseconds.
                     */
                    override fun onFirstLocalVideoFrame(
                        source: Constants.VideoSourceType,
                        width: Int,
                        height: Int,
                        elapsed: Int,
                    ) {
                        previewListener.get()?.invoke()
                    }
                }
            },
        ) as RtcEngineEx

        try {
            checkRtc(
                engine.setPlaybackAudioFrameBeforeMixingParameters(
                    48_000,
                    1,
                    480,
                ),
                "set remote PCM format",
            )
            checkRtc(
                engine.registerAudioFrameObserver(
                    RtcRemoteAudioSink { channelId, uid ->
                        val room = activeRoom.get()?.takeIf { !it.closed && it.roomId == channelId }
                        room?.let { audioSinks[it.userId(uid)] }
                    },
                ),
                "registerAudioFrameObserver",
            )
            checkRtc(
                engine.enableVideo(),
                "enableVideo",
            )
            checkRtc(
                engine.enableLocalVideo(false),
                "disable initial camera capture",
            )
            checkRtc(
                engine.enableLocalAudio(false),
                "disable initial microphone capture",
            )
            checkRtc(
                engine.registerVideoFrameObserver(
                    object : IVideoFrameObserver {

                        /**
                         * Requests read-only frame observation without modifying native RTC pixels.
                         *
                         * @return The read-only frame processing mode.
                         */
                        override fun getVideoFrameProcessMode() = IVideoFrameObserver.PROCESS_MODE_READ_ONLY

                        /**
                         * Selects the pixel format delivered to the observer.
                         *
                         * @return The I420 pixel format.
                         */
                        override fun getVideoFormatPreference() = IVideoFrameObserver.VIDEO_PIXEL_I420

                        /**
                         * Specifies whether RTC applies rotation before delivering observer frames.
                         *
                         * @return False; rotation remains in frame metadata.
                         */
                        override fun getRotationApplied() = false

                        /**
                         * Specifies whether RTC applies mirroring before delivering observer frames.
                         *
                         * @return False; callback frames are not premirrored.
                         */
                        override fun getMirrorApplied() = false

                        /**
                         * Selects post-capture and pre-render frame observation.
                         *
                         * @return The capture and pre-render observation position bitmask.
                         */
                        override fun getObservedFramePosition() = IVideoFrameObserver.POSITION_PRE_RENDERER or IVideoFrameObserver.POSITION_POST_CAPTURER

                        /**
                         * Confirms a new camera frame and allows it to continue through RTC processing.
                         *
                         * @param source The RTC capture source identifier.
                         * @param frame The video frame to process, display, or submit.
                         * @return True to allow RTC to continue using the frame.
                         */
                        override fun onCaptureVideoFrame(
                            source: Int,
                            frame: io.agora.base.VideoFrame,
                        ): Boolean {
                            if (awaitingCameraFrame.compareAndSet(
                                true,
                                false,
                            )) previewListener.get()?.invoke()

                            return true
                        }

                        /**
                         * Allows pre-encode frames to continue to the encoder.
                         *
                         * @param source The RTC capture source identifier.
                         * @param frame The video frame to process, display, or submit.
                         * @return True to allow RTC encoding.
                         */
                        override fun onPreEncodeVideoFrame(
                            source: Int,
                            frame: io.agora.base.VideoFrame,
                        ) = true

                        /**
                         * Allows media player frames to continue through RTC processing.
                         *
                         * @param frame The video frame to process, display, or submit.
                         * @param playerId The RTC media player identifier.
                         * @return True to allow RTC to process the player frame.
                         */
                        override fun onMediaPlayerVideoFrame(
                            frame: io.agora.base.VideoFrame,
                            playerId: Int,
                        ) = true

                        /**
                         * Delivers remote render frames to the registered receiver for the current user.
                         *
                         * @param channel The RTC room name.
                         * @param uid The RTC-assigned numeric user identifier.
                         * @param frame The video frame to process, display, or submit.
                         * @return True to allow RTC rendering.
                         */
                        override fun onRenderVideoFrame(
                            channel: String,
                            uid: Int,
                            frame: io.agora.base.VideoFrame,
                        ): Boolean {
                            val room = activeRoom.get()?.takeIf { !it.closed && it.roomId == channel } ?: return true
                            val (sink, renderClockOffsetUs) = frameSinks[room.userId(uid)] ?: return true

                            try {
                                sink.onFrame(AgoraRemoteVideoFrame(frame, renderClockOffsetUs))
                                sink.onFirstFrame(
                                    frame.rotatedWidth,
                                    frame.rotatedHeight,
                                )
                            } catch (
                                error: Exception,
                            ) {
                                sink.onError(
                                    XmaxError(
                                        XmaxErrorCode.RTC_ERROR,
                                        "Remote video frame processing failed",
                                        cause = error,
                                    ),
                                )
                            }

                            return true
                        }
                    },
                ),
                "registerVideoFrameObserver",
            )
        } catch (
            error: Throwable,
        ) {
            RtcEngine.destroy()
            throw error
        }
    }

    /**
     * Applies the uplink encoder configuration.
     *
     * @param configuration Uplink dimensions, frame rate, bitrate, and encoding preference.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun configureVideoEncoding(
        configuration: VideoEncodingConfiguration,
    ): Int {
        val encoderConfiguration = RtcVideoConverter.makeEncoderConfiguration(
            configuration,
            cameraPosition = cameraPosition.takeUnless { externalVideo },
        )
        val connection = if (externalVideo) activeRoom.get()?.videoEncodingConnection() else null
        val result = if (connection != null) {
            engine.setVideoEncoderConfigurationEx(
                encoderConfiguration,
                connection,
            )
        } else {
            engine.setVideoEncoderConfiguration(encoderConfiguration)
        }

        if (result >= 0) videoEncodingConfiguration = configuration
        return result
    }

    /**
     * Updates encoding mirroring after camera or source changes, preserving dimensions, frame rate, and
     * bitrate.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    private fun updateVideoEncoding(): Int =
        videoEncodingConfiguration?.let(::configureVideoEncoding) ?: 0

    /**
     * Submits one external video frame with optional SEI metadata.
     *
     * @param frame The video frame to process, display, or submit.
     * @param seiData Optional video metadata; unused by the current Agora implementation.
     */
    override fun pushExternalVideoFrame(
        frame: VideoFrame,
        seiData: ByteArray?,
    ) {
        // Submit without per-frame SEI; rejection of an individual frame does not terminate the source.
        engine.pushExternalVideoFrame(RtcVideoConverter.convertFrame(frame))
    }

    /**
     * Submits one external 10 ms PCM audio frame.
     *
     * @param frame The PCM audio frame to play or submit.
     */
    override fun pushExternalAudioFrame(
        frame: AudioFrame,
    ) {
        if (audioTrackId < 0) return

        engine.pushExternalAudioFrame(
            RtcAudioConverter.convertFrame(frame),
            frame.timestampUs / 1_000L,
            48_000,
            1,
            Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE,
            audioTrackId,
        )
    }

    /**
     * Selects external frames as the primary video source.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun useExternalVideoSource(): Int {
        val result = engine.setExternalVideoSource(
            true,
            false,
            Constants.ExternalVideoSourceType.VIDEO_FRAME,
        )
        if (result < 0) return result

        externalVideo = true
        return updateVideoEncoding()
    }

    /**
     * Selects external PCM as the primary audio source.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun startExternalAudioSource(): Int {
        if (audioTrackId >= 0) return 0

        val id = engine.createCustomAudioTrack(
            Constants.AudioTrackType.AUDIO_TRACK_DIRECT,
            AudioTrackConfig().apply {
                enableLocalPlayback = false
                enableAudioProcessing = false
            },
        )
        if (id < 0) return id

        audioTrackId = id
        externalAudio = true
        return 0
    }

    /**
     * Stops RTC external audio input.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun stopExternalAudioSource(): Int {
        if (audioTrackId < 0) return 0

        val result = engine.destroyCustomAudioTrack(audioTrackId)
        if (result >= 0) {
            audioTrackId = -1
            externalAudio = false
        }

        return result
    }

    /**
     * Selects internal RTC audio input and starts microphone capture.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun startAudioCapture(): Int {
        if (externalAudio) {
            val result = stopExternalAudioSource()
            if (result < 0) return result
        }

        return engine.enableLocalAudio(true)
    }

    /**
     * Stops internal RTC microphone capture.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun stopAudioCapture(): Int = engine.enableLocalAudio(false)

    /**
     * Starts internal RTC camera capture with the requested format.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @param frameRate The video frame rate, in fps.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun startVideoCapture(
        width: Int,
        height: Int,
        frameRate: Int,
    ): Int {
        val source = engine.setExternalVideoSource(
            false,
            false,
            Constants.ExternalVideoSourceType.VIDEO_FRAME,
        )
        if (source < 0) return source

        externalVideo = false
        val config = updateVideoCaptureFormat(
            width,
            height,
            frameRate,
        )
        if (config < 0) return config

        val enable = engine.enableLocalVideo(true)
        if (enable < 0) return enable

        awaitingCameraFrame.set(true)
        val result = engine.startPreview()
        if (result >= 0) cameraStarted = true
        return result
    }

    /**
     * Updates capture dimensions and preview cropping while retaining the camera and render binding.
     * Restores the previous capture and encoding configuration if the update fails.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @param frameRate The capture frame rate, in fps.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun updateVideoCaptureFormat(
        width: Int,
        height: Int,
        frameRate: Int,
    ): Int {
        val previousCapture = captureFormat
        val previousEncoding = videoEncodingConfiguration
        val restoredEncoding = previousEncoding ?: VideoEncodingConfiguration(
            width = previousCapture.width,
            height = previousCapture.height,
            frameRate = previousCapture.fps,
        )
        val nextEncoding = restoredEncoding.copy(
            width = width,
            height = height,
            frameRate = frameRate,
        )
        val encoding = configureVideoEncoding(nextEncoding)
        if (encoding < 0) return encoding

        captureFormat = CameraCapturerConfiguration.CaptureFormat(
            width,
            height,
            frameRate,
        )
        val result = engine.setCameraCapturerConfiguration(cameraConfiguration())
        if (result < 0) {
            captureFormat = previousCapture
            engine.setCameraCapturerConfiguration(cameraConfiguration())
            configureVideoEncoding(restoredEncoding)
            videoEncodingConfiguration = previousEncoding
        }

        return result
    }

    /**
     * Stops internal RTC camera capture.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun stopVideoCapture(): Int {
        cameraStarted = false
        awaitingCameraFrame.set(false)
        val preview = engine.stopPreview()
        val capture = engine.enableLocalVideo(false)
        return if (preview < 0) preview else capture
    }

    /**
     * Switches the camera used by internal RTC capture.
     *
     * @param position The front or rear camera to use.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun switchCamera(
        position: CameraPosition,
    ): Int {
        if (cameraStarted && cameraPosition != position) {
            awaitingCameraFrame.set(true)
            val result = engine.switchCamera()
            if (result < 0) return result
        }
        cameraPosition = position
        if (!cameraStarted) {
            val result = engine.setCameraCapturerConfiguration(cameraConfiguration())
            if (result < 0) return result
        }

        return updateVideoEncoding()
    }

    /**
     * Builds the current camera position and capture format configuration.
     *
     * @return The current camera capture configuration.
     */
    private fun cameraConfiguration() = CameraCapturerConfiguration(
        if (cameraPosition == CameraPosition.FRONT) CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_FRONT
        else CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_REAR,
        captureFormat,
    ).apply { followEncodeDimensionRatio = true }

    /**
     * Binds local video to a rendering view.
     *
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun bindLocalVideo(
        view: View,
        contentMode: VideoContentMode,
    ): Int =
        engine.setupLocalVideo(
            RtcVideoConverter.makeLocalCanvas(
                view,
                contentMode,
            ),
        )

    /**
     * Unbinds the local rendering view.
     *
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun unbindLocalVideo(): Int = engine.setupLocalVideo(
        RtcVideoConverter.makeLocalCanvas(
            null,
            VideoContentMode.FILL,
        ),
    )

    /**
     * Binds a remote user's video to a view with the requested scaling mode.
     *
     * @param userId The room's business user identifier.
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun bindRemoteVideo(
        userId: String,
        view: View,
        contentMode: VideoContentMode,
    ): Int {
        val room = requireNotNull(activeRoom.get())
        return engine.setupRemoteVideoEx(
            RtcVideoConverter.makeCanvas(
                view,
                contentMode,
                room.uid(userId),
            ),
            room.connection(),
        )
    }

    /**
     * Unbinds a remote user's video view.
     *
     * @param userId The room's business user identifier.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun unbindRemoteVideo(
        userId: String,
    ): Int {
        val room = activeRoom.get() ?: return 0
        return engine.setupRemoteVideoEx(
            RtcVideoConverter.makeCanvas(
                null,
                VideoContentMode.FILL,
                room.uid(userId),
            ),
            room.connection(),
        )
    }

    /**
     * Replaces the camera preview readiness listener; null unregisters it.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setCameraPreviewReadyListener(
        listener: (() -> Unit)?,
    ) { previewListener.set(listener) }

    /**
     * Replaces the frame listener for a remote stream.
     *
     * @param streamId The remote media stream or data channel identifier.
     * @param listener The event listener, or null to unregister it.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun setRemoteVideoFrameListener(
        streamId: String,
        listener: RtcRemoteVideoSink?,
    ): Int {
        if (listener == null) {
            frameSinks.remove(streamId)
        } else {
            val beforeNs = System.nanoTime()
            val renderTimeUs = System.currentTimeMillis() * 1_000L
            val afterNs = System.nanoTime()
            val nativeTimeUs = (beforeNs + (afterNs - beforeNs) / 2) / 1_000L
            frameSinks[streamId] = listener to (renderTimeUs - nativeTimeUs)
        }
        return 0
    }

    /**
     * Sets a publisher's PCM receiver without changing subscriptions or volume.
     *
     * @param streamId The native publisher identifier.
     * @param listener The PCM receiver, or null to unregister it.
     * @return Zero when the receiver has been updated.
     */
    override fun setRemoteAudioFrameListener(
        streamId: String,
        listener: RtcRemoteAudioFrameListener?,
    ): Int {
        if (listener == null) audioSinks.remove(streamId) else audioSinks[streamId] = listener
        return 0
    }

    /**
     * Sets playback volume for a remote user's audio.
     *
     * @param streamId The remote media stream or data channel identifier.
     * @param volume The remote volume, from 0 to 100.
     * @return The RTC result: nonnegative if accepted, negative on failure.
     */
    override fun setRemoteAudioVolume(
        streamId: String,
        volume: Int,
    ): Int {
        val room = requireNotNull(activeRoom.get())
        return engine.adjustUserPlaybackSignalVolumeEx(
            room.uid(streamId),
            volume,
            room.connection(),
        )
    }

    /**
     * Sets the RTC connection and media event listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setEventListener(
        listener: RtcEventListener?,
    ) { this.listener.set(listener) }

    /**
     * Sets the RTC network and media quality listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setQualityListener(
        listener: RtcQualityListener?,
    ) { qualityListener.set(listener) }

    /**
     * Creates the operations for joining a specified RTC room.
     *
     * @param roomId The RTC room identifier.
     * @return The RTC operations for the room.
     */
    override fun createRoom(
        roomId: String,
    ): RtcRoom = AgoraRoom(roomId)

    /**
     * A room membership with independent callbacks, sending queue, and account mappings; ignores callbacks
     * after leaving.
     */
    private inner class AgoraRoom(
        /**
         * The RTC room identifier.
         */
        val roomId: String,
    ) : RtcRoom {

        /**
         * Whether this room is closed.
         */
        @Volatile var closed = false
            private set

        /**
         * The local numeric UID assigned on room entry.
         */
        private var localUid = 0

        /**
         * The local user's string account.
         */
        private var localAccount = ""

        /**
         * The room data channel identifier.
         */
        private var dataStreamId = -1

        /**
         * The room connection event listener.
         */
        private var stateListener: ((String, Boolean, String?) -> Unit)? = null

        /**
         * The remote user account mappings.
         */
        private val accounts = ConcurrentHashMap<Int, String>()

        /**
         * Remote video publication state by user.
         */
        private val publications = ConcurrentHashMap<Int, Boolean>()

        /**
         * The signaling packet codec.
         */
        private val codec = RoomMessageCodec()

        /**
         * The scope sending room signals.
         */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * The ordered room signaling queue.
         */
        private val sender = RtcMessageQueue(
            scope = scope,
            send = { bytes ->
                synchronized(sendLock) {
                    if (!isCurrent()) throw CancellationException("RTC room was closed")

                    val result = engine.sendStreamMessageEx(
                        dataStreamId,
                        bytes,
                        connection(),
                    )
                    if (result < 0) {
                        throw XmaxError(
                            XmaxErrorCode.RTC_ERROR,
                            "Agora room signaling failed: $result",
                        )
                    }
                }
            },
            onFailure = {
                if (isCurrent()) stateListener?.invoke(
                    roomId,
                    false,
                    "AGORA_SEND_FAILED",
                )
            },
        )

        /**
         * Whether room entry has been confirmed.
         */
        @Volatile private var joined = false

        /**
         * The lock protecting signal submission.
         */
        private val sendLock = Any()

        /**
         * Returns the Agora connection identity for this room.
         *
         * @return The RTC connection for this room.
         */
        fun connection() = RtcConnection(
            roomId,
            localUid,
        )

        /**
         * Returns the active room connection used for external video encoding.
         *
         * @return The active room's RTC connection, or null before connection.
         */
        fun videoEncodingConnection(): RtcConnection? =
            if (joined && isCurrent()) connection() else null

        /**
         * Resolves a numeric RTC UID to its business user account.
         *
         * @param uid The RTC-assigned numeric user identifier.
         * @return The business user account associated with the numeric UID.
         */
        fun userId(
            uid: Int,
        ): String = accounts[uid] ?: Integer.toUnsignedString(uid)

        /**
         * Resolves a business account to its numeric RTC UID.
         *
         * @param account The RTC user account string.
         * @return The corresponding numeric RTC user identifier.
         */
        fun uid(
            account: String,
        ): Int {
            accounts.entries.firstOrNull { it.value == account }?.let { return it.key }
            val info = UserInfo()
            if (engine.getUserInfoByUserAccountEx(
                account,
                info,
                connection(),
            ) == 0) {
                accounts[info.uid] = account
                return info.uid
            }

            throw XmaxError(
                XmaxErrorCode.RTC_ERROR,
                "RTC user account is not available: $account",
            )
        }

        /**
         * Checks whether this room remains active and open.
         *
         * @return True if this room is still current.
         */
        private fun isCurrent() = !closed && activeRoom.get() === this

        /**
         * Updates remote video publication state and notifies subscribers.
         *
         * @param uid The RTC-assigned numeric user identifier.
         * @param published Whether the remote video is published.
         */
        private fun publish(
            uid: Int,
            published: Boolean,
        ) {
            if (!isCurrent()) return

            publications[uid] = published
            // Use the business account for remote identity; confirm the result publisher only after account mapping is available.
            if (accounts[uid] != null) listener.get()?.onRemoteVideoPublished(
                RemoteStream(
                    roomId,
                    userId(uid),
                ),
                published,
            )
        }

        /**
         * The room-specific RTC callbacks.
         */
        private val events = object : IRtcEngineEventHandler() {

            /**
             * Confirms successful room entry and wakes its waiter.
             *
             * @param channel The RTC room name.
             * @param uid The RTC-assigned numeric user identifier.
             * @param elapsed The RTC-reported elapsed time, in milliseconds.
             */
            override fun onJoinChannelSuccess(
                channel: String,
                uid: Int,
                elapsed: Int,
            ) {
                if (!isCurrent()) return

                localUid = uid
                dataStreamId = engine.createDataStreamEx(
                    DataStreamConfig().apply {
                        ordered = true
                        syncWithAudio = false
                    },
                    connection(),
                )
                if (dataStreamId < 0) {
                    stateListener?.invoke(
                        roomId,
                        false,
                        "AGORA_SEND_FAILED",
                    )
                    return
                }

                joined = true
                stateListener?.invoke(
                    roomId,
                    true,
                    null,
                )
            }

            /**
             * Updates the mapping between numeric RTC UIDs and user accounts.
             *
             * @param uid The RTC-assigned numeric user identifier.
             * @param info The user account associated with the numeric UID.
             */
            override fun onUserInfoUpdated(
                uid: Int,
                info: UserInfo,
            ) {
                if (!isCurrent()) return

                val account = info.userAccount?.takeIf(String::isNotBlank) ?: return
                accounts[uid] = account
                publications[uid]?.let { publish(
                    uid,
                    it,
                ) }
            }

            /**
             * Records a remote user joining the room.
             *
             * @param uid The RTC-assigned numeric user identifier.
             * @param elapsed The RTC-reported elapsed time, in milliseconds.
             */
            override fun onUserJoined(
                uid: Int,
                elapsed: Int,
            ) {
                val info = UserInfo()
                if (isCurrent() && engine.getUserInfoByUidEx(
                    uid,
                    info,
                    connection(),
                ) == 0) onUserInfoUpdated(
                    uid,
                    info,
                )
            }

            /**
             * Clears a departing user's publication state and notifies subscribers.
             *
             * @param uid The RTC-assigned numeric user identifier.
             * @param reason The reason for the state transition or failure.
             */
            override fun onUserOffline(
                uid: Int,
                reason: Int,
            ) {
                publish(
                    uid,
                    false,
                )
                accounts.remove(uid)
                publications.remove(uid)
            }

            /**
             * Updates video availability from remote mute state.
             *
             * @param uid The RTC-assigned numeric user identifier.
             * @param muted Whether audio is muted.
             */
            override fun onUserMuteVideo(
                uid: Int,
                muted: Boolean,
            ) = publish(
                uid,
                !muted,
            )

            /**
             * Updates video availability from the remote video module state.
             *
             * @param uid The RTC-assigned numeric user identifier.
             * @param enabled Whether the capability is enabled.
             */
            override fun onUserEnableVideo(
                uid: Int,
                enabled: Boolean,
            ) = publish(
                uid,
                enabled,
            )

            /**
             * Updates video availability from remote capture state.
             *
             * @param uid The RTC-assigned numeric user identifier.
             * @param enabled Whether the capability is enabled.
             */
            override fun onUserEnableLocalVideo(
                uid: Int,
                enabled: Boolean,
            ) = publish(
                uid,
                enabled,
            )

            /**
             * Updates remote video publication from user state.
             *
             * @param uid The RTC-assigned numeric user identifier.
             * @param state The current state snapshot.
             */
            override fun onUserStateChanged(
                uid: Int,
                state: Int,
            ) = publish(
                uid,
                isAgoraVideoPublished(state),
            )

            /**
             * Converts remote video state into subscribable publication state.
             *
             * @param uid The RTC-assigned numeric user identifier.
             * @param state The current state snapshot.
             * @param reason The reason for the state transition or failure.
             * @param elapsed The RTC-reported elapsed time, in milliseconds.
             */
            override fun onRemoteVideoStateChanged(
                uid: Int,
                state: Int,
                reason: Int,
                elapsed: Int,
            ) {
                if (state == Constants.REMOTE_VIDEO_STATE_STARTING || state == Constants.REMOTE_VIDEO_STATE_DECODING) publish(
                    uid,
                    true,
                )

                if (reason == Constants.REMOTE_VIDEO_STATE_REASON_REMOTE_MUTED || reason == Constants.REMOTE_VIDEO_STATE_REASON_REMOTE_OFFLINE) publish(
                    uid,
                    false,
                )
            }

            /**
             * Reports RTC connection transitions and unrecoverable connection failures.
             *
             * @param state The current state snapshot.
             * @param reason The reason for the state transition or failure.
             */
            override fun onConnectionStateChanged(
                state: Int,
                reason: Int,
            ) {
                if (!isCurrent()) return

                if (state == Constants.CONNECTION_STATE_FAILED) stateListener?.invoke(
                    roomId,
                    false,
                    "AGORA_CONNECTION_FAILED",
                )
            }

            /**
             * Reports expired RTC credentials requiring a new business connection.
             */
            override fun onRequestToken() {
                if (isCurrent()) stateListener?.invoke(
                    roomId,
                    false,
                    "TOKEN_EXPIRED",
                )
            }

            /**
             * Delivers room data messages to the signaling handler.
             *
             * @param uid The RTC-assigned numeric user identifier.
             * @param streamId The remote media stream or data channel identifier.
             * @param data The binary data to read, transfer, or retain.
             */
            override fun onStreamMessage(
                uid: Int,
                streamId: Int,
                data: ByteArray,
            ) {
                if (!isCurrent()) return

                codec.decode(
                    userId(uid),
                    data.toString(Charsets.UTF_8),
                )?.let {
                    listener.get()?.onUserMessageReceived(
                        RemoteStream(
                            roomId,
                            userId(uid),
                        ),
                        it,
                    )
                }
            }

            /**
             * Receives uplink and downlink network quality.
             *
             * @param uid The RTC-assigned numeric user identifier.
             * @param txQuality The RTC uplink network quality rating.
             * @param rxQuality The RTC downlink network quality rating.
             */
            override fun onNetworkQuality(
                uid: Int,
                txQuality: Int,
                rxQuality: Int,
            ) {
                if (isCurrent() && uid == 0) qualityListener.get()?.onNetworkQuality(
                    RtcQualityConverter.convertLevel(txQuality),
                    RtcQualityConverter.convertLevel(rxQuality),
                )
            }

            /**
             * Converts local RTC statistics to SDK uplink statistics.
             *
             * @param source The RTC capture source identifier.
             * @param stats The RTC video statistics snapshot.
             */
            override fun onLocalVideoStats(
                source: Constants.VideoSourceType,
                stats: LocalVideoStats,
            ) {
                if (!isCurrent()) return

            val statistics = RtcQualityConverter.uplinkVideoStatistics(stats)
            RtcStatsLogger.logUplinkVideoStatistics(statistics)
            qualityListener.get()?.onUplinkVideoStatistics(statistics)
                qualityListener.get()?.onPerformanceAlarm(
                    limited = stats.qualityAdaptIndication == 1,
                    suggestedWidth = stats.encodedFrameWidth,
                    suggestedHeight = stats.encodedFrameHeight,
                    suggestedFrameRate = stats.encoderOutputFrameRate,
                )
            }

            /**
             * Converts remote RTC statistics to SDK downlink statistics.
             *
             * @param stats The RTC video statistics snapshot.
             */
            override fun onRemoteVideoStats(
                stats: RemoteVideoStats,
            ) {
                if (!isCurrent()) return

                val account = accounts[stats.uid] ?: return
            val statistics = RtcQualityConverter.downlinkVideoStatistics(
                account,
                stats,
            )
            RtcStatsLogger.logDownlinkVideoStatistics(statistics)
            qualityListener.get()?.onDownlinkVideoStatistics(statistics)
            }
        }

        /**
         * Replaces the RTC room or engine event listener.
         *
         * @param listener The event listener.
         * @return The RTC result: nonnegative if accepted, negative on failure.
         */
        override fun setEventListener(
            listener: (String, Boolean, String?) -> Unit,
        ): Int {
            stateListener = listener
            return 0
        }

        /**
         * Joins the RTC room with a business user account.
         *
         * @param configuration The room, user account, App ID, and room token.
         * @return The RTC result: nonnegative if accepted, negative on failure.
         */
        override fun join(
            configuration: RoomJoinConfiguration,
        ): Int {
            if (configuration.appId != appId) return -2

            if (!activeRoom.compareAndSet(
                null,
                this,
            )) return -5

            localAccount = configuration.userId
            val result = engine.joinChannelWithUserAccountEx(
                configuration.token,
                roomId,
                configuration.userId,
                ChannelMediaOptions().apply {
                    channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                    clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                    publishCameraTrack = false
                    publishCustomVideoTrack = false
                    publishMicrophoneTrack = false
                    publishCustomAudioTrack = false
                    autoSubscribeAudio = false
                    autoSubscribeVideo = false
                },
                events,
            )

            return result
        }

        /**
         * Updates local video publication according to the publish flag.
         *
         * @param publish True to publish media, false to unpublish it.
         * @return The RTC result: nonnegative if accepted, negative on failure.
         */
        override fun publishLocalVideo(
            publish: Boolean,
        ): Int {
            val result = engine.updateChannelMediaOptionsEx(
                ChannelMediaOptions().apply {
                    publishCameraTrack = publish && !externalVideo
                    publishCustomVideoTrack = publish && externalVideo
                    if (externalVideo) customVideoTrackId = 0
                },
                connection(),
            )
            if (result < 0) return result

            if (publish && externalVideo) return updateVideoEncoding()

            return result
        }

        /**
         * Updates local audio publication according to the publish flag.
         *
         * @param publish True to publish media, false to unpublish it.
         * @return The RTC result: nonnegative if accepted, negative on failure.
         */
        override fun publishLocalAudio(
            publish: Boolean,
        ): Int = engine.updateChannelMediaOptionsEx(
            ChannelMediaOptions().apply {
                publishMicrophoneTrack = publish && !externalAudio
                publishCustomAudioTrack = publish && externalAudio
                if (externalAudio) publishCustomAudioTrackId = audioTrackId
            },
            connection(),
        )

        /**
         * Updates remote video subscription.
         *
         * @param userId The room's business user identifier.
         * @param subscribe True to subscribe, false to unsubscribe.
         * @return The RTC result: nonnegative if accepted, negative on failure.
         */
        override fun subscribeRemoteVideo(
            userId: String,
            subscribe: Boolean,
        ): Int =
            engine.muteRemoteVideoStreamEx(
                uid(userId),
                !subscribe,
                connection(),
            )

        /**
         * Updates remote audio subscription.
         *
         * @param userId The room's business user identifier.
         * @param subscribe True to subscribe, false to unsubscribe.
         * @return The RTC result: nonnegative if accepted, negative on failure.
         */
        override fun subscribeRemoteAudio(
            userId: String,
            subscribe: Boolean,
        ): Int =
            engine.muteRemoteAudioStreamEx(
                uid(userId),
                !subscribe,
                connection(),
            )

        /**
         * Resolves the stream identifier used to subscribe to and render a remote user.
         *
         * @param userId The room's business user identifier.
         * @return The remote stream identifier matching its business user account.
         */
        override fun resolveRemoteStreamId(
            userId: String,
        ) = userId

        /**
         * Sends a message to the current RTC room.
         *
         * @param message The message to send or log.
         * @param replacingPendingMessage The coalescing key; retains only the latest unsent message for the
         * same key.
         * @return The RTC message identifier or error code.
         */
        override fun sendRoomMessage(
            message: String,
            replacingPendingMessage: String?,
        ): Long = synchronized(sendLock) {
            if (!isCurrent() || !joined) return -3

            sender.enqueue(
                message,
                replacingPendingMessage,
            )
            0L
        }

        /**
         * Discards coalescible signals that have not started sending.
         *
         * @param key The coalescing key to discard, or null for all coalescible signals.
         */
        override fun discardPendingMessages(
            key: String?,
        ) {
            sender.discardPendingMessages(key)
        }

        /**
         * Waits for queued room signals to be submitted without blocking the main thread.
         */
        override suspend fun awaitPendingMessages() {
            sender.flush()
        }

        /**
         * Leaves the room and clears its publication and subscription state.
         *
         * @return The RTC result: nonnegative if accepted, negative on failure.
         */
        override fun leave(): Int = synchronized(sendLock) {
            if (closed) return 0

            closed = true
            joined = false
            sender.close()
            scope.cancel()
            codec.clear()
            frameSinks.clear()
            audioSinks.clear()
            activeRoom.compareAndSet(
                this,
                null,
            )
            engine.leaveChannelWithUserAccountEx(
                roomId,
                localAccount,
            )
        }

        /**
         * Destroys the room and releases listeners and asynchronous tasks.
         */
        override fun destroy() {
            leave()
            stateListener = null
            accounts.clear()
            publications.clear()
        }
    }

    /**
     * Checks an RTC result and throws an operation-specific error on failure.
     *
     * @param code The RTC status or error code.
     * @param operation The operation name identifying the failure.
     */
    private fun checkRtc(
        code: Int,
        operation: String,
    ) {
        check(code >= 0) { "Agora $operation failed: $code" }
    }
}
