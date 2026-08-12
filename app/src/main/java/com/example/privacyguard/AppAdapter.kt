package com.example.privacyguard

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

class AppAdapter(
    private val onBlockChanged: (AppEntry, Boolean) -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>() {
    private val all = mutableListOf<AppEntry>()
    private val shown = mutableListOf<AppEntry>()
    private var query: String = ""

    fun submit(items: List<AppEntry>) {
        all.clear(); all.addAll(items)
        applyFilter()
    }

    fun filter(text: String) {
        query = text.trim().lowercase()
        applyFilter()
    }

    private fun applyFilter() {
        shown.clear()
        shown.addAll(if (query.isBlank()) all else all.filter {
            it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
        })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return Holder(v as ViewGroup)
    }

    override fun getItemCount() = shown.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(shown[position])
    }

    inner class Holder(root: ViewGroup) : RecyclerView.ViewHolder(root) {
        private val icon: ImageView = root.findViewById(R.id.appIcon)
        private val name: TextView = root.findViewById(R.id.appName)
        private val pkg: TextView = root.findViewById(R.id.packageName)
        private val privacy: TextView = root.findViewById(R.id.privacyInfo)
        private val network: TextView = root.findViewById(R.id.networkInfo)
        private val block: MaterialSwitch = root.findViewById(R.id.blockSwitch)

        fun bind(item: AppEntry) {
            icon.setImageDrawable(item.icon)
            name.text = item.label
            pkg.text = item.packageName
            privacy.text = if (item.privacyPermissions.isEmpty()) {
                "Sensible Berechtigungen: keine gefunden"
            } else {
                "Sensible Berechtigungen: ${item.privacyPermissions.joinToString(", ")}"
            }
            network.text = if (item.receivedBytes == null || item.sentBytes == null) {
                "WLAN-Verkehr (24 h): Nutzungszugriff erforderlich"
            } else {
                "WLAN-Verkehr (24 h): ↓ ${formatBytes(item.receivedBytes)}  ↑ ${formatBytes(item.sentBytes)}"
            }

            block.setOnCheckedChangeListener(null)
            block.isChecked = BlockedAppsStore.get(itemView.context).contains(item.packageName)
            block.setOnCheckedChangeListener { _, checked -> onBlockChanged(item, checked) }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
        return if (i == 0) "${value.toLong()} ${units[i]}" else "%.1f %s".format(value, units[i])
    }
}
