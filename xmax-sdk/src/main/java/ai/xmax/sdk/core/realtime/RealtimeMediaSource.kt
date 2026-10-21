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
 * 实时生成模型支持的本地媒体来源。
 */
public enum class RealtimeMediaSource(
    /**
     * 媒体输入类型的协议标识。
     */
    public val id: String
) {

    /**
     * 摄像头输入。
     */
    CAMERA("camera"),

    /**
     * 视频输入。
     */
    VIDEO("video"),

    /**
     * 图片输入。
     */
    IMAGE("image"),
}
