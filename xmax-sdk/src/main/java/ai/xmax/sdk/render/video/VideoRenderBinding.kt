//
//  VideoRenderBinding.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.video

import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxRealtimeVideoView
import java.lang.ref.WeakReference

/**
 * Binds realtime tracks to Android rendering views.
 */
internal class VideoRenderBinding(
    /**
     * The function binding a track to a view and scaling mode.
     */
    private val attachHandler: (XmaxRealtimeVideoView, VideoContentMode) -> Unit,

    /**
     * The function detaching a track's view.
     */
    private val detachHandler: (XmaxRealtimeVideoView) -> Unit,
) {

    /**
     * Bindings between tracks and display components.
     */
    private var attachedView: WeakReference<XmaxRealtimeVideoView>? = null

    /**
     * Creates a video rendering binding.
     *
     * @param imageFrame The image frame used for local preview.
     */
    constructor(
        imageFrame: VideoFrame,
    ) : this(
        attachHandler = { view, contentMode ->
            view.displayImageFrame(
                imageFrame,
                contentMode,
            )
        },
        detachHandler = XmaxRealtimeVideoView::clearImageFrame,
    )

    /**
     * Binds a track to a preview view.
     *
     * @param view The view to bind or unbind.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    @Synchronized
    fun attach(
        view: XmaxRealtimeVideoView,
        contentMode: VideoContentMode,
    ) {
        val previousView = attachedView?.get()
        if (previousView != null && previousView !== view) {
            detachHandler(previousView)
            attachedView = null
        }

        attachHandler(
            view,
            contentMode,
        )
        attachedView = WeakReference(view)
    }

    /**
     * Unbinds a track from a view.
     *
     * @param view The view to bind or unbind.
     */
    @Synchronized
    fun detach(
        view: XmaxRealtimeVideoView,
    ) {
        if (attachedView?.get() === view) detach()
    }

    /**
     * Unbinds a track from a view.
     */
    @Synchronized
    fun detach() {
        attachedView?.get()?.let(detachHandler)
        attachedView = null
    }
}
