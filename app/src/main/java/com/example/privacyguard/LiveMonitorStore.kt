package com.example.privacyguard

import android.content.Context

object LiveMonitorStore {
    enum class Mode { DROP_CAPTURE, PASS_THROUGH }

    data class State(
        val packageName: String,
        val mode: Mode,
        val untilMillis: Long
    )

    private const val PREFS = "live_monitor_prefs"
    private const val KEY_PACKAGE = "package"
    private const val KEY_MODE = "mode"
    private const val KEY_UNTIL = "until"

    fun start(context: Context, packageName: String, durationMillis: Long) {
        startDropCapture(context, packageName, durationMillis)
    }

    fun startDropCapture(context: Context, packageName: String, durationMillis: Long) {
        save(
            context,
            packageName,
            Mode.DROP_CAPTURE,
            System.currentTimeMillis() + durationMillis
        )
    }

    fun startPassThrough(context: Context, packageName: String) {
        save(context, packageName, Mode.PASS_THROUGH, Long.MAX_VALUE)
    }

    private fun save(context: Context, packageName: String, mode: Mode, untilMillis: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousPackage = prefs.getString(KEY_PACKAGE, null)
        val previousMode = parseMode(prefs.getString(KEY_MODE, null))

        if (previousPackage != null && previousMode == Mode.PASS_THROUGH &&
            (mode != Mode.PASS_THROUGH || previousPackage != packageName)
        ) {
            MonitorSessionStore.stop(context, previousPackage)
        }
        if (mode == Mode.PASS_THROUGH) {
            MonitorSessionStore.start(context, packageName)
        }

        prefs.edit()
            .putString(KEY_PACKAGE, packageName)
            .putString(KEY_MODE, mode.name)
            .putLong(KEY_UNTIL, untilMillis)
            .apply()
    }

    fun stop(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packageName = prefs.getString(KEY_PACKAGE, null)
        val mode = parseMode(prefs.getString(KEY_MODE, null))
        if (packageName != null && mode == Mode.PASS_THROUGH) {
            MonitorSessionStore.stop(context, packageName)
        }
        prefs.edit().clear().apply()
    }

    fun active(context: Context): State? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packageName = prefs.getString(KEY_PACKAGE, null) ?: return null
        val mode = parseMode(prefs.getString(KEY_MODE, Mode.DROP_CAPTURE.name)) ?: Mode.DROP_CAPTURE
        val until = prefs.getLong(KEY_UNTIL, 0L)
        if (mode == Mode.DROP_CAPTURE && until <= System.currentTimeMillis()) {
            prefs.edit().clear().apply()
            return null
        }
        return State(packageName, mode, until)
    }

    private fun parseMode(raw: String?): Mode? = try {
        raw?.let { Mode.valueOf(it) }
    } catch (_: Exception) {
        null
    }
}
