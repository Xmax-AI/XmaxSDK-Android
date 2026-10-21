//
//  CameraPosition.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * 相机采集使用的镜头方向。
 */
public enum class CameraPosition(
    /**
     * 需要规范化、校验或显示的文本。
     */
    public val value: String
) {

    /**
     * 前置摄像头。
     */
    FRONT("front"),

    /**
     * 后置摄像头。
     */
    BACK("back"),
}
