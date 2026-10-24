//
//  RealtimeExternalVideoSource.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import android.widget.FrameLayout

/**
 * A video source that supplies frames captured, decoded, or processed by your app.
 *
 * Your app manages permissions, capture devices, playback, and resource cleanup. A source can also supply
 * PCM audio and an SDK-hosted preview. XmaxSDK does not capture microphone audio or play submitted audio
 * locally; the source controls local monitoring through [setPreviewAudio].
 *
 * Lifecycle and preview methods run on the main thread. You can submit frames synchronously from a capture
 * or processing thread.
 */
public interface RealtimeExternalVideoSource {

    /**
     * The source's original display format; the SDK supplies the required frame format through
     * sink.videoFormat.
     */
    public val videoFormat: RealtimeVideoFormat

    /**
     * Whether the source provides audio; false by default and fixed for one binding. Enabling it does not
     * start microphone capture.
     */
    public val hasAudio: Boolean
        get() = false

    /**
     * Starts delivering video frames to the specified sink.
     *
     * Install the frame callback and return when the source is ready to deliver frames. Your app rotates,
     * crops, and scales pixels to [RealtimeExternalFrameSink.videoFormat]; frame delivery must not wait for
     * generation to begin or complete. Report source completion or unrecoverable failure through
     * [RealtimeExternalFrameSink.reportError]. XmaxSDK may call [stop] even if startup fails.
     *
     * @param sink The SDK receiver for external media frames.
     */
    public suspend fun start(
        sink: RealtimeExternalFrameSink,
    )

    /**
     * Stops delivering frames and releases the source's reference to the sink.
     *
     * This method must be safe to call more than once. Your app retains ownership of its capture devices
     * and players and decides when to release them.
     */
    public suspend fun stop()

    /**
     * Optionally attaches local preview to the SDK container; does nothing by default, allowing your app to
     * display its own preview.
     *
     * @param container The Android container hosting local preview.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    public fun attachPreview(
        container: FrameLayout,
        contentMode: VideoContentMode,
    ) = Unit

    /**
     * Optionally detaches preview without stopping capture; does nothing by default.
     *
     * @param container The Android container hosting local preview.
     */
    public fun detachPreview(
        container: FrameLayout,
    ) = Unit

    /**
     * Optionally controls local monitoring volume without changing submitted PCM; does nothing by default.
     *
     * @param volume The playback volume, from 0 to 1.
     * @param muted Whether audio is muted.
     */
    public fun setPreviewAudio(
        volume: Float,
        muted: Boolean,
    ) = Unit
}

/**
 * A receiver for video and optional audio from a single source attachment.
 *
 * The sink processes frames synchronously and discards submissions after unbinding. Input arrays may be
 * reused after each method returns. Video uses top-down RGBA; audio uses 48 kHz mono PCM16LE with 480 samples
 * per packet. Audio and video share a monotonic microsecond clock; timestamps must keep increasing across
 * seeks and loops.
 */
public interface RealtimeExternalFrameSink {

    /**
     * The input format required by XmaxSDK.
     */
    public val videoFormat: RealtimeVideoFormat

    /**
     * Whether the current generation requests frames from this source.
     *
     * When false, your app may skip readback or conversion without stopping capture or preview.
     */
    public val isRequestingFrames: Boolean

    /**
     * Submits an RGBA video frame for realtime generation.
     *
     * @param rgba Tightly packed RGBA pixels matching the current input dimensions.
     * @param timestampUs A nonnegative, strictly increasing timestamp from a monotonic clock, in
     * microseconds.
     */
    public fun pushVideo(
        rgba: ByteArray,
        timestampUs: Long,
    )

    /**
     * Submits a PCM audio frame for realtime generation.
     *
     * @param pcm A 10 ms packet of 48 kHz mono PCM16 little-endian audio, containing 480 samples.
     * @param timestampUs A nonnegative, strictly increasing timestamp on the video timeline, in microseconds.
     */
    public fun pushAudio(
        pcm: ByteArray,
        timestampUs: Long,
    )

    /**
     * Reports a failure in the current media input or generation task.
     *
     * @param error The operation failure or SDK error.
     */
    public fun reportError(
        error: Throwable,
    )
}
