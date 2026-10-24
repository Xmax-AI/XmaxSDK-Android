//
//  RealtimeStreamReplacement.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * The result of replacing external input; remoteStream is provided only if generation resumes successfully.
 */
public data class RealtimeStreamReplacement(
    /**
     * The local input stream.
     */
    public val localStream: RealtimeMediaStream,

    /**
     * The remote generation result stream.
     */
    public val remoteStream: RealtimeMediaStream?,
)
