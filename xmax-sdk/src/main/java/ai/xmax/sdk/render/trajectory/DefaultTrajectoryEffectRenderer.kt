//
//  DefaultTrajectoryEffectRenderer.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The built-in glowing trajectory effect.
 *
 * @param context The Android context; views should use the current UI theme's context.
 */
public class DefaultTrajectoryEffectRenderer(
    context: Context,
) : View(context),
    TrajectoryEffectRendering {

    /**
     * The effect view hosted by the interaction overlay.
     */
    override val view: View
        get() = this

    /**
     * The positions and animation state of active trajectories.
     */
    private val activeTrajectories = mutableMapOf<TrajectoryID, ActiveTrajectory>()

    /**
     * The paint used to draw trajectory effects.
     */
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The blend mode used to overlay trajectory light effects.
     */
    private val additiveXfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)

    /**
     * The offscreen trail buffer.
     */
    private var trailBitmap: Bitmap? = null

    /**
     * The canvas drawing into the trail buffer.
     */
    private var trailCanvas: Canvas? = null

    /**
     * Whether the trail buffer still contains visible pixels.
     */
    private var hasTrailPixels = false

    /**
     * The trail decay frame count since movement stopped.
     */
    private var idleFadeFrameCount = 0

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
    }

    /**
     * Starts drawing newly added trajectories.
     *
     * @param points Trajectory points with positions and timestamps.
     */
    override fun renderBegan(
        points: List<TrajectoryPoint>,
    ) {
        points.forEach { point ->
            activeTrajectories[point.id] = ActiveTrajectory(
                location = PointF(
                    point.location.x,
                    point.location.y,
                ),
                startTime = point.timestamp,
            )
        }
        invalidate()
    }

    /**
     * Updates the positions of visible trajectories.
     *
     * @param points Trajectory points with positions and timestamps.
     */
    override fun renderMoved(
        points: List<TrajectoryPoint>,
    ) {
        points.forEach { point ->
            val trajectory = activeTrajectories[point.id]
            if (trajectory == null) {
                activeTrajectories[point.id] = ActiveTrajectory(
                    location = PointF(
                        point.location.x,
                        point.location.y,
                    ),
                    startTime = point.timestamp,
                )
                return@forEach
            }

            if (distanceSquared(
                trajectory.location,
                point.location,
            ) >= MINIMUM_MOVE_SQUARED) {
                drawTrailSegment(
                    trajectory.location,
                    point.location,
                )
            }
            trajectory.location = PointF(
                point.location.x,
                point.location.y,
            )
        }
        invalidate()
    }

    /**
     * Ends display of the specified trajectories.
     *
     * @param identifiers The trajectory identifiers to end.
     */
    override fun renderEnded(
        identifiers: List<TrajectoryID>,
    ) {
        identifiers.forEach(activeTrajectories::remove)
        invalidate()
    }

    /**
     * Clears all trajectories and trails.
     */
    override fun reset() {
        activeTrajectories.clear()
        clearTrailBitmap()
        invalidate()
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
        rebuildTrailBitmap(
            width,
            height,
        )
    }

    /**
     * Restores display bindings when the view attaches to a window.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (trailBitmap == null) rebuildTrailBitmap(
            width,
            height,
        )
    }

    /**
     * Unbinds and stops display callbacks when the view leaves its window.
     */
    override fun onDetachedFromWindow() {
        reset()
        trailBitmap?.recycle()
        trailBitmap = null
        trailCanvas = null
        super.onDetachedFromWindow()
    }

    /**
     * Draws trajectory trails, glows, and animation effects.
     *
     * @param canvas The canvas drawing this view.
     */
    override fun onDraw(
        canvas: Canvas,
    ) {
        super.onDraw(canvas)
        fadeTrailBitmap()
        trailBitmap?.let { canvas.drawBitmap(
            it,
            0f,
            0f,
            null,
        ) }

        val now = SystemClock.elapsedRealtimeNanos() / NANOS_PER_SECOND
        activeTrajectories.values.forEach { trajectory ->
            drawPulsingRings(
                canvas,
                trajectory,
                now,
            )
            drawOrbitParticles(
                canvas,
                trajectory,
                now,
            )
            drawHeadGlow(
                canvas,
                trajectory.location,
            )
        }

        if (hasTrailPixels || activeTrajectories.isNotEmpty()) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Rebuilds the trail buffer for the current view dimensions.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     */
    private fun rebuildTrailBitmap(
        width: Int,
        height: Int,
    ) {
        trailBitmap?.recycle()
        trailBitmap = null
        trailCanvas = null
        hasTrailPixels = false
        idleFadeFrameCount = 0
        if (width <= 0 || height <= 0) return

        trailBitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888,
        ).also { bitmap ->
            trailCanvas = Canvas(bitmap)
        }
    }

    /**
     * Draws a glowing trail between adjacent touch positions.
     *
     * @param start The segment start in view pixels.
     * @param end The segment end in view pixels.
     */
    private fun drawTrailSegment(
        start: PointF,
        end: PointF,
    ) {
        val canvas = trailCanvas ?: return
        drawLine(
            canvas,
            start,
            end,
            dp(18f),
            GLOW_COLOR,
            0.22f,
            dp(12f),
        )
        drawLine(
            canvas,
            start,
            end,
            dp(10f),
            GLOW_COLOR,
            0.52f,
            dp(6f),
        )
        drawLine(
            canvas,
            start,
            end,
            dp(3f),
            Color.WHITE,
            0.82f,
            0f,
        )
        hasTrailPixels = true
        idleFadeFrameCount = 0
    }

    /**
     * Draws a segment with the requested width, opacity, and blur radius.
     *
     * @param canvas The canvas drawing this view.
     * @param start The segment start in view pixels.
     * @param end The segment end in view pixels.
     * @param width The frame width, in pixels.
     * @param color The ARGB color.
     * @param alpha The opacity, from 0 to 1.
     * @param blur The blur radius, in pixels.
     */
    private fun drawLine(
        canvas: Canvas,
        start: PointF,
        end: PointF,
        width: Float,
        color: Int,
        alpha: Float,
        blur: Float,
    ) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = width
        paint.color = color
        paint.alpha = (alpha * 255f).toInt()
        paint.maskFilter = if (blur > 0f) BlurMaskFilter(
            blur,
            BlurMaskFilter.Blur.NORMAL,
        ) else null
        paint.xfermode = additiveXfermode
        canvas.drawLine(
            start.x,
            start.y,
            end.x,
            end.y,
            paint,
        )
        paint.xfermode = null
        paint.maskFilter = null
    }

    /**
     * Fades existing trails by reducing their opacity.
     */
    private fun fadeTrailBitmap() {
        if (!hasTrailPixels) return

        trailCanvas?.drawColor(
            FADE_COLOR,
            PorterDuff.Mode.DST_OUT,
        )
        if (activeTrajectories.isNotEmpty()) {
            idleFadeFrameCount = 0
            return
        }

        idleFadeFrameCount += 1
        if (idleFadeFrameCount > IDLE_FADE_FRAME_LIMIT) clearTrailBitmap()
    }

    /**
     * Clears the trail buffer.
     */
    private fun clearTrailBitmap() {
        trailCanvas?.drawColor(
            Color.TRANSPARENT,
            PorterDuff.Mode.CLEAR,
        )
        hasTrailPixels = false
        idleFadeFrameCount = 0
    }

    /**
     * Draws animated rings around a touch point.
     *
     * @param canvas The canvas drawing this view.
     * @param trajectory The active trajectory being drawn.
     * @param now The current monotonic time, in seconds.
     */
    private fun drawPulsingRings(
        canvas: Canvas,
        trajectory: ActiveTrajectory,
        now: Double,
    ) {
        val elapsed = (now - trajectory.startTime).toFloat()
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = GLOW_COLOR
        paint.xfermode = additiveXfermode
        repeat(2) { index ->
            val pulse = ((sin((elapsed * 1.2f + index * 0.5f) * PI * 2) + 1) / 2).toFloat()
            val radius = dp(14f + index * 18f + pulse * 8f)
            val alpha = 0.5f * (1f - index * 0.2f) * (0.5f + pulse * 0.5f)
            paint.alpha = (alpha * 255f).toInt()
            canvas.drawCircle(
                trajectory.location.x,
                trajectory.location.y,
                radius,
                paint,
            )
        }
        paint.xfermode = null
    }

    /**
     * Draws particles moving around a touch point.
     *
     * @param canvas The canvas drawing this view.
     * @param trajectory The active trajectory being drawn.
     * @param now The current monotonic time, in seconds.
     */
    private fun drawOrbitParticles(
        canvas: Canvas,
        trajectory: ActiveTrajectory,
        now: Double,
    ) {
        val elapsed = (now - trajectory.startTime).toFloat()
        repeat(4) { index ->
            val direction = if (index % 2 == 0) 1f else -1f
            val baseAngle = index.toFloat() / 4f * PI.toFloat() * 2f
            val angle = baseAngle + elapsed * 0.06f * direction
            val centerX = trajectory.location.x + cos(angle) * dp(22f)
            val centerY = trajectory.location.y + sin(angle) * dp(22f)
            val alpha = 0.6f * (0.6f + sin(elapsed * 3f + index) * 0.4f)
            drawRadialGlow(
                canvas,
                centerX,
                centerY,
                dp(6f),
                GLOW_COLOR,
                alpha,
            )
        }
    }

    /**
     * Draws the glow at the trajectory head.
     *
     * @param canvas The canvas drawing this view.
     * @param location The touch position in view pixels.
     */
    private fun drawHeadGlow(
        canvas: Canvas,
        location: PointF,
    ) {
        drawRadialGlow(
            canvas,
            location.x,
            location.y,
            dp(16f),
            GLOW_COLOR,
            0.9f,
        )
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.xfermode = additiveXfermode
        canvas.drawCircle(
            location.x,
            location.y,
            dp(5f),
            paint,
        )
        paint.xfermode = null
    }

    /**
     * Draws a radial glow with the requested position, radius, and opacity.
     *
     * @param canvas The canvas drawing this view.
     * @param centerX The horizontal effect center, in pixels.
     * @param centerY The vertical effect center, in pixels.
     * @param radius The effect radius, in pixels.
     * @param color The ARGB color.
     * @param alpha The opacity, from 0 to 1.
     */
    private fun drawRadialGlow(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        color: Int,
        alpha: Float,
    ) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(
                colorWithAlpha(
                    color,
                    alpha,
                ),
                colorWithAlpha(
                    color,
                    alpha * 0.6f,
                ),
                Color.TRANSPARENT,
            ),
            floatArrayOf(
                0f,
                0.4f,
                1f,
            ),
            Shader.TileMode.CLAMP,
        )
        paint.xfermode = additiveXfermode
        canvas.drawCircle(
            centerX,
            centerY,
            radius,
            paint,
        )
        paint.xfermode = null
        paint.shader = null
    }

    /**
     * Converts density-independent pixels to device pixels.
     *
     * @param value The density-independent pixel value.
     * @return The device pixel value.
     */
    private fun dp(
        value: Float,
    ): Float = value * resources.displayMetrics.density

    /**
     * The position and animation origin of an active trajectory.
     */
    private data class ActiveTrajectory(
        /**
         * The touch position in view pixels.
         */
        var location: PointF,

        /**
         * The monotonic trajectory start time, in seconds.
         */
        val startTime: Double,
    )

    /**
     * Trajectory color, sampling, and decay settings.
     */
    private companion object {

        /**
         * The base trajectory glow color.
         */
        const val GLOW_COLOR: Int = -16_711_836

        /**
         * The minimum squared movement distance, in square pixels.
         */
        const val MINIMUM_MOVE_SQUARED = 0.25f

        /**
         * The maximum trail decay frame count after movement stops.
         */
        const val IDLE_FADE_FRAME_LIMIT = 64

        /**
         * The number of nanoseconds per second.
         */
        const val NANOS_PER_SECOND = 1_000_000_000.0

        /**
         * The translucent color used for each trail decay frame.
         */
        val FADE_COLOR: Int = Color.argb(
            (0.05f * 255f).toInt(),
            0,
            0,
            0,
        )

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
         * Applies opacity to a color.
         *
         * @param color The ARGB color.
         * @param alpha The opacity, from 0 to 1.
         * @return The ARGB color with the requested opacity.
         */
        fun colorWithAlpha(
            color: Int,
            alpha: Float,
        ): Int {
            return Color.argb(
                (alpha.coerceIn(
                    0f,
                    1f,
                ) * 255f).toInt(),
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            )
        }
    }
}
