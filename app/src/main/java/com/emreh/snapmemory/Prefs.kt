package com.emreh.snapmemory

import android.content.Context

object Prefs {
    private const val FILE = "settings"
    private const val INTERVAL = "interval_sec"
    private const val EXCLUDED = "excluded_packages"
    private const val ENABLED = "enabled"
    private const val FOLDER_URI = "folder_uri"
    private const val RETENTION = "retention_days"
    private const val APP_LOCK = "app_lock"
    private const val LAST_CAPTURE_AT = "last_capture_at"
    private const val LAST_ERROR = "last_error"

    private val DEFAULT_EXCLUDED = setOf(
        "com.android.systemui",
        "com.android.keyguard",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.microsoft.swiftkey",
        "com.google.android.apps.authenticator2",
        "com.google.android.apps.googleauthenticator",
        "com.google.android.apps.passwordmanager",
        "com.azure.authenticator",
        "com.onepassword.android",
        "com.x8bit.bitwarden",
        "com.beemdevelopment.aegis",
        "com.kunzisoft.keepass.free",
        "com.authy.auth",
        "com.google.android.gms",
        "com.android.settings",
        "com.google.android.apps.walletnfcrel"
    )

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun interval(c: Context): Long = p(c).getLong(INTERVAL, 7L)
    fun setInterval(c: Context, value: Long) =
        p(c).edit().putLong(INTERVAL, value.coerceIn(5L, 20L)).apply()

    fun excluded(c: Context): Set<String> =
        (DEFAULT_EXCLUDED + (p(c).getStringSet(EXCLUDED, emptySet()) ?: emptySet())).toSet()

    fun setExcluded(c: Context, values: Set<String>) =
        p(c).edit().putStringSet(EXCLUDED, values).apply()

    fun enabled(c: Context): Boolean = p(c).getBoolean(ENABLED, true)
    fun setEnabled(c: Context, value: Boolean) =
        p(c).edit().putBoolean(ENABLED, value).apply()

    fun folderUri(c: Context): String? = p(c).getString(FOLDER_URI, null)
    fun setFolderUri(c: Context, uri: String?) =
        p(c).edit().putString(FOLDER_URI, uri).apply()

    fun retentionDays(c: Context): Int = p(c).getInt(RETENTION, 30)
    fun setRetentionDays(c: Context, value: Int) =
        p(c).edit().putInt(RETENTION, value.coerceIn(7, 90)).apply()

    fun appLock(c: Context): Boolean = p(c).getBoolean(APP_LOCK, false)
    fun setAppLock(c: Context, value: Boolean) =
        p(c).edit().putBoolean(APP_LOCK, value).apply()

    fun lastCaptureAt(c: Context): Long = p(c).getLong(LAST_CAPTURE_AT, 0L)
    fun setLastCaptureAt(c: Context, value: Long) =
        p(c).edit().putLong(LAST_CAPTURE_AT, value).apply()

    fun lastError(c: Context): String = p(c).getString(LAST_ERROR, "") ?: ""
    fun setLastError(c: Context, value: String) =
        p(c).edit().putString(LAST_ERROR, value.take(240)).apply()
}
