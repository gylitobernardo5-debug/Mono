package com.example

import com.example.vpn.packet.IpPacket
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IpPacketTest {

  @Test
  fun tcpSynAckRoundTripAndChecksumAreValid() {
    val srcIp = InetAddress.getByName("1.1.1.1").address
    val dstIp = InetAddress.getByName("10.88.0.2").address
    val payload = "PING".toByteArray()

    val pkt =
      IpPacket.buildTcpPacket(
        srcIp = srcIp,
        dstIp = dstIp,
        srcPort = 443,
        dstPort = 51234,
        seqNum = 0x1000_0001L,
        ackNum = 0x2000_0002L,
        flags = IpPacket.TCP_PSH or IpPacket.TCP_ACK,
        payload = payload,
        includeMssOption = true,
      )

    // Verify IPv4 header checksum validates to 0 when including checksum field
    assertEquals(0, computeFull16BitComplement(pkt, 0, 20))

    val parsed = IpPacket.parse(pkt, pkt.size)
    assertNotNull(parsed)
    assertEquals(4, parsed!!.version)
    assertEquals(IpPacket.PROTO_TCP, parsed.protocol)
    assertEquals("1.1.1.1", parsed.srcIpString())
    assertEquals("10.88.0.2", parsed.dstIpString())
    val tcpHeader = parsed.tcp!!
    assertEquals(443, tcpHeader.srcPort)
    assertEquals(51234, tcpHeader.dstPort)
    assertEquals(0x1000_0001L, tcpHeader.seqNum)
    assertEquals(0x2000_0002L, tcpHeader.ackNum)
    assertEquals(payload.size, parsed.payloadLength)
    assertArrayEquals(
      payload,
      pkt.copyOfRange(parsed.payloadOffset, parsed.payloadOffset + parsed.payloadLength),
    )
  }

  @Test
  fun udpDnsPacketRoundTripIsValid() {
    val srcIp = InetAddress.getByName("1.1.1.1").address
    val dstIp = InetAddress.getByName("10.88.0.2").address
    val dnsBytes = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00)

    val pkt =
      IpPacket.buildUdpPacket(
        srcIp = srcIp,
        dstIp = dstIp,
        srcPort = 53,
        dstPort = 44123,
        payload = dnsBytes,
      )

    assertEquals(0, computeFull16BitComplement(pkt, 0, 20))
    val parsed = IpPacket.parse(pkt, pkt.size)!!
    assertEquals(IpPacket.PROTO_UDP, parsed.protocol)
    val udpHeader = parsed.udp!!
    assertEquals(53, udpHeader.srcPort)
    assertEquals(44123, udpHeader.dstPort)
    assertEquals(dnsBytes.size, parsed.payloadLength)
  }

  private fun computeFull16BitComplement(data: ByteArray, offset: Int, len: Int): Int {
    var sum = 0L
    var i = offset
    while (i < offset + len) {
      sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
      i += 2
    }
    while ((sum ushr 16) != 0L) {
      sum = (sum and 0xFFFFL) + (sum ushr 16)
    }
    return (sum.inv() and 0xFFFFL).toInt()
  }
}
