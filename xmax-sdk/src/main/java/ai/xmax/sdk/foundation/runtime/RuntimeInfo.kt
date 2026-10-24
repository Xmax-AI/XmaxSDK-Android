//
//  RuntimeInfo.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.runtime

import ai.xmax.sdk.XmaxSdk
import android.os.Build
import org.json.JSONObject

/**
 * SDK runtime metadata shared by API requests and room signals.
 */
internal data class RuntimeInfo(
    /**
     * The client platform name.
     */
    val platform: String,

    /**
     * The operating system version.
     */
    val osVersion: String,

    /**
     * The XmaxSDK version.
     */
    val sdkVersion: String,

    /**
     * The device model.
     */
    val deviceModel: String,
) {

    /**
     * Encodes runtime metadata as JSON for server requests.
     *
     * @return JSON for the current operation.
     */
    fun toJson(): JSONObject = JSONObject()
        .put(
            "platform",
            platform,
        )
        .put(
            "os_version",
            osVersion,
        )
        .put(
            "sdk_version",
            sdkVersion,
        )
        .put(
            "device_model",
            deviceModel,
        )

    /**
     * The source of current device and SDK metadata.
     */
    companion object {

        /**
         * The current platform, operating system, SDK version, and device model.
         */
        val current: RuntimeInfo by lazy {
            RuntimeInfo(
                platform = "android",
                osVersion = Build.VERSION.RELEASE.orEmpty().trim().ifEmpty { "unknown" },
                sdkVersion = XmaxSdk.VERSION,
                deviceModel = Build.MODEL.orEmpty().trim().ifEmpty { "unknown" },
            )
        }
    }
}
