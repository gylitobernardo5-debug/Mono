package com.example.vpn.tunnel

import com.example.data.model.SniProfileEntity
import com.example.vpn.ssh.DropbearEngine
import com.example.vpn.tls.TlsSniVerifier
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Abstraction for opening real tunneled TCP streams and DNS-over-tunnel queries.
 */
interface UpstreamDialer {
  val modeTag: String

  fun openTcpTunnel(destIp: String, destPort: Int): Socket

  fun queryDnsThroughTunnel(dnsQuery: ByteArray, dnsServerIp: String = "1.1.1.1"): ByteArray? {
    val sock = openTcpTunnel(dnsServerIp, 53)
    return try {
      sock.soTimeout = 5000
      val out = sock.getOutputStream()
      val inp = sock.getInputStream()
      // RFC 1035 TCP DNS framing: 2-byte big-endian length prefix
      val lenPrefix =
        byteArrayOf(((dnsQuery.size ushr 8) and 0xFF).toByte(), (dnsQuery.size and 0xFF).toByte())
      out.write(lenPrefix)
      out.write(dnsQuery)
      out.flush()

      val respLenBuf = ByteArray(2)
      if (!readFully(inp, respLenBuf)) return null
      val respLen = ((respLenBuf[0].toInt() and 0xFF) shl 8) or (respLenBuf[1].toInt() and 0xFF)
      if (respLen <= 0 || respLen > 4096) return null
      val resp = ByteArray(respLen)
      if (!readFully(inp, resp)) return null
      resp
    } catch (_: Exception) {
      null
    } finally {
      runCatching { sock.close() }
    }
  }

  private fun readFully(inp: java.io.InputStream, dst: ByteArray): Boolean {
    var off = 0
    while (off < dst.size) {
      val r = inp.read(dst, off, dst.size - off)
      if (r <= 0) return false
      off += r
    }
    return true
  }
}

class DropbearSocks5Dialer(
  private val socksPort: Int,
) : UpstreamDialer {
  override val modeTag: String = "DROPBEAR-SSH"

  override fun openTcpTunnel(destIp: String, destPort: Int): Socket {
    val socket = Socket()
    socket.tcpNoDelay = true
    socket.keepAlive = true
    socket.soTimeout = 10000
    socket.connect(InetSocketAddress("127.0.0.1", socksPort), 4000)
    if (!DropbearEngine.socks5Connect(socket.getInputStream(), socket.getOutputStream(), destIp, destPort)) {
      socket.close()
      throw IOException("Dropbear SOCKS5 CONNECT failed for $destIp:$destPort")
    }
    socket.soTimeout = 0
    return socket
  }
}

class TlsSniTunnelDialer(
  private val profile: SniProfileEntity,
  private val socketProtector: (Socket) -> Unit,
) : UpstreamDialer {
  override val modeTag: String = "TLS-SNI"

  override fun openTcpTunnel(destIp: String, destPort: Int): Socket {
    return TlsSniVerifier.openTunneledTlsSocket(
      profile = profile,
      destHost = destIp,
      destPort = destPort,
      socketProtector = socketProtector,
    )
  }
}
