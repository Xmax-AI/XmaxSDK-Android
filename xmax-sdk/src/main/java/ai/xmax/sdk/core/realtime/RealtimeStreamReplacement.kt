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
 * 外部输入替换结果；remoteStream 仅在自动恢复生成成功时提供。
 */
public data class RealtimeStreamReplacement(
    /**
     * 本地输入媒体流。
     */
    public val localStream: RealtimeMediaStream,

    /**
     * 远端生成结果流。
     */
    public val remoteStream: RealtimeMediaStream?,
)
