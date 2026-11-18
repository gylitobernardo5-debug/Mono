package com.example.vpn.packet

import java.net.InetAddress

/**
 * Real IPv4 + TCP/UDP packet parser and builder for Android VpnService TUN interface.
 * Handles accurate IPv4 header checksums, TCP/UDP pseudo-header checksums, and SYN MSS clamping.
 */
object IpPacket {
  const val PROTO_ICMP = 1
  const val PROTO_TCP = 6
  const val PROTO_UDP = 17

  const val TCP_FIN = 0x01
  const val TCP_SYN = 0x02
  const val TCP_RST = 0x04
  const val TCP_PSH = 0x08
  const val TCP_ACK = 0x10

  data class ParsedIpv4(
    val version: Int,
    val headerLength: Int,
    val totalLength: Int,
    val protocol: Int,
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val tcp: TcpHeader? = null,
    val udp: UdpHeader? = null,
    val payloadOffset: Int,
    val payloadLength: Int,
  ) {
    fun srcIpString(): String = InetAddress.getByAddress(srcIp).hostAddress ?: "0.0.0.0"

    fun dstIpString(): String = InetAddress.getByAddress(dstIp).hostAddress ?: "0.0.0.0"
  }

  data class TcpHeader(
    val srcPort: Int,
    val dstPort: Int,
    val seqNum: Long,
    val ackNum: Long,
    val dataOffset: Int,
    val flags: Int,
    val window: Int,
  )

  data class UdpHeader(
    val srcPort: Int,
    val dstPort: Int,
    val length: Int,
  )

  fun parse(packet: ByteArray, length: Int): ParsedIpv4? {
    if (length < 20) return null
    val verIhl = packet[0].toInt() and 0xFF
    val version = verIhl ushr 4
    if (version != 4) return null
    val ihl = (verIhl and 0x0F) * 4
    if (ihl < 20 || length < ihl) return null

    val totalLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
    val effectiveLen = minOf(length, totalLen)
    if (effectiveLen < ihl) return null

    val protocol = packet[9].toInt() and 0xFF
    val srcIp = packet.copyOfRange(12, 16)
    val dstIp = packet.copyOfRange(16, 20)

    return when (protocol) {
      PROTO_TCP -> {
        if (effectiveLen < ihl + 20) return null
        val srcPort = readU16(packet, ihl)
        val dstPort = readU16(packet, ihl + 2)
        val seq = readU32(packet, ihl + 4)
        val ack = readU32(packet, ihl + 8)
        val dataOff = ((packet[ihl + 12].toInt() and 0xF0) ushr 4) * 4
        if (dataOff < 20 || effectiveLen < ihl + dataOff) return null
        val flags = packet[ihl + 13].toInt() and 0x3F
        val window = readU16(packet, ihl + 14)
        val payloadOff = ihl + dataOff
        val payloadLen = (effectiveLen - payloadOff).coerceAtLeast(0)
        ParsedIpv4(
          version = version,
          headerLength = ihl,
          totalLength = effectiveLen,
          protocol = protocol,
          srcIp = srcIp,
          dstIp = dstIp,
          tcp = TcpHeader(srcPort, dstPort, seq, ack, dataOff, flags, window),
          payloadOffset = payloadOff,
          payloadLength = payloadLen,
        )
      }
      PROTO_UDP -> {
        if (effectiveLen < ihl + 8) return null
        val srcPort = readU16(packet, ihl)
        val dstPort = readU16(packet, ihl + 2)
        val udpLen = readU16(packet, ihl + 4)
        val payloadOff = ihl + 8
        val payloadLen = (effectiveLen - payloadOff).coerceAtLeast(0)
        ParsedIpv4(
          version = version,
          headerLength = ihl,
          totalLength = effectiveLen,
          protocol = protocol,
          srcIp = srcIp,
          dstIp = dstIp,
          udp = UdpHeader(srcPort, dstPort, udpLen),
          payloadOffset = payloadOff,
          payloadLength = payloadLen,
        )
      }
      else ->
        ParsedIpv4(
          version = version,
          headerLength = ihl,
          totalLength = effectiveLen,
          protocol = protocol,
          srcIp = srcIp,
          dstIp = dstIp,
          payloadOffset = ihl,
          payloadLength = (effectiveLen - ihl).coerceAtLeast(0),
        )
    }
  }

