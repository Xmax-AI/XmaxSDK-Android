//
//  TrajectoryOverlayView.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render.trajectory

import ai.xmax.sdk.DefaultTrajectoryEffectRenderer
import androidx.compose.ui.unit.IntSize
import ai.xmax.sdk.TrajectoryEffectRendering
import ai.xmax.sdk.TrajectoryID
import ai.xmax.sdk.TrajectoryPoint
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.media.interaction.InteractionCoordinateMapper
import ai.xmax.sdk.media.interaction.InteractionFrame
import android.content.Context
import android.graphics.PointF
import android.os.SystemClock
import android.util.SizeF
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Collects multitouch input, drives trajectory effects, and submits interaction frames at a fixed cadence.
 *
 * @param context The Android context; views should use the current UI theme's context.
 */
internal class TrajectoryOverlayView(
    context: Context,
) : FrameLayout(context) {

    /**
     * The active touch points.
     */
    private val activeTouches = mutableMapOf<Int, ActiveTouch>()

    /**
     * The current trajectory submission and coordinate mapping binding.
     */
    private var binding: TrajectoryBinding? = null

    /**
     * The underlying video's fit or fill mode.
     */
    private var videoContentMode = VideoContentMode.FILL

    /**
     * The media input dimensions used for trajectory normalization.
     */
    private var resolvedMediaSize: IntSize? = null

    /**
     * The current trajectory effect renderer.
     */
    private var renderer: TrajectoryEffectRendering = DefaultTrajectoryEffectRenderer(context)

    /**
     * Whether your app enables touch interaction.
     */
    private var requestedInteractionEnabled = true

    /**
     * The timestamp of the last touch sample.
     */
    private var lastSampleNanos = 0L

    /**
     * Whether a display frame callback is scheduled.
     */
    private var frameCallbackPosted = false

    /**
     * Samples trajectories and updates effects on each display frame.
     */
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameCallbackPosted = false
        submitIfNeeded(frameTimeNanos)
        if (activeTouches.isNotEmpty()) postFrameCallback()
    }

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        clipChildren = false
        addRendererView(renderer)
        updateInteractionState()
    }

    /**
     * Replaces the trajectory renderer and updates its view binding.
     *
     * @param renderer The component drawing trajectory effects.
     */
    fun setRenderer(
        renderer: TrajectoryEffectRendering,
    ) {
        cancelInteraction()
        this.renderer.reset()
        removeView(this.renderer.view)
        this.renderer = renderer
        addRendererView(renderer)
    }

    /**
     * Sets the interaction target, or null to unbind it.
     *
     * @param binding The track's display or interaction binding.
     */
    fun setBinding(
        binding: TrajectoryBinding?,
    ) {
        this.binding = binding
        if (binding == null) cancelInteraction()
        updateInteractionState()
    }

    /**
     * Unbinds only if the specified binding is still active.
     *
     * @param expected The expected current binding, protecting a later replacement from being unbound.
     */
    fun clearBinding(
        expected: TrajectoryBinding,
    ) {
        if (binding === expected) setBinding(null)
    }

    /**
     * Updates the video scaling mode used for trajectory coordinates.
     *
     * @param contentMode Aspect-preserving fit or fill scaling.
     */
    fun setContentMode(
        contentMode: VideoContentMode,
    ) {
        if (videoContentMode == contentMode) return
        videoContentMode = contentMode
        cancelInteraction()
        updateInteractionState()
    }

    /**
     * Updates resolved media dimensions and clears trajectories using the old coordinates.
     *
     * @param resolvedMediaSize The resolved local media dimensions, or null when unknown.
     */
    fun setResolvedMediaSize(
        resolvedMediaSize: IntSize?,
    ) {
        if (this.resolvedMediaSize == resolvedMediaSize) return
        this.resolvedMediaSize = resolvedMediaSize
        cancelInteraction()
        updateInteractionState()
    }

    /**
     * Sets whether your app permits touch interaction.
     *
     * @param enabled Whether the capability is enabled.
     */
    fun setRequestedInteractionEnabled(
        enabled: Boolean,
    ) {
        if (requestedInteractionEnabled == enabled) return
        requestedInteractionEnabled = enabled
        if (!enabled) cancelInteraction()
        updateInteractionState()
    }

    /**
     * Handles touch starts, movement, and endings, updating trajectory display and submission.
     *
     * @param event The Android touch event.
     * @return True if this view handled the touch event.
     */
    override fun onTouchEvent(
        event: MotionEvent,
    ): Boolean {
        val interactionFrame = interactionFrame()
        if (!isEnabled || !isClickable || interactionFrame == null) return false
        if ((event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) &&
            !interactionFrame.contains(
                event.getX(event.actionIndex),
                event.getY(event.actionIndex),
            )
        ) {
            return event.actionMasked != MotionEvent.ACTION_DOWN
        }
        val timestamp = nowSeconds()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> {
                val index = event.actionIndex
                val touch = ActiveTouch(
                    id = TrajectoryID(),
                    location = clamped(
                        PointF(
                            event.getX(index),
                            event.getY(index),
                        ),
                    ),
                )
                activeTouches[event.getPointerId(index)] = touch
                renderer.renderBegan(listOf(touch.asTrajectoryPoint(timestamp)))
                // Use Choreographer's clock, which excludes device deep sleep.
                submitIfNeeded(
                    System.nanoTime(),
                    force = true,
                )
                postFrameCallback()
            }
            MotionEvent.ACTION_MOVE -> {
                val moved = buildList {
                    for (index in 0 until event.pointerCount) {
                        val pointerId = event.getPointerId(index)
                        val touch = activeTouches[pointerId] ?: continue
                        val location = clamped(
                            PointF(
                                event.getX(index),
                                event.getY(index),
                            ),
                        )
                        if (distanceSquared(
                            touch.location,
                            location,
                        ) < MINIMUM_MOVE_SQUARED) continue
                        touch.location = location
                        add(touch.asTrajectoryPoint(timestamp))
                    }
                }
                if (moved.isNotEmpty()) renderer.renderMoved(moved)
            }
            MotionEvent.ACTION_UP -> {
                finishPointer(event.getPointerId(event.actionIndex))
                performClick()
            }
            MotionEvent.ACTION_POINTER_UP -> finishPointer(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> cancelInteraction()
        }
        return true
    }

    /**
     * Forwards view clicks for accessibility support.
     *
     * @return True if the click was handled.
     */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * Unbinds and stops display callbacks when the view leaves its window.
     */
    override fun onDetachedFromWindow() {
        cancelInteraction()
        super.onDetachedFromWindow()
    }

    /**
     * Updates drawing layout for new view dimensions.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     * @param oldWidth The previous view width, in pixels.
     * @param oldHeight The previous view height, in pixels.
     */
    override fun onSizeChanged(
        width: Int,
        height: Int,
        oldWidth: Int,
        oldHeight: Int,
    ) {
        super.onSizeChanged(
            width,
            height,
            oldWidth,
            oldHeight,
        )
        if (width != oldWidth || height != oldHeight) {
            cancelInteraction()
            updateInteractionState()
        }
    }

    /**
     * Ends the trajectory for a touch pointer.
     *
     * @param pointerId The Android touch pointer identifier.
     */
    private fun finishPointer(
        pointerId: Int,
    ) {
        val identifier = activeTouches.remove(pointerId)?.id ?: return
        renderer.renderEnded(listOf(identifier))
        if (activeTouches.isEmpty()) stopFrameCallback()
    }

    /**
     * Submits trajectories after the sampling interval or when forced.
     *
     * @param nowNanos The current monotonic time, in nanoseconds.
     * @param force Whether to submit immediately regardless of the interval; defaults to false.
     */
    private fun submitIfNeeded(
        nowNanos: Long,
        force: Boolean = false,
    ) {
        if (activeTouches.isEmpty()) return
        if (!force && nowNanos - lastSampleNanos < SAMPLE_INTERVAL_NANOS) return
        lastSampleNanos = nowNanos
        binding?.submit(
            InteractionFrame(
                points = activeTouches.values.map { PointF(
                    it.location.x,
                    it.location.y,
                ) },
                viewportSize = SizeF(
                    width.toFloat(),
                    height.toFloat(),
                ),
                contentMode = videoContentMode,
            ),
        )
    }

    /**
     * Ends active touch points and clears trajectory effects.
     */
    private fun cancelInteraction() {
        val identifiers = activeTouches.values.map(ActiveTouch::id)
        activeTouches.clear()
        if (identifiers.isNotEmpty()) renderer.renderEnded(identifiers)
        renderer.reset()
        lastSampleNanos = 0L
        stopFrameCallback()
    }

    /**
     * Updates interaction availability from the binding, format, and app setting.
     */
    private fun updateInteractionState() {
        isEnabled = requestedInteractionEnabled && binding != null && interactionFrame() != null
        isClickable = isEnabled
    }

    /**
     * Calculates the interactive intersection of media and viewport bounds.
     *
     * @return The touchable viewport region, or null for invalid dimensions or no intersection.
     */
    private fun interactionFrame() = resolvedMediaSize?.let { size ->
        InteractionCoordinateMapper.displayedFrame(
            viewportSize = SizeF(
                width.toFloat(),
                height.toFloat(),
            ),
            resolvedMediaSize = SizeF(
                size.width.toFloat(),
                size.height.toFloat(),
            ),
            contentMode = videoContentMode,
        )?.let { displayed ->
            android.graphics.RectF(
                displayed.left.coerceAtLeast(0f),
                displayed.top.coerceAtLeast(0f),
                displayed.right.coerceAtMost(width.toFloat()),
                displayed.bottom.coerceAtMost(height.toFloat()),
            ).takeUnless { it.isEmpty }
        }
    }

    /**
     * Clamps a touch point to the displayed video region.
     *
     * @param point The touch location in preview coordinates, in pixels.
     * @return The clamped point in view pixels.
     */
    private fun clamped(
        point: PointF,
    ): PointF {
        val frame = interactionFrame() ?: return point
        return PointF(
            point.x.coerceIn(
                frame.left,
                frame.right,
            ),
            point.y.coerceIn(
                frame.top,
                frame.bottom,
            ),
        )
    }

    /**
     * Schedules trajectory sampling on the next display frame.
     */
    private fun postFrameCallback() {
        if (frameCallbackPosted) return
        frameCallbackPosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * Cancels display-frame trajectory sampling.
     */
    private fun stopFrameCallback() {
        if (!frameCallbackPosted) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameCallbackPosted = false
    }

    /**
     * Adds the renderer's view to the interaction overlay.
     *
     * @param renderer The component drawing trajectory effects.
     */
    private fun addRendererView(
        renderer: TrajectoryEffectRendering,
    ) {
        val rendererView = renderer.view
        (rendererView.parent as? android.view.ViewGroup)?.removeView(rendererView)
        rendererView.isClickable = false
        addView(
            rendererView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /**
     * The identifier and position of an active touch point.
     */
    private data class ActiveTouch(
        /**
         * The continuous-drag identifier for this pointer.
         */
        val id: TrajectoryID,

        /**
         * The touch position in view pixels.
         */
        var location: PointF,
    )

    /**
     * Converts a touch position to a timestamped trajectory point.
     *
     * @param timestamp The sample time, in seconds.
     * @return The trajectory identifier, position, and timestamp.
     */
    private fun ActiveTouch.asTrajectoryPoint(
        timestamp: Double,
    ): TrajectoryPoint {
        val frame = resolvedMediaSize?.let { size ->
            InteractionCoordinateMapper.displayedFrame(
                viewportSize = SizeF(
                    width.toFloat(),
                    height.toFloat(),
                ),
                resolvedMediaSize = SizeF(
                    size.width.toFloat(),
                    size.height.toFloat(),
                ),
                contentMode = videoContentMode,
            )
        }
        val normalized = if (frame != null && frame.width() > 0f && frame.height() > 0f) {
            PointF(
                (location.x - frame.left) / frame.width(),
                (location.y - frame.top) / frame.height(),
            )
        } else {
            PointF(
                0f,
                0f,
            )
        }
        return TrajectoryPoint(
            id = id,
            location = PointF(
                location.x,
                location.y,
            ),
            normalizedLocation = normalized,
            timestamp = timestamp,
        )
    }

    /**
     * Touch sampling intervals and movement thresholds.
     */
    private companion object {

        /**
         * The minimum trajectory sampling interval, in nanoseconds.
         */
        const val SAMPLE_INTERVAL_NANOS = 33_333_333L

        /**
         * The minimum squared movement distance, in square pixels.
         */
        const val MINIMUM_MOVE_SQUARED = 0.25f

        /**
         * Computes squared point distance for movement threshold comparisons.
         *
         * @param first The first point.
         * @param second The second point.
         * @return The squared distance, in square pixels.
         */
        fun distanceSquared(
            first: PointF,
            second: PointF,
        ): Float {
            val dx = second.x - first.x
            val dy = second.y - first.y
            return dx * dx + dy * dy
        }

        /**
         * Reads the monotonic clock used by trajectory animation, in seconds.
         *
         * @return The current monotonic time, in seconds.
         */
        fun nowSeconds(): Double = SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0
    }
}
