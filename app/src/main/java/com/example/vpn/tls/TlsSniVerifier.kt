package com.example.vpn.tls

import android.util.Base64
import com.example.data.model.SniProfileEntity
import com.example.data.model.TlsTransportType
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TlsProbeResult(
  val tcpReachable: Boolean,
  val tlsHandshakeOk: Boolean,
  val certificateValid: Boolean,
  val tunnelCompatible: Boolean,
  val endToEndVerified: Boolean,
  val latencyMs: Long,
  val tlsProtocol: String = "---",
  val cipherSuite: String = "---",
  val certSubject: String = "---",
  val externalIp: String? = null,
  val diagnosticMessage: String,
)

/**
 * Real TLS + SNI verifier.
 * Performs actual TCP connect, SSLSocket handshake with explicit SNIHostName in ClientHello,
 * X.509 certificate validation, AND real tunnel forwarding test (HTTP CONNECT / SOCKS5 / WS)
 * to confirm end-to-end internet traversal. Never declares a plain TLS handshake as a VPN tunnel.
 */
object TlsSniVerifier {
  private const val CONNECT_TIMEOUT_MS = 5000
  private const val READ_TIMEOUT_MS = 6000

  suspend fun probeProfile(
    profile: SniProfileEntity,
    socketProtector: ((Socket) -> Unit)? = null,
  ): TlsProbeResult =
    withContext(Dispatchers.IO) {
      val startedAt = System.currentTimeMillis()
      val rawSocket = Socket()
      var sslSocket: SSLSocket? = null
      try {
        socketProtector?.invoke(rawSocket)
        rawSocket.tcpNoDelay = true
        rawSocket.keepAlive = true
        rawSocket.soTimeout = READ_TIMEOUT_MS
        rawSocket.connect(
          InetSocketAddress(profile.gatewayHost.trim(), profile.gatewayPort),
          CONNECT_TIMEOUT_MS,
        )

        val trustManager = systemTrustManager()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())

        sslSocket =
          sslContext.socketFactory.createSocket(
            rawSocket,
            profile.sniHost.trim().ifEmpty { profile.gatewayHost.trim() },
            profile.gatewayPort,
            true,
          ) as SSLSocket

        val effectiveSni = profile.sniHost.trim().ifEmpty { profile.gatewayHost.trim() }
        val params = sslSocket.sslParameters
        if (effectiveSni.isNotEmpty() && !isIpLiteral(effectiveSni)) {
          params.serverNames = listOf(SNIHostName(effectiveSni))
        }
        sslSocket.sslParameters = params
        sslSocket.soTimeout = READ_TIMEOUT_MS
        sslSocket.startHandshake()

        val session = sslSocket.session
        val protocol = session.protocol ?: "TLS"
        val cipher = session.cipherSuite ?: "---"
        val peerCert = session.peerCertificates.firstOrNull() as? X509Certificate
        val certSubject = peerCert?.subjectX500Principal?.name ?: "Sem certificado X.509"

        val hostnameMatches =
          if (!profile.verifyHostname || effectiveSni.isEmpty()) {
            true
          } else {
            HttpsURLConnection.getDefaultHostnameVerifier().verify(effectiveSni, session)
          }

        if (!hostnameMatches) {
          val elapsed = System.currentTimeMillis() - startedAt
          return@withContext TlsProbeResult(
            tcpReachable = true,
            tlsHandshakeOk = true,
            certificateValid = false,
            tunnelCompatible = false,
            endToEndVerified = false,
            latencyMs = elapsed,
            tlsProtocol = protocol,
            cipherSuite = cipher,
            certSubject = certSubject,
            diagnosticMessage =
              "Certificado TLS não corresponde ao SNI '$effectiveSni' ($certSubject).",
          )
        }

        // TLS + SNI handshake succeeded. Now test REAL tunnel forwarding — never assume TLS == VPN!
        val tunnelCheck =
          testTunnelForwarding(
            input = BufferedInputStream(sslSocket.inputStream),
            output = sslSocket.outputStream,
            transportType = profile.transportType,
            targetHost = "cp.cloudflare.com",
            targetPort = 80,
            customHeader = profile.customHeader,
          )

        val elapsed = System.currentTimeMillis() - startedAt
        if (!tunnelCheck.first) {
          return@withContext TlsProbeResult(
            tcpReachable = true,
            tlsHandshakeOk = true,
            certificateValid = true,
            tunnelCompatible = false,
            endToEndVerified = false,
            latencyMs = elapsed,
            tlsProtocol = protocol,
            cipherSuite = cipher,
            certSubject = certSubject,
            diagnosticMessage =
              "TLS+SNI ($protocol) aceite, mas o destino NÃO autoriza túnel VPN: ${tunnelCheck.second}",
          )
        }

        // Send a real HTTP/1.1 request INSIDE the established tunnel to verify actual traffic traversal
        val httpOk = verifyHttp204OverTunnel(sslSocket.inputStream, sslSocket.outputStream)
        val totalElapsed = System.currentTimeMillis() - startedAt
        if (!httpOk.first) {
          return@withContext TlsProbeResult(
            tcpReachable = true,
            tlsHandshakeOk = true,
            certificateValid = true,
            tunnelCompatible = false,
            endToEndVerified = false,
            latencyMs = totalElapsed,
            tlsProtocol = protocol,
            cipherSuite = cipher,
            certSubject = certSubject,
            diagnosticMessage =
              "Negociação de túnel inicializada, mas falhou o tráfego real fim-a-fim: ${httpOk.second}",
          )
        }

        TlsProbeResult(
          tcpReachable = true,
          tlsHandshakeOk = true,
          certificateValid = true,
          tunnelCompatible = true,
          endToEndVerified = true,
          latencyMs = totalElapsed,
          tlsProtocol = protocol,
          cipherSuite = cipher,
          certSubject = certSubject,
          diagnosticMessage =
            "Túnel real verificado ($protocol · SNI: $effectiveSni · ${totalElapsed}ms).",
        )
      } catch (e: javax.net.ssl.SSLHandshakeException) {
        TlsProbeResult(
          tcpReachable = true,
          tlsHandshakeOk = false,
          certificateValid = false,
          tunnelCompatible = false,
          endToEndVerified = false,
          latencyMs = System.currentTimeMillis() - startedAt,
          diagnosticMessage = "Falha TLS/Certificado: ${e.localizedMessage ?: e.javaClass.simpleName}",
        )
      } catch (e: Exception) {
        TlsProbeResult(
          tcpReachable = false,
          tlsHandshakeOk = false,
          certificateValid = false,
          tunnelCompatible = false,
          endToEndVerified = false,
          latencyMs = System.currentTimeMillis() - startedAt,
          diagnosticMessage = "Falha de ligação: ${e.localizedMessage ?: e.javaClass.simpleName}",
        )
      } finally {
        runCatching { sslSocket?.close() }
        runCatching { rawSocket.close() }
      }
    }

  fun openTunneledTlsSocket(
    profile: SniProfileEntity,
    destHost: String,
    destPort: Int,
    socketProtector: ((Socket) -> Unit)? = null,
  ): SSLSocket {
    val rawSocket = Socket()
    socketProtector?.invoke(rawSocket)
    rawSocket.tcpNoDelay = true
    rawSocket.keepAlive = true
    rawSocket.soTimeout = 15000
    rawSocket.connect(
      InetSocketAddress(profile.gatewayHost.trim(), profile.gatewayPort),
      CONNECT_TIMEOUT_MS,
    )

    val trustManager = systemTrustManager()
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf(trustManager), SecureRandom())

    val effectiveSni = profile.sniHost.trim().ifEmpty { profile.gatewayHost.trim() }
    val sslSocket =
      sslContext.socketFactory.createSocket(
        rawSocket,
        effectiveSni,
        profile.gatewayPort,
        true,
      ) as SSLSocket

    val params = sslSocket.sslParameters
    if (effectiveSni.isNotEmpty() && !isIpLiteral(effectiveSni)) {
      params.serverNames = listOf(SNIHostName(effectiveSni))
    }
    sslSocket.sslParameters = params
    sslSocket.startHandshake()

    if (profile.verifyHostname && effectiveSni.isNotEmpty()) {
      val ok = HttpsURLConnection.getDefaultHostnameVerifier().verify(effectiveSni, sslSocket.session)
      if (!ok) {
        sslSocket.close()
        throw javax.net.ssl.SSLPeerUnverifiedException("SNI hostname mismatch for $effectiveSni")
      }
    }

    val tunnelRes =
      testTunnelForwarding(
        input = sslSocket.inputStream,
        output = sslSocket.outputStream,
        transportType = profile.transportType,
        targetHost = destHost,
        targetPort = destPort,
        customHeader = profile.customHeader,
      )
    if (!tunnelRes.first) {
      sslSocket.close()
      throw java.io.IOException("Tunnel rejected by gateway: ${tunnelRes.second}")
    }
    sslSocket.soTimeout = 0
    return sslSocket
  }

  internal fun testTunnelForwarding(
    input: InputStream,
    output: OutputStream,
    transportType: TlsTransportType,
    targetHost: String,
    targetPort: Int,
    customHeader: String = "",
  ): Pair<Boolean, String> {
    return when (transportType) {
      TlsTransportType.HTTP_CONNECT_TLS -> {
        val sb = StringBuilder()
        sb.append("CONNECT ").append(targetHost).append(':').append(targetPort).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(targetHost).append(':').append(targetPort).append("\r\n")
        sb.append("Proxy-Connection: Keep-Alive\r\n")
        sb.append("User-Agent: G-VPN-Extreme/2.0\r\n")
        if (customHeader.isNotBlank()) {
          sb.append(customHeader.trim()).append("\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.US_ASCII))
        output.flush()

        val responseHeader = readHttpHeaderBlock(input)
        val statusLine = responseHeader.lineSequence().firstOrNull().orEmpty()
        if (statusLine.contains(" 200 ") || statusLine.endsWith(" 200")) {
          true to statusLine
        } else {
          false to statusLine.ifBlank { "Sem resposta HTTP CONNECT do servidor" }
        }
      }
      TlsTransportType.SOCKS5_TLS -> {
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val greet = ByteArray(2)
        if (!readFully(input, greet) || greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) {
          return false to "SOCKS5 greeting recusado"
        }
        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        val req = ByteArray(4 + 1 + hostBytes.size + 2)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = 0x03
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((targetPort ushr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
        output.write(req)
        output.flush()

        val rep = ByteArray(4)
        if (!readFully(input, rep) || rep[0] != 0x05.toByte() || rep[1] != 0x00.toByte()) {
          return false to "SOCKS5 CONNECT recusado (código ${rep[1].toInt() and 0xFF})"
        }
        when (rep[3].toInt() and 0xFF) {
          0x01 -> readFully(input, ByteArray(6))
          0x04 -> readFully(input, ByteArray(18))
          0x03 -> {
            val lenArr = ByteArray(1)
            if (readFully(input, lenArr)) {
              readFully(input, ByteArray((lenArr[0].toInt() and 0xFF) + 2))
            }
          }
        }
        true to "SOCKS5/TLS CONNECT OK"
      }
      TlsTransportType.WEBSOCKET_TLS -> {
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val sb = StringBuilder()
        sb.append("GET / HTTP/1.1\r\n")
        sb.append("Host: ").append(targetHost).append("\r\n")
        sb.append("Upgrade: websocket\r\n")
        sb.append("Connection: Upgrade\r\n")
        sb.append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
        sb.append("Sec-WebSocket-Version: 13\r\n")
        sb.append("X-Tunnel-Target: ").append(targetHost).append(':').append(targetPort).append("\r\n")
        if (customHeader.isNotBlank()) {
          sb.append(customHeader.trim()).append("\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.US_ASCII))
        output.flush()
        val responseHeader = readHttpHeaderBlock(input)
        val statusLine = responseHeader.lineSequence().firstOrNull().orEmpty()
        if (statusLine.contains(" 101 ")) {
          true to statusLine
        } else {
          false to statusLine.ifBlank { "Servidor não suporta WebSocket Relay (esperado HTTP 101)" }
        }
      }
    }
  }

  private fun verifyHttp204OverTunnel(input: InputStream, output: OutputStream): Pair<Boolean, String> {
    return try {
      val req =
        "GET /generate_204 HTTP/1.1\r\nHost: cp.cloudflare.com\r\nConnection: close\r\nUser-Agent: G-VPN/2.0\r\n\r\n"
      output.write(req.toByteArray(Charsets.US_ASCII))
      output.flush()
      val header = readHttpHeaderBlock(input)
      val status = header.lineSequence().firstOrNull().orEmpty()
      if (status.contains(" 204") || status.contains(" 200")) {
        true to status
      } else {
        false to status.ifBlank { "Sem resposta HTTP através do túnel" }
      }
    } catch (e: Exception) {
      false to (e.localizedMessage ?: "Erro de leitura fim-a-fim")
    }
  }

  private fun readHttpHeaderBlock(input: InputStream): String {
    val bytes = ArrayList<Byte>(256)
    var prev3 = 0
    var prev2 = 0
    var prev1 = 0
    while (bytes.size < 8192) {
      val b = input.read()
      if (b == -1) break
      bytes.add(b.toByte())
      if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
        break
      }
      prev3 = prev2
      prev2 = prev1
      prev1 = b
    }
    return String(bytes.toByteArray(), Charsets.US_ASCII)
  }

  private fun readFully(input: InputStream, dst: ByteArray): Boolean {
    var off = 0
    while (off < dst.size) {
      val r = input.read(dst, off, dst.size - off)
      if (r <= 0) return false
      off += r
    }
    return true
  }

  private fun systemTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
  }

  private fun isIpLiteral(value: String): Boolean {
    return value.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) || value.contains(':')
  }
}
