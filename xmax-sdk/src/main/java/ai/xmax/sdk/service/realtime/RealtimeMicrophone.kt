//
//  RealtimeMicrophone.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * The microphone input captured and published when a local camera stream connects.
 */
public sealed interface RealtimeMicrophone {

    /**
     * Leaves microphone permission, capture, and publication disabled.
     */
    public data object Disabled : RealtimeMicrophone

    /**
     * Uses Android's default audio input routing.
     */
    public data object SystemDefault : RealtimeMicrophone

    /**
     * Captures from an explicitly selected audio input.
     *
     * @property id The identifier returned by [MediaDevices.listMicrophoneDevices].
     */
    public data class Device(
        public val id: String,
    ) : RealtimeMicrophone
}
