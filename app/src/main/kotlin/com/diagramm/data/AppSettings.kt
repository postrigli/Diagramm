package com.diagramm.data

import android.content.Context

/** Small persistent preferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Apps section: sort by cache size instead of total size (the default is total). */
    var appsSortByCache: Boolean
        get() = prefs.getBoolean(KEY_APPS_SORT_BY_CACHE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_APPS_SORT_BY_CACHE, value).apply()
        }

    private companion object {
        const val KEY_APPS_SORT_BY_CACHE = "apps_sort_by_cache"
    }
}
