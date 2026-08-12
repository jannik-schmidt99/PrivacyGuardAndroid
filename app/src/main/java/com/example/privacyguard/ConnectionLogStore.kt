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
        val firstSeenMillis: Long,
        val lastSeenMillis: Long,
        val capturedBytes: Long,
        val packetCount: Long
    )

    private data class MutableRecord(
        val packageName: String,
        val protocol: String,
        val sourcePort: Int,
        val destinationIp: String,
        val destinationPort: Int,
        var firstSeenMillis: Long,
        var lastSeenMillis: Long,
        var capturedBytes: Long,
        var packetCount: Long
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
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        val destination = packet.destinationAddress.hostAddress ?: return
        val key = listOf(
            packageName,
            packet.protocolLabel,
            packet.sourcePort.toString(),
            destination,
            packet.destinationPort.toString()
        ).joinToString("|")

        val existing = records[key]
        if (existing == null) {
            records[key] = MutableRecord(
                packageName = packageName,
                protocol = packet.protocolLabel,
                sourcePort = packet.sourcePort,
                destinationIp = destination,
                destinationPort = packet.destinationPort,
                firstSeenMillis = now,
                lastSeenMillis = now,
                capturedBytes = packet.packetBytes.toLong(),
                packetCount = 1L
            )
            trimOldest()
        } else {
            existing.lastSeenMillis = now
            existing.capturedBytes += packet.packetBytes.toLong()
            existing.packetCount += 1L
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
                    firstSeenMillis = it.firstSeenMillis,
                    lastSeenMillis = it.lastSeenMillis,
                    capturedBytes = it.capturedBytes,
                    packetCount = it.packetCount
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
                val record = MutableRecord(
                    packageName = item.getString("package"),
                    protocol = item.getString("protocol"),
                    sourcePort = item.getInt("sourcePort"),
                    destinationIp = item.getString("destinationIp"),
                    destinationPort = item.getInt("destinationPort"),
                    firstSeenMillis = item.getLong("firstSeen"),
                    lastSeenMillis = item.getLong("lastSeen"),
                    capturedBytes = item.getLong("bytes"),
                    packetCount = item.getLong("packets")
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
                    .put("firstSeen", record.firstSeenMillis)
                    .put("lastSeen", record.lastSeenMillis)
                    .put("bytes", record.capturedBytes)
                    .put("packets", record.packetCount)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, array.toString())
            .apply()
        lastPersistMillis = System.currentTimeMillis()
    }
}
