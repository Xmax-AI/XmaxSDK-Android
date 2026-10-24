//
//  XmaxEnvironment.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * The Xmax service environment.
 */
public enum class XmaxEnvironment {

    /**
     * The China service environment.
     */
    CHINA,

    /**
     * The global service environment.
     */
    GLOBAL;

    /**
     * The API endpoint for this environment.
     */
    internal val apiBaseUrl: String
        get() = when (this) {
            CHINA -> "https://cloud.xmax.22duck.cn/open/api/v1"
            GLOBAL -> "https://api.xmax.cloud/open/api/v1"
        }

    /**
     * The realtime session endpoint for this environment.
     */
    internal val realtimeApiBaseUrl: String
        get() = when (this) {
            CHINA -> apiBaseUrl
            GLOBAL -> "https://api.xmax.ai/open/api/v1"
        }
}
