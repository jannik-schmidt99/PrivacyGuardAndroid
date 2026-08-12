package com.example.privacyguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress

class FirewallVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingStop: Runnable? = null
    private var monitorExpiry: Runnable? = null
    private var localSocksProxy: LocalSocks5Proxy? = null
    private var hevRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITOR -> {
                val targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (!targetPackage.isNullOrBlank()) {
                    val duration = intent.getLongExtra(EXTRA_DURATION_MILLIS, DEFAULT_CAPTURE_MILLIS)
                        .coerceIn(5_000L, 120_000L)
                    LiveMonitorStore.startDropCapture(this, targetPackage, duration)
                }
            }

            ACTION_START_PASSTHROUGH_MONITOR -> {
                val targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (!targetPackage.isNullOrBlank() && BlockedAppsStore.get(this).isEmpty()) {
                    RelayDiagnosticsStore.reset(targetPackage)
                    LiveMonitorStore.startPassThrough(this, targetPackage)
                }
            }

            ACTION_STOP_MONITOR -> LiveMonitorStore.stop(this)
        }

        startInForeground()
        applyFirewallRules()
        return START_STICKY
    }

    private fun baseBuilder(blocking: Boolean): Builder = Builder()
        .setSession("Privacy Guard")
        .setMtu(MTU)
        .setBlocking(blocking)
        .addAddress("10.77.0.1", 32)
        .addAddress("fd77:77::1", 128)
        .addRoute("0.0.0.0", 0)
        .addRoute("::", 0)

    private fun applyFirewallRules() {
        pendingStop?.let { handler.removeCallbacks(it) }
        pendingStop = null

        val blocked = BlockedAppsStore.get(this)
        var monitor = LiveMonitorStore.active(this)

        if (monitor?.mode == LiveMonitorStore.Mode.PASS_THROUGH && blocked.isNotEmpty()) {
            // Never weaken firewall rules just to keep monitoring alive.
            stopPassThroughDataPlane()
            LiveMonitorStore.stop(this)
            monitor = null
        }

        scheduleMonitorExpiry(monitor)

        if (monitor?.mode == LiveMonitorStore.Mode.PASS_THROUGH) {
            startPassThroughMonitor(monitor.packageName)
            return
        }

        stopPassThroughDataPlane()

        val routedPackages = linkedSetOf<String>()
        routedPackages.addAll(blocked)
        if (monitor?.mode == LiveMonitorStore.Mode.DROP_CAPTURE) {
            routedPackages.add(monitor.packageName)
        }

        if (routedPackages.isEmpty()) {
            releaseAllAppsFromVpn()
            return
        }

        val builder = baseBuilder(blocking = true)
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

    private fun startPassThroughMonitor(monitoredPackage: String) {
        stopPassThroughDataPlane()

        val upstreamNetwork = selectUnderlyingNetwork()
        if (upstreamNetwork == null) {
            RelayDiagnosticsStore.error(monitoredPackage, "No validated Wi-Fi/mobile upstream network available")
            LiveMonitorStore.stop(this)
            releaseAllAppsFromVpn()
            return
        }

        val builder = baseBuilder(blocking = false)
            .setUnderlyingNetworks(arrayOf(upstreamNetwork))
        try {
            builder.addAllowedApplication(monitoredPackage)
        } catch (_: Exception) {
            RelayDiagnosticsStore.error(monitoredPackage, "Could not route monitored app into VPN")
            LiveMonitorStore.stop(this)
            releaseAllAppsFromVpn()
            return
        }
        addUnderlyingDnsServers(builder, upstreamNetwork)

        val oldInterface = vpnInterface
        val newInterface = try {
            builder.establish()
        } catch (error: Exception) {
            RelayDiagnosticsStore.error(monitoredPackage, "VPN establish failed: ${error.javaClass.simpleName}: ${error.message ?: "unknown"}")
            null
        }

        if (newInterface == null) {
            LiveMonitorStore.stop(this)
            releaseAllAppsFromVpn()
            return
        }

        vpnInterface = newInterface
        try {
            oldInterface?.close()
        } catch (_: Exception) {
        }

        try {
            if (!setUnderlyingNetworks(arrayOf(upstreamNetwork))) {
                RelayDiagnosticsStore.error(monitoredPackage, "Android rejected VPN underlying-network update")
            }
        } catch (error: Exception) {
            RelayDiagnosticsStore.error(monitoredPackage, "Underlying network: ${error.javaClass.simpleName}: ${error.message ?: "unknown"}")
        }

        val proxy = LocalSocks5Proxy(this, monitoredPackage, upstreamNetwork)
        val proxyPort = try {
            proxy.start()
        } catch (error: Exception) {
            RelayDiagnosticsStore.error(monitoredPackage, "Local SOCKS start failed: ${error.javaClass.simpleName}: ${error.message ?: "unknown"}")
            -1
        }

        if (proxyPort <= 0) {
            proxy.stop()
            LiveMonitorStore.stop(this)
            releaseAllAppsFromVpn()
            return
        }

        val config = File(cacheDir, "privacyguard-hev.conf")
        val configText = buildString {
            append("misc:\n")
            append("  task-stack-size: 86016\n")
            append("  tcp-buffer-size: 65536\n")
            append("  log-level: warn\n")
            append("tunnel:\n")
            append("  mtu: $MTU\n")
            append("  icmp: 'reply'\n")
            append("socks5:\n")
            append("  address: '127.0.0.1'\n")
            append("  port: $proxyPort\n")
            append("  udp: 'udp'\n")
        }

        val started = try {
            config.writeText(configText)
            HevTunnel.TProxyStartService(config.absolutePath, newInterface.fd)
        } catch (error: Throwable) {
            RelayDiagnosticsStore.error(monitoredPackage, "HEV start failed: ${error.javaClass.simpleName}: ${error.message ?: "unknown"}")
            false
        }

        if (!started) {
            proxy.stop()
            LiveMonitorStore.stop(this)
            try {
                newInterface.close()
            } catch (_: Exception) {
            }
            vpnInterface = null
            releaseAllAppsFromVpn()
            return
        }

        localSocksProxy = proxy
        hevRunning = true
        startInForeground()
    }

    private fun selectUnderlyingNetwork(): Network? {
        return try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            connectivity.allNetworks
                .mapNotNull { network ->
                    val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    ) {
                        return@mapNotNull null
                    }
                    network to caps
                }
                .maxByOrNull { (_, caps) ->
                    var score = 0
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 100
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 20
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 10
                    score
                }
                ?.first
        } catch (_: Exception) {
            null
        }
    }

    private fun addUnderlyingDnsServers(builder: Builder, upstreamNetwork: Network) {
        try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val dnsServers = connectivity.getLinkProperties(upstreamNetwork)?.dnsServers.orEmpty()
            dnsServers.distinct().forEach { builder.addDnsServer(it) }
            if (dnsServers.isEmpty()) {
                builder.addDnsServer("1.1.1.1")
            }
        } catch (_: Exception) {
            try {
                builder.addDnsServer("1.1.1.1")
            } catch (_: Exception) {
            }
        }
    }

    private fun stopPassThroughDataPlane() {
        if (hevRunning) {
            try {
                HevTunnel.TProxyStopService()
            } catch (_: Throwable) {
            }
            hevRunning = false
        }
        localSocksProxy?.stop()
        localSocksProxy = null
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
                    // Drop-firewall mode deliberately does not forward packets.
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
        if (state == null || state.mode != LiveMonitorStore.Mode.DROP_CAPTURE) return

        val delay = (state.untilMillis - System.currentTimeMillis()).coerceAtLeast(1L)
        val expiry = Runnable {
            val current = LiveMonitorStore.active(this)
            if (current == null ||
                (current.mode == LiveMonitorStore.Mode.DROP_CAPTURE &&
                    current.untilMillis <= System.currentTimeMillis() + 250L)
            ) {
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
        stopPassThroughDataPlane()
        val oldInterface = vpnInterface

        if (oldInterface == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val releaseInterface = try {
            baseBuilder(blocking = true)
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
        val title: String
        val text: String
        when (monitor?.mode) {
            LiveMonitorStore.Mode.PASS_THROUGH -> {
                title = "Privacy Guard live monitor"
                text = "Monitoring ${appLabel(monitor.packageName)} while keeping its internet connection active."
            }
            LiveMonitorStore.Mode.DROP_CAPTURE -> {
                title = "Privacy Guard live capture"
                text = "Capturing ${appLabel(monitor.packageName)} attempts; its internet is temporarily blocked."
            }
            null -> {
                title = "Privacy Guard active"
                text = "Selected apps have no internet access."
            }
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
        stopPassThroughDataPlane()
        LiveMonitorStore.stop(this)
        super.onRevoke()
    }

    override fun onDestroy() {
        pendingStop?.let { handler.removeCallbacks(it) }
        monitorExpiry?.let { handler.removeCallbacks(it) }
        pendingStop = null
        monitorExpiry = null
        stopPassThroughDataPlane()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_MONITOR = "com.example.privacyguard.action.START_MONITOR"
        const val ACTION_START_PASSTHROUGH_MONITOR = "com.example.privacyguard.action.START_PASSTHROUGH_MONITOR"
        const val ACTION_STOP_MONITOR = "com.example.privacyguard.action.STOP_MONITOR"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_DURATION_MILLIS = "duration_millis"
        const val DEFAULT_CAPTURE_MILLIS = 30_000L
        private const val MTU = 1500
    }
}
