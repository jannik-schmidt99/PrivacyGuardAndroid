package com.example.privacyguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

object DnsObservationStore {
    data class Mapping(
        val packageName: String,
        val ip: String,
        val domain: String,
        val firstSeenMillis: Long,
        val lastSeenMillis: Long
    )

    private data class MutableMapping(
        val packageName: String,
        val ip: String,
        val domain: String,
        var firstSeenMillis: Long,
        var lastSeenMillis: Long
    )

    private const val PREFS = "dns_observation_prefs"
    private const val KEY_JSON = "mappings"
    private const val MAX_MAPPINGS = 1000
    private const val LOOKUP_MAX_AGE_MILLIS = 30L * 60L * 1000L

    private val mappings = LinkedHashMap<String, MutableMapping>()
    private var loaded = false

    @Synchronized
    fun observeResponse(
        context: Context,
        packageName: String,
        data: ByteArray,
        offset: Int,
        length: Int
    ) {
        ensureLoaded(context)
        val end = offset + length
        if (length < 12 || end > data.size) return

        val flags = u16(data, offset + 2, end) ?: return
        if (flags and 0x8000 == 0) return // DNS response bit must be set.
        val questionCount = u16(data, offset + 4, end) ?: return
        val answerCount = u16(data, offset + 6, end) ?: return
        if (questionCount <= 0) return

        var index = offset + 12
        val firstQuestion = readName(data, index, offset, end) ?: return
        val queryDomain = firstQuestion.name.trimEnd('.').lowercase()
        index = firstQuestion.nextIndex
        if (index + 4 > end || queryDomain.isBlank()) return
        index += 4 // QTYPE + QCLASS

        // Skip any additional questions.
        repeat(questionCount - 1) {
            val question = readName(data, index, offset, end) ?: return
            index = question.nextIndex
            if (index + 4 > end) return
            index += 4
        }

        val now = System.currentTimeMillis()
        var changed = false
        repeat(answerCount) {
            val answerName = readName(data, index, offset, end) ?: return@repeat
            index = answerName.nextIndex
            if (index + 10 > end) return@repeat
            val type = u16(data, index, end) ?: return@repeat
            val clazz = u16(data, index + 2, end) ?: return@repeat
            val rdLength = u16(data, index + 8, end) ?: return@repeat
            index += 10
            if (index + rdLength > end) return@repeat

            val address = when {
                clazz == 1 && type == 1 && rdLength == 4 ->
                    InetAddress.getByAddress(data.copyOfRange(index, index + 4))
                clazz == 1 && type == 28 && rdLength == 16 ->
                    InetAddress.getByAddress(data.copyOfRange(index, index + 16))
                else -> null
            }

            if (address != null) {
                val ip = address.hostAddress
                if (!ip.isNullOrBlank()) {
                    val key = key(packageName, ip, queryDomain)
                    val existing = mappings[key]
                    if (existing == null) {
                        mappings[key] = MutableMapping(packageName, ip, queryDomain, now, now)
                    } else {
                        existing.lastSeenMillis = now
                    }
                    changed = true
                }
            }
            index += rdLength
        }

        if (changed) {
            trimOldest()
            persist(context)
        }
    }

    @Synchronized
    fun lookup(context: Context, packageName: String, ip: String): String? {
        ensureLoaded(context)
        val cutoff = System.currentTimeMillis() - LOOKUP_MAX_AGE_MILLIS
        return mappings.values
            .asSequence()
            .filter { it.packageName == packageName && it.ip == ip && it.lastSeenMillis >= cutoff }
            .maxByOrNull { it.lastSeenMillis }
            ?.domain
    }

    @Synchronized
    fun mappingsForApp(context: Context, packageName: String): List<Mapping> {
        ensureLoaded(context)
        return mappings.values
            .asSequence()
            .filter { it.packageName == packageName }
            .sortedByDescending { it.lastSeenMillis }
            .map { Mapping(it.packageName, it.ip, it.domain, it.firstSeenMillis, it.lastSeenMillis) }
            .toList()
    }

    private fun readName(data: ByteArray, start: Int, base: Int, end: Int): NameResult? {
        var index = start
        var returnIndex = -1
        var jumps = 0
        val labels = mutableListOf<String>()
        val visited = hashSetOf<Int>()

        while (index in base until end && jumps < 32) {
            if (!visited.add(index)) return null
            val size = data[index].toInt() and 0xff
            when {
                size == 0 -> {
                    if (returnIndex < 0) returnIndex = index + 1
                    return NameResult(labels.joinToString("."), returnIndex)
                }
                size and 0xC0 == 0xC0 -> {
                    if (index + 1 >= end) return null
                    val pointer = ((size and 0x3f) shl 8) or (data[index + 1].toInt() and 0xff)
                    val target = base + pointer
                    if (target !in base until end) return null
                    if (returnIndex < 0) returnIndex = index + 2
                    index = target
                    jumps++
                }
                size and 0xC0 != 0 -> return null
                else -> {
                    val labelStart = index + 1
                    val labelEnd = labelStart + size
                    if (labelEnd > end) return null
                    labels += String(data, labelStart, size, Charsets.UTF_8)
                    index = labelEnd
                }
            }
        }
        return null
    }

    private fun u16(data: ByteArray, index: Int, end: Int): Int? {
        if (index < 0 || index + 1 >= end) return null
        return ((data[index].toInt() and 0xff) shl 8) or (data[index + 1].toInt() and 0xff)
    }

    private fun key(packageName: String, ip: String, domain: String) = "$packageName|$ip|$domain"

    private fun trimOldest() {
        while (mappings.size > MAX_MAPPINGS) {
            val oldest = mappings.minByOrNull { it.value.lastSeenMillis }?.key ?: return
            mappings.remove(oldest)
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
                val mapping = MutableMapping(
                    packageName = item.getString("package"),
                    ip = item.getString("ip"),
                    domain = item.getString("domain"),
                    firstSeenMillis = item.getLong("firstSeen"),
                    lastSeenMillis = item.getLong("lastSeen")
                )
                mappings[key(mapping.packageName, mapping.ip, mapping.domain)] = mapping
            }
            trimOldest()
        } catch (_: Exception) {
            mappings.clear()
        }
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        mappings.values.forEach {
            array.put(
                JSONObject()
                    .put("package", it.packageName)
                    .put("ip", it.ip)
                    .put("domain", it.domain)
                    .put("firstSeen", it.firstSeenMillis)
                    .put("lastSeen", it.lastSeenMillis)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, array.toString())
            .apply()
    }

    private data class NameResult(val name: String, val nextIndex: Int)
}
