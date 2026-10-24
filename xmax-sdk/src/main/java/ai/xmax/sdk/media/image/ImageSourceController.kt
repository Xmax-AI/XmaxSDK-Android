//
//  ImageSourceController.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.image

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeMediaSource
import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.media.image.DecodedImage
import ai.xmax.sdk.foundation.media.image.ImageManaging
import ai.xmax.sdk.media.MediaVideoFrameListener
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Processes a local image to the target format and repeatedly outputs frames at a fixed rate.
 */
internal class ImageSourceController(
    /**
     * The image decoder and orientation normalizer.
     */
    private val imageManager: ImageManaging,

    /**
     * The service resolving media dimensions and model constraints.
     */
    private val mediaService: MediaServicing,

    /**
     * The local video frame listener.
     */
    private val frameListener: MediaVideoFrameListener,

    /**
     * The media failure callback.
     */
    private val errorListener: (XmaxError) -> Unit,

    /**
     * The function loading bytes from a readable URI.
     */
    private val uriDataLoader: suspend (Uri) -> ByteArray,

    /**
     * The monotonic clock returning microseconds.
     */
    private val timestampUsProvider: () -> Long = {
        (SystemClock.elapsedRealtimeNanos() / 1_000L).coerceAtLeast(0L)
    },

    /**
     * The scope continuously supplying media frames.
     */
    private val outputScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ImageSourceControlling {

    /**
     * The lock protecting prepared image input.
     */
    private val stateLock = Any()

    /**
     * The prepared image frame.
     */
    private var preparedSource: Pair<RealtimeVideoFormat, VideoFrame>? = null

    /**
     * The coroutine producing timed image frames.
     */
    private var outputJob: Job? = null

    /**
     * Whether repeated image output is running.
     */
    private var isRunning = false

    /**
     * Creates an image source controller.
     *
     * @param context The Android context used for system services and media resources.
     * @param imageManager The image decoder and orientation normalizer.
     * @param mediaService The service resolving dimensions and model input constraints.
     * @param frameListener The local video frame listener.
     * @param errorListener The media failure callback.
     * @param outputScope The scope continuously supplying media frames.
     */
    constructor(
        context: Context,
        imageManager: ImageManaging,
        mediaService: MediaServicing,
        frameListener: MediaVideoFrameListener,
        errorListener: (XmaxError) -> Unit,
        outputScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) : this(
        imageManager = imageManager,
        mediaService = mediaService,
        frameListener = frameListener,
        errorListener = errorListener,
        uriDataLoader = { uri -> readUriBytes(
            context,
            uri,
        ) },
        outputScope = outputScope,
    )

    /**
     * Decodes an image and prepares its aspect-preserving input format and video frame.
     *
     * @param imageData Encoded image file bytes, not raw pixels.
     * @return The image input format and corresponding video frame.
     */
    override suspend fun prepare(
        imageData: ByteArray,
    ): Pair<RealtimeVideoFormat, VideoFrame> = withContext(Dispatchers.Default) {
        prepareDecoded(imageManager.decode(imageData))
    }

    /**
     * Decodes an image and prepares its aspect-preserving input format and video frame.
     *
     * @param bitmap The Android image to process or display.
     * @return The image input format and corresponding video frame.
     */
    override suspend fun prepare(
        bitmap: Bitmap,
    ): Pair<RealtimeVideoFormat, VideoFrame> = withContext(Dispatchers.Default) {
        prepareDecoded(imageManager.decode(bitmap))
    }

    /**
     * Decodes an image and prepares its aspect-preserving input format and video frame.
     *
     * @param uri A media URI your app can read.
     * @return The image input format and corresponding video frame.
     */
    override suspend fun prepare(
        uri: Uri,
    ): Pair<RealtimeVideoFormat, VideoFrame> = prepare(uriDataLoader(uri))

    /**
     * Decodes an image and prepares its aspect-preserving input format and video frame.
     *
     * @param decodedImage The decoded, orientation-normalized image.
     * @return The image input format and corresponding video frame.
     */
    override suspend fun prepare(
        decodedImage: DecodedImage,
    ): Pair<RealtimeVideoFormat, VideoFrame> = withContext(Dispatchers.Default) {
        prepareDecoded(decodedImage)
    }

    /**
     * Starts repeating the prepared image at the configured frame rate.
     */
    override fun start() {
        val source = synchronized(stateLock) {
            val source = preparedSource ?: throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Prepare the local image before starting the image source",
            )
            if (isRunning) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Local image source is already active",
                )
            }

            isRunning = true
            source
        }

        try {
            emitFrame(source.second)
        } catch (
            error: Throwable,
        ) {
            synchronized(stateLock) { isRunning = false }
            throw XmaxError.from(error)
        }

        val intervalMillis = (1_000L / source.first.fps).coerceAtLeast(1L)
        val job = outputScope.launch {
            while (isActive) {
                delay(intervalMillis)
                val frame = synchronized(stateLock) {
                    if (!isRunning) return@launch

                    preparedSource?.second
                } ?: return@launch

                try { emitFrame(frame) }
                catch (
                    error: Throwable,
                ) {
                    currentCoroutineContext().ensureActive()
                    errorListener(XmaxError.from(error))
                    return@launch
                }
            }
        }

        synchronized(stateLock) {
            if (isRunning) outputJob = job else job.cancel()
        }
    }

    /**
     * Stops image frame output and clears prepared input resources.
     */
    override suspend fun stop() {
        val job = synchronized(stateLock) {
            isRunning = false
            preparedSource = null
            outputJob.also { outputJob = null }
        }

        job?.cancelAndJoin()
    }

    /**
     * Converts a decoded image to an input format and reusable frame.
     *
     * @param decodedImage The decoded, orientation-normalized image.
     * @return The image input format and corresponding video frame.
     */
    private fun prepareDecoded(
        decodedImage: DecodedImage,
    ): Pair<RealtimeVideoFormat, VideoFrame> {
        synchronized(stateLock) {
            if (preparedSource != null) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Stop the current image source before preparing another image",
                )
            }
        }
        val resolvedFormat = resolveVideoFormat(decodedImage.size)
        val source = resolvedFormat to decodedImage.makeVideoFrame(resolvedFormat)
        synchronized(stateLock) {
            if (preparedSource != null) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Another image source was prepared concurrently",
                )
            }

            preparedSource = source
        }

        return source
    }

    /**
     * Resolves aspect-preserving input dimensions from the orientation-corrected image.
     *
     * @param sourceSize The original display dimensions, in pixels.
     * @return The resolved video dimensions, frame rate, and encoding settings.
     */
    private fun resolveVideoFormat(
        sourceSize: IntSize,
    ): RealtimeVideoFormat {
        if (sourceSize.width < 2 || sourceSize.height < 2) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Image dimensions must be at least two pixels",
            )
        }

        val resolvedMediaSize = mediaService.resolveMediaSize(
            sourceSize,
            RealtimeMediaSource.IMAGE,
        )

        return RealtimeVideoFormat(
            resolvedMediaSize.width,
            resolvedMediaSize.height,
            mediaService.model.defaultFrameRate,
        )
    }

    /**
     * Submits the current image frame to the media listener.
     *
     * @param frame The video frame to process, display, or submit.
     */
    private fun emitFrame(
        frame: VideoFrame,
    ) {
        frameListener(frame.updating(timestampUsProvider()))
    }

    /**
     * The local image content reader.
     */
    private companion object {

        /**
         * Reads URI contents accessible to your app.
         *
         * @param context The Android context used for system services and media resources.
         * @param uri A media URI your app can read.
         * @return An array containing the valid bytes.
         */
        suspend fun readUriBytes(
            context: Context,
            uri: Uri,
        ): ByteArray =
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw XmaxError(
                        XmaxErrorCode.MEDIA_ERROR,
                        "Failed to open local image URI",
                    )
            }
    }
}
