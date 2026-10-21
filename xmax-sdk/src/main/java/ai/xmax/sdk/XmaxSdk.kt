//
//  XmaxSdk.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * 提供当前 Android SDK 构建的版本信息。
 */
public object XmaxSdk {

    /**
     * 当前 SDK 构建的语义化版本号。
     */
    @JvmField
    public val VERSION: String = BuildConfig.XMAX_SDK_VERSION
}

