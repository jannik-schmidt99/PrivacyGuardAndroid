package com.example.privacyguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TrafficTimelineStore {
    data class WindowSummary(
        val sentBytes: Long,
        val receivedBytes: Long,
        val foregroundSentBytes: Long,
        val backgroundSentBytes: Long,
        val unknownSentBytes: Long,
        val destinationCount: Int,
        val activeHours: Int
    ) {
        val totalBytes: Long get() = sentBytes + receivedBytes
    }

    data class DestinationTraffic(
        val protocol: String,
        val destinationIp: String,
        val destinationPort: Int,
        val domain: String?,
        val sentBytes: Long,
        val receivedBytes: Long,
        val backgroundSentBytes: Long,
        val lastActivityMillis: Long
    )

    data class HourSummary(
        val hourStartMillis: Long,
        val sentBytes: Long,
        val receivedBytes: Long,
        val backgroundSentBytes: Long,
        val destinationCount: Int
    )

    data class Report(
        val currentStartMillis: Long,
        val currentEndMillis: Long,
        val previousStartMillis: Long,
        val previousEndMillis: Long,
        val current: WindowSummary,
        val previous: WindowSummary,
        val currentObservedMillis: Long,
        val previousObservedMillis: Long,
        val topUploads: List<DestinationTraffic>,
        val topBackgroundUploads: List<DestinationTraffic>,
        val recentHours: List<HourSummary>
    ) {
        val comparisonReady: Boolean
            get() = currentObservedMillis >= MIN_COMPARE_OBSERVED_MILLIS &&
                previousObservedMillis >= MIN_COMPARE_OBSERVED_MILLIS

        fun currentUploadBytesPerObservedHour(): Double? =
            ratePerObservedHour(current.sentBytes, currentObservedMillis)

        fun previousUploadBytesPerObservedHour(): Double? =
            ratePerObservedHour(previous.sentBytes, previousObservedMillis)
    }

    private data class MutableBucket(
        val packageName: String,
        val hourStartMillis: Long,
        val protocol: String,
        val destinationIp: String,
        val destinationPort: Int,
        var domain: String?,
        var sentBytes: Long,
        var receivedBytes: Long,
        var foregroundSentBytes: Long,
        var backgroundSentBytes: Long,
        var unknownSentBytes: Long,
        var relayEvents: Long,
        var lastActivityMillis: Long
    )

    private const val PREFS = "traffic_timeline_prefs"
    private const val KEY_JSON = "buckets"
    private const val HOUR_MILLIS = 60L * 60L * 1000L
    private const val RETENTION_MILLIS = 7L * 24L * HOUR_MILLIS
    private const val MAX_BUCKETS = 6000
    private const val PERSIST_INTERVAL_MILLIS = 5000L
    private const val MIN_COMPARE_OBSERVED_MILLIS = 5L * 60L * 1000L

    private val buckets = LinkedHashMap<String, MutableBucket>()
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
        foreground: Boolean?,
        relayEvents: Long = 1L,
        now: Long = System.currentTimeMillis()
    ) {
        ensureLoaded(context)
        val hourStart = floorHour(now)
        val key = key(packageName, hourStart, protocol, destinationIp, destinationPort)
        val safeSent = sentBytes.coerceAtLeast(0L)
        val safeReceived = receivedBytes.coerceAtLeast(0L)
        val safeEvents = relayEvents.coerceAtLeast(0L)
        val existing = buckets[key]

        if (existing == null) {
            buckets[key] = MutableBucket(
                packageName = packageName,
                hourStartMillis = hourStart,
                protocol = protocol,
                destinationIp = destinationIp,
                destinationPort = destinationPort,
                domain = domain,
                sentBytes = safeSent,
                receivedBytes = safeReceived,
                foregroundSentBytes = if (foreground == true) safeSent else 0L,
                backgroundSentBytes = if (foreground == false) safeSent else 0L,
                unknownSentBytes = if (foreground == null) safeSent else 0L,
                relayEvents = safeEvents,
                lastActivityMillis = now
            )
        } else {
            if (!domain.isNullOrBlank()) existing.domain = domain
            existing.sentBytes += safeSent
            existing.receivedBytes += safeReceived
            when (foreground) {
                true -> existing.foregroundSentBytes += safeSent
                false -> existing.backgroundSentBytes += safeSent
                null -> existing.unknownSentBytes += safeSent
            }
            existing.relayEvents += safeEvents
            existing.lastActivityMillis = now
        }

        prune(now)
        if (now - lastPersistMillis >= PERSIST_INTERVAL_MILLIS) persist(context)
    }

    @Synchronized
    fun report(
        context: Context,
        packageName: String,
        now: Long = System.currentTimeMillis()
    ): Report {
        ensureLoaded(context)
        prune(now)
        // Flush the latest in-memory counters when the user opens the report.
        persist(context)

        val currentHour = floorHour(now)
        val currentStart = currentHour - 23L * HOUR_MILLIS
        val currentEnd = currentHour + HOUR_MILLIS
        val previousStart = currentStart - 24L * HOUR_MILLIS
        val previousEnd = currentStart

        val appBuckets = buckets.values.filter { it.packageName == packageName }
        val currentBuckets = appBuckets.filter { it.hourStartMillis in currentStart until currentEnd }
        val previousBuckets = appBuckets.filter { it.hourStartMillis in previousStart until previousEnd }

        return Report(
            currentStartMillis = currentStart,
            currentEndMillis = currentEnd,
            previousStartMillis = previousStart,
            previousEndMillis = previousEnd,
            current = summarize(currentBuckets),
            previous = summarize(previousBuckets),
            currentObservedMillis = MonitorSessionStore.observedMillis(
                context,
                packageName,
                currentStart,
                minOf(currentEnd, now),
                now
            ),
            previousObservedMillis = MonitorSessionStore.observedMillis(
                context,
                packageName,
                previousStart,
                previousEnd,
                now
            ),
            topUploads = aggregateDestinations(currentBuckets)
                .sortedByDescending { it.sentBytes }
                .take(5),
            topBackgroundUploads = aggregateDestinations(currentBuckets)
                .filter { it.backgroundSentBytes > 0L }
                .sortedByDescending { it.backgroundSentBytes }
                .take(5),
            recentHours = summarizeHours(currentBuckets)
                .sortedByDescending { it.hourStartMillis }
                .take(8)
        )
    }

    @Synchronized
    fun clear(context: Context, packageName: String) {
        ensureLoaded(context)
        buckets.entries.removeAll { it.value.packageName == packageName }
        persist(context)
    }

    private fun summarize(source: List<MutableBucket>): WindowSummary {
        val endpoints = HashSet<String>()
        val hours = HashSet<Long>()
        var sent = 0L
        var received = 0L
        var foregroundSent = 0L
        var backgroundSent = 0L
        var unknownSent = 0L

        source.forEach { bucket ->
            sent += bucket.sentBytes
            received += bucket.receivedBytes
            foregroundSent += bucket.foregroundSentBytes
            backgroundSent += bucket.backgroundSentBytes
            unknownSent += bucket.unknownSentBytes
            endpoints += endpointKey(bucket.protocol, bucket.destinationIp, bucket.destinationPort)
            hours += bucket.hourStartMillis
        }

        return WindowSummary(
            sentBytes = sent,
            receivedBytes = received,
            foregroundSentBytes = foregroundSent,
            backgroundSentBytes = backgroundSent,
            unknownSentBytes = unknownSent,
            destinationCount = endpoints.size,
            activeHours = hours.size
        )
    }

    private fun aggregateDestinations(source: List<MutableBucket>): List<DestinationTraffic> {
        data class MutableDestinationTraffic(
            val protocol: String,
            val ip: String,
            val port: Int,
            var domain: String?,
            var sent: Long = 0L,
            var received: Long = 0L,
            var backgroundSent: Long = 0L,
            var lastActivity: Long = 0L
        )

        val grouped = LinkedHashMap<String, MutableDestinationTraffic>()
        source.forEach { bucket ->
            val key = endpointKey(bucket.protocol, bucket.destinationIp, bucket.destinationPort)
            val item = grouped.getOrPut(key) {
                MutableDestinationTraffic(
                    protocol = bucket.protocol,
                    ip = bucket.destinationIp,
                    port = bucket.destinationPort,
                    domain = bucket.domain
                )
            }
            if (!bucket.domain.isNullOrBlank()) item.domain = bucket.domain
            item.sent += bucket.sentBytes
            item.received += bucket.receivedBytes
            item.backgroundSent += bucket.backgroundSentBytes
            item.lastActivity = maxOf(item.lastActivity, bucket.lastActivityMillis)
        }

        return grouped.values.map {
            DestinationTraffic(
                protocol = it.protocol,
                destinationIp = it.ip,
                destinationPort = it.port,
                domain = it.domain,
                sentBytes = it.sent,
                receivedBytes = it.received,
                backgroundSentBytes = it.backgroundSent,
                lastActivityMillis = it.lastActivity
            )
        }
    }

    private fun summarizeHours(source: List<MutableBucket>): List<HourSummary> {
        data class MutableHour(
            var sent: Long = 0L,
            var received: Long = 0L,
            var backgroundSent: Long = 0L,
            val endpoints: MutableSet<String> = HashSet()
        )

        val grouped = sortedMapOf<Long, MutableHour>()
        source.forEach { bucket ->
            val hour = grouped.getOrPut(bucket.hourStartMillis) { MutableHour() }
            hour.sent += bucket.sentBytes
            hour.received += bucket.receivedBytes
            hour.backgroundSent += bucket.backgroundSentBytes
            hour.endpoints += endpointKey(bucket.protocol, bucket.destinationIp, bucket.destinationPort)
        }

        return grouped.map { (hourStart, data) ->
            HourSummary(
                hourStartMillis = hourStart,
                sentBytes = data.sent,
                receivedBytes = data.received,
                backgroundSentBytes = data.backgroundSent,
                destinationCount = data.endpoints.size
            )
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
                val bucket = MutableBucket(
                    packageName = item.getString("package"),
                    hourStartMillis = item.getLong("hour"),
                    protocol = item.getString("protocol"),
                    destinationIp = item.getString("ip"),
                    destinationPort = item.getInt("port"),
                    domain = item.optString("domain").takeIf { it.isNotBlank() },
                    sentBytes = item.optLong("sent", 0L),
                    receivedBytes = item.optLong("received", 0L),
                    foregroundSentBytes = item.optLong("foregroundSent", 0L),
                    backgroundSentBytes = item.optLong("backgroundSent", 0L),
                    unknownSentBytes = item.optLong("unknownSent", 0L),
                    relayEvents = item.optLong("events", 0L),
                    lastActivityMillis = item.optLong("lastActivity", item.getLong("hour"))
                )
                buckets[key(
                    bucket.packageName,
                    bucket.hourStartMillis,
                    bucket.protocol,
                    bucket.destinationIp,
                    bucket.destinationPort
                )] = bucket
            }
            prune(System.currentTimeMillis())
        } catch (_: Exception) {
            buckets.clear()
        }
    }

    private fun prune(now: Long) {
        val cutoff = now - RETENTION_MILLIS
        buckets.entries.removeAll { it.value.hourStartMillis < cutoff }
        while (buckets.size > MAX_BUCKETS) {
            val oldest = buckets.minByOrNull { it.value.hourStartMillis }?.key ?: return
            buckets.remove(oldest)
        }
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        buckets.values.forEach { bucket ->
            array.put(
                JSONObject()
                    .put("package", bucket.packageName)
                    .put("hour", bucket.hourStartMillis)
                    .put("protocol", bucket.protocol)
                    .put("ip", bucket.destinationIp)
                    .put("port", bucket.destinationPort)
                    .put("domain", bucket.domain ?: "")
                    .put("sent", bucket.sentBytes)
                    .put("received", bucket.receivedBytes)
                    .put("foregroundSent", bucket.foregroundSentBytes)
                    .put("backgroundSent", bucket.backgroundSentBytes)
                    .put("unknownSent", bucket.unknownSentBytes)
                    .put("events", bucket.relayEvents)
                    .put("lastActivity", bucket.lastActivityMillis)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, array.toString())
            .apply()
        lastPersistMillis = System.currentTimeMillis()
    }

    private fun key(packageName: String, hour: Long, protocol: String, ip: String, port: Int): String =
        "$packageName|$hour|$protocol|$ip|$port"

    private fun endpointKey(protocol: String, ip: String, port: Int): String =
        "$protocol|$ip|$port"

    private fun floorHour(timeMillis: Long): Long = timeMillis - (timeMillis % HOUR_MILLIS)

    private fun ratePerObservedHour(bytes: Long, observedMillis: Long): Double? {
        if (observedMillis <= 0L) return null
        return bytes.toDouble() * HOUR_MILLIS.toDouble() / observedMillis.toDouble()
    }
}
