//
//  RtcEngineManager.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.ErrorMessageFormatter
import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/**
 * 表示对进程级 RTC Engine 的独占使用权。
 */
internal data class RtcEngineLease(
    /**
     * 本次引擎租约的标识，用于核验释放权限。
     */
    val id: UUID = UUID.randomUUID(),

    /**
     * 用于执行 RTC 操作的平台引擎。
     */
    val engine: RtcPlatform,
)

/**
 * 管理声网 RTC Engine 的进程级独占生命周期。
 */
internal open class RtcEngineManager internal constructor(
    /**
     * 创建 RTC 平台引擎的工厂函数。
     */
    private val makeEngine: () -> RtcPlatform?,

    /**
     * 释放底层 RTC 引擎资源的函数。
     */
    private val destroyEngine: () -> Unit,
) {

    /**
     * 引擎租约与共享状态的并发保护。
     */
    private val leaseMutex = Mutex()

    /**
     * 保护引擎生命周期状态的锁。
     */
    private val stateLock = Any()

    /**
     * 当前引擎占用及销毁失败状态。
     */
    private var activeLease: RtcEngineLease? = null

    /**
     * 引擎销毁过程中发生的错误。
     */
    private var destructionFailure: Throwable? = null

    /**
     * 创建 RtcEngineManager，并配置其依赖和运行参数。
     *
     * @param context 访问 Android 系统服务或媒体资源所需的上下文。
     * @param appId RTC 应用标识，不是应用证书或房间 Token；默认值为 `DEFAULT_APP_ID`。
     */
    internal constructor(
        context: Context,
        appId: String = DEFAULT_APP_ID,
    ) : this(
        makeEngine = { createAgoraRtcEngine(context.applicationContext, appId) },
        destroyEngine = ::destroyAgoraRtcEngine,
    )

    /**
     * 等待并获取 RTC 引擎的独占租约；等待期间支持协程取消。
     *
     * @return 当前调用方独占使用的 RTC 引擎租约。
     */
    internal open suspend fun acquire(): RtcEngineLease {
        leaseMutex.lock()
        try {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) { destructionFailure }?.let {
                throw rtcError("RTC Engine is unavailable after a failed native destruction", it)
            }
            val engine = makeEngine() ?: throw rtcError("Failed to create RTC Engine")
            val lease = RtcEngineLease(engine = engine)
            synchronized(stateLock) {
                activeLease = lease
            }
            currentCoroutineContext().ensureActive()
            return lease
        } catch (error: Throwable) {
            val lease = synchronized(stateLock) {
                activeLease.also { activeLease = null }
            }
            try {
                if (lease != null) destroyEngine()
            } catch (cleanupError: Throwable) {
                synchronized(stateLock) { destructionFailure = cleanupError }
                if (cleanupError !== error) error.addSuppressed(cleanupError)
            } finally {
                leaseMutex.unlock()
            }
            throw when (error) {
                is CancellationException,
                is XmaxError,
                -> error

                else -> rtcError("Failed to create RTC Engine: ${ErrorMessageFormatter.format(error)}", error)
            }
        }
    }

    /**
     * 释放匹配的 RTC 引擎租约；过期租约不影响当前引擎。
     *
     * @param lease 当前引擎的独占使用凭据。
     */
    internal open fun release(
        lease: RtcEngineLease
    ) {
        val shouldRelease = synchronized(stateLock) {
            if (activeLease?.id != lease.id) {
                false
            } else {
                activeLease = null
                true
            }
        }
        if (!shouldRelease) return

        try {
            destroyEngine()
        } catch (error: Throwable) {
            synchronized(stateLock) { destructionFailure = error }
            throw rtcError("RTC Engine destruction failed; native reuse is disabled", error)
        } finally {
            leaseMutex.unlock()
        }
    }

    /**
     * RTC 引擎的默认配置与进程级共享入口。
     */
    internal companion object {

        /**
         * 本地预览初始化 RTC 引擎时使用的默认 App ID。
         */
        const val DEFAULT_APP_ID: String = "5588dfe2e7874bd08ea28f4968ec2d40"

        /**
         * 进程内共享的 RTC 引擎管理器。
         */
        @Volatile
        private var sharedInstance: RtcEngineManager? = null

        /**
         * 取得应用范围内共享的 RTC 引擎管理器。
         *
         * @param context 访问 Android 系统服务或媒体资源所需的上下文。
         * @return 应用共享的 RTC 引擎管理器。
         */
        fun shared(
            context: Context
        ): RtcEngineManager =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: RtcEngineManager(context).also {
                    sharedInstance = it
                }
            }

        /**
         * 创建包含 RTC 故障原因的 SDK 错误。
         *
         * @param message 待发送或记录的消息文本。
         * @param cause 触发当前错误的底层异常。
         * @return 包含错误类别、描述和可用底层原因的 SDK 错误。
         */
        private fun rtcError(
            message: String,
            cause: Throwable? = null,
        ): XmaxError = XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = message,
            cause = cause,
        )
    }
}
