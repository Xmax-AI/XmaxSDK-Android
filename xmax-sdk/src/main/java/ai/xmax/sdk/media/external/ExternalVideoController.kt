//
//  ExternalVideoController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.external

import ai.xmax.sdk.*
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 绑定外部供帧，不接管采集器或播放器；沿用 SDK 的轨道所有权、模型规格和媒体错误清理。
 */
internal class ExternalVideoController(
    /**
     * 提供 RTC 操作的管理器。
     */
    private val rtc: RtcManaging,

    /**
     * 解析媒体尺寸及模型输入规则的服务。
     */
    private val mediaService: MediaServicing,

    /**
     * 查询当前生成是否需要外部输入帧的函数。
     */
    private val requestingFrames: () -> Boolean,

    /**
     * 接收本地视频帧的回调。
     */
    private val videoListener: (VideoFrame) -> Unit,

    /**
     * 接收本地音频帧的回调。
     */
    private val audioListener: (AudioFrame) -> Unit,

    /**
     * 接收媒体错误的回调。
     */
    private val errorListener: (XmaxError) -> Unit,
) {

    /**
     * 外部输入状态的并发保护。
     */
    private val lock = Any()

    /**
     * 当前外部源、帧接收器和媒体轨道。
     */
    private var source: RealtimeExternalVideoSource? = null

    /**
     * 接收当前外部源音视频帧的入口。
     */
    private var sink: Sink? = null

    /**
     * 当前外部源对应的本地视频轨道。
     */
    @Volatile var currentTrack: RealtimeVideoTrack? = null
        private set

    /**
     * 当前外部源是否提供音频帧。
     */
    @Volatile var hasAudio = false
        private set

    /**
     * 外部源的本地音频预览配置。
     */
    private var volume = 0.45f

    /**
     * 外部源的本地音频预览是否静音。
     */
    private var muted = false

    /**
     * 绑定外部输入源并创建对应的本地媒体流。
     *
     * @param source 接入方负责启停及释放的外部音视频源。
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun create(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream = withContext(Dispatchers.Main.immediate) {
        check(this@ExternalVideoController.source == null)
        val requested = videoFormat ?: source.videoFormat.copy(fps = mediaService.model.defaultFrameRate)
        if (requested.fps <= 0) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Video stream frame rate must be greater than zero")
        }
        val size = mediaService.resolveMediaSize(IntSize(requested.width, requested.height), RealtimeMediaSource.CAMERA)
        val format = requested.copy(width = size.width, height = size.height).also(RealtimeVideoFormat::validate)
        val track = RealtimeVideoTrack("external0", format)
        val input = Sink(format, source.hasAudio)
        this@ExternalVideoController.source = source
        synchronized(lock) { sink = input }
        try {
            rtc.useExternalVideoSource()
            hasAudio = source.hasAudio
            if (hasAudio) rtc.startExternalAudioSource()
            VideoRenderRegistry.register(track, VideoRenderBinding(
                attachHandler = { view, mode -> source.attachPreview(view, mode) },
                detachHandler = { view -> source.detachPreview(view) },
            ))
            currentTrack = track
            source.setPreviewAudio(volume, muted)
            source.start(input)
            RealtimeMediaStream(StreamID.LOCAL.value, track)
        } catch (error: Throwable) {
            cleanupAfterFailure(error, { stop() })
            throw XmaxError.from(error)
        }
    }

    /**
     * 更新外部媒体源的预览音量或静音设置。
     *
     * @param volume 播放音量，范围为 0 到 1。
     * @param muted 是否静音。
     */
    suspend fun setAudio(
        volume: Float? = null,
        muted: Boolean? = null
    ): Unit = withContext(Dispatchers.Main.immediate) {
        volume?.let { this@ExternalVideoController.volume = it }
        muted?.let { this@ExternalVideoController.muted = it }
        source?.setPreviewAudio(this@ExternalVideoController.volume, this@ExternalVideoController.muted)
    }

    /**
     * 停止向 SDK 供帧并清除 sink，必须幂等；不代表关闭接入方的摄像头或播放器。
     */
    suspend fun stop(): Unit = withContext(Dispatchers.Main.immediate) {
        synchronized(lock) { sink = null }
        val previousSource = source
        val previousTrack = currentTrack
        val hadAudio = hasAudio
        source = null
        currentTrack = null
        hasAudio = false
        muted = false
        cleanupResources(
            { previousSource?.setPreviewAudio(volume, false) },
            { previousSource?.stop() },
            { previousTrack?.let { VideoRenderRegistry.binding(it)?.detach() } },
            { previousTrack?.let(VideoRenderRegistry::unregister) },
            { if (hadAudio) rtc.stopExternalAudioSource() },
        )
    }

    /**
     * 向当前外部媒体输入提交音视频帧和错误的接收器。
     */
    private inner class Sink(
        /**
         * 视频尺寸、帧率及上行编码参数。
         */
        override val videoFormat: RealtimeVideoFormat,

        /**
         * 是否向 RTC 提供外部音频。
         */
        private val audioEnabled: Boolean,
    ) : RealtimeExternalFrameSink {

        /**
         * 音视频提交时间及当前供帧需求。
         */
        private var lastVideoUs = -1L

        /**
         * 最后接收的音频帧时间戳，单位为微秒。
         */
        private var lastAudioUs = -1L

        /**
         * 当前生成任务是否需要该外部源供帧。
         */
        override val isRequestingFrames: Boolean
            get() = synchronized(lock) { sink === this && requestingFrames() }

        /**
         * 向 SDK 提交一帧外部采集的 RGBA 视频数据。
         *
         * @param rgba 按当前输入尺寸排列的紧密 RGBA 像素数据。
         * @param timestampUs 帧的单调时间戳，单位为微秒。
         */
        override fun pushVideo(
            rgba: ByteArray,
            timestampUs: Long
        ) = submit {
            if (timestampUs <= lastVideoUs) return@submit
            require(rgba.size.toLong() == videoFormat.width.toLong() * videoFormat.height * 4)
            lastVideoUs = timestampUs
            videoListener(VideoFrame(
                VideoFormat(videoFormat.width, videoFormat.height, VideoPixelFormat.RGBA),
                timestampUs,
                listOf(VideoFramePlane(rgba, videoFormat.width * 4)),
            ))
        }

        /**
         * 向 SDK 提交外部采集的 PCM 音频数据。
         *
         * @param pcm 48 kHz、单声道、PCM16 小端音频字节。
         * @param timestampUs 帧的单调时间戳，单位为微秒。
         */
        override fun pushAudio(
            pcm: ByteArray,
            timestampUs: Long
        ) = submit {
            if (!audioEnabled || timestampUs <= lastAudioUs) return@submit
            require(pcm.size == 960)
            lastAudioUs = timestampUs
            audioListener(AudioFrame(pcm, timestampUs))
        }

        /**
         * 报告当前媒体输入或生成任务的失败。
         *
         * @param error 本次操作失败的异常或 SDK 错误。
         */
        override fun reportError(
            error: Throwable
        ) {
            if (synchronized(lock) { sink === this }) {
                errorListener(XmaxError(XmaxErrorCode.MEDIA_ERROR, "External media source failed", cause = error))
            }
        }

        /**
         * 在外部源仍有效时提交媒体事件，失败时通知错误回调。
         *
         * @param action 需要执行的操作。
         */
        private fun submit(
            action: () -> Unit
        ) {
            try {
                synchronized(lock) {
                    if (sink !== this || !requestingFrames()) return
                    action()
                }
            } catch (error: Exception) { reportError(error) }
        }
    }
}
