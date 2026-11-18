package com.example

import com.example.data.model.SshAuthMethod
import com.example.data.model.SshProfileEntity
import com.example.data.model.TlsTransportType
import com.example.vpn.ssh.DropbearEngine
import com.example.vpn.tls.TlsSniVerifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsSniAndDropbearTest {

  @Test
  fun http403OnConnectIsRejectedAsNonTunnel() {
    val serverResponse = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray()
    val inp = ByteArrayInputStream(serverResponse)
    val out = ByteArrayOutputStream()

    val res =
      TlsSniVerifier.testTunnelForwarding(
        input = inp,
        output = out,
        transportType = TlsTransportType.HTTP_CONNECT_TLS,
        targetHost = "cp.cloudflare.com",
        targetPort = 80,
      )

    assertFalse(res.first)
    assertTrue(res.second.contains("403"))
    val sent = out.toString(Charsets.US_ASCII.name())
    assertTrue(sent.startsWith("CONNECT cp.cloudflare.com:80 HTTP/1.1"))
  }

  @Test
  fun http200OnConnectIsAcceptedAsTunnelTransport() {
    val serverResponse = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()
    val inp = ByteArrayInputStream(serverResponse)
    val out = ByteArrayOutputStream()

    val res =
      TlsSniVerifier.testTunnelForwarding(
        input = inp,
        output = out,
        transportType = TlsTransportType.HTTP_CONNECT_TLS,
        targetHost = "cp.cloudflare.com",
        targetPort = 80,
      )

    assertTrue(res.first)
    assertTrue(res.second.contains("200"))
  }

  @Test
  fun dropbearCommandIncludesRequiredFlagsAndDynamicSocks5() {
    val profile =
      SshProfileEntity(
        id = 1L,
        name = "Test Dropbear",
        host = "ssh.example.net",
        port = 443,
        username = "gvpnuser",
        authMethod = SshAuthMethod.PRIVATE_KEY,
        privateKeyContent = "KEY",
        keepAliveSeconds = 20,
      )
    val cmd =
      DropbearEngine.buildDropbearCommand(
        binaryPath = "/data/app/lib/arm64/libdropbear.so",
        profile = profile,
        socksPort = 10809,
        keyFilePath = "/tmp/active_id_key",
      )

    assertTrue(cmd.contains("-D"))
    assertTrue(cmd.contains("127.0.0.1:10809"))
    assertTrue(cmd.contains("-W"))
    assertTrue(cmd.contains("262144"))
    assertTrue(cmd.contains("-K"))
    assertTrue(cmd.contains("20"))
    assertTrue(cmd.contains("-i"))
    assertTrue(cmd.contains("/tmp/active_id_key"))
    assertTrue(cmd.last() == "ssh.example.net")
  }

  @Test
  fun dropbearErrorClassifierReportsAuthFailure() {
    val msg =
      DropbearEngine.classifyDropbearError(
        listOf("Login for user@host failed", "Permission denied (publickey,password)."),
        exitCode = 1,
      )
    assertTrue(msg.contains("Autenticação SSH Dropbear recusada"))
  }

  @Test
  fun allFourAndroidAbiDropbearBinariesExist() {
    val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    val projectRoot = File(System.getProperty("user.dir") ?: ".")
    val jniBase =
      if (File(projectRoot, "src/main/jniLibs").exists()) {
        File(projectRoot, "src/main/jniLibs")
      } else {
        File(projectRoot, "app/src/main/jniLibs")
      }

    abis.forEach { abi ->
      val bin = File(jniBase, "$abi/libdropbear.so")
      assertTrue("Missing Dropbear binary for $abi at ${bin.absolutePath}", bin.exists() && bin.length() > 100_000L)
    }
  }
}
