package com.example.privacyguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object MonitorSessionStore {
    data class Session(
        val packageName: String,
        val startMillis: Long,
        val endMillis: Long?
    )

    private data class MutableSession(
        val packageName: String,
        val startMillis: Long,
        var endMillis: Long?
    )

    private const val PREFS = "monitor_session_prefs"
    private const val KEY_JSON = "sessions"
    private const val RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_SESSIONS = 500

    private val sessions = mutableListOf<MutableSession>()
    private var loaded = false

    @Synchronized
    fun start(context: Context, packageName: String, now: Long = System.currentTimeMillis()) {
        ensureLoaded(context)
        sessions.filter { it.endMillis == null && it.packageName != packageName }
            .forEach { it.endMillis = now }
        if (sessions.any { it.packageName == packageName && it.endMillis == null }) return
        sessions.add(MutableSession(packageName, now, null))
        prune(now)
        persist(context)
    }

    @Synchronized
    fun stop(context: Context, packageName: String, now: Long = System.currentTimeMillis()) {
        ensureLoaded(context)
        var changed = false
        sessions.filter { it.packageName == packageName && it.endMillis == null }
            .forEach {
                it.endMillis = now.coerceAtLeast(it.startMillis)
                changed = true
            }
        if (changed) {
            prune(now)
            persist(context)
        }
    }

    @Synchronized
    fun observedMillis(
        context: Context,
        packageName: String,
        windowStartMillis: Long,
        windowEndMillis: Long,
        now: Long = System.currentTimeMillis()
    ): Long {
        ensureLoaded(context)
        if (windowEndMillis <= windowStartMillis) return 0L
        return sessions.asSequence()
            .filter { it.packageName == packageName }
            .sumOf { session ->
                val sessionEnd = (session.endMillis ?: now).coerceAtLeast(session.startMillis)
                val overlapStart = maxOf(session.startMillis, windowStartMillis)
                val overlapEnd = minOf(sessionEnd, windowEndMillis)
                (overlapEnd - overlapStart).coerceAtLeast(0L)
            }
    }

    @Synchronized
    fun snapshot(context: Context, packageName: String): List<Session> {
        ensureLoaded(context)
        return sessions.asSequence()
            .filter { it.packageName == packageName }
            .sortedByDescending { it.startMillis }
            .map { Session(it.packageName, it.startMillis, it.endMillis) }
            .toList()
    }

    @Synchronized
    fun clear(context: Context, packageName: String) {
        ensureLoaded(context)
        sessions.removeAll { it.packageName == packageName }
        persist(context)
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                sessions.add(
                    MutableSession(
                        packageName = item.getString("package"),
                        startMillis = item.getLong("start"),
                        endMillis = if (item.has("end") && !item.isNull("end")) item.getLong("end") else null
                    )
                )
            }
            prune(System.currentTimeMillis())
        } catch (_: Exception) {
            sessions.clear()
        }
    }

    private fun prune(now: Long) {
        val cutoff = now - RETENTION_MILLIS
        sessions.removeAll { session ->
            val end = session.endMillis
            end != null && end < cutoff
        }
        while (sessions.size > MAX_SESSIONS) {
            val oldestIndex = sessions.indices.minByOrNull { sessions[it].startMillis } ?: return
            sessions.removeAt(oldestIndex)
        }
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject()
                    .put("package", session.packageName)
                    .put("start", session.startMillis)
                    .put("end", session.endMillis ?: JSONObject.NULL)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, array.toString())
            .apply()
    }
}
