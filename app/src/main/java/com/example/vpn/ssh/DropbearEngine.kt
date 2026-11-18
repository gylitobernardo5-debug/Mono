package com.example.vpn.ssh

import android.content.Context
import android.system.Os
import com.example.data.model.LogLevel
import com.example.data.model.SshAuthMethod
import com.example.data.model.SshProfileEntity
import com.example.vpn.speed.GvpnEngineState
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class DropbearSessionResult(
  val success: Boolean,
  val socksPort: Int = 0,
  val pid: Int? = null,
  val externalIp: String? = null,
  val latencyMs: Long = 0L,
  val authState: String,
  val errorMessage: String? = null,
)

/**
 * Exclusive Dropbear SSH tunnel engine (uses bundled native Dropbear 2026.94 dbclient binary).
 * Never uses OpenSSH or any non-Dropbear SSH library.
 */
class DropbearEngine(private val context: Context) {
  @Volatile private var process: Process? = null
  @Volatile private var monitorJob: Job? = null
  @Volatile private var tempKeyFile: File? = null
  @Volatile var activeSocksPort: Int = 0
    private set

  fun resolveBinaryFile(): File {
    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    return File(nativeDir, "libdropbear.so")
  }

  fun findFreeLoopbackPort(): Int {
    ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { sock ->
      return sock.localPort
    }
  }

  suspend fun startAndVerifySession(
    profile: SshProfileEntity,
    scope: CoroutineScope,
    onUnexpectedExit: (String) -> Unit,
  ): DropbearSessionResult =
    withContext(Dispatchers.IO) {
      stop()

      val binary = resolveBinaryFile()
      if (!binary.exists() || !binary.canExecute()) {
        return@withContext DropbearSessionResult(
          success = false,
          authState = "Binário Dropbear ausente",
          errorMessage =
            "Binário nativo Dropbear (${binary.absolutePath}) não encontrado ou sem permissão de execução.",
        )
      }

      if (profile.host.isBlank() || profile.username.isBlank() || profile.port !in 1..65535) {
        return@withContext DropbearSessionResult(
          success = false,
          authState = "Configuração inválida",
          errorMessage = "Preencha host, porta (1-65535) e nome de utilizador SSH válidos.",
        )
      }

      if (profile.authMethod == SshAuthMethod.PASSWORD && profile.password.isEmpty()) {
        return@withContext DropbearSessionResult(
          success = false,
          authState = "Sem palavra-passe",
          errorMessage = "Introduza a palavra-passe SSH ou mude para Chave Privada.",
        )
      }

      if (profile.authMethod == SshAuthMethod.PRIVATE_KEY && profile.privateKeyContent.isBlank()) {
        return@withContext DropbearSessionResult(
          success = false,
          authState = "Sem chave privada",
          errorMessage = "Cole ou importe uma chave privada compatível com Dropbear.",
        )
      }

      val homeDir = File(context.filesDir, "dropbear_home").apply {
        mkdirs()
        runCatching { Os.chmod(absolutePath, 448) } // 0700
      }
      File(homeDir, ".ssh").apply {
        mkdirs()
        runCatching { Os.chmod(absolutePath, 448) } // 0700
      }

      var keyFilePath: String? = null
      if (profile.authMethod == SshAuthMethod.PRIVATE_KEY) {
        val kf = File(homeDir, "active_id_key")
        kf.writeText(profile.privateKeyContent.trim() + "\n")
        runCatching { Os.chmod(kf.absolutePath, 384) } // 0600
        tempKeyFile = kf
        keyFilePath = kf.absolutePath
      }

      val socksPort = findFreeLoopbackPort()
      val cmd = buildDropbearCommand(binary.absolutePath, profile, socksPort, keyFilePath)
      GvpnEngineState.log(
        "DROPBEAR",
        "A iniciar Dropbear nativo: ${profile.username}@${profile.host}:${profile.port} (SOCKS5 127.0.0.1:$socksPort)",
        LogLevel.INFO,
      )

      val startedAt = System.currentTimeMillis()
      val pb = ProcessBuilder(cmd)
      pb.redirectErrorStream(true)
      val env = pb.environment()
      env["HOME"] = homeDir.absolutePath
      if (profile.authMethod == SshAuthMethod.PASSWORD) {
        env["DROPBEAR_PASSWORD"] = profile.password
      } else {
        env.remove("DROPBEAR_PASSWORD")
      }

      val proc =
        try {
          pb.start()
        } catch (e: Exception) {
          return@withContext DropbearSessionResult(
            success = false,
            authState = "Falha ao executar Dropbear",
            errorMessage = "Não foi possível iniciar o processo Dropbear: ${e.localizedMessage}",
          )
        }
      process = proc
      val pid = extractPid(proc)

      val sessionReady = CompletableDeferred<Result<Unit>>()
      val outputLines = ArrayList<String>(16)

      monitorJob =
        scope.launch(Dispatchers.IO) {
          try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
              var line: String?
              while (reader.readLine().also { line = it } != null) {
                val msg = line!!.trim()
                if (msg.isEmpty()) continue
                synchronized(outputLines) {
                  if (outputLines.size < 25) outputLines.add(msg)
                }
                when {
                  msg.startsWith("G_DROPBEAR_SOCKS5_LISTENING:") -> {
                    GvpnEngineState.log("DROPBEAR", "Forwarder SOCKS5 Dropbear ativo na porta $socksPort", LogLevel.INFO)
                  }
                  msg == "G_DROPBEAR_SESSION_RUNNING" -> {
                    GvpnEngineState.log("DROPBEAR", "Sessão SSH Dropbear autenticada (SESSION_RUNNING)", LogLevel.SUCCESS)
                    if (!sessionReady.isCompleted) sessionReady.complete(Result.success(Unit))
                  }
                  else -> {
                    val level =
                      if (msg.contains("failed", ignoreCase = true) || msg.contains("error", ignoreCase = true) || msg.contains("denied", ignoreCase = true)) {
                        LogLevel.ERROR
                      } else {
                        LogLevel.INFO
                      }
                    GvpnEngineState.log("DROPBEAR", msg, level)
                  }
                }
              }
            }
          } catch (_: Exception) {
          }