  fun buildTcpPacket(
    srcIp: ByteArray,
    dstIp: ByteArray,
    srcPort: Int,
    dstPort: Int,
    seqNum: Long,
    ackNum: Long,
    flags: Int,
    window: Int = 65535,
    payload: ByteArray = ByteArray(0),
    payloadOffset: Int = 0,
    payloadLength: Int = payload.size,
    includeMssOption: Boolean = false,
  ): ByteArray {
    val tcpHeaderLen = if (includeMssOption) 24 else 20
    val totalLen = 20 + tcpHeaderLen + payloadLength
    val pkt = ByteArray(totalLen)

    // IPv4 header
    pkt[0] = 0x45.toByte()
    pkt[1] = 0x00
    writeU16(pkt, 2, totalLen)
    writeU16(pkt, 4, (System.nanoTime() and 0xFFFF).toInt())
    writeU16(pkt, 6, 0x4000) // Don't Fragment
    pkt[8] = 64 // TTL
    pkt[9] = PROTO_TCP.toByte()
    System.arraycopy(srcIp, 0, pkt, 12, 4)
    System.arraycopy(dstIp, 0, pkt, 16, 4)
    writeU16(pkt, 10, computeIpv4Checksum(pkt, 20))

    // TCP header
    val t = 20
    writeU16(pkt, t, srcPort)
    writeU16(pkt, t + 2, dstPort)
    writeU32(pkt, t + 4, seqNum)
    writeU32(pkt, t + 8, ackNum)
    pkt[t + 12] = ((tcpHeaderLen / 4) shl 4).toByte()
    pkt[t + 13] = (flags and 0x3F).toByte()
    writeU16(pkt, t + 14, window.coerceIn(0, 65535))
    writeU16(pkt, t + 18, 0)

    if (includeMssOption) {
      pkt[t + 20] = 0x02 // MSS option kind
      pkt[t + 21] = 0x04 // length
      writeU16(pkt, t + 22, 1460)
    }

    if (payloadLength > 0) {
      System.arraycopy(payload, payloadOffset, pkt, 20 + tcpHeaderLen, payloadLength)
    }

    val tcpChecksum = computeTransportChecksum(srcIp, dstIp, PROTO_TCP, pkt, t, tcpHeaderLen + payloadLength)
    writeU16(pkt, t + 16, tcpChecksum)
    return pkt
  }

  fun buildUdpPacket(
    srcIp: ByteArray,
    dstIp: ByteArray,
    srcPort: Int,
    dstPort: Int,
    payload: ByteArray,
  ): ByteArray {
    val udpLen = 8 + payload.size
    val totalLen = 20 + udpLen
    val pkt = ByteArray(totalLen)

    pkt[0] = 0x45.toByte()
    pkt[1] = 0x00
    writeU16(pkt, 2, totalLen)
    writeU16(pkt, 4, (System.nanoTime() and 0xFFFF).toInt())
    writeU16(pkt, 6, 0x4000)
    pkt[8] = 64
    pkt[9] = PROTO_UDP.toByte()
    System.arraycopy(srcIp, 0, pkt, 12, 4)
    System.arraycopy(dstIp, 0, pkt, 16, 4)
    writeU16(pkt, 10, computeIpv4Checksum(pkt, 20))

    val u = 20
    writeU16(pkt, u, srcPort)
    writeU16(pkt, u + 2, dstPort)
    writeU16(pkt, u + 4, udpLen)
    System.arraycopy(payload, 0, pkt, u + 8, payload.size)

    var udpChecksum = computeTransportChecksum(srcIp, dstIp, PROTO_UDP, pkt, u, udpLen)
    if (udpChecksum == 0) udpChecksum = 0xFFFF
    writeU16(pkt, u + 6, udpChecksum)
    return pkt
  }

  fun computeIpv4Checksum(header: ByteArray, headerLen: Int): Int {
    var sum = 0L
    var i = 0
    while (i < headerLen) {
      if (i == 10) {
        i += 2
        continue
      }
      val word = ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
      sum += word
      i += 2
    }
    while ((sum ushr 16) != 0L) {
      sum = (sum and 0xFFFFL) + (sum ushr 16)
    }
    return (sum.inv() and 0xFFFFL).toInt()
  }

  fun computeTransportChecksum(
    srcIp: ByteArray,
    dstIp: ByteArray,
    protocol: Int,
    pkt: ByteArray,
    offset: Int,
    segmentLength: Int,
  ): Int {
    var sum = 0L
    sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
    sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
    sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
    sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
    sum += protocol.toLong()
    sum += segmentLength.toLong()

    var i = 0
    while (i + 1 < segmentLength) {
      val pos = offset + i
      // Zero out checksum field during calculation
      val isChecksumField =
        (protocol == PROTO_TCP && i == 16) || (protocol == PROTO_UDP && i == 6)
      if (!isChecksumField) {
        val word = ((pkt[pos].toInt() and 0xFF) shl 8) or (pkt[pos + 1].toInt() and 0xFF)
        sum += word
      }
      i += 2
    }
    if (i < segmentLength) {
      sum += (pkt[offset + i].toInt() and 0xFF) shl 8
    }

    while ((sum ushr 16) != 0L) {
      sum = (sum and 0xFFFFL) + (sum ushr 16)
    }
    return (sum.inv() and 0xFFFFL).toInt()
  }

  private fun readU16(buf: ByteArray, off: Int): Int =
    ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

  private fun readU32(buf: ByteArray, off: Int): Long =
    ((buf[off].toLong() and 0xFFL) shl 24) or
      ((buf[off + 1].toLong() and 0xFFL) shl 16) or
      ((buf[off + 2].toLong() and 0xFFL) shl 8) or
      (buf[off + 3].toLong() and 0xFFL)

  private fun writeU16(buf: ByteArray, off: Int, value: Int) {
    buf[off] = ((value ushr 8) and 0xFF).toByte()
    buf[off + 1] = (value and 0xFF).toByte()
  }

  private fun writeU32(buf: ByteArray, off: Int, value: Long) {
    buf[off] = ((value ushr 24) and 0xFFL).toByte()
    buf[off + 1] = ((value ushr 16) and 0xFFL).toByte()
    buf[off + 2] = ((value ushr 8) and 0xFFL).toByte()
    buf[off + 3] = (value and 0xFFL).toByte()
  }
}
