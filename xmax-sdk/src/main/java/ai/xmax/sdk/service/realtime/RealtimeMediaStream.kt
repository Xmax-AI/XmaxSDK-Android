//
//  RealtimeMediaStream.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import java.util.concurrent.atomic.AtomicReference

/**
 * 用于本地预览或展示生成结果的媒体流。
 */
public class RealtimeMediaStream internal constructor(
    /**
     * 当前媒体流的标识。
     */
    public val id: String,

    /**
     * 媒体流包含的视频轨道。
     */
    public val videoTrack: RealtimeVideoTrack?,
)

/**
 * 实时视频轨道及其动态元数据。
 *
 * @param videoFormat 轨道的初始视频规格；未知时不提供。
 * @param position 摄像头轨道的初始镜头位置。
 */
public class RealtimeVideoTrack internal constructor(
    /**
     * 用于视频渲染与轨迹绑定的轨道标识。
     */
    public val id: String,

    videoFormat: RealtimeVideoFormat? = null,
    position: CameraPosition? = null,
) {

    /**
     * 轨道的当前视频规格与摄像头方向。
     */
    private val metadata = AtomicReference(Metadata(videoFormat, position))

    /**
     * 轨道当前的媒体输入规格；传输档位调整不会改变该规格。
     */
    public val videoFormat: RealtimeVideoFormat?
        get() = metadata.get().videoFormat

    /**
     * 摄像头轨道当前使用的镜头位置。
     */
    public val position: CameraPosition?
        get() = metadata.get().position

    /**
     * 更新轨道对外提供的视频规格快照。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     */
    internal fun updateVideoFormat(
        videoFormat: RealtimeVideoFormat
    ) {
        metadata.updateAndGet { it.copy(videoFormat = videoFormat) }
    }

    /**
     * 更新轨道对外提供的摄像头方向。
     *
     * @param position 需要使用的前置或后置摄像头。
     */
    internal fun updatePosition(
        position: CameraPosition
    ) {
        metadata.updateAndGet { it.copy(position = position) }
    }

    /**
     * 保存轨道当前的视频规格和摄像头方向快照。
     */
    private data class Metadata(
        /**
         * 轨道当前的视频规格。
         */
        val videoFormat: RealtimeVideoFormat?,

        /**
         * 摄像头轨道当前的镜头位置。
         */
        val position: CameraPosition?,
    )
}

/**
 * 内部相机预览就绪通知；公共就绪语义通过 RealtimeConnectionState.READY 表达。
 */
internal fun interface RealtimeCameraPreviewReadyListener {

    /**
     * 通知当前相机预览已可显示。
     */
    fun onCameraPreviewReady()
}
