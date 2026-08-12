package com.example.privacyguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.net.InetSocketAddress

class FirewallVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingStop: Runnable? = null
    private var monitorExpiry: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITOR -> {
                val targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (!targetPackage.isNullOrBlank()) {
                    val duration = intent.getLongExtra(EXTRA_DURATION_MILLIS, DEFAULT_CAPTURE_MILLIS)
                        .coerceIn(5_000L, 120_000L)
                    ConnectionLogStore.clear(this, targetPackage)
                    LiveMonitorStore.start(this, targetPackage, duration)
                }
            }
            ACTION_STOP_MONITOR -> LiveMonitorStore.stop(this)
        }

        startInForeground()
        applyFirewallRules()
        return START_STICKY
    }

    private fun baseBuilder(): Builder = Builder()
        .setSession("Privacy Guard Firewall")
        .setMtu(1500)
        .setBlocking(true)
        .addAddress("10.77.0.1", 32)
        .addRoute("0.0.0.0", 0)
        .addRoute("::", 0)

    private fun applyFirewallRules() {
        pendingStop?.let { handler.removeCallbacks(it) }
        pendingStop = null

        val monitor = LiveMonitorStore.active(this)
        scheduleMonitorExpiry(monitor)

        val routedPackages = linkedSetOf<String>()
        routedPackages.addAll(BlockedAppsStore.get(this))
        monitor?.packageName?.let { routedPackages.add(it) }

        if (routedPackages.isEmpty()) {
            releaseAllAppsFromVpn()
            return
        }

        val builder = baseBuilder()
        val validPackages = linkedSetOf<String>()
        for (packageName in routedPackages) {
            try {
                builder.addAllowedApplication(packageName)
                validPackages.add(packageName)
            } catch (_: Exception) {
                // App may have been uninstalled; ignore it.
            }
        }

        if (validPackages.isEmpty()) {
            releaseAllAppsFromVpn()
            return
        }

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
            startPacketReader(newInterface, validPackages)
            startInForeground()
        }
    }

    private fun startPacketReader(
        tunnel: ParcelFileDescriptor,
        routedPackages: Set<String>
    ) {
        val thread = Thread({
            val buffer = ByteArray(65535)
            try {
                val input = FileInputStream(tunnel.fileDescriptor)
                while (vpnInterface === tunnel) {
                    val length = input.read(buffer)
                    if (length <= 0) break
                    val packet = PacketInspector.parse(buffer, length) ?: continue
                    val ownerPackage = findOwnerPackage(packet, routedPackages) ?: continue
                    ConnectionLogStore.record(this, ownerPackage, packet)
                    // Deliberately do not write the packet back to the TUN.
                    // This service remains a drop firewall/capture interface.
                }
            } catch (_: Exception) {
                // Closing/replacing the VPN interface ends the blocking read.
            }
        }, "PrivacyGuardPacketReader")
        thread.isDaemon = true
        thread.start()
    }

    private fun findOwnerPackage(
        packet: PacketInspector.ParsedPacket,
        routedPackages: Set<String>
    ): String? {
        if (routedPackages.size == 1) return routedPackages.first()
        if (Build.VERSION.SDK_INT < 29) return null

        return try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val uid = connectivity.getConnectionOwnerUid(
                packet.protocol,
                InetSocketAddress(packet.sourceAddress, packet.sourcePort),
                InetSocketAddress(packet.destinationAddress, packet.destinationPort)
            )
            if (uid < 0) return null
            packageManager.getPackagesForUid(uid)
                ?.firstOrNull { it in routedPackages }
        } catch (_: Exception) {
            null
        }
    }

    private fun scheduleMonitorExpiry(state: LiveMonitorStore.State?) {
        monitorExpiry?.let { handler.removeCallbacks(it) }
        monitorExpiry = null
        if (state == null) return

        val delay = (state.untilMillis - System.currentTimeMillis()).coerceAtLeast(1L)
        val expiry = Runnable {
            val current = LiveMonitorStore.active(this)
            if (current == null || current.untilMillis <= System.currentTimeMillis() + 250L) {
                LiveMonitorStore.stop(this)
                applyFirewallRules()
                startInForeground()
            }
            monitorExpiry = null
        }
        monitorExpiry = expiry
        handler.postDelayed(expiry, delay)
    }

    private fun releaseAllAppsFromVpn() {
        val oldInterface = vpnInterface

        if (oldInterface == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

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

        val monitor = LiveMonitorStore.active(this)
        val title = if (monitor != null) "Privacy Guard live capture" else "Privacy Guard active"
        val text = if (monitor != null) {
            "Capturing ${appLabel(monitor.packageName)} connections; its internet is temporarily blocked."
        } else {
            "Selected apps have no internet access."
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun appLabel(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        packageName
    }

    override fun onRevoke() {
        LiveMonitorStore.stop(this)
        super.onRevoke()
    }

    override fun onDestroy() {
        pendingStop?.let { handler.removeCallbacks(it) }
        monitorExpiry?.let { handler.removeCallbacks(it) }
        pendingStop = null
        monitorExpiry = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_MONITOR = "com.example.privacyguard.action.START_MONITOR"
        const val ACTION_STOP_MONITOR = "com.example.privacyguard.action.STOP_MONITOR"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_DURATION_MILLIS = "duration_millis"
        const val DEFAULT_CAPTURE_MILLIS = 30_000L
    }
}
