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
 * An app-owned source of captured, decoded, or processed video, such as a camera or player.
 * XmaxSDK binds frame delivery without owning the device or player. Your app owns permissions, device
 * switching, and resource release.
 * Video-only input is the default; SDK local preview is optional, and sources may supply audio and preview
 * capabilities.
 * Lifecycle and preview methods run on the main thread; frames may be submitted synchronously from a
 * background thread.
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
     * Binds frame delivery before returning, without waiting for generation to finish.
     * Your app rotates, crops, and scales frames to sink.videoFormat; delivery must not wait for
     * startGeneration to return.
     * Report source completion or unrecoverable failure through sink.reportError. The SDK may call stop even
     * after start fails.
     *
     * @param sink The SDK receiver for external media frames.
     */
    public suspend fun start(
        sink: RealtimeExternalFrameSink,
    )

    /**
     * Stops frame delivery and clears the sink idempotently without closing your app's camera or player.
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
 * The frame receiver for one binding; submissions after unbinding are discarded. Input arrays may be reused
 * after each method returns.
 * Video uses top-down RGBA; audio uses 48 kHz mono PCM16LE with 480 samples per packet.
 * Audio and video share a monotonic microsecond clock; timestamps must keep increasing across seeks and
 * loops.
 */
public interface RealtimeExternalFrameSink {

    /**
     * The input format required by XmaxSDK.
     */
    public val videoFormat: RealtimeVideoFormat

    /**
     * When false, your app may skip expensive readback or conversion without stopping capture or preview.
     */
    public val isRequestingFrames: Boolean

    /**
     * Submits an externally captured RGBA video frame.
     *
     * @param rgba Tightly packed RGBA pixels matching the current input dimensions.
     * @param timestampUs The monotonic frame timestamp, in microseconds.
     */
    public fun pushVideo(
        rgba: ByteArray,
        timestampUs: Long,
    )

    /**
     * Submits externally captured PCM audio.
     *
     * @param pcm 48 kHz mono PCM16 little-endian audio bytes.
     * @param timestampUs The monotonic frame timestamp, in microseconds.
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
