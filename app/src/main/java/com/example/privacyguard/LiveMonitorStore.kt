package com.example.privacyguard

import android.content.Context

object LiveMonitorStore {
    data class State(val packageName: String, val untilMillis: Long)

    private const val PREFS = "live_monitor_prefs"
    private const val KEY_PACKAGE = "package"
    private const val KEY_UNTIL = "until"

    fun start(context: Context, packageName: String, durationMillis: Long) {
        val until = System.currentTimeMillis() + durationMillis
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PACKAGE, packageName)
            .putLong(KEY_UNTIL, until)
            .apply()
    }

    fun stop(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun active(context: Context): State? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packageName = prefs.getString(KEY_PACKAGE, null) ?: return null
        val until = prefs.getLong(KEY_UNTIL, 0L)
        if (until <= System.currentTimeMillis()) {
            prefs.edit().clear().apply()
            return null
        }
        return State(packageName, until)
    }
}
