//
//  RealtimeModel.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * A realtime generation model; [id] identifies the model in server session requests.
 */
public enum class RealtimeModel(
    /**
     * The model identifier sent to the server.
     */
    public val id: String,
) {

    /**
     * The X2.1 realtime generation model.
     */
    X2_1("x2.1"),

    ;

    /**
     * The display name, maintained separately from the session identifier [id].
     */
    public val displayName: String
        get() = when (this) {
            X2_1 -> "X2.1"
        }

    /**
     * Supported video formats; camera dimensions must match exactly, while frame rates are recommended
     * defaults.
     * Local images and videos retain their input aspect ratio; the server fits them into the closest model
     * canvas.
     * An empty list selects canvases using pixel-area bounds and model alignment instead of fixed sizes.
     */
    public val supportedVideoFormats: List<RealtimeVideoFormat>
        get() = listOf(
            RealtimeVideoFormat(
                width = 1_920,
                height = 1_080,
                fps = defaultFrameRate,
            ),
            RealtimeVideoFormat(
                width = 1_080,
                height = 1_920,
                fps = defaultFrameRate,
            ),
            RealtimeVideoFormat(
                width = 1_408,
                height = 1_408,
                fps = defaultFrameRate,
            ),
            RealtimeVideoFormat(
                width = 1_152,
                height = 1_536,
                fps = defaultFrameRate,
            ),
            RealtimeVideoFormat(
                width = 1_536,
                height = 1_152,
                fps = defaultFrameRate,
            ),
        )

    /**
     * The minimum local media and uplink pixel area; also limits variable generation canvases.
     */
    public val minimumInputPixels: Int
        get() = 500_000

    /**
     * The maximum local frame pixel area; also limits generation canvases when fixed buckets are absent.
     */
    public val maximumInputPixels: Int
        get() = 2_100_000

    /**
     * The alignment required for each input dimension when fixed resolution buckets are absent.
     */
    public val inputSizeAlignment: Int
        get() = 32

    /**
     * The default frame rate for media input.
     */
    public val defaultFrameRate: Int
        get() = 30

    /**
     * The default camera capture format.
     */
    public val defaultCameraVideoFormat: RealtimeVideoFormat
        get() = RealtimeVideoFormat(
            width = 1_080,
            height = 1_920,
            fps = defaultFrameRate,
        )
}
