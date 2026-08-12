package com.example.privacyguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat

class FirewallVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        rebuildFirewall()
        return START_STICKY
    }

    private fun rebuildFirewall() {
        vpnInterface?.close()
        vpnInterface = null

        val blocked = BlockedAppsStore.get(this)
        if (blocked.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val builder = Builder()
            .setSession("Privacy Guard Firewall")
            .setMtu(1500)
            .addAddress("10.77.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)

        var validApps = 0
        for (packageName in blocked) {
            try {
                builder.addAllowedApplication(packageName)
                validApps++
            } catch (_: Exception) {
                // App may have been uninstalled; ignore it.
            }
        }

        if (validApps == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Deliberately no packet forwarder: all packets from allowed (blocked)
        // apps enter this TUN interface and are dropped, creating a local firewall.
        vpnInterface = builder.establish()
    }

    private fun startInForeground() {
        val channelId = "firewall"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Privacy Firewall", NotificationManager.IMPORTANCE_LOW)
        )

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Privacy Guard aktiv")
            .setContentText("Ausgewählte Apps haben keinen Internetzugriff.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        vpnInterface?.close()
        vpnInterface = null
        super.onDestroy()
    }
}
