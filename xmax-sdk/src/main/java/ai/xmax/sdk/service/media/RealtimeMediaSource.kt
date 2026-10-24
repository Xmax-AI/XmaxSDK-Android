//
//  RealtimeMediaSource.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * A local media source for realtime generation.
 */
public enum class RealtimeMediaSource(
    /**
     * The protocol identifier for the media source.
     */
    public val id: String,
) {

    /**
     * Camera input.
     */
    CAMERA("camera"),

    /**
     * Video input.
     */
    VIDEO("video"),

    /**
     * Image input.
     */
    IMAGE("image"),

    /**
     * App-owned external frame input.
     */
    EXTERNAL("external"),
}
