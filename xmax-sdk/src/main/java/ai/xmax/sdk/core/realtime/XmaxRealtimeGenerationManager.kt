//
//  XmaxRealtimeGenerationManager.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.stream.StreamControlling
import ai.xmax.sdk.media.interaction.InteractionControlling
import ai.xmax.sdk.render.RenderControlling
import kotlinx.coroutines.currentCoroutineContext
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/**
 * Coordinates generation signaling, remote stream confirmation, condition updates, and
 * interactions.
 *
 * Generation becomes ready after result-stream confirmation, a renderable remote frame, and audio
 * activation.
 */
internal class XmaxRealtimeGenerationManager(
    /**
     * The controller submitting interaction trajectories.
     */
    private val interactionController: InteractionControlling,

    /**
     * The component managing RTC publication, subscriptions, and generation signaling.
     */
    private val streamController: StreamControlling,

    /**
     * The remote presentation gate confirming that the first generated frame is ready.
     */
    private val renderController: RenderControlling,

    /**
     * The function allocating a generation task identifier.
     */
    private val taskIdGenerator: () -> String = ::createTaskId,
) {

    /**
     * The lock protecting cached generation conditions.
     */
    private val stateLock = Any()

    /**
     * The last committed generation conditions.
     */
    private var currentContext: RealtimeContext? = null

    /**
     * Whether the current generation uses face restoration.
     */
    private var currentFaceRestoration = false

    /**
     * Pending replacements retain their context across reconnection rollback.
     */
    val cachedContext: RealtimeContext? get() = synchronized(stateLock) { currentContext }

    /**
     * Retains the last successful face restoration setting for generation restarts.
     */
    val cachedFaceRestoration: Boolean get() = synchronized(stateLock) { currentFaceRestoration }

    /**
     * Verifies that the request supplies a context or can reuse a cached context.
     *
     * @param context The generation conditions. Required for the first request; null reuses cached
     * conditions thereafter.
     */
    fun validateContext(
        context: RealtimeContext?,
    ) {
        if (context == null && synchronized(stateLock) { currentContext } == null) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "A realtime context is required for the first generation",
            )
        }
    }

    /**
     * Starts generation and waits for result-stream confirmation and a displayable first frame.
     *
     * Conditions are retained after the first frame and audio are ready. Failure or cancellation
     * stops the task; only results passing the validity check can return.
     *
     * @param videoFormat The resolved local media baseline used for interaction coordinates.
     * @param context The generation conditions. Required for the first request; null reuses cached
     * conditions thereafter.
     * @param faceRestoration Whether to enable face restoration through `face_replace`; defaults to false.
     * @param targetVideoFormat The downlink output format.
     * @param generationVideoFormat The model canvas format, independent of media input; defaults to
     * `videoFormat`.
     * @param paddingContain Whether the server fits input and removes padding from output; defaults to false.
     * @param ensureCurrent Checks operation ownership and throws cancellation if it is no longer current.
     * @return The unique identifier for the new generation task.
     */
    suspend fun start(
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext?,
        faceRestoration: Boolean = false,
        targetVideoFormat: RealtimeVideoFormat? = null,
        generationVideoFormat: RealtimeVideoFormat = videoFormat,
        paddingContain: Boolean = false,
        ensureCurrent: () -> Unit,
    ): String {
        val resolvedContext = context ?: synchronized(stateLock) { currentContext }
            ?: throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "A realtime context is required for the first generation",
            )

        val taskId = taskIdGenerator()
        val confirmation = streamController.beginGeneration(
            taskId,
            generationVideoFormat,
            resolvedContext,
            faceRestoration,
            targetVideoFormat,
            paddingContain,
        )

        try {
            confirmation.await()
            ensureCurrent()

            interactionController.startInteraction(
                taskId,
                videoFormat,
            )
            ensureCurrent()

            renderController.waitUntilRemoteFrameReady()
            ensureCurrent()
            streamController.activateRemoteAudio()
            ensureCurrent()

            synchronized(stateLock) {
                currentContext = resolvedContext
                currentFaceRestoration = faceRestoration
            }

            return taskId
        } catch (
            error: Throwable,
        ) {
            confirmation.cancel()
            cleanupAfterFailure(
                error,
                { stop(taskId) },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * Updates generation conditions and interactions without waiting for a new stream confirmation.
     *
     * Null conditions reuse the cache; face restoration uses the setting supplied with this call.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat The resolved local media baseline used for interaction coordinates.
     * @param context The requested conditions, or null to reuse the most recent successful conditions.
     * @param faceRestoration Whether to enable face restoration through `face_replace`; defaults to false.
     * @param generationVideoFormat The model canvas format, independent of media input; defaults to
     * `videoFormat`.
     * @param paddingContain Whether the server fits input and removes padding from output; defaults to false.
     * @param ensureCurrent Checks operation ownership and throws cancellation if it is no longer current.
     */
    suspend fun update(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext?,
        faceRestoration: Boolean = false,
        generationVideoFormat: RealtimeVideoFormat = videoFormat,
        paddingContain: Boolean = false,
        ensureCurrent: () -> Unit,
    ) {
        val resolvedContext = context ?: synchronized(stateLock) { currentContext }
            ?: throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "A realtime context is required for the first generation",
            )

        streamController.updateGeneration(
            taskId,
            generationVideoFormat,
            resolvedContext,
            faceRestoration,
            paddingContain,
        )
        ensureCurrent()

        interactionController.startInteraction(
            taskId,
            videoFormat,
        )

        synchronized(stateLock) {
            currentContext = resolvedContext
            currentFaceRestoration = faceRestoration
        }
    }

    /**
     * Stops interactions and sends a stop-generation signal.
     *
     * Successful conditions are retained for another generation; every independent cleanup step is
     * attempted.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     */
    suspend fun stop(
        taskId: String,
    ) {
        cleanupResources(
            { interactionController.stopInteraction() },
            { streamController.stopGeneration(taskId) },
        )
    }

    /**
     * Stops generation and clears cached conditions.
     *
     * An empty task identifier stops the current task in the stream controller.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     */
    suspend fun reset(
        taskId: String = "",
    ) {
        synchronized(stateLock) {
            currentContext = null
            currentFaceRestoration = false
        }
        stop(taskId)
    }

    /**
     * The encoding rules for generation task identifiers.
     */
    internal companion object {

        /**
         * Creates a random task identifier with an Android platform marker.
         *
         * @return A unique identifier for generation signaling.
         */
        fun createTaskId(): String {
            val uuid = UUID.randomUUID()
            val bytes = ByteBuffer.allocate(16)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return "task-$encoded?os=android"
        }
    }
}
