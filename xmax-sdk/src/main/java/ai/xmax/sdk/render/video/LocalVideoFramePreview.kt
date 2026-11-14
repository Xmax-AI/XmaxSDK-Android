//
//  LocalVideoFramePreview.kt
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
import android.graphics.Bitmap
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Displays the latest submitted frame without queuing stale frames or blocking capture on the UI thread.
 *
 * @property makeBitmap Converts an owned frame to a bitmap for local display.
 * @property onDisplayed Reports that a frame reached the bound preview.
 * @property onFailure The receiver of unrecoverable preview conversion or display failures.
 */
internal class LocalVideoFramePreview(
    private val makeBitmap: (VideoFrame) -> Bitmap,
    private val onDisplayed: () -> Unit = {},
    private val onFailure: (Throwable) -> Unit,
) {

    /**
     * The scope converting and displaying submitted frames.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The conflated wake-up queue for the latest frame.
     */
    private val updates = Channel<Unit>(Channel.CONFLATED)

    /**
     * The lock protecting preview target and retained frame state.
     */
    private val lock = Any()

    /**
     * The latest copied frame available for preview.
     */
    private var frame: VideoFrame? = null

    /**
     * The weakly held display target.
     */
    private var view: WeakReference<XmaxRealtimeVideoView>? = null

    /**
     * The aspect-preserving display mode.
     */
    private var mode = VideoContentMode.FILL

    /**
     * The revision rejecting deliveries prepared for an older view or binding.
     */
    private var revision = 0L

    init {
        scope.launch {
            for (ignored in updates) {
                val target = synchronized(lock) {
                    val frame = frame ?: return@synchronized null
                    val view = view?.get() ?: return@synchronized null
                    Triple(
                        frame,
                        view,
                        revision,
                    )
                } ?: continue
                var bitmap: Bitmap? = null
                var delivered = false
                try {
                    val image = makeBitmap(target.first)
                    bitmap = image
                    withContext(Dispatchers.Main.immediate) {
                        if (synchronized(lock) { revision == target.third && view?.get() === target.second }) {
                            target.second.displayDecodedVideoBitmap(
                                image,
                                mode,
                            )
                            delivered = true
                            onDisplayed()
                        }
                    }
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    error: Exception,
                ) {
                    onFailure(error)
                    return@launch
                } finally {
                    if (!delivered) bitmap?.recycle()
                }
            }
        }
    }

    /**
     * Attaches submitted-frame preview and schedules the latest available frame.
     *
     * @param view The display target.
     * @param mode The aspect-preserving fit or fill mode.
     */
    fun attach(
        view: XmaxRealtimeVideoView,
        mode: VideoContentMode,
    ) {
        synchronized(lock) {
            this.view = WeakReference(view)
            this.mode = mode
            revision++
        }
        view.prepareDecodedVideoPreview(mode)
        updates.trySend(Unit)
    }

    /**
     * Detaches a display target without ending capture or discarding the latest frame.
     *
     * @param view The target to detach.
     */
    fun detach(
        view: XmaxRealtimeVideoView,
    ) {
        synchronized(lock) {
            if (this.view?.get() === view) {
                this.view = null
                revision++
            }
        }
        view.clearDecodedVideoPreview()
    }

    /**
     * Retains the latest independently owned frame for preview.
     *
     * @param frame The copied, processed external frame.
     */
    fun enqueue(
        frame: VideoFrame,
    ) {
        val attached = synchronized(lock) {
            this.frame = frame
            view?.get() != null
        }
        if (attached) updates.trySend(Unit)
    }

    /**
     * Drops work prepared for the previous capture while keeping the preview view bound.
     */
    fun discardPendingFrame() {
        synchronized(lock) {
            revision++
            frame = null
        }
    }

    /**
     * Cancels pending display work and releases the preview's frame and target.
     */
    fun close() {
        val previous = synchronized(lock) {
            revision++
            frame = null
            view?.get().also { view = null }
        }
        updates.close()
        scope.cancel()
        previous?.clearDecodedVideoPreview()
    }
}
