package com.diagramm.data

import android.content.Context

/** What the last visit to the "Apps" section found; shown on the main screen without rescanning. */
data class AppsSummary(val totalBytes: Long, val cacheBytes: Long, val updatedAtMillis: Long)

class AppsSummaryStore(context: Context) {
    private val prefs = context.getSharedPreferences("apps_summary", Context.MODE_PRIVATE)

    fun read(): AppsSummary? {
        if (!prefs.contains(KEY_TOTAL)) return null
        return AppsSummary(prefs.getLong(KEY_TOTAL, 0), prefs.getLong(KEY_CACHE, 0), prefs.getLong(KEY_AT, 0))
    }

    /** Called after a scan and after every removal / cache clean-up, so the figure already excludes what the user deleted. */
    fun write(totalBytes: Long, cacheBytes: Long) {
        prefs.edit()
            .putLong(KEY_TOTAL, totalBytes)
            .putLong(KEY_CACHE, cacheBytes)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    private companion object {
        const val KEY_TOTAL = "total"
        const val KEY_CACHE = "cache"
        const val KEY_AT = "at"
    }
}
