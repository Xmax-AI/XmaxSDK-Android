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
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/**
 * Owns generation identifiers, cached conditions, and interaction bindings; RealtimeCoordinator serializes
 * operations.
 * Core shares one taskId with Stream and Interaction to associate signaling, frames, and trajectories.
 * This manager waits for Stream confirmation; XmaxRealtimeManager also waits for a renderable remote frame.
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
     * Requires initial generation conditions and permits later reuse of cached conditions.
     *
     * @param context Generation prompts and reference media; null reuses cached conditions, which must exist
     * for the first request.
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
     * Starts a generation task and commits its context and interaction binding after remote confirmation.
     * Failure or cancellation stops that taskId; only results passing ensureCurrent can return.
     *
     * @param videoFormat The resolved local media baseline used for interaction coordinates.
     * @param context Generation prompts and reference media; null reuses cached conditions, which must exist
     * for the first request.
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
     * Updates an existing task using its taskId; null conditions reuse the cache, while face restoration uses
     * this call's setting.
     *
     * @param taskId The identifier associating the generation request with its result stream.
     * @param videoFormat The resolved local media baseline used for interaction coordinates.
     * @param context Generation prompts and reference media; null reuses cached conditions, which must exist
     * for the first request.
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
     * Stops interaction and the task while retaining successful conditions for another generation; attempts
     * every independent cleanup step.
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
     * Clears cached conditions and stops generation when the connection resets; an empty taskId stops the
     * current task in Stream.
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
         * Encodes the UUID's 16 bytes as unpadded Base64URL and identifies the platform with a query
         * parameter.
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
