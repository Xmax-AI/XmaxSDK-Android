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
 * Exclusive ownership of the process-wide RTC engine.
 */
internal data class RtcEngineLease(
    /**
     * The lease identifier used to authorize engine release.
     */
    val id: UUID = UUID.randomUUID(),

    /**
     * The platform engine executing RTC operations.
     */
    val engine: RtcPlatform,
)

/**
 * Owns the process-wide exclusive lifecycle of the Agora RTC engine.
 */
internal open class RtcEngineManager internal constructor(
    /**
     * The factory creating an RTC platform engine.
     */
    private val makeEngine: () -> RtcPlatform?,

    /**
     * The function releasing native RTC engine resources.
     */
    private val destroyEngine: () -> Unit,
) {

    /**
     * The mutex protecting engine ownership and shared state.
     */
    private val leaseMutex = Mutex()

    /**
     * The lock protecting engine lifecycle state.
     */
    private val stateLock = Any()

    /**
     * The active engine lease.
     */
    private var activeLease: RtcEngineLease? = null

    /**
     * The failure encountered during engine destruction.
     */
    private var destructionFailure: Throwable? = null

    /**
     * Creates an RTC engine manager.
     *
     * @param context The Android context used for system services and media resources.
     * @param appId The RTC App ID, not an App Certificate or token; defaults to `DEFAULT_APP_ID`.
     */
    internal constructor(
        context: Context,
        appId: String = DEFAULT_APP_ID,
    ) : this(
        makeEngine = { createAgoraRtcEngine(
            context.applicationContext,
            appId,
        ) },
        destroyEngine = ::destroyAgoraRtcEngine,
    )

    /**
     * Waits for and acquires an exclusive RTC engine lease, supporting coroutine cancellation while waiting.
     *
     * @return The caller's exclusive RTC engine lease.
     */
    internal open suspend fun acquire(): RtcEngineLease {
        leaseMutex.lock()
        try {
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) { destructionFailure }?.let {
                throw rtcError(
                    "RTC Engine is unavailable after a failed native destruction",
                    it,
                )
            }
            val engine = makeEngine() ?: throw rtcError("Failed to create RTC Engine")
            val lease = RtcEngineLease(engine = engine)
            synchronized(stateLock) {
                activeLease = lease
            }
            currentCoroutineContext().ensureActive()
            return lease
        } catch (
            error: Throwable,
        ) {
            val lease = synchronized(stateLock) {
                activeLease.also { activeLease = null }
            }

            try {
                if (lease != null) destroyEngine()
            } catch (
                cleanupError: Throwable,
            ) {
                synchronized(stateLock) { destructionFailure = cleanupError }
                if (cleanupError !== error) error.addSuppressed(cleanupError)
            } finally {
                leaseMutex.unlock()
            }

            throw when (error) {
                is CancellationException,
                is XmaxError,
                -> error

                else -> rtcError(
                    "Failed to create RTC Engine: ${ErrorMessageFormatter.format(error)}",
                    error,
                )
            }
        }
    }

    /**
     * Releases the matching lease; stale leases cannot release the current engine.
     *
     * @param lease The current exclusive engine lease.
     */
    internal open fun release(
        lease: RtcEngineLease,
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
        } catch (
            error: Throwable,
        ) {
            synchronized(stateLock) { destructionFailure = error }
            throw rtcError(
                "RTC Engine destruction failed; native reuse is disabled",
                error,
            )
        } finally {
            leaseMutex.unlock()
        }
    }

    /**
     * The default RTC engine configuration and process-wide entry point.
     */
    internal companion object {

        /**
         * The App ID used to initialize RTC for local preview.
         */
        const val DEFAULT_APP_ID: String = "5588dfe2e7874bd08ea28f4968ec2d40"

        /**
         * The process-wide shared engine manager.
         */
        @Volatile
        private var sharedInstance: RtcEngineManager? = null

        /**
         * Returns the application-wide RTC engine manager.
         *
         * @param context The Android context used for system services and media resources.
         * @return The shared application engine manager.
         */
        fun shared(
            context: Context,
        ): RtcEngineManager =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: RtcEngineManager(context).also {
                    sharedInstance = it
                }
            }

        /**
         * Creates an SDK error describing an RTC failure.
         *
         * @param message The message to send or log.
         * @param cause The underlying exception that caused this error.
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
