//
//  XmaxRealtimeConnectionManager.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.media.interaction.InteractionControlling
import ai.xmax.sdk.render.RenderControlling
import ai.xmax.sdk.service.realtime.RealtimeSession
import ai.xmax.sdk.service.realtime.RealtimeSessionServicing
import ai.xmax.sdk.stream.StreamControlling
import ai.xmax.sdk.stream.StreamID
import ai.xmax.sdk.service.media.MediaService
import ai.xmax.sdk.stream.quality.QualityVideoFormat
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Manages server sessions, RTC connections, and remote media resources.
 *
 * Connection setup, disconnection, and rollback are serialized to prevent reuse of unreleased
 * resources.
 */
internal class XmaxRealtimeConnectionManager(
    /**
     * The service creating, renewing, and closing server sessions.
     */
    private val sessionService: RealtimeSessionServicing,

    /**
     * The controller submitting interaction trajectories.
     */
    private val interactionController: InteractionControlling,

    /**
     * The controller binding video tracks to preview views.
     */
    private val renderController: RenderControlling,

    /**
     * The component managing RTC publication, subscriptions, and generation signaling.
     */
    private val streamController: StreamControlling,

    /**
     * Model media sizing rules for transport and returned video.
     */
    private val mediaService: MediaServicing = MediaService(),
) {

    /**
     * The lock protecting connection state.
     */
    private val stateLock = Any()

    /**
     * The mutex serializing connection and disconnection.
     */
    private val operationMutex = Mutex()

    /**
     * The active server session.
     */
    private var activeRemoteTrack: RealtimeVideoTrack? = null

    /**
     * The server session used by the current connection.
     */
    private var activeSession: RealtimeSession? = null

    /**
     * The input format used for local publication.
     */
    private var inputFormat: RealtimeVideoFormat? = null

    /**
     * Whether the current input includes audio.
     */
    private var inputHasAudio = false

    /**
     * The target dimensions most recently prepared or submitted for returned video.
     */
    @Volatile var currentTargetSize: IntSize? = null
        private set

    /**
     * Prepares the initial encoder and remote dimensions for a connection.
     *
     * @param baseline The resolved local media format.
     * @param options The model configuration for this connection.
     * @param publishLocalMedia Whether to configure a local video uplink.
     * @return The initial downlink output format.
     */
    fun prepareQuality(
        baseline: RealtimeVideoFormat,
        options: RealtimeConfiguration,
        publishLocalMedia: Boolean = true,
    ): RealtimeVideoFormat {
        val quality = streamController.prepareQuality(
            baseline,
            options.model.minimumInputPixels,
            publishLocalMedia,
        )

        quality.uplink?.let {
            streamController.setVideoEncoderConfig(it)
            streamController.completeInitialQuality(downlink = false)
        }

        val target = resolveTargetVideoFormat(
            baseline,
            quality.downlinkLevel,
        )

        currentTargetSize = IntSize(
            target.width,
            target.height,
        )
        updateRemoteVideoFormat(baseline)
        return target
    }

    /**
     * Records the successfully applied initial quality after generation becomes ready.
     */
    fun completeInitialQuality() {
        streamController.completeInitialQuality(downlink = true)
    }

    /**
     * Resolves the returned video format using the media aspect ratio and quality level.
     *
     * @param baseline The fixed, resolved local media format.
     * @param level The requested downlink level.
     * @return Four-pixel-aligned output dimensions with 24 fps for bitrate estimation.
     */
    fun resolveTargetVideoFormat(
        baseline: RealtimeVideoFormat,
        level: Int,
    ): RealtimeVideoFormat {
        val size = mediaService.resolveTargetSize(
            IntSize(
                baseline.width,
                baseline.height,
            ),
            QualityVideoFormat.resolveDownlinkMaximumSize(
                baseline,
                level,
            ),
        )
        return baseline.copy(
            width = size.width,
            height = size.height,
            fps = 24,
            minimumBitrate = null,
            maximumBitrate = null,
        )
    }

    /**
     * Applies a quality level to uplink encoding or downlink output.
     *
     * The policy and history are committed only after successful application.
     *
     * @param level The requested quality level.
     * @param downlink Whether to update returned video instead of local encoding.
     * @param taskId The generation task receiving a downlink update.
     * @param reason The adjustment reason used in diagnostic logs.
     * @param ensureCurrent The validity check for the admitted operation.
     */
    suspend fun applyQualityLevel(
        level: Int,
        downlink: Boolean,
        taskId: String,
        reason: String = "manual",
        ensureCurrent: () -> Unit,
    ) {
        val configuration = streamController.qualityConfiguration ?: return
        val previous = if (downlink) configuration.downlinkLevel else configuration.uplinkLevel
            ?: throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "No uplink quality configuration is available",
            )

        try {
            ensureCurrent()
            val format = if (downlink) {
                resolveTargetVideoFormat(
                    configuration.baseline,
                    level,
                )
            } else {
                QualityVideoFormat.resolve(
                    configuration.baseline,
                    level,
                    downlink = false,
                    minimumInputPixels = configuration.minimumInputPixels,
                )
            }

            if (downlink) {
                streamController.updateDownlinkVideoResolution(
                    taskId,
                    format,
                    ensureCurrent,
                )

                currentTargetSize = IntSize(
                    format.width,
                    format.height,
                )
                updateRemoteVideoFormat(configuration.baseline)
            } else {
                if (!QualityVideoFormat.supportsEncoding(format)) {
                    throw XmaxError(
                        XmaxErrorCode.INVALID_CONFIGURATION,
                        "Quality level conflicts with the configured bitrate range",
                    )
                }

                streamController.setVideoEncoderConfig(format)
            }

            ensureCurrent()
            streamController.completeQuality(
                downlink,
                level,
                success = true,
            )

            XmaxLogger.realtime.debug(
                message = {
                    "Quality adjustment ${if (downlink) "downlink" else "uplink"}: L$previous -> L$level, " +
                        "${format.width}x${format.height}, $reason"
                },
            )
        } catch (
            cancelled: CancellationException,
        ) {
            streamController.resetQualitySamples()
            throw cancelled
        } catch (
            error: Throwable,
        ) {
            ensureCurrent()
            streamController.completeQuality(
                downlink,
                level,
                success = false,
            )
            throw error
        }
    }

    /**
     * Checks reuse against the actual room configuration rather than a media snapshot that may already be
     * unbound.
     *
     * @param format Video dimensions, frame rate, and uplink encoding options.
     * @param hasAudio Whether the input provides audio.
     * @return True if the current connection can accept the input.
     */
    fun acceptsInput(
        format: RealtimeVideoFormat,
        hasAudio: Boolean,
    ): Boolean = synchronized(stateLock) {
        activeSession != null && inputFormat == format && inputHasAudio == hasAudio
    }

    /**
     * The connected session identifier; empty before connection completes or after disconnection.
     */
    val currentSessionId: String
        get() = synchronized(stateLock) { activeSession?.id.orEmpty() }

    /**
     * The remote track container; its existence does not indicate generation or first-frame readiness.
     */
    val currentRemoteStream: RealtimeMediaStream?
        get() = synchronized(stateLock) {
            if (activeSession == null) null else activeRemoteTrack?.let {
                RealtimeMediaStream(
                    StreamID.REMOTE.value,
                    it,
                )
            }
        }

    /**
     * Updates the active remote track's actual video format; ignores updates when no track is active.
     *
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     */
    fun updateRemoteVideoFormat(
        videoFormat: RealtimeVideoFormat,
    ) {
        val track = synchronized(stateLock) { activeRemoteTrack } ?: return

        track.updateVideoFormat(
            currentTargetSize?.let {
                videoFormat.copy(
                    width = it.width,
                    height = it.height,
                )
            } ?: videoFormat,
        )
    }

    /**
     * Establishes a realtime session and prepares its remote stream.
     *
     * Only results belonging to the current operation are accepted. Setup failures release
     * connection resources while preserving the original error or cancellation; heartbeat failures
     * are forwarded for lifecycle cleanup.
     *
     * @param model The model defining server capabilities and input constraints.
     * @param videoFormat Video dimensions, frame rate, and uplink encoding options.
     * @param includeLocalAudio Whether to publish local audio alongside video.
     * @param publishLocalMedia Whether to publish local media; false for server-read network video, true by
     * default.
     * @param isCurrent Checks whether this operation still owns the result.
     * @param onHeartbeatFailure Receives the session identifier and error when renewal fails.
     */
    suspend fun connect(
        model: RealtimeModel,
        videoFormat: RealtimeVideoFormat,
        includeLocalAudio: Boolean,
        publishLocalMedia: Boolean = true,
        isCurrent: () -> Boolean,
        onHeartbeatFailure: suspend (String, XmaxError) -> Unit,
    ): RealtimeMediaStream = operationMutex.withLock {
        if (currentSessionId.isNotEmpty()) throw XmaxError(
            XmaxErrorCode.INVALID_CONFIGURATION,
            "Realtime connection is already open",
        )

        val timing = currentCoroutineContext()[RealtimeTiming.Attempt]
        timing?.mark(RealtimeTiming.Stage.CONNECTION_START)
        var session: RealtimeSession? = null

        try {
            timing?.mark(RealtimeTiming.Stage.SESSION_START)
            session = sessionService.createSession(model)
            timing?.mark(RealtimeTiming.Stage.SESSION_END)
            ensureCurrent(isCurrent)
            val connection = session.connection ?: throw XmaxError(
                XmaxErrorCode.SESSION_ERROR,
                "Session does not contain complete RTC join information",
            )

            streamController.connect(
                connection,
                includeLocalAudio,
                publishLocalMedia,
            ) {
                ensureCurrent(isCurrent)
            }
            ensureCurrent(isCurrent)

            sessionService.startHeartbeat(
                session.id,
                onHeartbeatFailure,
            )
            val remoteTrack = RealtimeVideoTrack(
                id = connection.botName ?: "video-remote",
                videoFormat = currentTargetSize?.let {
                    videoFormat.copy(
                        width = it.width,
                        height = it.height,
                    )
                } ?: videoFormat,
            )
            renderController.registerRemoteTrack(
                remoteTrack,
                IntSize(
                    videoFormat.width,
                    videoFormat.height,
                ),
            ) { frame ->
                interactionController.submitInteraction(frame)
            }

            synchronized(stateLock) {
                activeSession = session
                activeRemoteTrack = remoteTrack
                inputFormat = videoFormat
                inputHasAudio = includeLocalAudio
            }
            ensureCurrent(isCurrent)
            timing?.mark(RealtimeTiming.Stage.CONNECTION_END)
            return@withLock RealtimeMediaStream(
                StreamID.REMOTE.value,
                remoteTrack,
            )
        } catch (
            error: Throwable,
        ) {
            // Rollback still holds the operation lock, so a later connection cannot yet own these resources.
            cleanupAfterFailure(
                error,
                { currentTargetSize = null; streamController.resetQuality() },
                { sessionService.stopHeartbeat() },
                { val track = synchronized(stateLock) {
                    activeRemoteTrack.also { activeSession = null; activeRemoteTrack = null }
                }; renderController.resetRemoteTrack(track) },
                { streamController.disconnect() },
                { session?.id?.let { closeSessionBestEffort(it) } },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * Disconnects RTC, releases remote rendering resources, and closes the server session.
     *
     * Local media remains available.
     *
     * @return The closed session identifier, or null if no session was active.
     */
    suspend fun disconnect(): String? = operationMutex.withLock {
        val resources = synchronized(stateLock) {
            ConnectionResources(
                activeSession,
                activeRemoteTrack,
            ).also {
                activeSession = null
                activeRemoteTrack = null
                currentTargetSize = null
            }
        }

        cleanupResources(
            { streamController.resetQuality() },
            { sessionService.stopHeartbeat() },
            { renderController.resetRemoteTrack(resources.remoteTrack) },
            { streamController.disconnect() },
            { resources.session?.id?.let { closeSessionBestEffort(it) } },
        )
        resources.session?.id
    }

    /**
     * Server closure waits at most five seconds; failures are logged without blocking local cleanup
     * indefinitely.
     *
     * @param sessionId The server session identifier.
     */
    private suspend fun closeSessionBestEffort(
        sessionId: String,
    ) {
        try { withTimeout(5_000L) { sessionService.closeSession(sessionId) } }
        catch (
            error: Throwable,
        ) { logCleanupFailure(
            "Failed to close realtime session $sessionId",
            error,
        ) }
    }

    /**
     * Records cleanup failures without replacing the error that initiated cleanup.
     *
     * @param title The log entry title.
     * @param error The operation failure or SDK error.
     */
    private fun logCleanupFailure(
        title: String,
        error: Throwable,
    ) {
        XmaxLogger.realtime.error(
            message = {
                "$title\n└─ ${XmaxLogger.localized(
                    "原因：",
                    "Reason: ",
                )}${ErrorMessageFormatter.format(error)}"
            },
        )
    }

    /**
     * Verifies that a connection operation still belongs to the current lifecycle.
     *
     * Results that lost ownership are reported as cancellation rather than a new app-visible
     * failure.
     *
     * @param isCurrent Checks whether this operation still owns the result.
     */
    private fun ensureCurrent(
        isCurrent: () -> Boolean,
    ) {
        if (!isCurrent()) {
            throw CancellationException("Realtime connection was cancelled")
        }
    }

    /**
     * A disconnected connection snapshot whose resources remain owned by its cleanup operation.
     */
    private data class ConnectionResources(
        /**
         * The server session and its connection details.
         */
        val session: RealtimeSession?,

        /**
         * The active remote video track.
         */
        val remoteTrack: RealtimeVideoTrack?,
    )
}
