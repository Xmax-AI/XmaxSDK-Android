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
 *
 * @property value The string representation used for configuration or display.
 */
public enum class RealtimeVideoSampleMethod(
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
 *
 * @property path The video URL directly accessible to the generation server.
 * @property sampleMethod The server video sampling method; defaults to `RealtimeVideoSampleMethod.TIME`.
 */
public data class RealtimeReferenceVideo(
    public val path: String,
    public val sampleMethod: RealtimeVideoSampleMethod = RealtimeVideoSampleMethod.TIME,
)
