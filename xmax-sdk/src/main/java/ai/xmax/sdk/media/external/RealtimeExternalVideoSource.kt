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
     * The original, orientation-corrected frame dimensions and capture rate, fixed until [stop].
     * Submitted pixels must match these dimensions; the SDK resizes output into the model's area limits
     * with four-pixel alignment, enlarging small frames when necessary.
     */
    public val videoFormat: RealtimeVideoFormat

    /**
     * Whether the source provides audio; false by default and fixed for one binding. Enabling it does not
     * start microphone capture.
     */
    public val hasAudio: Boolean
        get() = false

    /**
     * Whether [attachPreview] supplies a native preview instead of the SDK's submitted-frame preview.
     * False by default; changing this value during a binding is unsupported.
     */
    public val providesPreview: Boolean
        get() = false

    /**
     * Starts delivering video frames to the specified sink.
     *
     * Install the frame callback and return when the source is ready to deliver frames. Your app applies
     * orientation and mirroring to pixels matching [videoFormat]; the SDK copies and resizes them.
     * Continue delivering frames for preview while disconnected, without waiting for generation to
     * begin or complete. Report source completion or unrecoverable failure through
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
     * Attaches a source-provided local preview when [providesPreview] is true.
     * Otherwise the SDK displays submitted frames without requiring a preview implementation.
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
 * reused after each method returns. Video uses top-down RGBA at the source's original dimensions.
 * Audio uses interleaved PCM16LE with its sample rate and channel count; the SDK converts it to 48 kHz
 * mono, 10 ms packets. Audio and video share a monotonic microsecond clock across seeks and loops.
 * Invalid or overlapping timestamps are ignored. A format or delivery failure invalidates the binding.
 */
public interface RealtimeExternalFrameSink {

    /**
     * The processed output format used for preview and generation, not the submitted pixel dimensions.
     */
    public val videoFormat: RealtimeVideoFormat

    /**
     * Whether this source binding still accepts frames for preview and generation.
     *
     * Remains true while disconnected. False after stop, replacement, or an unrecoverable failure;
     * your app may then skip readback or conversion without releasing its capture devices.
     */
    public val isRequestingFrames: Boolean

    /**
     * Copies and resizes an RGBA video frame for local preview and, when connected, generation.
     *
     * @param rgba Tightly packed pixels matching [RealtimeExternalVideoSource.videoFormat], with
     * orientation and mirroring already applied.
     * @param timestampUs A nonnegative, strictly increasing timestamp from a monotonic clock, in
     * microseconds.
     */
    public fun pushVideo(
        rgba: ByteArray,
        timestampUs: Long,
    )

    /**
     * Copies, converts, and packetizes PCM audio for realtime generation.
     *
     * Sample rate and channel count are fixed by the first accepted buffer. Gaps start a new segment
     * without inserting silence; overlapping buffers are ignored. Local playback remains app-owned.
     *
     * @param pcm Interleaved PCM16 little-endian samples containing complete audio frames.
     * @param timestampUs The first sample's nonnegative timestamp on the video clock, in microseconds.
     * @param sampleRate The input sample rate in Hz.
     * @param channelCount The input channel count; defaults to 1 and is downmixed to mono.
     */
    public fun pushAudio(
        pcm: ByteArray,
        timestampUs: Long,
        sampleRate: Int,
        channelCount: Int = 1,
    )

    /**
     * Submits already converted 48 kHz mono PCM16LE audio, retaining partial packets if necessary.
     *
     * @param pcm Complete mono PCM16 little-endian samples.
     * @param timestampUs The first sample's timestamp on the video clock, in microseconds.
     */
    public fun pushAudio(
        pcm: ByteArray,
        timestampUs: Long,
    ) = pushAudio(
        pcm,
        timestampUs,
        sampleRate = 48000,
        channelCount = 1,
    )

    /**
     * Invalidates this binding and reports its first unrecoverable media failure.
     *
     * @param error The operation failure or SDK error.
     */
    public fun reportError(
        error: Throwable,
    )
}
