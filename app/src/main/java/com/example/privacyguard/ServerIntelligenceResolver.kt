package com.example.privacyguard

import android.content.Context
import com.maxmind.db.Reader
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline IP intelligence backed by the DB-IP Lite ASN and Country MMDB files
 * bundled into the APK at build time. No observed destination IP is sent to a
 * remote lookup service.
 */
object ServerIntelligenceResolver {
    data class Intelligence(
        val asn: Long?,
        val provider: String?,
        val countryCode: String?,
        val countryName: String?
    ) {
        val identified: Boolean
            get() = asn != null || !provider.isNullOrBlank() || !countryCode.isNullOrBlank()
    }

    private data class Readers(
        val asn: Reader,
        val country: Reader
    )

    const val DATABASE_RELEASE = "DB-IP Lite 2026-08"
    private const val ASSET_ASN = "dbip-asn-lite-2026-08.mmdb"
    private const val ASSET_COUNTRY = "dbip-country-lite-2026-08.mmdb"
    private const val LOCAL_ASN = "dbip-asn-lite-2026-08.mmdb"
    private const val LOCAL_COUNTRY = "dbip-country-lite-2026-08.mmdb"

    private val cache = ConcurrentHashMap<String, Intelligence>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val lock = Any()

    @Volatile
    private var readers: Readers? = null

    @Volatile
    private var initializationFailed = false

    fun warmUp(context: Context): Boolean = ensureReaders(context.applicationContext) != null

    fun isReady(): Boolean = readers != null

    fun lookupIfReady(ip: String): Intelligence? {
        cache[ip]?.let { return it }
        if (missing.contains(ip)) return null
        val activeReaders = readers ?: return null
        return lookupWithReaders(activeReaders, ip)
    }

    fun lookup(context: Context, ip: String): Intelligence? {
        cache[ip]?.let { return it }
        if (missing.contains(ip)) return null
        val activeReaders = ensureReaders(context.applicationContext) ?: return null
        return lookupWithReaders(activeReaders, ip)
    }

    private fun lookupWithReaders(activeReaders: Readers, ip: String): Intelligence? {
        val address = try {
            InetAddress.getByName(ip)
        } catch (_: Exception) {
            missing += ip
            return null
        }

        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            missing += ip
            return null
        }

        val asnMap = readMap(activeReaders.asn, address)
        val countryMap = readMap(activeReaders.country, address)

        val asn = findLong(
            asnMap,
            "autonomous_system_number",
            "as_number",
            "asn"
        )
        val provider = findString(
            asnMap,
            "autonomous_system_organization",
            "as_organization",
            "organization",
            "organization_name",
            "isp"
        )

        val countryNode = findMap(countryMap, "country") ?: findMap(asnMap, "country")
        val countryCode = findString(countryNode, "iso_code", "country")
            ?: findString(countryMap, "country_code", "country")
        val names = findMap(countryNode, "names")
        val countryName = findString(names, "en")

        val result = Intelligence(
            asn = asn,
            provider = provider,
            countryCode = countryCode,
            countryName = countryName
        )

        if (result.identified) {
            cache[ip] = result
            return result
        }

        missing += ip
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun readMap(reader: Reader, address: InetAddress): Map<String, Any?>? = try {
        reader.get(address, Map::class.java) as? Map<String, Any?>
    } catch (_: Exception) {
        null
    }

    private fun ensureReaders(context: Context): Readers? {
        readers?.let { return it }
        if (initializationFailed) return null

        synchronized(lock) {
            readers?.let { return it }
            if (initializationFailed) return null

            return try {
                val directory = File(context.filesDir, "server_intelligence").apply { mkdirs() }
                val asnFile = File(directory, LOCAL_ASN)
                val countryFile = File(directory, LOCAL_COUNTRY)
                copyAssetIfNeeded(context, ASSET_ASN, asnFile)
                copyAssetIfNeeded(context, ASSET_COUNTRY, countryFile)

                Readers(
                    asn = Reader(asnFile),
                    country = Reader(countryFile)
                ).also { readers = it }
            } catch (_: Exception) {
                initializationFailed = true
                null
            }
        }
    }

    private fun copyAssetIfNeeded(context: Context, assetName: String, target: File) {
        val assetLength = context.assets.openFd(assetName).length
        if (target.exists() && target.length() == assetLength && target.length() > 0L) return

        val temporary = File(target.parentFile, "${target.name}.tmp")
        context.assets.open(assetName).use { input ->
            temporary.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private fun findMap(source: Map<*, *>?, key: String): Map<*, *>? {
        if (source == null) return null
        source[key]?.let { if (it is Map<*, *>) return it }
        source.values.forEach { value ->
            if (value is Map<*, *>) {
                value[key]?.let { if (it is Map<*, *>) return it }
            }
        }
        return null
    }

    private fun findString(source: Map<*, *>?, vararg keys: String): String? {
        if (source == null) return null
        keys.forEach { key ->
            val direct = source[key]
            if (direct is String && direct.isNotBlank()) return direct
        }
        source.values.forEach { value ->
            if (value is Map<*, *>) {
                keys.forEach { key ->
                    val nested = value[key]
                    if (nested is String && nested.isNotBlank()) return nested
                }
            }
        }
        return null
    }

    private fun findLong(source: Map<*, *>?, vararg keys: String): Long? {
        if (source == null) return null
        keys.forEach { key ->
            numberToLong(source[key])?.let { return it }
        }
        source.values.forEach { value ->
            if (value is Map<*, *>) {
                keys.forEach { key ->
                    numberToLong(value[key])?.let { return it }
                }
            }
        }
        return null
    }

    private fun numberToLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.removePrefix("AS").toLongOrNull()
        else -> null
    }
}
