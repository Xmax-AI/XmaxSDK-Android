//
//  RtcRemoteVideoSink.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.RealtimeVideoFrame

/**
 * A remote frame subscription with main-thread first-frame notifications; pixels are accessible only within
 * the RTC callback.
 */
internal fun interface RtcRemoteVideoSink {

    /**
     * Reports the first subscribed frame and its dimensions.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     */
    fun onFirstFrame(
        width: Int,
        height: Int,
    )

    /**
     * Receives a frame from the subscribed video stream.
     *
     * @param frame The video frame to process, display, or submit.
     */
    fun onFrame(
        frame: RtcRemoteVideoFrame,
    ) {}

    /**
     * Reports a failure in the current media or RTC operation.
     *
     * @param error The operation failure or SDK error.
     */
    fun onError(
        error: ai.xmax.sdk.XmaxError,
    ) {}
}

/**
 * A borrowed native frame; call copy before crossing threads or returning from onFrame.
 */
internal interface RtcRemoteVideoFrame {

    /**
     * The original frame dimensions, in pixels.
     */
    val width: Int

    /**
     * The original frame height, in pixels.
     */
    val height: Int

    /**
     * The rotated display dimensions, in pixels.
     */
    val displayWidth: Int get() = width

    /**
     * The displayed frame height, in pixels.
     */
    val displayHeight: Int get() = height

    /**
     * Copies remote pixels and metadata so callers can retain the frame after the callback returns.
     *
     * @return A remote frame with independently owned pixels.
     */
    fun copy(): RealtimeVideoFrame
}
