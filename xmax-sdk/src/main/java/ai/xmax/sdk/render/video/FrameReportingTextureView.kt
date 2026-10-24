//
//  FrameReportingTextureView.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView

/**
 * Observes presentation without replacing the RTC renderer's surface lifecycle callbacks.
 *
 * @param context The Android context; views should use the current UI theme's context.
 */
internal class FrameReportingTextureView(
    context: Context,
) : TextureView(context) {

    /**
     * The frame presentation callback.
     */
    var onFrameDisplayed: ((FrameReportingTextureView) -> Unit)? = null

    /**
     * The renderer's texture lifecycle listener.
     */
    private var rendererListener: SurfaceTextureListener? = null

    /**
     * Whether the texture is awaiting first-frame presentation.
     */
    private var frameCheckPending = false

    /**
     * Whether this texture has displayed a valid video frame.
     */
    private var hasDisplayedFrame = false

    /**
     * The task checking first-frame presentation.
     */
    private val frameCheck = Runnable {
        frameCheckPending = false
        val surface = surfaceTexture
        // Checks for a valid texture frame after drawing before notifying first-frame presentation.
        if (!hasDisplayedFrame && isAttachedToWindow && surface != null &&
            !surface.isReleased && surface.timestamp != 0L
        ) {
            hasDisplayedFrame = true
            onFrameDisplayed?.invoke(this)
        }
    }

    init {
        super.setSurfaceTextureListener(
            object : SurfaceTextureListener {

                /**
                 * Binds video output once the preview texture is available.
                 *
                 * @param surface The current preview texture.
                 * @param width The frame width, in pixels.
                 * @param height The frame height, in pixels.
                 */
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    rendererListener?.onSurfaceTextureAvailable(
                        surface,
                        width,
                        height,
                    )
                }

                /**
                 * Updates video layout after preview dimensions change.
                 *
                 * @param surface The current preview texture.
                 * @param width The frame width, in pixels.
                 * @param height The frame height, in pixels.
                 */
                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    rendererListener?.onSurfaceTextureSizeChanged(
                        surface,
                        width,
                        height,
                    )
                }

                /**
                 * Detaches a destroyed texture and determines who releases it.
                 *
                 * @param surface The current preview texture.
                 * @return Whether TextureView should release the texture.
                 */
                override fun onSurfaceTextureDestroyed(
                    surface: SurfaceTexture,
                ): Boolean {
                    removeCallbacks(frameCheck)
                    frameCheckPending = false
                    hasDisplayedFrame = false
                    return rendererListener?.onSurfaceTextureDestroyed(surface) ?: true
                }

                /**
                 * Receives a texture content update.
                 *
                 * @param surface The current preview texture.
                 */
                override fun onSurfaceTextureUpdated(
                    surface: SurfaceTexture,
                ) {
                    rendererListener?.onSurfaceTextureUpdated(surface)
                    if (surface === surfaceTexture && !hasDisplayedFrame && !frameCheckPending) {
                        frameCheckPending = true
                        postOnAnimation(frameCheck)
                    }
                }
            },
        )
    }

    /**
     * Sets the preview texture lifecycle listener.
     *
     * @param listener The event listener, or null to unregister it.
     */
    override fun setSurfaceTextureListener(
        listener: SurfaceTextureListener?,
    ) {
        rendererListener = listener
    }

    /**
     * Returns the app-provided texture listener.
     *
     * @return The app-provided texture listener, or null if unset.
     *
     * Return only the external registration to avoid callback cycles when RTC wraps the listener.
     */
    override fun getSurfaceTextureListener(): SurfaceTextureListener? = rendererListener

    /**
     * Unbinds and stops display callbacks when the view leaves its window.
     */
    override fun onDetachedFromWindow() {
        removeCallbacks(frameCheck)
        frameCheckPending = false
        super.onDetachedFromWindow()
    }
}
