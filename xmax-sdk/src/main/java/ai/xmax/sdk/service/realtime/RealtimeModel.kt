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
 * A realtime generation model supported by XmaxSDK.
 *
 * [id] identifies the model in server session requests.
 */
public enum class RealtimeModel(
    /**
     * The model identifier sent to the server.
     */
    public val id: String,

    /**
     * The display name shown to developers and users.
     */
    public val displayName: String,

    /**
     * The maximum local frame pixel area; also limits generation canvases when fixed buckets are absent.
     */
    public val maximumInputPixels: Int,

    /**
     * The fixed resolution buckets supported by the model.
     */
    private val cameraPresets: List<RealtimeCameraPreset>,
) {

    /**
     * The X2.1 realtime generation model.
     */
    X2_1(
        id = "x2.1",
        displayName = "X2.1",
        maximumInputPixels = 2_100_000,
        cameraPresets = listOf(
            RealtimeCameraPreset.RESOLUTION_1920X1080,
            RealtimeCameraPreset.RESOLUTION_1080X1920,
            RealtimeCameraPreset.RESOLUTION_1408X1408,
            RealtimeCameraPreset.RESOLUTION_1152X1536,
            RealtimeCameraPreset.RESOLUTION_1536X1152,
        ),
    ),

    /**
     * The X2.1 Flash realtime generation model.
     */
    X2_1_FLASH(
        id = "x2.1-flash",
        displayName = "X2.1 Flash",
        maximumInputPixels = 960 * 960,
        cameraPresets = listOf(
            RealtimeCameraPreset.RESOLUTION_704X1280,
            RealtimeCameraPreset.RESOLUTION_1280X704,
            RealtimeCameraPreset.RESOLUTION_960X960,
            RealtimeCameraPreset.RESOLUTION_1024X768,
            RealtimeCameraPreset.RESOLUTION_768X1024,
        ),
    ),

    ;

    /**
     * Supported video formats; camera dimensions must match exactly, while frame rates are recommended
     * defaults.
     * Images, local videos, and server-read videos retain their input aspect ratio; the server fits
     * them into the closest model canvas.
     * An empty list selects canvases using pixel-area bounds and model alignment instead of fixed sizes.
     */
    public val supportedVideoFormats: List<RealtimeVideoFormat>
        get() = cameraPresets.map { preset ->
            RealtimeVideoFormat(
                width = preset.size.width,
                height = preset.size.height,
                fps = defaultFrameRate,
            )
        }

    /**
     * The minimum local media and uplink pixel area; also limits variable generation canvases.
     */
    public val minimumInputPixels: Int
        get() = 200_000

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
     * The first supported portrait format, or the first supported format when no portrait is available.
     */
    public val defaultCameraVideoFormat: RealtimeVideoFormat
        get() {
            val formats = supportedVideoFormats
            return formats.firstOrNull { it.width < it.height } ?: formats.first()
        }
}
