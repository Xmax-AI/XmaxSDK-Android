//
//  QualityHistory.kt
//  XmaxSDK
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/Xmax-AI/XmaxSDK-Android
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.quality

import android.content.Context

/**
 * Retains successful levels for one hour by RTC vendor, model, and direction; defaults to L1 without a valid
 * record.
 */
internal class QualityHistory(
    /**
     * The function reading a direction's saved quality record.
     */
    private val readValue: (String) -> Entry? = { null },

    /**
     * The function saving a direction's quality level and timestamp.
     */
    private val writeValue: (String, Entry) -> Unit = { _, _ -> },

    /**
     * The Unix millisecond clock used to expire history.
     */
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * The cached history records by direction.
     */
    private val values = mutableMapOf<String, Entry>()

    /**
     * Reads a direction's history, returning L1 if absent, expired, or invalid.
     *
     * @param downlink True for downlink, false for uplink.
     * @return The valid saved level, or L1 if absent or expired.
     */
    @Synchronized
    fun read(
        downlink: Boolean,
    ): Int {
        val key = if (downlink) "downlink" else "uplink"
        val entry = values[key] ?: runCatching { readValue(key) }.getOrNull() ?: return QualityTiers.INITIAL_LEVEL
        val currentTime = now()
        if (entry.level !in 1..QualityTiers.percentages.size || entry.savedAt <= 0 || currentTime < entry.savedAt) {
            return QualityTiers.INITIAL_LEVEL
        }

        return if (currentTime - entry.savedAt < EXPIRATION_MILLIS) entry.level else QualityTiers.INITIAL_LEVEL
    }

    /**
     * Saves a successfully applied level with a one-hour lifetime from now.
     *
     * @param downlink True for downlink, false for uplink.
     * @param level The quality level to apply or retain; 1 is highest.
     */
    @Synchronized
    fun write(
        downlink: Boolean,
        level: Int,
    ) {
        if (level !in 1..QualityTiers.percentages.size) return
        val key = if (downlink) "downlink" else "uplink"
        val entry = Entry(
            level,
            now(),
        )
        values[key] = entry
        runCatching { writeValue(
            key,
            entry,
        ) }
    }

    /**
     * A quality history record and its Unix millisecond timestamp.
     */
    data class Entry(
        /**
         * The quality level to apply or retain; 1 is highest.
         */
        val level: Int,

        /**
         * The Unix millisecond timestamp when the level was saved.
         */
        val savedAt: Long,
    )

    /**
     * The quality history persistence and expiration settings.
     */
    companion object {

        /**
         * The maximum history age, in milliseconds.
         */
        private const val EXPIRATION_MILLIS = 60 * 60 * 1000L

        /**
         * Creates local quality history isolated by RTC vendor and model.
         *
         * @param context The Android context used for system services and media resources.
         * @param model The model defining server capabilities and input constraints.
         * @return History isolated by model and transmission direction.
         */
        fun create(
            context: Context,
            model: String,
        ): QualityHistory = runCatching {
            val preferences = context.applicationContext.getSharedPreferences(
                "xmax_quality_v1",
                Context.MODE_PRIVATE,
            )
            val prefix = "agora:$model:"
            QualityHistory(
                readValue = { direction ->
                    Entry(
                        level = preferences.getInt(
                            prefix + direction,
                            QualityTiers.INITIAL_LEVEL,
                        ),
                        savedAt = preferences.getLong(
                            prefix + direction + ":saved_at",
                            0L,
                        ),
                    )
                },
                writeValue = { direction, entry ->
                    preferences.edit()
                        .putInt(
                            prefix + direction,
                            entry.level,
                        )
                        .putLong(
                            prefix + direction + ":saved_at",
                            entry.savedAt,
                        )
                        .apply()
                },
            )
        }.getOrElse { QualityHistory() }
    }
}
