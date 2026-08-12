package com.example.privacyguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import java.text.DateFormat
import java.util.concurrent.Executors

class AppDetailActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var packageNameValue: String
    private lateinit var networkStatus: TextView
    private lateinit var wifiUsage: TextView
    private lateinit var mobileUsage: TextView
    private lateinit var foregroundUsage: TextView
    private lateinit var backgroundUsage: TextView
    private lateinit var lastActivity: TextView
    private lateinit var usageAccessButton: Button
    private var selectedPeriodMillis = DAY

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
        usageAccessButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        populateAppInfo()
        setupPeriodSelector()
    }

    override fun onResume() {
        super.onResume()
        if (::packageNameValue.isInitialized) loadNetworkUsage()
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
            val formatted = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(latest)
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
        worker.shutdownNow()
        super.onDestroy()
    }

    private data class PermissionState(val label: String, val granted: Boolean)

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        private const val DAY = 24L * 60L * 60L * 1000L
    }
}
