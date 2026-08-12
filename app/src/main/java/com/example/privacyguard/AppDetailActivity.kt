package com.example.privacyguard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class AppDetailActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val liveHandler = Handler(Looper.getMainLooper())
    private lateinit var packageNameValue: String
    private lateinit var networkStatus: TextView
    private lateinit var wifiUsage: TextView
    private lateinit var mobileUsage: TextView
    private lateinit var foregroundUsage: TextView
    private lateinit var backgroundUsage: TextView
    private lateinit var lastActivity: TextView
    private lateinit var usageAccessButton: Button
    private lateinit var liveMonitorStatus: TextView
    private lateinit var liveMonitorButton: Button
    private lateinit var clearLiveLogButton: Button
    private lateinit var liveConnectionsContainer: LinearLayout
    private lateinit var destinationSummary: TextView
    private lateinit var clearDestinationHistoryButton: Button
    private lateinit var destinationHistoryContainer: LinearLayout
    private var selectedPeriodMillis = DAY

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && ::packageNameValue.isInitialized) {
            startPassThroughMonitorNow()
        } else {
            renderLiveMonitor()
        }
    }

    private val liveTicker = object : Runnable {
        override fun run() {
            if (::packageNameValue.isInitialized) renderLiveMonitor()
            liveHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_detail)

        packageNameValue = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            finish()
            return
        }

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        networkStatus = findViewById(R.id.networkStatus)
        wifiUsage = findViewById(R.id.wifiUsage)
        mobileUsage = findViewById(R.id.mobileUsage)
        foregroundUsage = findViewById(R.id.foregroundUsage)
        backgroundUsage = findViewById(R.id.backgroundUsage)
        lastActivity = findViewById(R.id.lastActivity)
        usageAccessButton = findViewById(R.id.usageAccessButtonDetail)
        liveMonitorStatus = findViewById(R.id.liveMonitorStatus)
        liveMonitorButton = findViewById(R.id.liveMonitorButton)
        clearLiveLogButton = findViewById(R.id.clearLiveLogButton)
        liveConnectionsContainer = findViewById(R.id.liveConnectionsContainer)
        destinationSummary = findViewById(R.id.destinationSummary)
        clearDestinationHistoryButton = findViewById(R.id.clearDestinationHistoryButton)
        destinationHistoryContainer = findViewById(R.id.destinationHistoryContainer)

        usageAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        liveMonitorButton.setOnClickListener { toggleLiveMonitor() }
        clearLiveLogButton.setOnClickListener {
            ConnectionLogStore.clear(this, packageNameValue)
            renderLiveMonitor()
        }
        clearDestinationHistoryButton.setOnClickListener {
            DestinationHistoryStore.clear(this, packageNameValue)
            renderDestinationIntelligence()
        }

        populateAppInfo()
        setupPeriodSelector()
    }

    override fun onResume() {
        super.onResume()
        if (::packageNameValue.isInitialized) {
            loadNetworkUsage()
            liveHandler.removeCallbacks(liveTicker)
            liveHandler.post(liveTicker)
        }
    }

    override fun onPause() {
        liveHandler.removeCallbacks(liveTicker)
        super.onPause()
    }

    private fun populateAppInfo() {
        val pm = packageManager
        val appInfo = try {
            pm.getApplicationInfo(packageNameValue, 0)
        } catch (_: Exception) {
            finish()
            return
        }

        findViewById<ImageView>(R.id.detailAppIcon).setImageDrawable(pm.getApplicationIcon(appInfo))
        findViewById<TextView>(R.id.detailAppName).text = pm.getApplicationLabel(appInfo)
        findViewById<TextView>(R.id.detailPackageName).text = packageNameValue

        val packageInfo = getPackageInfoWithPermissions(packageNameValue)
        val versionName = packageInfo?.versionName ?: "—"
        findViewById<TextView>(R.id.appTechnicalInfo).text =
            getString(R.string.app_technical_info, versionName, appInfo.uid, appInfo.targetSdkVersion)

        val requested = packageInfo?.requestedPermissions.orEmpty()
        val broadVisibility = requested.contains(Manifest.permission.QUERY_ALL_PACKAGES)
        findViewById<TextView>(R.id.packageVisibility).text = if (broadVisibility) {
            getString(R.string.package_visibility_broad)
        } else {
            getString(R.string.package_visibility_limited)
        }

        val permissionsContainer = findViewById<LinearLayout>(R.id.permissionsContainer)
        permissionsContainer.removeAllViews()
        val sensitive = buildSensitivePermissions(packageInfo)
        if (sensitive.isEmpty()) {
            addPermissionLine(permissionsContainer, getString(R.string.no_sensitive_permissions))
        } else {
            sensitive.forEach { permission ->
                val state = if (permission.granted) getString(R.string.granted) else getString(R.string.denied)
                addPermissionLine(permissionsContainer, "${permission.label} — $state")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackageInfoWithPermissions(packageName: String): PackageInfo? = try {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
    } catch (_: Exception) {
        null
    }

    private fun buildSensitivePermissions(packageInfo: PackageInfo?): List<PermissionState> {
        if (packageInfo == null) return emptyList()
        val requested = packageInfo.requestedPermissions ?: return emptyList()
        val flags = packageInfo.requestedPermissionsFlags ?: IntArray(requested.size)
        return requested.mapIndexedNotNull { index, permission ->
            val label = readablePermission(permission) ?: return@mapIndexedNotNull null
            val granted = index < flags.size &&
                flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            PermissionState(label, granted)
        }.distinctBy { it.label }
    }

    private fun readablePermission(permission: String): String? = when (permission) {
        Manifest.permission.CAMERA -> "Camera"
        Manifest.permission.RECORD_AUDIO -> "Microphone"
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> "Location"
        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS -> "Contacts"
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR -> "Calendar"
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS -> "Phone status"
        Manifest.permission.CALL_PHONE -> "Phone calls"
        Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS -> "SMS"
        Manifest.permission.BODY_SENSORS -> "Body sensors"
        Manifest.permission.ACTIVITY_RECOGNITION -> "Physical activity"
        Manifest.permission.READ_MEDIA_IMAGES -> "Photos / images"
        Manifest.permission.READ_MEDIA_VIDEO -> "Videos"
        Manifest.permission.READ_MEDIA_AUDIO -> "Audio files"
        Manifest.permission.BLUETOOTH_SCAN -> "Nearby Bluetooth scanning"
        Manifest.permission.BLUETOOTH_CONNECT -> "Nearby Bluetooth devices"
        Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby Wi-Fi devices"
        else -> null
    }

    private fun addPermissionLine(container: LinearLayout, text: String) {
        val view = TextView(this)
        view.text = text
        view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        val verticalPadding = (6 * resources.displayMetrics.density).toInt()
        view.setPadding(0, verticalPadding, 0, verticalPadding)
        container.addView(view)
    }

    private fun setupPeriodSelector() {
        val group = findViewById<MaterialButtonToggleGroup>(R.id.periodToggle)
        group.check(R.id.period24h)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedPeriodMillis = when (checkedId) {
                R.id.period7d -> 7L * DAY
                R.id.period30d -> 30L * DAY
                else -> DAY
            }
            loadNetworkUsage()
        }
    }

    private fun loadNetworkUsage() {
        val appInfo = try {
            packageManager.getApplicationInfo(packageNameValue, 0)
        } catch (_: Exception) {
            return
        }

        if (!NetworkUsageReader.hasUsageAccess(this)) {
            usageAccessButton.visibility = View.VISIBLE
            networkStatus.text = getString(R.string.usage_access_needed_detail)
            clearNetworkValues()
            return
        }

        usageAccessButton.visibility = View.GONE
        val requestedPeriod = selectedPeriodMillis
        networkStatus.text = getString(R.string.loading_network_usage)
        worker.execute {
            val usage = NetworkUsageReader.readForPeriod(this, appInfo.uid, requestedPeriod)
            runOnUiThread {
                if (requestedPeriod != selectedPeriodMillis) return@runOnUiThread
                showNetworkUsage(usage)
            }
        }
    }

    private fun showNetworkUsage(usage: NetworkUsageReader.PeriodUsage?) {
        if (usage == null) {
            networkStatus.text = getString(R.string.network_usage_unavailable)
            clearNetworkValues()
            return
        }

        networkStatus.text = getString(R.string.network_usage_ready)
        wifiUsage.text = getString(R.string.network_row, "Wi-Fi", formatTraffic(usage.wifi))
        mobileUsage.text = getString(R.string.network_row, "Mobile", formatTraffic(usage.mobile))
        foregroundUsage.text = getString(R.string.network_row, "Foreground", formatTraffic(usage.foreground))
        backgroundUsage.text = getString(R.string.network_row, "Background/default", formatTraffic(usage.backgroundDefault))

        val latest = listOfNotNull(
            usage.wifi?.lastActivityMillis,
            usage.mobile?.lastActivityMillis
        ).maxOrNull()
        lastActivity.text = if (latest == null) {
            getString(R.string.last_activity_none)
        } else {
            val formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(latest))
            getString(R.string.last_activity, formatted)
        }
    }

    private fun clearNetworkValues() {
        wifiUsage.text = getString(R.string.network_row, "Wi-Fi", "—")
        mobileUsage.text = getString(R.string.network_row, "Mobile", "—")
        foregroundUsage.text = getString(R.string.network_row, "Foreground", "—")
        backgroundUsage.text = getString(R.string.network_row, "Background/default", "—")
        lastActivity.text = getString(R.string.last_activity_none)
    }

    private fun toggleLiveMonitor() {
        val active = LiveMonitorStore.active(this)
        if (active?.packageName == packageNameValue) {
            stopLiveMonitor()
            return
        }
        if (active != null || BlockedAppsStore.get(this).isNotEmpty()) {
            renderLiveMonitor()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startPassThroughMonitorNow()
        }
    }

    private fun startPassThroughMonitorNow() {
        if (BlockedAppsStore.get(this).isNotEmpty()) {
            renderLiveMonitor()
            return
        }
        val intent = Intent(applicationContext, FirewallVpnService::class.java)
            .setAction(FirewallVpnService.ACTION_START_PASSTHROUGH_MONITOR)
            .putExtra(FirewallVpnService.EXTRA_PACKAGE_NAME, packageNameValue)
        ContextCompat.startForegroundService(applicationContext, intent)
        renderLiveMonitor()
    }

    private fun stopLiveMonitor() {
        val intent = Intent(applicationContext, FirewallVpnService::class.java)
            .setAction(FirewallVpnService.ACTION_STOP_MONITOR)
        ContextCompat.startForegroundService(applicationContext, intent)
        renderLiveMonitor()
    }

    private fun renderLiveMonitor() {
        if (!::liveMonitorStatus.isInitialized) return
        val active = LiveMonitorStore.active(this)
        val blockedPackages = BlockedAppsStore.get(this)
        val targetBlocked = blockedPackages.contains(packageNameValue)

        when {
            active?.packageName == packageNameValue && active.mode == LiveMonitorStore.Mode.PASS_THROUGH -> {
                liveMonitorStatus.text = getString(R.string.live_monitor_passthrough_active)
                liveMonitorButton.text = getString(R.string.stop_capture)
                liveMonitorButton.visibility = View.VISIBLE
                liveMonitorButton.isEnabled = true
            }
            active?.packageName == packageNameValue && active.mode == LiveMonitorStore.Mode.DROP_CAPTURE -> {
                val seconds = ((active.untilMillis - System.currentTimeMillis()).coerceAtLeast(0L) + 999L) / 1000L
                liveMonitorStatus.text = getString(R.string.live_monitor_active, seconds)
                liveMonitorButton.text = getString(R.string.stop_capture)
                liveMonitorButton.visibility = View.VISIBLE
                liveMonitorButton.isEnabled = true
            }
            active != null -> {
                liveMonitorStatus.text = getString(R.string.live_monitor_other_app, appLabel(active.packageName))
                liveMonitorButton.text = getString(R.string.start_capture)
                liveMonitorButton.visibility = View.VISIBLE
                liveMonitorButton.isEnabled = false
            }
            targetBlocked -> {
                liveMonitorStatus.text = getString(R.string.live_monitor_blocked_app)
                liveMonitorButton.text = getString(R.string.start_capture)
                liveMonitorButton.visibility = View.VISIBLE
                liveMonitorButton.isEnabled = false
            }
            blockedPackages.isNotEmpty() -> {
                liveMonitorStatus.text = getString(R.string.live_monitor_requires_no_blocks, blockedPackages.size)
                liveMonitorButton.text = getString(R.string.start_capture)
                liveMonitorButton.visibility = View.VISIBLE
                liveMonitorButton.isEnabled = false
            }
            else -> {
                liveMonitorStatus.text = getString(R.string.live_monitor_ready)
                liveMonitorButton.text = getString(R.string.start_capture)
                liveMonitorButton.visibility = View.VISIBLE
                liveMonitorButton.isEnabled = true
            }
        }

        val diagnostics = RelayDiagnosticsStore.snapshot(packageNameValue)
        if (diagnostics != null &&
            (active?.packageName == packageNameValue || !diagnostics.lastError.isNullOrBlank())
        ) {
            liveMonitorStatus.append(
                "\nRelay: TCP ${diagnostics.tcpConnected} connected / ${diagnostics.tcpFailed} failed" +
                    " · UDP ${diagnostics.udpSent} sent / ${diagnostics.udpReceived} received"
            )
            if (!diagnostics.lastError.isNullOrBlank()) {
                liveMonitorStatus.append("\nLast relay error: ${diagnostics.lastError}")
            }
        }

        renderDestinationIntelligence()
        renderLiveConnections()
    }

    private fun renderDestinationIntelligence() {
        if (!::destinationSummary.isInitialized) return
        val destinations = DestinationHistoryStore.snapshot(this, packageNameValue)
        val now = System.currentTimeMillis()
        val newCount = destinations.count { now - it.firstSeenMillis <= DAY }
        val domainCount = destinations.count { !it.domain.isNullOrBlank() }
        val backgroundCount = destinations.count { it.backgroundEvents > 0L }
        destinationSummary.text = getString(
            R.string.destination_summary,
            destinations.size,
            newCount,
            domainCount,
            backgroundCount
        )

        destinationHistoryContainer.removeAllViews()
        if (destinations.isEmpty()) {
            addDestinationLine(getString(R.string.no_destination_history))
            return
        }

        destinations.take(MAX_VISIBLE_DESTINATIONS).forEach { destination ->
            val first = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(destination.firstSeenMillis))
            val last = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(destination.lastSeenMillis))
            val domain = destination.domain ?: getString(R.string.domain_unknown)
            val endpoint = formatEndpoint(destination.destinationIp, destination.destinationPort)
            val state = stateLabel(
                destination.foregroundEvents,
                destination.backgroundEvents,
                destination.unknownStateEvents
            )
            val newPrefix = if (now - destination.firstSeenMillis <= DAY) {
                "${getString(R.string.new_destination)} · "
            } else {
                ""
            }

            addDestinationLine(
                "$newPrefix${destination.protocol}\n$domain\n$endpoint\n" +
                    "First seen: $first · Last seen: $last\n" +
                    "↑ ${formatBytes(destination.sentBytes)}   ↓ ${formatBytes(destination.receivedBytes)}\n$state"
            )
        }
    }

    private fun renderLiveConnections() {
        val records = ConnectionLogStore.snapshot(this, packageNameValue)
        liveConnectionsContainer.removeAllViews()
        if (records.isEmpty()) {
            addConnectionLine(getString(R.string.no_live_connections))
            return
        }

        records.take(MAX_VISIBLE_CONNECTIONS).forEach { record ->
            val first = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(record.firstSeenMillis))
            val last = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(record.lastSeenMillis))
            val endpoint = formatEndpoint(record.destinationIp, record.destinationPort)
            val timing = if (first == last) first else "$first → $last"
            val domain = record.domain ?: getString(R.string.domain_unknown)
            val state = stateLabel(record.foregroundEvents, record.backgroundEvents, record.unknownStateEvents)
            addConnectionLine(
                "$timing   ${record.protocol}\n$domain\n$endpoint\n" +
                    "↑ ${formatBytes(record.sentBytes)}   ↓ ${formatBytes(record.receivedBytes)} · " +
                    "${record.packetCount} relay events\n$state"
            )
        }
    }

    private fun stateLabel(foregroundEvents: Long, backgroundEvents: Long, unknownEvents: Long): String = when {
        backgroundEvents > 0L -> getString(R.string.background_observed)
        foregroundEvents > 0L -> getString(R.string.foreground_observed)
        unknownEvents > 0L -> getString(R.string.app_state_unknown)
        else -> getString(R.string.app_state_unknown)
    }

    private fun formatEndpoint(ip: String, port: Int): String = if (ip.contains(':')) {
        "[$ip]:$port"
    } else {
        "$ip:$port"
    }

    private fun appLabel(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        packageName
    }

    private fun addDestinationLine(text: String) {
        val view = TextView(this)
        view.text = text
        view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        val vertical = (10 * resources.displayMetrics.density).toInt()
        view.setPadding(0, vertical, 0, vertical)
        destinationHistoryContainer.addView(view)
    }

    private fun addConnectionLine(text: String) {
        val view = TextView(this)
        view.text = text
        view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        val vertical = (9 * resources.displayMetrics.density).toInt()
        view.setPadding(0, vertical, 0, vertical)
        liveConnectionsContainer.addView(view)
    }

    private fun formatTraffic(usage: NetworkUsageReader.TrafficUsage?): String {
        if (usage == null) return "Unavailable"
        return "↓ ${formatBytes(usage.receivedBytes)}   ↑ ${formatBytes(usage.sentBytes)}"
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) "${value.toLong()} ${units[index]}" else "%.1f %s".format(value, units[index])
    }

    override fun onDestroy() {
        liveHandler.removeCallbacks(liveTicker)
        worker.shutdownNow()
        super.onDestroy()
    }

    private data class PermissionState(val label: String, val granted: Boolean)

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        private const val DAY = 24L * 60L * 60L * 1000L
        private const val MAX_VISIBLE_CONNECTIONS = 80
        private const val MAX_VISIBLE_DESTINATIONS = 80
    }
}
