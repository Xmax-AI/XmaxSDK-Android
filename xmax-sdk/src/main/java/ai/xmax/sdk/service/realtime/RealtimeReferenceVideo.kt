//
//  RealtimeReferenceVideo.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * The sampling method for server-read reference video.
 */
public enum class RealtimeVideoSampleMethod(
    /**
     * The string representation used for configuration or display.
     */
    internal val value: String,
) {

    /**
     * Samples network video at time intervals.
     */
    TIME("time"),

    /**
     * Samples network video by frame rate.
     */
    FPS("fps"),
}

/**
 * A reference video directly accessible to the server.
 */
public data class RealtimeReferenceVideo(
    /**
     * The video URL directly accessible to the generation server.
     */
    public val path: String,

    /**
     * The server video sampling method; defaults to `RealtimeVideoSampleMethod.TIME`.
     */
    public val sampleMethod: RealtimeVideoSampleMethod = RealtimeVideoSampleMethod.TIME,
)
