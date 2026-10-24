//
//  XmaxVideoView.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.render.trajectory.TrajectoryBinding
import ai.xmax.sdk.render.trajectory.TrajectoryOverlayView
import ai.xmax.sdk.render.trajectory.TrajectoryRegistry
import ai.xmax.sdk.render.video.FrameReportingTextureView
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * An Android container displaying a local or remote realtime video track.
 *
 * @param context The Android context; views should use the current UI theme's context.
 * @param attrs The XML view attributes; optional for programmatic construction.
 * @param defStyleAttr The default style attribute resource.
 */
public class XmaxVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(
    context,
    attrs,
    defStyleAttr,
) {

    /**
     * The RTC video view.
     */
    private var renderView: FrameReportingTextureView? = null

    /**
     * The image view displaying still images or decoded local frames.
     */
    private val imageView = ImageView(context)

    /**
     * The overlay collecting touches and drawing trajectory effects.
     */
    private val trajectoryOverlayView = TrajectoryOverlayView(context)

    /**
     * The handler for main-thread presentation callbacks.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The current preview image.
     */
    private var displayedBitmap: Bitmap? = null

    /**
     * The active track's rendering binding.
     */
    private var attachedBinding: VideoRenderBinding? = null

    /**
     * The active track's trajectory binding.
     */
    private var attachedTrajectoryBinding: TrajectoryBinding? = null

    /**
     * The current display revision.
     */
    private var renderGeneration = 0L

    /**
     * Whether the current binding has displayed a valid frame.
     */
    private var hasDisplayedFrame = false

    /**
     * The main-thread task confirming frame presentation.
     */
    private var pendingFrameDisplay: Runnable? = null

    /**
     * Internal presentation signal; this is separate from RTC decode readiness.
     */
    internal var frameDisplayHandler: ((RealtimeVideoTrack) -> Unit)? = null

    /**
     * The callback requesting a display transition when the current image becomes invalid.
     */
    internal var frameInvalidationHandler: ((RealtimeVideoTrack) -> Unit)? = null

    /**
     * Whether the current track may be marked as presented.
     */
    internal var isFrameDisplayEnabled = false
        private set

    /**
     * The currently displayed video track.
     */
    public var track: RealtimeVideoTrack? = null
        set(
            value,
        ) {
            if (field === value) return

            detachCurrentTrack()
            field = value
            attachCurrentTrackIfNeeded()
        }

    /**
     * The video's scaling mode within the container.
     */
    public var videoContentMode: VideoContentMode = VideoContentMode.FILL
        set(
            value,
        ) {
            if (field == value) return

            detachCurrentTrack()
            field = value
            attachCurrentTrackIfNeeded()
        }

    /**
     * Whether remote trajectory effects and submission are enabled.
     */
    public var isInteractionEnabled: Boolean = true
        set(
            value,
        ) {
            if (field == value) return

            field = value
            trajectoryOverlayView.setRequestedInteractionEnabled(value)
        }

    /**
     * The custom trajectory effect; null restores the SDK renderer.
     */
    public var trajectoryRenderer: TrajectoryEffectRendering? = null
        set(
            value,
        ) {
            field = value
            trajectoryOverlayView.setRenderer(value ?: DefaultTrajectoryEffectRenderer(context))
        }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        addView(
            imageView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            trajectoryOverlayView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            ),
        )
        imageView.visibility = View.GONE
    }

    /**
     * The rendering view supplied to RTC.
     */
    internal val rtcRenderView: View
        get() = checkNotNull(renderView) { "RTC rendering has not been prepared" }

    /**
     * Prepares a texture view for network video preview.
     *
     * @return The texture view used for network video preview.
     */
    internal fun prepareNetworkVideoRendering(): android.view.TextureView {
        prepareRtcVideoRendering()
        return checkNotNull(renderView)
    }

    /**
     * Prepares the view required for RTC video rendering.
     */
    internal fun prepareRtcVideoRendering() {
        invalidateVideoPresentation()
        clearImageFrame()
        removeRtcRenderView()
        // Each track binding gets a separate rendering surface, keeping first-frame notifications scoped to that track.
        val generation = renderGeneration
        renderView = FrameReportingTextureView(context).also { textureView ->
            textureView.onFrameDisplayed = { source ->
                if (renderView === source && renderGeneration == generation) {
                    scheduleFrameDisplayed()
                }
            }
            addView(
                textureView,
                0,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT,
                ),
            )
        }
        isFrameDisplayEnabled = true
    }

    /**
     * Restore local preview before releasing remote rendering resources to avoid a blank frame.
     */
    internal fun invalidateVideoPresentation() {
        renderGeneration += 1L
        isFrameDisplayEnabled = false
        hasDisplayedFrame = false
        pendingFrameDisplay?.let(mainHandler::removeCallbacks)
        pendingFrameDisplay = null
        renderView?.onFrameDisplayed = null
        track?.let { frameInvalidationHandler?.invoke(it) }
    }

    /**
     * Displays an image frame with the requested scaling mode.
     *
     * @param frame The video frame to process, display, or submit.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    internal fun displayImageFrame(
        frame: VideoFrame,
        contentMode: VideoContentMode,
    ) {
        displayBitmap(
            makeBitmap(frame),
            contentMode,
        )
    }

    /**
     * Prepares the image preview layer for decoded local video.
     *
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    internal fun prepareDecodedVideoPreview(
        contentMode: VideoContentMode,
    ) {
        imageView.scaleType = when (contentMode) {
            VideoContentMode.FIT -> ImageView.ScaleType.FIT_CENTER
            VideoContentMode.FILL -> ImageView.ScaleType.CENTER_CROP
        }
        renderView?.visibility = View.GONE
    }

    /**
     * Updates the current decoded local video preview image.
     *
     * @param bitmap The Android image to process or display.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    internal fun displayDecodedVideoBitmap(
        bitmap: Bitmap,
        contentMode: VideoContentMode,
    ) {
        displayBitmap(
            bitmap,
            contentMode,
        )
    }

    /**
     * Displays an image in the preview layer using the requested scaling mode.
     *
     * @param bitmap The Android image to process or display.
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    private fun displayBitmap(
        bitmap: Bitmap,
        contentMode: VideoContentMode,
    ) {
        isFrameDisplayEnabled = true
        displayedBitmap?.recycle()
        displayedBitmap = bitmap
        imageView.scaleType = when (contentMode) {
            VideoContentMode.FIT -> ImageView.ScaleType.FIT_CENTER
            VideoContentMode.FILL -> ImageView.ScaleType.CENTER_CROP
        }
        imageView.setImageBitmap(bitmap)
        imageView.visibility = View.VISIBLE
        renderView?.visibility = View.GONE
    }

    /**
     * Clears the decoded local video preview image.
     */
    internal fun clearDecodedVideoPreview() {
        clearImageFrame()
    }

    /**
     * Clears the current image preview.
     */
    internal fun clearImageFrame() {
        val bitmapToClear = displayedBitmap
        val action: () -> Unit = action@{
            if (displayedBitmap !== bitmapToClear) return@action

            imageView.setImageDrawable(null)
            imageView.visibility = View.GONE
            displayedBitmap?.recycle()
            displayedBitmap = null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.invoke()
        } else {
            mainHandler.post(action)
        }
    }

    /**
     * Restores display bindings when the view attaches to a window.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachCurrentTrackIfNeeded()
    }

    /**
     * Unbinds and stops display callbacks when the view leaves its window.
     */
    override fun onDetachedFromWindow() {
        detachCurrentTrack()
        super.onDetachedFromWindow()
    }

    /**
     * Draws child views and reports submitted presentation.
     *
     * @param canvas The canvas drawing this view.
     */
    override fun dispatchDraw(
        canvas: Canvas,
    ) {
        super.dispatchDraw(canvas)
        if (imageView.visibility == View.VISIBLE && displayedBitmap != null) {
            scheduleFrameDisplayed()
        }
    }

    /**
     * Attaches rendering for the current unbound track.
     */
    private fun attachCurrentTrackIfNeeded() {
        if (!isAttachedToWindow) return

        val currentTrack = track ?: return
        VideoRenderRegistry.binding(currentTrack)?.let { binding ->
            runCatching {
                binding.attach(
                    this,
                    videoContentMode,
                )
                attachedBinding = binding
            }.onFailure { error ->
                attachedBinding = null
                XmaxLogger.render.error(
                    message = {
                        "绑定视频渲染视图失败 (Failed to Attach Video Render View)\n" +
                            "└─ ${XmaxLogger.localized(
                                "原因：",
                                "Reason: ",
                            )}${ErrorMessageFormatter.format(error)}"
                    },
                )
            }
        }
        TrajectoryRegistry.binding(currentTrack)?.let { binding ->
            binding.attach(
                trajectoryOverlayView,
                videoContentMode,
            )
            attachedTrajectoryBinding = binding
        }
        trajectoryOverlayView.bringToFront()
    }

    /**
     * Detaches rendering for the current track.
     */
    private fun detachCurrentTrack() {
        invalidateVideoPresentation()
        attachedBinding?.let { binding ->
            runCatching { binding.detach(this) }
                .onFailure { error ->
                    XmaxLogger.render.error(
                        message = {
                            "解绑视频渲染视图失败 (Failed to Detach Video Render View)\n" +
                                "└─ ${XmaxLogger.localized(
                                    "原因：",
                                    "Reason: ",
                                )}${ErrorMessageFormatter.format(error)}"
                        },
                    )
                }
        }
        attachedTrajectoryBinding?.detach(trajectoryOverlayView)
        attachedBinding = null
        attachedTrajectoryBinding = null
        removeRtcRenderView()
        clearImageFrame()
    }

    /**
     * Removes the RTC rendering view and clears its reference.
     */
    private fun removeRtcRenderView() {
        renderView?.let { textureView ->
            textureView.onFrameDisplayed = null
            removeView(textureView)
        }
        renderView = null
    }

    /**
     * Schedules presentation notification after the view draws.
     */
    private fun scheduleFrameDisplayed() {
        if (!isFrameDisplayEnabled || hasDisplayedFrame || pendingFrameDisplay != null || attachedBinding == null) return

        val currentTrack = track ?: return
        val generation = renderGeneration
        pendingFrameDisplay = Runnable {
            pendingFrameDisplay = null
            if (isFrameDisplayEnabled && renderGeneration == generation && track === currentTrack && isAttachedToWindow) {
                hasDisplayedFrame = true
                frameDisplayHandler?.invoke(currentTrack)
            }
        }.also(mainHandler::post)
    }

    /**
     * Converts an SDK video frame to a preview Bitmap.
     *
     * @param frame The video frame to process, display, or submit.
     * @return An image for local display.
     */
    private fun makeBitmap(
        frame: VideoFrame,
    ): Bitmap {
        if (frame.format.pixelFormat != VideoPixelFormat.RGBA || frame.planes.size != 1) {
            throw IllegalArgumentException("Image preview requires a single RGBA video plane")
        }

        val width = frame.format.width
        val height = frame.format.height
        val plane = frame.planes.single()
        val bytes = plane.selectedBytesView()
        require(bytes.size >= width * height * RGBA_BYTES_PER_PIXEL) {
            "Image preview RGBA plane is smaller than its declared format"
        }
        val pixels = IntArray(width * height)
        pixels.indices.forEach { index ->
            val offset = index * RGBA_BYTES_PER_PIXEL
            val red = bytes[offset].toInt() and 0xFF
            val green = bytes[offset + 1].toInt() and 0xFF
            val blue = bytes[offset + 2].toInt() and 0xFF
            val alpha = bytes[offset + 3].toInt() and 0xFF
            pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }

        return Bitmap.createBitmap(
            pixels,
            width,
            height,
            Bitmap.Config.ARGB_8888,
        )
    }

    /**
     * Video pixel layout constants.
     */
    private companion object {

        /**
         * The number of bytes per RGBA pixel.
         */
        const val RGBA_BYTES_PER_PIXEL = 4
    }
}
