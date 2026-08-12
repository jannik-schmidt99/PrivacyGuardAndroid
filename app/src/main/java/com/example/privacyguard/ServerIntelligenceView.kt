package com.example.privacyguard

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class ServerIntelligenceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val handler = Handler(Looper.getMainLooper())
    private val titleView = TextView(context)
    private val explanationView = TextView(context)
    private val summaryView = TextView(context)
    private val entriesContainer = LinearLayout(context)
    private val attributionView = TextView(context)
    private var packageNameValue: String? = null
    private var warmUpStarted = false

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MILLIS)
        }
    }

    init {
        orientation = VERTICAL

        titleView.text = "Server intelligence"
        titleView.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        titleView.setTypeface(titleView.typeface, android.graphics.Typeface.BOLD)
        addView(titleView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(28)
        })

        explanationView.text =
            "Resolves observed destination IPs locally to ASN, network operator and IP country. " +
                "No destination IP is sent to an online lookup service."
        explanationView.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        addView(explanationView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        summaryView.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        summaryView.setTypeface(summaryView.typeface, android.graphics.Typeface.BOLD)
        summaryView.setPadding(dp(12), dp(12), dp(12), dp(12))
        summaryView.setBackgroundColor(resolveSurfaceContainerColor())
        addView(summaryView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })

        entriesContainer.orientation = VERTICAL
        addView(entriesContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        attributionView.text =
            "Offline data: ${ServerIntelligenceResolver.DATABASE_RELEASE} · DB-IP Lite · CC BY 4.0"
        attributionView.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        addView(attributionView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        packageNameValue = (context as? Activity)?.intent
            ?.getStringExtra(AppDetailActivity.EXTRA_PACKAGE_NAME)
        render()
        startWarmUp()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, REFRESH_MILLIS)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun startWarmUp() {
        if (warmUpStarted) return
        warmUpStarted = true
        Thread({
            val ready = ServerIntelligenceResolver.warmUp(context.applicationContext)
            if (ready) {
                val pkg = packageNameValue
                if (!pkg.isNullOrBlank()) {
                    DestinationHistoryStore.snapshot(context, pkg)
                        .asSequence()
                        .map { it.destinationIp }
                        .distinct()
                        .take(MAX_VISIBLE)
                        .forEach { ServerIntelligenceResolver.lookup(context.applicationContext, it) }
                }
            }
            handler.post { render() }
        }, "PrivacyGuard-ServerIntel").apply {
            isDaemon = true
            start()
        }
    }

    private fun render() {
        val pkg = packageNameValue
        if (pkg.isNullOrBlank()) {
            summaryView.text = "Server intelligence unavailable"
            entriesContainer.removeAllViews()
            return
        }

        val destinations = DestinationHistoryStore.snapshot(context, pkg)
        if (destinations.isEmpty()) {
            summaryView.text = "No destinations available yet"
            entriesContainer.removeAllViews()
            return
        }

        if (!ServerIntelligenceResolver.isReady()) {
            summaryView.text = "Loading offline ASN and country databases…"
            entriesContainer.removeAllViews()
            return
        }

        val enriched = destinations.take(MAX_VISIBLE).map { destination ->
            destination to ServerIntelligenceResolver.lookupIfReady(destination.destinationIp)
        }

        val identifiedCount = enriched.count { it.second?.identified == true }
        val asnCount = enriched.mapNotNull { it.second?.asn }.distinct().size
        val countryCount = enriched.mapNotNull { it.second?.countryCode }.distinct().size
        summaryView.text =
            "${destinations.size} destinations · $identifiedCount identified · $asnCount ASNs · $countryCount IP countries"

        entriesContainer.removeAllViews()
        enriched.forEach { (destination, intelligence) ->
            addEntry(destination, intelligence)
        }
    }

    private fun addEntry(
        destination: DestinationHistoryStore.Destination,
        intelligence: ServerIntelligenceResolver.Intelligence?
    ) {
        val view = TextView(context)
        view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        view.setPadding(0, dp(9), 0, dp(9))

        val domain = destination.domain ?: "Domain unknown"
        val endpoint = formatEndpoint(destination.destinationIp, destination.destinationPort)
        val provider = intelligence?.provider?.takeIf { it.isNotBlank() } ?: "Provider unknown"
        val asn = intelligence?.asn?.let { "AS$it" } ?: "ASN unknown"
        val country = when {
            !intelligence?.countryName.isNullOrBlank() && !intelligence?.countryCode.isNullOrBlank() ->
                "${intelligence?.countryName} (${intelligence?.countryCode})"
            !intelligence?.countryCode.isNullOrBlank() -> intelligence?.countryCode ?: "Unknown"
            else -> "Country unknown"
        }

        view.text =
            "$domain\n$endpoint\n$asn · $provider\nIP country: $country\n" +
                "↑ ${formatBytes(destination.sentBytes)}   ↓ ${formatBytes(destination.receivedBytes)}"
        entriesContainer.addView(view)
    }

    private fun formatEndpoint(ip: String, port: Int): String = if (ip.contains(':')) {
        "[$ip]:$port"
    } else {
        "$ip:$port"
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) {
            "${value.toLong()} ${units[index]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }

    private fun resolveSurfaceContainerColor(): Int {
        val typedValue = android.util.TypedValue()
        val resolved = context.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSurfaceContainer,
            typedValue,
            true
        )
        return if (resolved) typedValue.data else android.graphics.Color.TRANSPARENT
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REFRESH_MILLIS = 5000L
        private const val MAX_VISIBLE = 80
    }
}
