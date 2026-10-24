//
//  XmaxLogger.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * The enabled XmaxSDK log categories.
 */
public class XmaxLoggerOption(
    /**
     * The underlying value represented by this object.
     */
    public val rawValue: Int,
) {

    /**
     * Combines two sets of logging options.
     *
     * @param other The object to compare or combine.
     * @return The combined logging options.
     */
    public operator fun plus(
        other: XmaxLoggerOption,
    ): XmaxLoggerOption =
        XmaxLoggerOption(rawValue or other.rawValue)

    /**
     * Checks whether this set includes the requested log category.
     *
     * @param option The category controlling whether this message is logged.
     * @return True if the option is included.
     */
    public operator fun contains(
        option: XmaxLoggerOption,
    ): Boolean =
        option.rawValue != 0 && rawValue and option.rawValue == option.rawValue

    /**
     * Whether all logging is disabled.
     */
    public val isEmpty: Boolean
        get() = rawValue == 0

    /**
     * Compares objects by value.
     *
     * @param other The object to compare or combine.
     */
    override fun equals(
        other: Any?,
    ): Boolean =
        other is XmaxLoggerOption && rawValue == other.rawValue

    /**
     * Computes a hash consistent with value equality.
     */
    override fun hashCode(): Int = rawValue

    /**
     * Returns a bitmask description of the logging options.
     */
    override fun toString(): String = "XmaxLoggerOption(rawValue=$rawValue)"

    /**
     * Predefined logging options.
     */
    public companion object {

        /**
         * Disables XmaxSDK logging.
         */
        @JvmField
        public val none: XmaxLoggerOption = XmaxLoggerOption(0)

        /**
         * Business events from Room, API, Realtime, Storage, and related domains.
         */
        @JvmField
        public val business: XmaxLoggerOption = XmaxLoggerOption(1 shl 0)

        /**
         * RTC performance metrics and device alarms.
         */
        @JvmField
        public val performance: XmaxLoggerOption = XmaxLoggerOption(1 shl 1)

        /**
         * Enables all XmaxSDK logs.
         */
        @JvmField
        public val all: XmaxLoggerOption = business + performance
    }
}

/**
 * Writes system logs with the Xmax prefix and category.
 */
