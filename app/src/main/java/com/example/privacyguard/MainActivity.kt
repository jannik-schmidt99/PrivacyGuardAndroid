package com.example.privacyguard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: AppAdapter
    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private var pendingPackage: String? = null

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pkg = pendingPackage
        pendingPackage = null
        if (result.resultCode == Activity.RESULT_OK && pkg != null) {
            BlockedAppsStore.setBlocked(this, pkg, true)
            applyFirewallRules()
            loadApps()
        } else {
            loadApps()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        val recycler: RecyclerView = findViewById(R.id.appList)
        val search: EditText = findViewById(R.id.searchInput)
        val usageButton: Button = findViewById(R.id.usageAccessButton)

        adapter = AppAdapter { app, blocked -> setBlocked(app, blocked) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        usageButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { adapter.filter(s?.toString() ?: "") }
            override fun afterTextChanged(s: Editable?) {}
        })

        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    private fun setBlocked(app: AppEntry, blocked: Boolean) {
        if (!blocked) {
            BlockedAppsStore.setBlocked(this, app.packageName, false)
            applyFirewallRules()
            loadApps()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingPackage = app.packageName
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            BlockedAppsStore.setBlocked(this, app.packageName, true)
            applyFirewallRules()
            loadApps()
        }
    }

    private fun applyFirewallRules() {
        // Always let the running VpnService apply the new rule set. Even when
        // the blocked list becomes empty, the service must run briefly so it
        // can replace the old per-app VPN interface and release those apps.
        val intent = Intent(applicationContext, FirewallVpnService::class.java)
        ContextCompat.startForegroundService(applicationContext, intent)
    }

    private fun loadApps() {
        status.text = "Lade Apps und Netzwerkstatistik…"
        worker.execute {
            val pm = packageManager
            val installed = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                .filter { it.packageName != packageName }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }

            val items = installed.map { info ->
                val packageInfo = try {
                    pm.getPackageInfo(info.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                } catch (_: Exception) { null }

                val privacy = packageInfo?.requestedPermissions.orEmpty()
                    .mapNotNull { readablePrivacyPermission(it) }
                    .distinct()

                val usage = NetworkUsageReader.readWifi24h(this, info.uid)
                AppEntry(
                    label = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    uid = info.uid,
                    icon = pm.getApplicationIcon(info),
                    privacyPermissions = privacy,
                    receivedBytes = usage?.first,
                    sentBytes = usage?.second
                )
            }.sortedBy { it.label.lowercase() }

            runOnUiThread {
                adapter.submit(items)
                val access = if (NetworkUsageReader.hasUsageAccess(this)) "Nutzungszugriff aktiv" else "Nutzungszugriff fehlt"
                val blocked = BlockedAppsStore.get(this).size
                status.text = "${items.size} Apps · $access · $blocked gesperrt"
            }
        }
    }

    private fun readablePrivacyPermission(permission: String): String? = when (permission) {
        Manifest.permission.CAMERA -> "Kamera"
        Manifest.permission.RECORD_AUDIO -> "Mikrofon"
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> "Standort"
        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS -> "Kontakte"
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR -> "Kalender"
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS -> "Telefonstatus"
        Manifest.permission.CALL_PHONE -> "Telefonieren"
        Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS -> "SMS"
        else -> null
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