          val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
          val reason = classifyDropbearError(outputLines, exitCode)
          if (!sessionReady.isCompleted) {
            sessionReady.complete(Result.failure(IllegalStateException(reason)))
          } else if (process === proc) {
            onUnexpectedExit(reason)
          }
        }

      val readyOutcome = withTimeoutOrNull(15_000L) { sessionReady.await() }
      if (readyOutcome == null) {
        stop()
        return@withContext DropbearSessionResult(
          success = false,
          authState = "Tempo esgotado (Dropbear)",
          errorMessage = "Timeout (15s) ao autenticar sessão SSH Dropbear em ${profile.host}:${profile.port}.",
        )
      }

      if (readyOutcome.isFailure) {
        val reason = readyOutcome.exceptionOrNull()?.message ?: "Falha na sessão Dropbear"
        stop()
        return@withContext DropbearSessionResult(
          success = false,
          authState = "Autenticação/Sessão recusada",
          errorMessage = reason,
        )
      }

      // Give Dropbear 150ms to finish binding listener if needed, then verify REAL end-to-end tunnel traffic!
      delay(150)
      activeSocksPort = socksPort

      GvpnEngineState.log(
        "DROPBEAR",
        "Sessão autenticada. A confirmar tráfego real através do túnel Dropbear...",
        LogLevel.INFO,
      )
      val verifyResult = verifyEndToEndViaSocks5(socksPort)
      if (!verifyResult.first) {
        stop()
        return@withContext DropbearSessionResult(
          success = false,
          authState = "Autenticado, mas sem túnel real",
          errorMessage =
            "A sessão Dropbear autenticou, mas falhou o encaminhamento real de tráfego: ${verifyResult.second}",
        )
      }

      val elapsed = System.currentTimeMillis() - startedAt
      DropbearSessionResult(
        success = true,
        socksPort = socksPort,
        pid = pid,
        externalIp = verifyResult.third,
        latencyMs = elapsed,
        authState = "Autenticado via Dropbear (${profile.authMethod.label})",
      )
    }

  fun stop() {
    activeSocksPort = 0
    val p = process
    process = null
    monitorJob?.cancel()
    monitorJob = null
    if (p != null) {
      runCatching {
        p.destroy()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
          if (!p.waitFor(400, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
          }
        }
      }
    }
    runCatching { tempKeyFile?.delete() }
    tempKeyFile = null
  }

  private fun verifyEndToEndViaSocks5(socksPort: Int): Triple<Boolean, String, String?> {
    val sock = Socket()
    return try {
      sock.tcpNoDelay = true
      sock.soTimeout = 7000
      sock.connect(InetSocketAddress("127.0.0.1", socksPort), 3000)
      val out = sock.getOutputStream()
      val inp = sock.getInputStream()

      // SOCKS5 CONNECT to cp.cloudflare.com:80
      if (!socks5Connect(inp, out, "cp.cloudflare.com", 80)) {
        return Triple(false, "O servidor SSH recusou encaminhamento direct-tcpip (AllowTcpForwarding?).", null)
      }

      val req =
        "GET /generate_204 HTTP/1.1\r\nHost: cp.cloudflare.com\r\nConnection: close\r\nUser-Agent: G-VPN/2.0\r\n\r\n"
      out.write(req.toByteArray(Charsets.US_ASCII))
      out.flush()

      val header = readHeader(inp)
      val status = header.lineSequence().firstOrNull().orEmpty()
      if (!status.contains(" 204") && !status.contains(" 200")) {
        return Triple(false, "Sem conectividade Internet através do túnel SSH ($status).", null)
      }
      sock.close()

      val externalIp = fetchExternalIpViaSocks5(socksPort)
      Triple(true, "OK", externalIp)
    } catch (e: Exception) {
      Triple(false, e.localizedMessage ?: "Erro no teste fim-a-fim SOCKS5", null)
    } finally {
      runCatching { sock.close() }
    }
  }

  private fun fetchExternalIpViaSocks5(socksPort: Int): String? {
    val sock = Socket()
    return try {
      sock.tcpNoDelay = true
      sock.soTimeout = 5000
      sock.connect(InetSocketAddress("127.0.0.1", socksPort), 2500)
      val out = sock.getOutputStream()
      val inp = sock.getInputStream()
      if (!socks5Connect(inp, out, "api.ipify.org", 80)) return null
      out.write(
        "GET / HTTP/1.1\r\nHost: api.ipify.org\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
      )
      out.flush()
      val fullResp = inp.readBytes().toString(Charsets.UTF_8)
      val body = fullResp.substringAfter("\r\n\r\n", "").trim()
      body.takeIf { it.matches(Regex("^[0-9a-fA-F.:]{7,45}$")) }
    } catch (_: Exception) {
      null
    } finally {
      runCatching { sock.close() }
    }
  }

  private fun readHeader(inp: InputStream): String {
    val buf = ArrayList<Byte>(256)
    var p3 = 0
    var p2 = 0
    var p1 = 0
    while (buf.size < 4096) {
      val b = inp.read()
      if (b == -1) break
      buf.add(b.toByte())
      if (p3 == '\r'.code && p2 == '\n'.code && p1 == '\r'.code && b == '\n'.code) break
      p3 = p2
      p2 = p1
      p1 = b
    }
    return String(buf.toByteArray(), Charsets.US_ASCII)
  }

  private fun extractPid(proc: Process): Int? {
    return runCatching {
      val field = proc.javaClass.getDeclaredField("pid")
      field.isAccessible = true
      field.getInt(proc)
    }.getOrNull()
  }

  companion object {
    fun buildDropbearCommand(
      binaryPath: String,
      profile: SshProfileEntity,
      socksPort: Int,
      keyFilePath: String?,
    ): List<String> {
      val args =
        mutableListOf(
          binaryPath,
          "-N",
          "-T",
          "-y",
          "-W",
          "262144",
          "-K",
          profile.keepAliveSeconds.coerceIn(5, 120).toString(),
          "-D",
          "127.0.0.1:$socksPort",
          "-p",
          profile.port.toString(),
          "-l",
          profile.username.trim(),
        )
      if (!keyFilePath.isNullOrBlank()) {
        args.add("-i")
        args.add(keyFilePath)
      }
      args.add(profile.host.trim())
      return args
    }

    fun classifyDropbearError(lines: List<String>, exitCode: Int): String {
      val joined = lines.joinToString(" | ")
      return when {
        joined.contains("Permission denied", ignoreCase = true) ||
          joined.contains("Login for", ignoreCase = true) ||
          joined.contains("No auth methods", ignoreCase = true) ->
          "Autenticação SSH Dropbear recusada (credenciais ou chave rejeitadas)."
        joined.contains("Host key mismatch", ignoreCase = true) ||
          joined.contains("fingerprint", ignoreCase = true) ->
          "Chave do servidor SSH alterada (Host key mismatch)."
        joined.contains("Connection refused", ignoreCase = true) ->
          "Ligação recusada pelo servidor SSH na porta especificada."
        joined.contains("No route to host", ignoreCase = true) ||
          joined.contains("Network is unreachable", ignoreCase = true) ||
          joined.contains("Failed resolving", ignoreCase = true) ->
          "Servidor SSH inalcançável ou DNS indisponível."
        joined.isNotBlank() ->
          "Dropbear terminou (código $exitCode): ${lines.lastOrNull().orEmpty()}"
        else ->
          "Processo Dropbear encerrado (código $exitCode)."
      }
    }

    fun socks5Connect(inp: InputStream, out: OutputStream, destHost: String, destPort: Int): Boolean {
      out.write(byteArrayOf(0x05, 0x01, 0x00))
      out.flush()
      val greet = ByteArray(2)
      if (!readExact(inp, greet) || greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) {
        return false
      }
      val hostBytes = destHost.toByteArray(Charsets.UTF_8)
      val req = ByteArray(4 + 1 + hostBytes.size + 2)
      req[0] = 0x05
      req[1] = 0x01
      req[2] = 0x00
      req[3] = 0x03
      req[4] = hostBytes.size.toByte()
      System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
      req[5 + hostBytes.size] = ((destPort ushr 8) and 0xFF).toByte()
      req[6 + hostBytes.size] = (destPort and 0xFF).toByte()
      out.write(req)
      out.flush()

      val rep = ByteArray(4)
      if (!readExact(inp, rep) || rep[0] != 0x05.toByte() || rep[1] != 0x00.toByte()) {
        return false
      }
      when (rep[3].toInt() and 0xFF) {
        0x01 -> readExact(inp, ByteArray(6))
        0x04 -> readExact(inp, ByteArray(18))
        0x03 -> {
          val len = ByteArray(1)
          if (readExact(inp, len)) readExact(inp, ByteArray((len[0].toInt() and 0xFF) + 2))
        }
      }
      return true
    }

    private fun readExact(inp: InputStream, dst: ByteArray): Boolean {
      var off = 0
      while (off < dst.size) {
        val r = inp.read(dst, off, dst.size - off)
        if (r <= 0) return false
        off += r
      }
      return true
    }
  }
}
