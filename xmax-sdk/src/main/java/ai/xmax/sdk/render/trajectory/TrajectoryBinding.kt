//
//  TrajectoryBinding.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.trajectory

import androidx.compose.ui.unit.IntSize
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.media.interaction.InteractionFrame
import android.os.Handler
import android.os.Looper

/**
 * 将渲染层触摸输入转交给媒体交互层。
 */
internal class TrajectoryBinding(
    /**
     * 接收触控轨迹帧的回调。
     */
    private val interactionListener: (InteractionFrame) -> Unit,

    /**
     * 处理后的本地媒体尺寸，作为固定的交互坐标基准。
     */
    private val resolvedMediaSize: IntSize,
) {

    /**
     * 轨迹绑定状态的并发保护。
     */
    private val stateLock = Any()

    /**
     * 交互覆盖层及视频坐标换算配置。
     */
    private var overlayView: TrajectoryOverlayView? = null

    /**
     * 轨迹映射所使用的视频缩放与裁剪方式。
     */
    private var contentMode = VideoContentMode.FILL

    /**
     * 将轨迹绑定接入指定交互覆盖层。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    fun attach(
        view: TrajectoryOverlayView,
        contentMode: VideoContentMode
    ) {
        synchronized(stateLock) {
            if (overlayView !== view) overlayView?.clearBinding(this)
            overlayView = view
            this.contentMode = contentMode
        }
        view.setBinding(this)
        view.setContentMode(contentMode)
        view.setResolvedMediaSize(resolvedMediaSize)
    }

    /**
     * 解除指定覆盖层的轨迹绑定。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     */
    fun detach(
        view: TrajectoryOverlayView
    ) {
        val shouldDetach = synchronized(stateLock) {
            if (overlayView !== view) false else {
                overlayView = null
                true
            }
        }
        if (shouldDetach) view.clearBinding(this)
    }

    /**
     * 取消当前轨迹绑定并清除视图交互。
     */
    fun invalidate() {
        synchronized(stateLock) {
            overlayView.also { overlayView = null }
        }?.runOnMain { clearBinding(this@TrajectoryBinding) }
    }

    /**
     * 更新交互坐标换算所需的显示配置并通知当前覆盖层。
     *
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    fun update(
        contentMode: VideoContentMode
    ) {
        synchronized(stateLock) {
            this.contentMode = contentMode
            overlayView
        }?.runOnMain { setContentMode(contentMode) }
    }

    /**
     * 向当前轨迹监听器提交触控帧。
     *
     * @param frame 当前触控轨迹及显示画面尺寸。
     */
    fun submit(
        frame: InteractionFrame
    ) {
        interactionListener(frame)
    }

    /**
     * 在主线程对当前轨迹视图执行更新。
     *
     * @param action 需要执行的操作。
     */
    private inline fun TrajectoryOverlayView.runOnMain(
        crossinline action: TrajectoryOverlayView.() -> Unit
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            MAIN_HANDLER.post { action() }
        }
    }

    /**
     * 轨迹视图更新的主线程调度入口。
     */
    private companion object {

        /**
         * 将轨迹绑定更新投递到主线程的调度器。
         */
        val MAIN_HANDLER = Handler(Looper.getMainLooper())
    }
}
