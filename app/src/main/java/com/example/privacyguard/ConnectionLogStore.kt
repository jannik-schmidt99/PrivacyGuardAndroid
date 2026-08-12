package com.example.privacyguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ConnectionLogStore {
    data class Record(
        val packageName: String,
        val protocol: String,
        val sourcePort: Int,
        val destinationIp: String,
        val destinationPort: Int,
        val domain: String?,
        val firstSeenMillis: Long,
        val lastSeenMillis: Long,
        val sentBytes: Long,
        val receivedBytes: Long,
        val packetCount: Long,
        val foregroundEvents: Long,
        val backgroundEvents: Long,
        val unknownStateEvents: Long
    ) {
        val capturedBytes: Long get() = sentBytes + receivedBytes
    }

    private data class MutableRecord(
        val packageName: String,
        val protocol: String,
        val sourcePort: Int,
        val destinationIp: String,
        val destinationPort: Int,
        var domain: String?,
        var firstSeenMillis: Long,
        var lastSeenMillis: Long,
        var sentBytes: Long,
        var receivedBytes: Long,
        var packetCount: Long,
        var foregroundEvents: Long,
        var backgroundEvents: Long,
        var unknownStateEvents: Long
    )

    private const val PREFS = "connection_log_prefs"
    private const val KEY_JSON = "records"
    private const val MAX_RECORDS = 250

    private val records = LinkedHashMap<String, MutableRecord>()
    private var loaded = false
    private var lastPersistMillis = 0L

    @Synchronized
    fun clear(context: Context, packageName: String) {
        ensureLoaded(context)
        records.entries.removeAll { it.value.packageName == packageName }
        persist(context, force = true)
    }

    @Synchronized
    fun record(context: Context, packageName: String, packet: PacketInspector.ParsedPacket) {
        val destination = packet.destinationAddress.hostAddress ?: return
        recordTransfer(
            context = context,
            packageName = packageName,
            protocol = packet.protocolLabel,
            sourcePort = packet.sourcePort,
            destinationIp = destination,
            destinationPort = packet.destinationPort,
            sentBytes = packet.packetBytes.toLong(),
            receivedBytes = 0L,
            packetDelta = 1L
        )
    }

    @Synchronized
    fun recordTransfer(
        context: Context,
        packageName: String,
        protocol: String,
        sourcePort: Int,
        destinationIp: String,
        destinationPort: Int,
        sentBytes: Long,
        receivedBytes: Long,
        packetDelta: Long = 1L
    ) {
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        val domain = DnsObservationStore.lookup(context, packageName, destinationIp)
        val foreground = AppVisibilityTracker.isLikelyForeground(context, packageName)
        val key = listOf(
            packageName,
            protocol,
            sourcePort.toString(),
            destinationIp,
            destinationPort.toString()
        ).joinToString("|")

        val safeSent = sentBytes.coerceAtLeast(0L)
        val safeReceived = receivedBytes.coerceAtLeast(0L)
        val safePackets = packetDelta.coerceAtLeast(0L)
        val existing = records[key]
        if (existing == null) {
            records[key] = MutableRecord(
                packageName = packageName,
                protocol = protocol,
                sourcePort = sourcePort,
                destinationIp = destinationIp,
                destinationPort = destinationPort,
                domain = domain,
                firstSeenMillis = now,
                lastSeenMillis = now,
                sentBytes = safeSent,
                receivedBytes = safeReceived,
                packetCount = safePackets,
                foregroundEvents = if (foreground == true) safePackets else 0L,
                backgroundEvents = if (foreground == false) safePackets else 0L,
                unknownStateEvents = if (foreground == null) safePackets else 0L
            )
            trimOldest()
        } else {
            existing.lastSeenMillis = now
            if (!domain.isNullOrBlank()) existing.domain = domain
            existing.sentBytes += safeSent
            existing.receivedBytes += safeReceived
            existing.packetCount += safePackets
            when (foreground) {
                true -> existing.foregroundEvents += safePackets
                false -> existing.backgroundEvents += safePackets
                null -> existing.unknownStateEvents += safePackets
            }
        }

        DestinationHistoryStore.record(
            context = context,
            packageName = packageName,
            protocol = protocol,
            destinationIp = destinationIp,
            destinationPort = destinationPort,
            domain = domain,
            sentBytes = safeSent,
            receivedBytes = safeReceived,
            foreground = foreground
        )

        val liveState = LiveMonitorStore.active(context)
        if (liveState?.mode == LiveMonitorStore.Mode.PASS_THROUGH &&
            liveState.packageName == packageName
        ) {
            TrafficTimelineStore.record(
                context = context,
                packageName = packageName,
                protocol = protocol,
                destinationIp = destinationIp,
                destinationPort = destinationPort,
                domain = domain,
                sentBytes = safeSent,
                receivedBytes = safeReceived,
                foreground = foreground,
                relayEvents = safePackets,
                now = now
            )
        }

        persist(context, force = now - lastPersistMillis >= 1000L)
    }

    @Synchronized
    fun snapshot(context: Context, packageName: String): List<Record> {
        ensureLoaded(context)
        return records.values
            .asSequence()
            .filter { it.packageName == packageName }
            .sortedByDescending { it.lastSeenMillis }
            .map {
                Record(
                    packageName = it.packageName,
                    protocol = it.protocol,
                    sourcePort = it.sourcePort,
                    destinationIp = it.destinationIp,
                    destinationPort = it.destinationPort,
                    domain = it.domain,
                    firstSeenMillis = it.firstSeenMillis,
                    lastSeenMillis = it.lastSeenMillis,
                    sentBytes = it.sentBytes,
                    receivedBytes = it.receivedBytes,
                    packetCount = it.packetCount,
                    foregroundEvents = it.foregroundEvents,
                    backgroundEvents = it.backgroundEvents,
                    unknownStateEvents = it.unknownStateEvents
                )
            }
            .toList()
    }

    private fun trimOldest() {
        while (records.size > MAX_RECORDS) {
            val oldestKey = records.minByOrNull { it.value.lastSeenMillis }?.key ?: return
            records.remove(oldestKey)
        }
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
                val legacyBytes = item.optLong("bytes", 0L)
                val packetCount = item.optLong("packets", 0L)
                val record = MutableRecord(
                    packageName = item.getString("package"),
                    protocol = item.getString("protocol"),
                    sourcePort = item.optInt("sourcePort", 0),
                    destinationIp = item.getString("destinationIp"),
                    destinationPort = item.getInt("destinationPort"),
                    domain = item.optString("domain").takeIf { it.isNotBlank() },
                    firstSeenMillis = item.getLong("firstSeen"),
                    lastSeenMillis = item.getLong("lastSeen"),
                    sentBytes = if (item.has("sentBytes")) item.optLong("sentBytes", 0L) else legacyBytes,
                    receivedBytes = item.optLong("receivedBytes", 0L),
                    packetCount = packetCount,
                    foregroundEvents = item.optLong("foregroundEvents", 0L),
                    backgroundEvents = item.optLong("backgroundEvents", 0L),
                    unknownStateEvents = item.optLong(
                        "unknownStateEvents",
                        if (!item.has("foregroundEvents") && !item.has("backgroundEvents")) packetCount else 0L
                    )
                )
                val key = listOf(
                    record.packageName,
                    record.protocol,
                    record.sourcePort.toString(),
                    record.destinationIp,
                    record.destinationPort.toString()
                ).joinToString("|")
                records[key] = record
            }
            trimOldest()
        } catch (_: Exception) {
            records.clear()
        }
    }

    private fun persist(context: Context, force: Boolean) {
        if (!force) return
        val array = JSONArray()
        records.values.forEach { record ->
            array.put(
                JSONObject()
                    .put("package", record.packageName)
                    .put("protocol", record.protocol)
                    .put("sourcePort", record.sourcePort)
                    .put("destinationIp", record.destinationIp)
                    .put("destinationPort", record.destinationPort)
                    .put("domain", record.domain ?: "")
                    .put("firstSeen", record.firstSeenMillis)
                    .put("lastSeen", record.lastSeenMillis)
                    .put("sentBytes", record.sentBytes)
                    .put("receivedBytes", record.receivedBytes)
                    .put("bytes", record.sentBytes + record.receivedBytes)
                    .put("packets", record.packetCount)
                    .put("foregroundEvents", record.foregroundEvents)
                    .put("backgroundEvents", record.backgroundEvents)
                    .put("unknownStateEvents", record.unknownStateEvents)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, array.toString())
            .apply()
        lastPersistMillis = System.currentTimeMillis()
    }
}
