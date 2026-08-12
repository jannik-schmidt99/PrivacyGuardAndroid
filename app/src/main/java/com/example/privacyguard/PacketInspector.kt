package com.example.privacyguard

import java.net.InetAddress
import kotlin.math.min

object PacketInspector {
    data class ParsedPacket(
        val ipVersion: Int,
        val protocol: Int,
        val protocolLabel: String,
        val sourceAddress: InetAddress,
        val sourcePort: Int,
        val destinationAddress: InetAddress,
        val destinationPort: Int,
        val packetBytes: Int
    )

    fun parse(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < 1) return null
        return when ((buffer[0].toInt() ushr 4) and 0x0f) {
            4 -> parseIpv4(buffer, length)
            6 -> parseIpv6(buffer, length)
            else -> null
        }
    }

    private fun parseIpv4(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < 20) return null
        val headerLength = (buffer[0].toInt() and 0x0f) * 4
        if (headerLength < 20 || length < headerLength + 4) return null

        val totalLength = u16(buffer, 2)
        val fragmentField = u16(buffer, 6)
        val fragmentOffset = fragmentField and 0x1fff
        if (fragmentOffset != 0) return null

        val protocol = u8(buffer[9])
        if (protocol != PROTOCOL_TCP && protocol != PROTOCOL_UDP) return null

        val source = InetAddress.getByAddress(buffer.copyOfRange(12, 16))
        val destination = InetAddress.getByAddress(buffer.copyOfRange(16, 20))
        val sourcePort = u16(buffer, headerLength)
        val destinationPort = u16(buffer, headerLength + 2)
        val packetBytes = min(length, if (totalLength > 0) totalLength else length)

        return ParsedPacket(
            ipVersion = 4,
            protocol = protocol,
            protocolLabel = if (protocol == PROTOCOL_TCP) "TCP" else "UDP",
            sourceAddress = source,
            sourcePort = sourcePort,
            destinationAddress = destination,
            destinationPort = destinationPort,
            packetBytes = packetBytes
        )
    }

    private fun parseIpv6(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < 44) return null
        val payloadLength = u16(buffer, 4)
        val protocol = u8(buffer[6])
        if (protocol != PROTOCOL_TCP && protocol != PROTOCOL_UDP) return null

        val source = InetAddress.getByAddress(buffer.copyOfRange(8, 24))
        val destination = InetAddress.getByAddress(buffer.copyOfRange(24, 40))
        val sourcePort = u16(buffer, 40)
        val destinationPort = u16(buffer, 42)
        val packetBytes = min(length, 40 + payloadLength)

        return ParsedPacket(
            ipVersion = 6,
            protocol = protocol,
            protocolLabel = if (protocol == PROTOCOL_TCP) "TCP" else "UDP",
            sourceAddress = source,
            sourcePort = sourcePort,
            destinationAddress = destination,
            destinationPort = destinationPort,
            packetBytes = packetBytes
        )
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xff

    private fun u16(buffer: ByteArray, offset: Int): Int =
        (u8(buffer[offset]) shl 8) or u8(buffer[offset + 1])

    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17
}
