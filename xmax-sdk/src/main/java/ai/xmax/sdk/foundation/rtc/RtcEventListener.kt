//
//  RtcEventListener.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

/**
 * Receives RTC media and data signaling events.
 */
internal interface RtcEventListener {

    /**
     * Reports an unusable RTC room; normal automatic reconnection does not trigger this callback.
     *
     * @param roomId The RTC room identifier.
     * @param error The operation failure or SDK error.
     */
    fun onRoomTerminated(
        roomId: String,
        error: ai.xmax.sdk.XmaxError,
    ) = Unit

    /**
     * Handles remote publication changes only for the current room, excluding stale room events.
     *
     * @param stream The remote stream identified by room and user.
     * @param published Whether the remote video is published.
     */
    fun onRemoteVideoPublished(
        stream: RemoteStream,
        published: Boolean,
    )

    /**
     * Receives structured task notifications from remote room users.
     *
     * @param stream The remote stream identified by room and user.
     * @param message The message to send or log.
     */
    fun onUserMessageReceived(
        stream: RemoteStream,
        message: String,
    ) = Unit

    /**
     * Handles SEI carried by remote video.
     *
     * @param stream The remote stream identified by room and user.
     * @param message The message to send or log.
     */
    fun onSeiMessageReceived(
        stream: RemoteStream,
        message: String,
    )
}
