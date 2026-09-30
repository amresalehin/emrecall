package com.emreh.snapmemory

import android.content.Context

object Prefs {
    private const val FILE = "settings"
    private const val INTERVAL = "interval_sec"
    private const val RETENTION = "retention_days"
    private const val EXCLUDED = "excluded_packages"
    private const val ENABLED = "enabled"

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun interval(c: Context): Long = p(c).getLong(INTERVAL, 7L)
    fun setInterval(c: Context, value: Long) = p(c).edit().putLong(INTERVAL, value).apply()

    fun retention(c: Context): Int = p(c).getInt(RETENTION, 30)
    fun setRetention(c: Context, value: Int) = p(c).edit().putInt(RETENTION, value).apply()

    fun excluded(c: Context): Set<String> =
        p(c).getStringSet(EXCLUDED, emptySet()) ?: emptySet()

    fun setExcluded(c: Context, values: Set<String>) =
        p(c).edit().putStringSet(EXCLUDED, values).apply()

    fun enabled(c: Context): Boolean = p(c).getBoolean(ENABLED, true)
    fun setEnabled(c: Context, value: Boolean) =
        p(c).edit().putBoolean(ENABLED, value).apply()
}
