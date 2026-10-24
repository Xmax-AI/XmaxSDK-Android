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
 * Connects rendering touch input to media interactions.
 */
internal class TrajectoryBinding(
    /**
     * The interaction frame callback.
     */
    private val interactionListener: (InteractionFrame) -> Unit,

    /**
     * The fixed, resolved local media dimensions for interaction coordinates.
     */
    private val resolvedMediaSize: IntSize,
) {

    /**
     * The lock protecting trajectory bindings.
     */
    private val stateLock = Any()

    /**
     * The current interaction overlay.
     */
    private var overlayView: TrajectoryOverlayView? = null

    /**
     * The scaling mode used to map trajectory coordinates.
     */
    private var contentMode = VideoContentMode.FILL

    /**
     * Attaches trajectory input to an interaction overlay.
     *
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    fun attach(
        view: TrajectoryOverlayView,
        contentMode: VideoContentMode,
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
     * Detaches trajectory input from an overlay.
     *
     * @param view The view to bind or unbind.
     */
    fun detach(
        view: TrajectoryOverlayView,
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
     * Cancels the trajectory binding and clears view interaction.
     */
    fun invalidate() {
        synchronized(stateLock) {
            overlayView.also { overlayView = null }
        }?.runOnMain { clearBinding(this@TrajectoryBinding) }
    }

    /**
     * Updates coordinate mapping configuration and notifies the active overlay.
     *
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    fun update(
        contentMode: VideoContentMode,
    ) {
        synchronized(stateLock) {
            this.contentMode = contentMode
            overlayView
        }?.runOnMain { setContentMode(contentMode) }
    }

    /**
     * Submits a touch frame to the current trajectory listener.
     *
     * @param frame The current trajectory and displayed image dimensions.
     */
    fun submit(
        frame: InteractionFrame,
    ) {
        interactionListener(frame)
    }

    /**
     * Updates the current trajectory view on the main thread.
     *
     * @param action The operation to perform.
     */
    private inline fun TrajectoryOverlayView.runOnMain(
        crossinline action: TrajectoryOverlayView.() -> Unit,
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            MAIN_HANDLER.post { action() }
        }
    }

    /**
     * The main-thread trajectory view dispatcher.
     */
    private companion object {

        /**
         * The handler posting trajectory updates to the main thread.
         */
        val MAIN_HANDLER = Handler(Looper.getMainLooper())
    }
}
