package com.example.privacyguard

import android.content.Context

object BlockedAppsStore {
    private const val PREFS = "firewall_prefs"
    private const val KEY = "blocked_packages"

    fun get(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            ?.toSet() ?: emptySet()

    fun setBlocked(context: Context, packageName: String, blocked: Boolean) {
        val updated = get(context).toMutableSet()
        if (blocked) updated.add(packageName) else updated.remove(packageName)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, updated).apply()
    }
}
