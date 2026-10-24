//
//  RealtimeFileVideoSource.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package com.xmax.xlab.modules.xlrealtime

import ai.xmax.media3.XmaxMedia3Source
import ai.xmax.sdk.RealtimeExternalFrameSink
import ai.xmax.sdk.RealtimeExternalVideoSource
import ai.xmax.sdk.RealtimeVideoFormat
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Supplies a looping local video and original PCM through the SDK's external capture API.
 *
 * XLab owns the player and releases it after the SDK unbinds the source. Local audio stays silent;
 * submitted PCM remains unchanged for generation and remote playback.
 */
@OptIn(UnstableApi::class)
internal class RealtimeFileVideoSource private constructor(
    private val adapter: XmaxMedia3Source,
    private val player: ExoPlayer,
    override val videoFormat: RealtimeVideoFormat,
    override val hasAudio: Boolean,
) : RealtimeExternalVideoSource by adapter {

    private var released = false

    override suspend fun start(sink: RealtimeExternalFrameSink) {
        check(!released) { "The external video source has been released" }
        adapter.start(sink)
        // Restart each binding on the player's shared video and PCM playback timeline.
        player.seekTo(0)
        player.play()
    }

    override suspend fun stop() {
        adapter.stop()
        if (!released) player.pause()
    }

    override fun setPreviewAudio(volume: Float, muted: Boolean) {
        if (!released) player.volume = 0f
    }

    /**
     * Releases the app-owned player before disposing its decoder surfaces.
     */
    suspend fun release(): Unit = withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext

        released = true
        try {
            player.release()
        } finally {
            adapter.release()
        }
    }

    companion object {

        /**
         * Prepares a selected video without creating a generation session or acquiring media permissions.
         */
        suspend fun make(
            context: Context,
            uri: Uri,
        ): RealtimeFileVideoSource {
            var adapter: XmaxMedia3Source? = null
            var player: ExoPlayer? = null
            var source: RealtimeFileVideoSource? = null
            try {
                return withContext(Dispatchers.Main.immediate) {
                    val preparedAdapter = XmaxMedia3Source(context.applicationContext).also { adapter = it }
                    val preparedPlayer = ExoPlayer.Builder(
                        context.applicationContext,
                        preparedAdapter.renderersFactory,
                    ).build().also { player = it }
                    preparedAdapter.attachPlayer(preparedPlayer)
                    preparedPlayer.volume = 0f
                    preparedPlayer.repeatMode = Player.REPEAT_MODE_ONE
                    preparedPlayer.setMediaItem(MediaItem.fromUri(uri))
                    preparedPlayer.prepare()
                    withTimeoutOrNull(15_000) { awaitReady(preparedPlayer) }
                        ?: throw IOException("External video preparation timed out")

                    val format = preparedAdapter.videoFormat.also(RealtimeVideoFormat::validate)
                    RealtimeFileVideoSource(
                        preparedAdapter,
                        preparedPlayer,
                        format,
                        preparedAdapter.hasAudio,
                    ).also { source = it }
                }
            } catch (error: Throwable) {
                // Also cover cancellation while the prepared source is returning to the caller's dispatcher.
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    try {
                        val prepared = source
                        if (prepared != null) {
                            prepared.release()
                        } else {
                            try {
                                player?.release()
                            } finally {
                                adapter?.release()
                            }
                        }
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                }
                throw error
            }
        }

        /**
         * Waits for display dimensions and selected audio tracks, removing the listener on every exit.
         */
        private suspend fun awaitReady(player: ExoPlayer) {
            var listener: Player.Listener? = null
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    fun resolve() {
                        if (!continuation.isActive) return

                        val error = player.playerError
                        if (error != null) {
                            continuation.resumeWith(Result.failure(error))
                        } else if (player.playbackState == Player.STATE_READY &&
                            player.videoSize.width > 0 && player.videoSize.height > 0
                        ) {
                            continuation.resumeWith(Result.success(Unit))
                        }
                    }
                    listener = object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) = resolve()
                    }.also(player::addListener)
                    resolve()
                }
            } finally {
                listener?.let(player::removeListener)
            }
        }
    }
}