internal class XmaxLogger private constructor(
    /**
     * The business domain of this log category.
     */
    private val category: String,
) {

    /**
     * The language used for log details, independent of service routing.
     */
    enum class Language {

        /**
         * Chinese log details.
         */
        CHINESE,

        /**
         * English log details.
         */
        ENGLISH,
    }

    /**
     * Writes a debug message when the requested category is enabled.
     *
     * @param message Builds the log text only when logging is enabled.
     * @param option The controlling log category; defaults to `XmaxLoggerOption.business`.
     */
    fun debug(
        message: () -> String,
        option: XmaxLoggerOption = XmaxLoggerOption.business,
    ) {
        write(
            XmaxLogLevel.DEBUG,
            message,
            option,
        )
    }

    /**
     * Writes an informational message when the requested category is enabled.
     *
     * @param message Builds the log text only when logging is enabled.
     * @param option The controlling log category; defaults to `XmaxLoggerOption.business`.
     */
    fun info(
        message: () -> String,
        option: XmaxLoggerOption = XmaxLoggerOption.business,
    ) {
        write(
            XmaxLogLevel.INFO,
            message,
            option,
        )
    }

    /**
     * Writes a warning when the requested category is enabled.
     *
     * @param message Builds the log text only when logging is enabled.
     * @param option The controlling log category; defaults to `XmaxLoggerOption.business`.
     */
    fun warn(
        message: () -> String,
        option: XmaxLoggerOption = XmaxLoggerOption.business,
    ) {
        write(
            XmaxLogLevel.WARNING,
            message,
            option,
        )
    }

    /**
     * Writes an error when the requested category is enabled.
     *
     * @param message Builds the log text only when logging is enabled.
     * @param option The controlling log category; defaults to `XmaxLoggerOption.business`.
     */
    fun error(
        message: () -> String,
        option: XmaxLoggerOption = XmaxLoggerOption.business,
    ) {
        write(
            XmaxLogLevel.ERROR,
            message,
            option,
        )
    }

    /**
     * Formats category-prefixed log text with a trailing blank line.
     *
     * @param message The message to send or log.
     * @return Category-prefixed text ending with a newline.
     */
    fun formattedMessage(
        message: String,
    ): String {
        val prefix = "[Xmax][$category]"
        return message.trimEnd(
            '\r',
            '\n',
        ).lines()
            .joinToString(
                separator = "\n",
                postfix = "\n\n",
            ) { "$prefix $it" }
    }

    /**
     * Writes a message at the requested level with the SDK category prefix.
     *
     * @param level The log severity.
     * @param message Builds the log text only when logging is enabled.
     * @param option The category controlling whether this message is logged.
     */
    private fun write(
        level: XmaxLogLevel,
        message: () -> String,
        option: XmaxLoggerOption,
    ) {
        if (!isEnabled(option)) return

        sink.get().write(
            level,
            TAG,
            formattedMessage(message()),
        )
    }

    /**
     * Checks whether the requested log category is enabled.
     *
     * @param option The category controlling whether this message is logged.
     * @return True if the log category is enabled.
     */
    private fun isEnabled(
        option: XmaxLoggerOption,
    ): Boolean {
        if (option.isEmpty) return false
        return XmaxLoggerOption(configuration.get().options).contains(option)
    }

    /**
     * Provides domain-specific loggers and shared output configuration.
     */
    companion object {

        /**
         * Logs realtime media and generation lifecycle events.
         */
        val realtime = XmaxLogger("Realtime")

        /**
         * Logs RTC engine and media transport events.
         */
        val rtc = XmaxLogger("RTC")

        /**
         * Logs media preparation, capture, and playback events.
         */
        val media = XmaxLogger("Media")

        /**
         * Logs API requests and responses.
         */
        val api = XmaxLogger("API")

        /**
         * Logs file upload, download, and storage events.
         */
        val storage = XmaxLogger("Storage")

        /**
         * Logs room connections and signaling.
         */
        val room = XmaxLogger("Room")

        /**
         * Logs stream publication and generation tasks.
         */
        val stream = XmaxLogger("Stream")

        /**
         * Logs video rendering events.
         */
        val render = XmaxLogger("Render")

        /**
         * Logs trajectories and interaction events.
         */
        val interaction = XmaxLogger("Interaction")

        /**
         * Logs permission check results.
         */
        val permission = XmaxLogger("Permission")

        /**
         * Logs generation startup timing.
         */
        val timing = XmaxLogger("Timing")

        /**
         * The Android log tag for XmaxSDK.
         */
        private const val TAG = "XmaxSDK"

        /**
         * An immutable snapshot of enabled log categories and detail language.
         */
        private data class Configuration(
            /**
             * The enabled log categories.
             */
            val options: Int,

            /**
             * The language used for log details.
             */
            val language: Language,
        )

        /**
         * The current log categories and detail language.
         */
        private val configuration = AtomicReference(
            Configuration(
                XmaxLoggerOption.none.rawValue,
                Language.CHINESE,
            ),
        )

        /**
         * The target receiving formatted log messages.
         */
        private val sink = AtomicReference<XmaxLogSink>(AndroidXmaxLogSink)

        /**
         * Sets the shared logging options and output language.
         *
         * @param options The enabled logging categories.
         * @param language The language used for log details; defaults to Chinese.
         */
        fun configure(
            options: XmaxLoggerOption,
            language: Language = Language.CHINESE,
        ) {
            configuration.set(
                Configuration(
                    options.rawValue,
                    language,
                ),
            )
        }

        /**
         * Selects detail text using the configured log language.
         *
         * @param chinese The Chinese text.
         * @param english The English text.
         * @return The text for the configured log language.
         */
        fun localized(
            chinese: String,
            english: String,
        ): String =
            if (configuration.get().language == Language.CHINESE) chinese else english

        /**
         * Replaces the logging target; null restores Android system logging.
         *
         * @param value The replacement logging target, or null to restore the default.
         */
        internal fun setSinkForTesting(
            value: XmaxLogSink?,
        ) {
            sink.set(value ?: AndroidXmaxLogSink)
        }
    }
}

/**
 * The severity of an SDK log message.
 */
internal enum class XmaxLogLevel {

    /**
     * A debug message.
     */
    DEBUG,

    /**
     * An informational message.
     */
    INFO,

    /**
     * A warning about a condition that permits continued operation.
     */
    WARNING,

    /**
     * An operation failure.
     */
    ERROR,
}

/**
 * The output interface for formatted SDK log messages.
 */
internal fun interface XmaxLogSink {

    /**
     * Writes a message at the requested level with the SDK category prefix.
     *
     * @param level The log severity.
     * @param tag The Android log tag.
     * @param message The message to send or log.
     */
    fun write(
        level: XmaxLogLevel,
        tag: String,
        message: String,
    )
}

/**
 * Writes SDK messages to the Android system log.
 */
private object AndroidXmaxLogSink : XmaxLogSink {

    /**
     * Writes a message at the requested level with the SDK category prefix.
     *
     * @param level The log severity.
     * @param tag The Android log tag.
     * @param message The message to send or log.
     */
    override fun write(
        level: XmaxLogLevel,
        tag: String,
        message: String,
    ) {
        when (level) {
            XmaxLogLevel.DEBUG -> Log.d(
                tag,
                message,
            )
            XmaxLogLevel.INFO -> Log.i(
                tag,
                message,
            )
            XmaxLogLevel.WARNING -> Log.w(
                tag,
                message,
            )
            XmaxLogLevel.ERROR -> Log.e(
                tag,
                message,
            )
        }
    }
}
