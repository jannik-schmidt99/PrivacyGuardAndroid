package com.example.privacyguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat

class FirewallVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingStop: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        applyFirewallRules()
        return START_STICKY
    }

    private fun baseBuilder(): Builder = Builder()
        .setSession("Privacy Guard Firewall")
        .setMtu(1500)
        .addAddress("10.77.0.1", 32)
        .addRoute("0.0.0.0", 0)
        .addRoute("::", 0)

    private fun applyFirewallRules() {
        pendingStop?.let { handler.removeCallbacks(it) }
        pendingStop = null

        val blocked = BlockedAppsStore.get(this)
        if (blocked.isEmpty()) {
            releaseAllAppsFromVpn()
            return
        }

        val builder = baseBuilder()
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
            releaseAllAppsFromVpn()
            return
        }

        // Establish the replacement interface BEFORE closing the old file
        // descriptor. Android deactivates the previous VPN interface when a
        // new one is established. This forces the per-app UID routing table to
        // be replaced as one network transition instead of stop/wait/start.
        val oldInterface = vpnInterface
        val newInterface = try {
            builder.establish()
        } catch (_: Exception) {
            null
        }

        if (newInterface != null) {
            vpnInterface = newInterface
            try {
                oldInterface?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun releaseAllAppsFromVpn() {
        val oldInterface = vpnInterface

        if (oldInterface == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // An empty addAllowedApplication list means ALL apps would use the
        // VPN, so we must not establish an empty firewall. Instead establish a
        // short-lived replacement VPN that contains only Privacy Guard itself.
        // Establishing it makes Android atomically deactivate the old per-app
        // VPN and therefore releases previously blocked apps such as WhatsApp.
        val releaseInterface = try {
            baseBuilder()
                .addAllowedApplication(packageName)
                .establish()
        } catch (_: Exception) {
            null
        }

        if (releaseInterface != null) {
            vpnInterface = releaseInterface
            try {
                oldInterface.close()
            } catch (_: Exception) {
            }

            val stop = Runnable {
                if (vpnInterface === releaseInterface) {
                    try {
                        releaseInterface.close()
                    } catch (_: Exception) {
                    }
                    vpnInterface = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                pendingStop = null
            }
            pendingStop = stop
            handler.postDelayed(stop, 750L)
        } else {
            try {
                oldInterface.close()
            } catch (_: Exception) {
            }
            vpnInterface = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
        pendingStop?.let { handler.removeCallbacks(it) }
        pendingStop = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        super.onDestroy()
    }
}
