//
//  XmaxRealtimeVideoView.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.MainThread

/**
 * A realtime video container whose tracks must be set on the main thread; keeps local preview attached until
 * remote video fades in.
 * Clearing [remoteTrack] or stopping SDK remote rendering immediately restores local preview.
 * This view does not start or stop media streams.
 *
 * @param context The Android context; views should use the current UI theme's context.
 * @param attrs The XML view attributes; optional for programmatic construction.
 * @param defStyleAttr The default style attribute resource.
 */
@MainThread
public class XmaxRealtimeVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(
    context,
    attrs,
    defStyleAttr,
) {

    /**
     * The local preview video container.
     */
    private val localVideoView = XmaxVideoView(context).apply {
        isInteractionEnabled = false
    }

    /**
     * The downlink view displaying generation results.
     */
    private val remoteVideoView = XmaxVideoView(context).apply {
        isInteractionEnabled = false
    }

    /**
     * Whether remote video is currently visible.
     */
    private var isRemoteDisplayed = false

    /**
     * The display revision for preview-to-result transitions.
     */
    private var presentationVersion = 0L

    /**
     * The optional app-owned local preview view.
     *
     * An app-owned local preview that replaces localTrack's rendering surface.
     * Session recreation retains this view, and remote first-frame readiness uses the same fade-in
     * transition.
     * The view must have no parent or already be a direct child of this container.
     */
    public var localPreviewView: View? = null
        set(
            value,
        ) {
            if (field === value) return
            require(value !== this && (value?.parent == null || value.parent === this))
            field?.let(::removeView)
            field = value
            localVideoView.track = localTrack.takeIf { value == null }
            localVideoView.visibility = if (value == null) View.VISIBLE else View.GONE
            if (value != null && value.parent == null) {
                addView(
                    value,
                    LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            showLocalPreview()
        }

    /**
     * The local input track, whose preview stays attached while remote video is visible.
     */
    public var localTrack: RealtimeVideoTrack? = null
        set(
            value,
        ) {
            if (field === value) return
            field = value
            localVideoView.track = value.takeIf { localPreviewView == null }
        }

    /**
     * The remote generation track; replacements await a new first frame, and null immediately restores
     * preview.
     */
    public var remoteTrack: RealtimeVideoTrack? = null
        set(
            value,
        ) {
            if (field === value) return
            field = value
            showLocalPreview()
            remoteVideoView.track = value
        }

    /**
     * The aspect-preserving scaling mode for video.
     *
     * Applies to both local and remote video.
     */
    public var videoContentMode: VideoContentMode = VideoContentMode.FILL
        set(
            value,
        ) {
            if (field == value) return
            field = value
            showLocalPreview()
            localVideoView.videoContentMode = value
            remoteVideoView.videoContentMode = value
        }

    /**
     * Enables trajectories only after remote video becomes visible; local preview never receives trajectory
     * input.
     */
    public var isInteractionEnabled: Boolean = true
        set(
            value,
        ) {
            field = value
            remoteVideoView.isInteractionEnabled = value && isRemoteDisplayed
        }

    /**
     * The remote trajectory effect, or null for the built-in renderer.
     */
    public var trajectoryRenderer: TrajectoryEffectRendering?
        get() = remoteVideoView.trajectoryRenderer
        set(
            value,
        ) {
            remoteVideoView.trajectoryRenderer = value
        }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        // Keep remote rendering mounted below preview while awaiting results so frames continue to arrive.
        addView(
            remoteVideoView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            localVideoView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            ),
        )
        remoteVideoView.frameInvalidationHandler = { invalidatedTrack ->
            if (remoteTrack === invalidatedTrack) {
                showLocalPreview()
            }
        }
        showLocalPreview()
    }

    /**
     * Restores display bindings when the view attaches to a window.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showLocalPreview()
    }

    /**
     * Unbinds and stops display callbacks when the view leaves its window.
     */
    override fun onDetachedFromWindow() {
        showLocalPreview()
        super.onDetachedFromWindow()
    }

    /**
     * Restores local preview and hides the generated result.
     */
    private fun showLocalPreview() {
        val version = ++presentationVersion
        isRemoteDisplayed = false
        remoteVideoView.animate().cancel()
        remoteVideoView.isInteractionEnabled = false
        (localPreviewView ?: localVideoView).bringToFront()
        remoteVideoView.alpha = 1f
        remoteVideoView.frameDisplayHandler = { displayedTrack ->
            if (presentationVersion == version && remoteVideoView.isFrameDisplayEnabled &&
                remoteTrack === displayedTrack && isAttachedToWindow && !isRemoteDisplayed
            ) {
                isRemoteDisplayed = true
                remoteVideoView.alpha = 0f
                remoteVideoView.bringToFront()
                remoteVideoView.isInteractionEnabled = isInteractionEnabled
                remoteVideoView.animate()
                    .alpha(1f)
                    .setDuration(REMOTE_FADE_DURATION_MS)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
        }
    }

    /**
     * The generated-video transition settings.
     */
    private companion object {

        /**
         * The generated-video fade-in duration, in milliseconds.
         */
        const val REMOTE_FADE_DURATION_MS = 300L
    }
}
