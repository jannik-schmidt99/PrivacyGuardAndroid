package com.example.privacyguard

import android.net.VpnService
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class LocalSocks5Proxy(
    private val vpnService: VpnService,
    private val monitoredPackage: String
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val closeables = Collections.synchronizedSet(mutableSetOf<Closeable>())

    fun start(): Int {
        if (running) return serverSocket?.localPort ?: -1
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        serverSocket = server
        closeables.add(server)
        running = true
        executor.execute { acceptLoop(server) }
        return server.localPort
    }

    fun stop() {
        running = false
        val snapshot = synchronized(closeables) { closeables.toList() }
        snapshot.forEach { closeQuietly(it) }
        closeables.clear()
        serverSocket = null
        executor.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            closeables.add(client)
            executor.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()
            if (!negotiate(input, output)) return

            val request = readRequest(input) ?: return
            when (request.command) {
                CMD_CONNECT -> handleConnect(client, input, output, request)
                CMD_UDP_ASSOCIATE -> handleUdpAssociate(client, input, output)
                else -> writeReply(output, REP_COMMAND_NOT_SUPPORTED, null)
            }
        } catch (_: Exception) {
        } finally {
            closeables.remove(client)
            closeQuietly(client)
        }
    }

    private fun negotiate(input: InputStream, output: OutputStream): Boolean {
        if (readU8(input) != SOCKS_VERSION) return false
        val methodCount = readU8(input)
        var supportsNoAuth = false
        repeat(methodCount) {
            if (readU8(input) == METHOD_NO_AUTH) supportsNoAuth = true
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), if (supportsNoAuth) METHOD_NO_AUTH.toByte() else METHOD_NONE.toByte()))
        output.flush()
        return supportsNoAuth
    }

    private fun readRequest(input: InputStream): SocksRequest? {
        if (readU8(input) != SOCKS_VERSION) return null
        val command = readU8(input)
        readU8(input)
        val address = readAddress(input, readU8(input)) ?: return null
        val port = (readU8(input) shl 8) or readU8(input)
        return SocksRequest(command, address, port)
    }

    private fun handleConnect(
        client: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        request: SocksRequest
    ) {
        val remote = Socket()
        closeables.add(remote)
        try {
            if (!vpnService.protect(remote)) {
                writeReply(clientOutput, REP_GENERAL_FAILURE, null)
                return
            }
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(request.address, request.port), CONNECT_TIMEOUT_MS)
            writeReply(clientOutput, REP_SUCCEEDED, remote.localSocketAddress as? InetSocketAddress)

            val destinationIp = request.address.hostAddress ?: request.address.hostName
            val connectionId = client.port
            val reverse = executor.submit {
                try {
                    copyCounted(
                        remote.getInputStream(),
                        client.getOutputStream(),
                        protocol = "TCP",
                        sourcePort = connectionId,
                        destinationIp = destinationIp,
                        destinationPort = request.port,
                        sent = false
                    )
                } catch (_: Exception) {
                } finally {
                    closeQuietly(client)
                    closeQuietly(remote)
                }
            }

            try {
                copyCounted(
                    clientInput,
                    remote.getOutputStream(),
                    protocol = "TCP",
                    sourcePort = connectionId,
                    destinationIp = destinationIp,
                    destinationPort = request.port,
                    sent = true
                )
            } finally {
                closeQuietly(remote)
                closeQuietly(client)
                reverse.cancel(true)
            }
        } catch (_: Exception) {
            try {
                writeReply(clientOutput, REP_HOST_UNREACHABLE, null)
            } catch (_: Exception) {
            }
        } finally {
            closeables.remove(remote)
            closeQuietly(remote)
        }
    }

    private fun handleUdpAssociate(client: Socket, controlInput: InputStream, controlOutput: OutputStream) {
        val relay = DatagramSocket(null)
        val remote = DatagramSocket(null)
        closeables.add(relay)
        closeables.add(remote)
        try {
            relay.reuseAddress = true
            relay.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            remote.reuseAddress = true
            remote.bind(InetSocketAddress(0))
            if (!vpnService.protect(remote)) {
                writeReply(controlOutput, REP_GENERAL_FAILURE, null)
                return
            }
            writeReply(
                controlOutput,
                REP_SUCCEEDED,
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), relay.localPort)
            )

            val associationId = client.port
            val clientAddress = AtomicReference<SocketAddress?>(null)
            val remoteReceiver = executor.submit {
                receiveRemoteUdp(remote, relay, clientAddress, associationId)
            }
            val relayReceiver = executor.submit {
                receiveSocksUdp(relay, remote, clientAddress, associationId)
            }

            while (running && !client.isClosed) {
                if (controlInput.read() < 0) break
            }
            relayReceiver.cancel(true)
            remoteReceiver.cancel(true)
        } catch (_: Exception) {
        } finally {
            closeables.remove(relay)
            closeables.remove(remote)
            closeQuietly(relay)
            closeQuietly(remote)
        }
    }

    private fun receiveSocksUdp(
        relay: DatagramSocket,
        remote: DatagramSocket,
        clientAddress: AtomicReference<SocketAddress?>,
        associationId: Int
    ) {
        val buffer = ByteArray(MAX_UDP_PACKET)
        while (running && !relay.isClosed && !remote.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                relay.receive(packet)
                clientAddress.set(packet.socketAddress)
                val decoded = decodeUdpPacket(packet.data, packet.offset, packet.length) ?: continue
                if (decoded.fragment != 0) continue
                val payloadLength = decoded.payloadLength
                val outbound = DatagramPacket(
                    packet.data,
                    decoded.payloadOffset,
                    payloadLength,
                    decoded.address,
                    decoded.port
                )
                remote.send(outbound)
                val ip = decoded.address.hostAddress ?: decoded.address.hostName
                ConnectionLogStore.recordTransfer(
                    vpnService,
                    monitoredPackage,
                    "UDP",
                    associationId,
                    ip,
                    decoded.port,
                    sentBytes = payloadLength.toLong(),
                    receivedBytes = 0L,
                    packetDelta = 1L
                )
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun receiveRemoteUdp(
        remote: DatagramSocket,
        relay: DatagramSocket,
        clientAddress: AtomicReference<SocketAddress?>,
        associationId: Int
    ) {
        val buffer = ByteArray(MAX_UDP_PACKET)
        while (running && !remote.isClosed && !relay.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                remote.receive(packet)
                val client = clientAddress.get() ?: continue

                if (packet.port == DNS_PORT) {
                    DnsObservationStore.observeResponse(
                        vpnService,
                        monitoredPackage,
                        packet.data,
                        packet.offset,
                        packet.length
                    )
                }

                val wrapped = encodeUdpPacket(packet.address, packet.port, packet.data, packet.offset, packet.length)
                relay.send(DatagramPacket(wrapped, wrapped.size, client))
                val ip = packet.address.hostAddress ?: packet.address.hostName
                ConnectionLogStore.recordTransfer(
                    vpnService,
                    monitoredPackage,
                    "UDP",
                    associationId,
                    ip,
                    packet.port,
                    sentBytes = 0L,
                    receivedBytes = packet.length.toLong(),
                    packetDelta = 1L
                )
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun copyCounted(
        input: InputStream,
        output: OutputStream,
        protocol: String,
        sourcePort: Int,
        destinationIp: String,
        destinationPort: Int,
        sent: Boolean
    ) {
        val buffer = ByteArray(32 * 1024)
        while (running) {
            val length = input.read(buffer)
            if (length <= 0) break
            output.write(buffer, 0, length)
            output.flush()
            ConnectionLogStore.recordTransfer(
                vpnService,
                monitoredPackage,
                protocol,
                sourcePort,
                destinationIp,
                destinationPort,
                sentBytes = if (sent) length.toLong() else 0L,
                receivedBytes = if (sent) 0L else length.toLong(),
                packetDelta = 1L
            )
        }
    }

    private fun decodeUdpPacket(data: ByteArray, offset: Int, length: Int): UdpRequest? {
        if (length < 4) return null
        var index = offset
        if (data[index++].toInt() != 0 || data[index++].toInt() != 0) return null
        val fragment = data[index++].toInt() and 0xff
        val addressType = data[index++].toInt() and 0xff
        val addressResult = readAddressFromBytes(data, index, offset + length, addressType) ?: return null
        index = addressResult.second
        if (index + 2 > offset + length) return null
        val port = ((data[index].toInt() and 0xff) shl 8) or (data[index + 1].toInt() and 0xff)
        index += 2
        return UdpRequest(fragment, addressResult.first, port, index, offset + length - index)
    }

    private fun encodeUdpPacket(
        address: InetAddress,
        port: Int,
        data: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray {
        val addressBytes = address.address
        val addressType = if (address is Inet6Address) ATYP_IPV6 else ATYP_IPV4
        val result = ByteArray(4 + addressBytes.size + 2 + length)
        var index = 0
        result[index++] = 0
        result[index++] = 0
        result[index++] = 0
        result[index++] = addressType.toByte()
        addressBytes.copyInto(result, index)
        index += addressBytes.size
        result[index++] = ((port ushr 8) and 0xff).toByte()
        result[index++] = (port and 0xff).toByte()
        data.copyInto(result, index, offset, offset + length)
        return result
    }

    private fun readAddress(input: InputStream, type: Int): InetAddress? = when (type) {
        ATYP_IPV4 -> InetAddress.getByAddress(readExact(input, 4))
        ATYP_IPV6 -> InetAddress.getByAddress(readExact(input, 16))
        ATYP_DOMAIN -> {
            val length = readU8(input)
            val host = String(readExact(input, length), Charsets.UTF_8)
            InetAddress.getByName(host)
        }
        else -> null
    }

    private fun readAddressFromBytes(
        data: ByteArray,
        start: Int,
        end: Int,
        type: Int
    ): Pair<InetAddress, Int>? {
        var index = start
        return when (type) {
            ATYP_IPV4 -> {
                if (index + 4 > end) return null
                InetAddress.getByAddress(data.copyOfRange(index, index + 4)) to (index + 4)
            }
            ATYP_IPV6 -> {
                if (index + 16 > end) return null
                InetAddress.getByAddress(data.copyOfRange(index, index + 16)) to (index + 16)
            }
            ATYP_DOMAIN -> {
                if (index >= end) return null
                val size = data[index++].toInt() and 0xff
                if (index + size > end) return null
                val host = String(data, index, size, Charsets.UTF_8)
                InetAddress.getByName(host) to (index + size)
            }
            else -> null
        }
    }

    private fun writeReply(output: OutputStream, reply: Int, address: InetSocketAddress?) {
        val bindAddress = address?.address
        val port = address?.port ?: 0
        val addressBytes = when (bindAddress) {
            is Inet6Address -> bindAddress.address
            is Inet4Address -> bindAddress.address
            else -> byteArrayOf(0, 0, 0, 0)
        }
        val type = if (addressBytes.size == 16) ATYP_IPV6 else ATYP_IPV4
        val response = ByteArray(4 + addressBytes.size + 2)
        response[0] = SOCKS_VERSION.toByte()
        response[1] = reply.toByte()
        response[2] = 0
        response[3] = type.toByte()
        addressBytes.copyInto(response, 4)
        val portIndex = 4 + addressBytes.size
        response[portIndex] = ((port ushr 8) and 0xff).toByte()
        response[portIndex + 1] = (port and 0xff).toByte()
        output.write(response)
        output.flush()
    }

    private fun readU8(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException()
        return value
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
        return result
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    private data class SocksRequest(val command: Int, val address: InetAddress, val port: Int)
    private data class UdpRequest(
        val fragment: Int,
        val address: InetAddress,
        val port: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    companion object {
        private const val SOCKS_VERSION = 5
        private const val METHOD_NO_AUTH = 0
        private const val METHOD_NONE = 0xff
        private const val CMD_CONNECT = 1
        private const val CMD_UDP_ASSOCIATE = 3
        private const val REP_SUCCEEDED = 0
        private const val REP_GENERAL_FAILURE = 1
        private const val REP_HOST_UNREACHABLE = 4
        private const val REP_COMMAND_NOT_SUPPORTED = 7
        private const val ATYP_IPV4 = 1
        private const val ATYP_DOMAIN = 3
        private const val ATYP_IPV6 = 4
        private const val DNS_PORT = 53
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val MAX_UDP_PACKET = 65_535
    }
}
