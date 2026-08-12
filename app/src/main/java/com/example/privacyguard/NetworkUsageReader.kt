package com.example.privacyguard

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process

object NetworkUsageReader {
    data class TrafficUsage(
        val receivedBytes: Long,
        val sentBytes: Long,
        val lastActivityMillis: Long?
    )

    data class PeriodUsage(
        val wifi: TrafficUsage?,
        val mobile: TrafficUsage?,
        val foreground: TrafficUsage?,
        val backgroundDefault: TrafficUsage?
    )

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun readWifi24h(context: Context, uid: Int): Pair<Long, Long>? {
        if (!hasUsageAccess(context)) return null
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val end = System.currentTimeMillis()
        val start = end - 24L * 60L * 60L * 1000L
        val wifi = readNetwork(manager, ConnectivityManager.TYPE_WIFI, uid, start, end) ?: return null
        return wifi.receivedBytes to wifi.sentBytes
    }

    fun readForPeriod(context: Context, uid: Int, durationMillis: Long): PeriodUsage? {
        if (!hasUsageAccess(context)) return null
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val end = System.currentTimeMillis()
        val start = end - durationMillis

        val wifi = readNetwork(manager, ConnectivityManager.TYPE_WIFI, uid, start, end)
        val mobile = readNetwork(manager, ConnectivityManager.TYPE_MOBILE, uid, start, end)

        val foreground = addTraffic(
            readState(manager, ConnectivityManager.TYPE_WIFI, uid, start, end, NetworkStats.Bucket.STATE_FOREGROUND),
            readState(manager, ConnectivityManager.TYPE_MOBILE, uid, start, end, NetworkStats.Bucket.STATE_FOREGROUND)
        )
        val background = addTraffic(
            readState(manager, ConnectivityManager.TYPE_WIFI, uid, start, end, NetworkStats.Bucket.STATE_DEFAULT),
            readState(manager, ConnectivityManager.TYPE_MOBILE, uid, start, end, NetworkStats.Bucket.STATE_DEFAULT)
        )

        return PeriodUsage(wifi, mobile, foreground, background)
    }

    private fun readNetwork(
        manager: NetworkStatsManager,
        networkType: Int,
        uid: Int,
        start: Long,
        end: Long
    ): TrafficUsage? = try {
        manager.queryDetailsForUid(networkType, null, start, end, uid).use { stats ->
            sumBuckets(stats)
        }
    } catch (_: Exception) {
        null
    }

    private fun readState(
        manager: NetworkStatsManager,
        networkType: Int,
        uid: Int,
        start: Long,
        end: Long,
        state: Int
    ): TrafficUsage? = try {
        manager.queryDetailsForUidTagState(
            networkType,
            null,
            start,
            end,
            uid,
            NetworkStats.Bucket.TAG_NONE,
            state
        ).use { stats ->
            sumBuckets(stats)
        }
    } catch (_: Exception) {
        null
    }

    private fun sumBuckets(stats: NetworkStats): TrafficUsage {
        var rx = 0L
        var tx = 0L
        var lastActivity: Long? = null
        val bucket = NetworkStats.Bucket()
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bucket)
            rx += bucket.rxBytes
            tx += bucket.txBytes
            if (bucket.rxBytes > 0L || bucket.txBytes > 0L) {
                val end = bucket.endTimeStamp
                lastActivity = if (lastActivity == null || end > lastActivity) end else lastActivity
            }
        }
        return TrafficUsage(rx, tx, lastActivity)
    }

    private fun addTraffic(a: TrafficUsage?, b: TrafficUsage?): TrafficUsage? {
        if (a == null && b == null) return null
        val rx = (a?.receivedBytes ?: 0L) + (b?.receivedBytes ?: 0L)
        val tx = (a?.sentBytes ?: 0L) + (b?.sentBytes ?: 0L)
        val last = listOfNotNull(a?.lastActivityMillis, b?.lastActivityMillis).maxOrNull()
        return TrafficUsage(rx, tx, last)
    }
}
