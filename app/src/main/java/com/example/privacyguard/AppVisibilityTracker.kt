package com.example.privacyguard

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

object AppVisibilityTracker {
    private data class CachedState(
        var foreground: Boolean?,
        var lastQueryMillis: Long
    )

    private const val INITIAL_LOOKBACK_MILLIS = 12L * 60L * 60L * 1000L
    private const val REFRESH_MILLIS = 1500L
    private val cache = HashMap<String, CachedState>()

    @Synchronized
    fun isLikelyForeground(context: Context, packageName: String): Boolean? {
        val now = System.currentTimeMillis()
        val current = cache[packageName]
        if (current != null && now - current.lastQueryMillis < REFRESH_MILLIS) {
            return current.foreground
        }

        if (!NetworkUsageReader.hasUsageAccess(context)) {
            cache[packageName] = CachedState(null, now)
            return null
        }

        val begin = if (current == null) {
            now - INITIAL_LOOKBACK_MILLIS
        } else {
            (current.lastQueryMillis - 1000L).coerceAtLeast(now - INITIAL_LOOKBACK_MILLIS)
        }

        var state = current?.foreground
        try {
            val manager = context.getSystemService(UsageStatsManager::class.java)
            val events = manager.queryEvents(begin, now + 1L)
            val event = UsageEvents.Event()
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName != packageName) continue
                when (event.eventType) {
                    1 -> state = true  // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
                    2 -> state = false // MOVE_TO_BACKGROUND / ACTIVITY_PAUSED
                }
            }
        } catch (_: Exception) {
            cache[packageName] = CachedState(current?.foreground, now)
            return current?.foreground
        }

        cache[packageName] = CachedState(state, now)
        return state
    }
}
