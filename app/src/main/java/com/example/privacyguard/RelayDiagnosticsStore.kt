package com.example.privacyguard

object RelayDiagnosticsStore {
    data class Snapshot(
        val tcpConnected: Long,
        val tcpFailed: Long,
        val udpSent: Long,
        val udpReceived: Long,
        val lastError: String?,
        val lastActivityMillis: Long
    )

    private data class MutableSnapshot(
        var tcpConnected: Long = 0L,
        var tcpFailed: Long = 0L,
        var udpSent: Long = 0L,
        var udpReceived: Long = 0L,
        var lastError: String? = null,
        var lastActivityMillis: Long = 0L
    )

    private val states = HashMap<String, MutableSnapshot>()

    @Synchronized
    fun reset(packageName: String) {
        states[packageName] = MutableSnapshot()
    }

    @Synchronized
    fun tcpConnected(packageName: String) {
        val state = state(packageName)
        state.tcpConnected++
        state.lastActivityMillis = System.currentTimeMillis()
    }

    @Synchronized
    fun tcpFailed(packageName: String, error: Throwable?) {
        val state = state(packageName)
        state.tcpFailed++
        state.lastError = errorText(error)
        state.lastActivityMillis = System.currentTimeMillis()
    }

    @Synchronized
    fun udpSent(packageName: String) {
        val state = state(packageName)
        state.udpSent++
        state.lastActivityMillis = System.currentTimeMillis()
    }

    @Synchronized
    fun udpReceived(packageName: String) {
        val state = state(packageName)
        state.udpReceived++
        state.lastActivityMillis = System.currentTimeMillis()
    }

    @Synchronized
    fun error(packageName: String, message: String) {
        val state = state(packageName)
        state.lastError = message.take(180)
        state.lastActivityMillis = System.currentTimeMillis()
    }

    @Synchronized
    fun snapshot(packageName: String): Snapshot? = states[packageName]?.let {
        Snapshot(
            tcpConnected = it.tcpConnected,
            tcpFailed = it.tcpFailed,
            udpSent = it.udpSent,
            udpReceived = it.udpReceived,
            lastError = it.lastError,
            lastActivityMillis = it.lastActivityMillis
        )
    }

    private fun state(packageName: String): MutableSnapshot =
        states.getOrPut(packageName) { MutableSnapshot() }

    private fun errorText(error: Throwable?): String {
        if (error == null) return "Unknown relay error"
        val type = error.javaClass.simpleName.ifBlank { "Relay error" }
        val message = error.message?.takeIf { it.isNotBlank() }
        return if (message == null) type else "$type: $message"
    }
}
