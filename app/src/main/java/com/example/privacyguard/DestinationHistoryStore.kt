package com.example.privacyguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object DestinationHistoryStore {
    data class Destination(
        val packageName: String,
        val protocol: String,
        val destinationIp: String,
        val destinationPort: Int,
        val domain: String?,
        val firstSeenMillis: Long,
        val lastSeenMillis: Long,
        val sentBytes: Long,
        val receivedBytes: Long,
        val relayEvents: Long,
        val foregroundEvents: Long,
        val backgroundEvents: Long,
        val unknownStateEvents: Long
    )

    private data class MutableDestination(
        val packageName: String,
        val protocol: String,
        val destinationIp: String,
        val destinationPort: Int,
        var domain: String?,
        var firstSeenMillis: Long,
        var lastSeenMillis: Long,
        var sentBytes: Long,
        var receivedBytes: Long,
        var relayEvents: Long,
        var foregroundEvents: Long,
        var backgroundEvents: Long,
        var unknownStateEvents: Long
    )

    private const val PREFS = "destination_history_prefs"
    private const val KEY_JSON = "destinations"
    private const val MAX_DESTINATIONS = 1200

    private val destinations = LinkedHashMap<String, MutableDestination>()
    private var loaded = false
    private var lastPersistMillis = 0L

    @Synchronized
    fun record(
        context: Context,
        packageName: String,
        protocol: String,
        destinationIp: String,
        destinationPort: Int,
        domain: String?,
        sentBytes: Long,
        receivedBytes: Long,
        foreground: Boolean?
    ) {
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        val key = key(packageName, protocol, destinationIp, destinationPort)
        val existing = destinations[key]
        if (existing == null) {
            destinations[key] = MutableDestination(
                packageName = packageName,
                protocol = protocol,
                destinationIp = destinationIp,
                destinationPort = destinationPort,
                domain = domain,
                firstSeenMillis = now,
                lastSeenMillis = now,
                sentBytes = sentBytes.coerceAtLeast(0L),
                receivedBytes = receivedBytes.coerceAtLeast(0L),
                relayEvents = 1L,
                foregroundEvents = if (foreground == true) 1L else 0L,
                backgroundEvents = if (foreground == false) 1L else 0L,
                unknownStateEvents = if (foreground == null) 1L else 0L
            )
            trimOldest()
        } else {
            existing.lastSeenMillis = now
            if (!domain.isNullOrBlank()) existing.domain = domain
            existing.sentBytes += sentBytes.coerceAtLeast(0L)
            existing.receivedBytes += receivedBytes.coerceAtLeast(0L)
            existing.relayEvents += 1L
            when (foreground) {
                true -> existing.foregroundEvents += 1L
                false -> existing.backgroundEvents += 1L
                null -> existing.unknownStateEvents += 1L
            }
        }
        if (now - lastPersistMillis >= 1000L) persist(context)
    }

    @Synchronized
    fun snapshot(context: Context, packageName: String): List<Destination> {
        ensureLoaded(context)
        return destinations.values
            .asSequence()
            .filter { it.packageName == packageName }
            .sortedByDescending { it.lastSeenMillis }
            .map {
                Destination(
                    packageName = it.packageName,
                    protocol = it.protocol,
                    destinationIp = it.destinationIp,
                    destinationPort = it.destinationPort,
                    domain = it.domain,
                    firstSeenMillis = it.firstSeenMillis,
                    lastSeenMillis = it.lastSeenMillis,
                    sentBytes = it.sentBytes,
                    receivedBytes = it.receivedBytes,
                    relayEvents = it.relayEvents,
                    foregroundEvents = it.foregroundEvents,
                    backgroundEvents = it.backgroundEvents,
                    unknownStateEvents = it.unknownStateEvents
                )
            }
            .toList()
    }

    @Synchronized
    fun clear(context: Context, packageName: String) {
        ensureLoaded(context)
        destinations.entries.removeAll { it.value.packageName == packageName }
        persist(context)
    }

    private fun key(packageName: String, protocol: String, ip: String, port: Int) =
        "$packageName|$protocol|$ip|$port"

    private fun trimOldest() {
        while (destinations.size > MAX_DESTINATIONS) {
            val oldest = destinations.minByOrNull { it.value.lastSeenMillis }?.key ?: return
            destinations.remove(oldest)
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val destination = MutableDestination(
                    packageName = item.getString("package"),
                    protocol = item.getString("protocol"),
                    destinationIp = item.getString("ip"),
                    destinationPort = item.getInt("port"),
                    domain = item.optString("domain").takeIf { it.isNotBlank() },
                    firstSeenMillis = item.getLong("firstSeen"),
                    lastSeenMillis = item.getLong("lastSeen"),
                    sentBytes = item.optLong("sentBytes", 0L),
                    receivedBytes = item.optLong("receivedBytes", 0L),
                    relayEvents = item.optLong("relayEvents", 0L),
                    foregroundEvents = item.optLong("foregroundEvents", 0L),
                    backgroundEvents = item.optLong("backgroundEvents", 0L),
                    unknownStateEvents = item.optLong("unknownStateEvents", 0L)
                )
                destinations[key(destination.packageName, destination.protocol, destination.destinationIp, destination.destinationPort)] = destination
            }
            trimOldest()
        } catch (_: Exception) {
            destinations.clear()
        }
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        destinations.values.forEach {
            array.put(
                JSONObject()
                    .put("package", it.packageName)
                    .put("protocol", it.protocol)
                    .put("ip", it.destinationIp)
                    .put("port", it.destinationPort)
                    .put("domain", it.domain ?: "")
                    .put("firstSeen", it.firstSeenMillis)
                    .put("lastSeen", it.lastSeenMillis)
                    .put("sentBytes", it.sentBytes)
                    .put("receivedBytes", it.receivedBytes)
                    .put("relayEvents", it.relayEvents)
                    .put("foregroundEvents", it.foregroundEvents)
                    .put("backgroundEvents", it.backgroundEvents)
                    .put("unknownStateEvents", it.unknownStateEvents)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, array.toString())
            .apply()
        lastPersistMillis = System.currentTimeMillis()
    }
}
