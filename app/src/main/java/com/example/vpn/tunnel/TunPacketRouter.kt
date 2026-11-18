package com.example.vpn.tunnel

import android.os.ParcelFileDescriptor
import com.example.data.model.LogLevel
import com.example.vpn.packet.IpPacket
import com.example.vpn.speed.GvpnEngineState
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Extreme Speed Engine V2 TUN Packet Router.
 * Reads raw IPv4 packets from Android VpnService TUN descriptor, translates TCP flows and
 * UDP port-53 DNS queries into real tunneled streams via UpstreamDialer, and writes response
 * packets back through a serialized TUN writer channel.
 */
class TunPacketRouter(
  private val tunFd: ParcelFileDescriptor,
  private val dialer: UpstreamDialer,
  private val scope: CoroutineScope,
  private val onCriticalTransportFailure: (String) -> Unit,
) {
  private val running = AtomicBoolean(true)
  private val tunWriteChannel = Channel<ByteArray>(capacity = 512)
  private val tcpSessions = ConcurrentHashMap<FlowKey, TcpFlowSession>()

  private var readerJob: Job? = null
  private var writerJob: Job? = null

  data class FlowKey(
    val srcIpInt: Int,
    val srcPort: Int,
    val dstIpInt: Int,
    val dstPort: Int,
  )

  fun start() {
    val inputStream = FileInputStream(tunFd.fileDescriptor)
    val outputStream = FileOutputStream(tunFd.fileDescriptor)

    writerJob =
      scope.launch(Dispatchers.IO) {
        try {
          for (packet in tunWriteChannel) {
            if (!running.get()) break
            outputStream.write(packet)
          }
        } catch (e: Exception) {
          if (running.get()) {
            GvpnEngineState.log("TUN-WRITE", "Erro no descritor TUN: ${e.message}", LogLevel.ERROR)
            onCriticalTransportFailure("Descritor TUN encerrado.")
          }
        }
      }

    readerJob =
      scope.launch(Dispatchers.IO) {
        val readBuf = ByteArray(32768)
        try {
          while (running.get()) {
            val n = inputStream.read(readBuf)
            if (n < 0) break
            if (n == 0) continue
            val parsed = IpPacket.parse(readBuf, n) ?: continue
            when (parsed.protocol) {
              IpPacket.PROTO_TCP -> handleTcpPacket(parsed, readBuf)
              IpPacket.PROTO_UDP -> handleUdpPacket(parsed, readBuf)
            }
          }
        } catch (e: Exception) {
          if (running.get()) {
            GvpnEngineState.log("TUN-READ", "Leitura TUN interrompida: ${e.message}", LogLevel.WARN)
            onCriticalTransportFailure("Falha na leitura do túnel TUN.")
          }
        }
      }
  }

  fun stop() {
    if (!running.getAndSet(false)) return
    readerJob?.cancel()
    writerJob?.cancel()
    tunWriteChannel.close()
    tcpSessions.values.forEach { it.closeQuietly() }
    tcpSessions.clear()
  }

  private fun handleUdpPacket(pkt: IpPacket.ParsedIpv4, rawBuf: ByteArray) {
    val udp = pkt.udp ?: return
    if (udp.dstPort != 53 || pkt.payloadLength <= 0) return

    val queryPayload = rawBuf.copyOfRange(pkt.payloadOffset, pkt.payloadOffset + pkt.payloadLength)
    GvpnEngineState.recordTxBytes(pkt.payloadLength)

    scope.launch(Dispatchers.IO) {
      val dnsServer = pkt.dstIpString()
      val response =
        dialer.queryDnsThroughTunnel(queryPayload, dnsServer)
          ?: dialer.queryDnsThroughTunnel(queryPayload, "1.1.1.1")
      if (response != null && running.get()) {
        GvpnEngineState.recordRxBytes(response.size)
        val udpResp =
          IpPacket.buildUdpPacket(
            srcIp = pkt.dstIp,
            dstIp = pkt.srcIp,
            srcPort = udp.dstPort,
            dstPort = udp.srcPort,
            payload = response,
          )
        tunWriteChannel.trySend(udpResp)
      }
    }
  }

  private fun handleTcpPacket(pkt: IpPacket.ParsedIpv4, rawBuf: ByteArray) {
    val tcp = pkt.tcp ?: return
    val key =
      FlowKey(
        srcIpInt = ipToInt(pkt.srcIp),
        srcPort = tcp.srcPort,
        dstIpInt = ipToInt(pkt.dstIp),
        dstPort = tcp.dstPort,
      )

    val isSyn = (tcp.flags and IpPacket.TCP_SYN) != 0 && (tcp.flags and IpPacket.TCP_ACK) == 0
    val isRst = (tcp.flags and IpPacket.TCP_RST) != 0
    val isFin = (tcp.flags and IpPacket.TCP_FIN) != 0

    if (isRst) {
      tcpSessions.remove(key)?.closeQuietly()
      return
    }

    if (isSyn) {
      tcpSessions.remove(key)?.closeQuietly()
      val session =
        TcpFlowSession(
          key = key,
          clientIp = pkt.srcIp,
          remoteIp = pkt.dstIp,
          clientPort = tcp.srcPort,
          remotePort = tcp.dstPort,
          initialClientSeq = tcp.seqNum,
          remoteIpStr = pkt.dstIpString(),
        )
      tcpSessions[key] = session
      session.startConnect()
      return
    }

    val session = tcpSessions[key] ?: return

    if (pkt.payloadLength > 0) {
      val data = rawBuf.copyOfRange(pkt.payloadOffset, pkt.payloadOffset + pkt.payloadLength)
      session.onClientPayload(tcp.seqNum, data)
    }

    if (isFin) {
      session.onClientFin(tcp.seqNum, pkt.payloadLength)
    }
  }

  private inner class TcpFlowSession(
    private val key: FlowKey,
    private val clientIp: ByteArray,
    private val remoteIp: ByteArray,
    private val clientPort: Int,
    private val remotePort: Int,
    initialClientSeq: Long,
    private val remoteIpStr: String,
  ) {
    @Volatile private var upstreamSocket: Socket? = null
    @Volatile private var closed = false
    private var clientNextSeq: Long = (initialClientSeq + 1L) and 0xFFFF_FFFFL
    private var serverSeq: Long = (System.nanoTime() and 0x7FFF_FFFFL) or 0x1000_0000L
    private val upstreamSendChannel = Channel<ByteArray>(capacity = 128)

    fun startConnect() {
      GvpnEngineState.incrementFlows()
      scope.launch(Dispatchers.IO) {
        try {
          val sock = dialer.openTcpTunnel(remoteIpStr, remotePort)
          if (closed || !running.get()) {
            sock.close()
            return@launch
          }
          upstreamSocket = sock

          // Send SYN+ACK back to Android app via TUN
          val synAck =
            IpPacket.buildTcpPacket(
              srcIp = remoteIp,
              dstIp = clientIp,
              srcPort = remotePort,
              dstPort = clientPort,
              seqNum = serverSeq,
              ackNum = clientNextSeq,
              flags = IpPacket.TCP_SYN or IpPacket.TCP_ACK,
              includeMssOption = true,
            )
          serverSeq = (serverSeq + 1L) and 0xFFFF_FFFFL
          tunWriteChannel.send(synAck)

          // Launch upstream writer
          launch(Dispatchers.IO) {
            try {
              val out = sock.getOutputStream()
              for (chunk in upstreamSendChannel) {
                if (closed) break
                out.write(chunk)
                out.flush()
              }
            } catch (_: Exception) {
              sendRstAndClose()
            }
          }

          // Upstream reader -> TUN packets (with adaptive buffer pool)
          val inp = sock.getInputStream()
          while (!closed && running.get()) {
            val buf = GvpnEngineState.bufferPool.acquire()
            try {
              val n = inp.read(buf, 0, minOf(buf.size, 1400))
              if (n <= 0) {
                sendFinAndClose()
                break
              }
              GvpnEngineState.recordRxBytes(n)
              val dataPkt =
                IpPacket.buildTcpPacket(
                  srcIp = remoteIp,
                  dstIp = clientIp,
                  srcPort = remotePort,
                  dstPort = clientPort,
                  seqNum = serverSeq,
                  ackNum = clientNextSeq,
                  flags = IpPacket.TCP_PSH or IpPacket.TCP_ACK,
                  payload = buf,
                  payloadOffset = 0,
                  payloadLength = n,
                )
              serverSeq = (serverSeq + n.toLong()) and 0xFFFF_FFFFL
              tunWriteChannel.send(dataPkt)
            } finally {
              GvpnEngineState.bufferPool.release(buf)
            }
          }
        } catch (_: Exception) {
          sendRstAndClose()
        }
      }
    }

    @Synchronized
    fun onClientPayload(seqNum: Long, data: ByteArray) {
      if (closed) return
      // Only accept in-order bytes cleanly; send ACK immediately
      if (seqNum == clientNextSeq) {
        clientNextSeq = (clientNextSeq + data.size.toLong()) and 0xFFFF_FFFFL
        GvpnEngineState.recordTxBytes(data.size)
        upstreamSendChannel.trySend(data)
      }
      val ackPkt =
        IpPacket.buildTcpPacket(
          srcIp = remoteIp,
          dstIp = clientIp,
          srcPort = remotePort,
          dstPort = clientPort,
          seqNum = serverSeq,
          ackNum = clientNextSeq,
          flags = IpPacket.TCP_ACK,
        )
      tunWriteChannel.trySend(ackPkt)
    }

    @Synchronized
    fun onClientFin(seqNum: Long, payloadLength: Int) {
      if (closed) return
      val expectedFinSeq = (seqNum + payloadLength.toLong()) and 0xFFFF_FFFFL
      if (expectedFinSeq == clientNextSeq) {
        clientNextSeq = (clientNextSeq + 1L) and 0xFFFF_FFFFL
      }
      val finAck =
        IpPacket.buildTcpPacket(
          srcIp = remoteIp,
          dstIp = clientIp,
          srcPort = remotePort,
          dstPort = clientPort,
          seqNum = serverSeq,
          ackNum = clientNextSeq,
          flags = IpPacket.TCP_FIN or IpPacket.TCP_ACK,
        )
      serverSeq = (serverSeq + 1L) and 0xFFFF_FFFFL
      tunWriteChannel.trySend(finAck)
      closeQuietly()
    }

    private fun sendFinAndClose() {
      if (closed) return
      val finPkt =
        IpPacket.buildTcpPacket(
          srcIp = remoteIp,
          dstIp = clientIp,
          srcPort = remotePort,
          dstPort = clientPort,
          seqNum = serverSeq,
          ackNum = clientNextSeq,
          flags = IpPacket.TCP_FIN or IpPacket.TCP_ACK,
        )
      tunWriteChannel.trySend(finPkt)
      closeQuietly()
    }

    private fun sendRstAndClose() {
      if (closed) return
      val rstPkt =
        IpPacket.buildTcpPacket(
          srcIp = remoteIp,
          dstIp = clientIp,
          srcPort = remotePort,
          dstPort = clientPort,
          seqNum = serverSeq,
          ackNum = clientNextSeq,
          flags = IpPacket.TCP_RST or IpPacket.TCP_ACK,
        )
      tunWriteChannel.trySend(rstPkt)
      closeQuietly()
    }

    fun closeQuietly() {
      if (closed) return
      closed = true
      tcpSessions.remove(key)
      GvpnEngineState.decrementFlows()
      upstreamSendChannel.close()
      runCatching { upstreamSocket?.close() }
      upstreamSocket = null
    }
  }

  private fun ipToInt(ip: ByteArray): Int =
    ((ip[0].toInt() and 0xFF) shl 24) or
      ((ip[1].toInt() and 0xFF) shl 16) or
      ((ip[2].toInt() and 0xFF) shl 8) or
      (ip[3].toInt() and 0xFF)
}
