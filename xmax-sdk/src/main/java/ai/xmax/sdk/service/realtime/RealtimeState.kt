//
//  RealtimeState.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * A realtime connection lifecycle state.
 *
 * @property value The string representation used for configuration or display.
 */
public enum class RealtimeConnectionState(
    public val value: String,
) {

    /**
     * No local media is available, or all resources are closed.
     */
    IDLE("Idle"),

    /**
     * Local input is being prepared.
     */
    PREPARING("Preparing"),

    /**
     * Local media is ready without a generation service connection.
     */
    READY("Ready"),

    /**
     * A server session and RTC connection are being established.
     */
    CONNECTING("Connecting"),

    /**
     * The connection is ready to start generation.
     */
    CONNECTED("Connected"),

    /**
     * The current generation result is displayable.
     */
    GENERATING("Generating"),

    /**
     * Generation is stopping and connection resources are being released.
     */
    DISCONNECTING("Disconnecting"),
}

/**
 * The reason for entering a realtime state.
 */
public sealed interface RealtimeReason {

    /**
     * An explicit stop or normal resource release.
     */
    public data object Normal : RealtimeReason

    /**
     * A display orientation change requiring generation reconfiguration.
     */
    public data object OrientationChanged : RealtimeReason

    /**
     * A call or runtime failure ended the current operation.
     *
     * @property error The operation failure or SDK error.
     */
    public data class Failure(
        public val error: XmaxError,
    ) : RealtimeReason
}

/**
 * A snapshot of realtime lifecycle state.
 *
 * @property connectionState The current media and generation connection state.
 * @property sessionId The server session identifier.
 * @property taskId The task identifier associating generation requests with result streams.
 * @property reason The reason for this state, cleared when a new operation begins.
 */
public data class RealtimeState(
    public val connectionState: RealtimeConnectionState,
    public val sessionId: String? = null,
    public val taskId: String? = null,
    public val reason: RealtimeReason? = null,
)

/**
 * A realtime state listener.
 */
public fun interface RealtimeStateListener {

    /**
     * Reports the manager's latest realtime state.
     *
     * @param state The current state snapshot.
     */
    public fun onStateChanged(
        state: RealtimeState,
    )
}
