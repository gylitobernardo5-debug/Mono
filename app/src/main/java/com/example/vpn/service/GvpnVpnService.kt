package com.example.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.GvpnDatabase
import com.example.data.model.ConnectionPhase
import com.example.data.model.LogLevel
import com.example.data.model.SniProfileEntity
import com.example.data.model.SshProfileEntity
import com.example.data.model.TlsTransportType
import com.example.data.model.VpnMode
import com.example.vpn.discovery.FreeInfraDiscovery
import com.example.vpn.speed.GvpnEngineState
import com.example.vpn.ssh.DropbearEngine
import com.example.vpn.tls.TlsSniVerifier
import com.example.vpn.tunnel.DropbearSocks5Dialer
import com.example.vpn.tunnel.TlsSniTunnelDialer
import com.example.vpn.tunnel.TunPacketRouter
import com.example.vpn.tunnel.UpstreamDialer
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GvpnVpnService : VpnService() {
  companion object {
    const val ACTION_CONNECT = "com.example.gvpn.ACTION_CONNECT"
    const val ACTION_DISCONNECT = "com.example.gvpn.ACTION_DISCONNECT"
    const val EXTRA_MODE = "extra_mode"
    const val EXTRA_PROFILE_ID = "extra_profile_id"

    private const val CHANNEL_ID = "gvpn_tunnel_channel"
    private const val NOTIFICATION_ID = 2026

    fun buildConnectIntent(context: Context, mode: VpnMode, profileId: Long): Intent =
      Intent(context, GvpnVpnService::class.java).apply {
        action = ACTION_CONNECT
        putExtra(EXTRA_MODE, mode.name)
        putExtra(EXTRA_PROFILE_ID, profileId)
      }

    fun buildDisconnectIntent(context: Context): Intent =
      Intent(context, GvpnVpnService::class.java).apply {
        action = ACTION_DISCONNECT
      }
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val manualDisconnect = AtomicBoolean(false)

  private var connectionJob: Job? = null
  private var tickerJob: Job? = null
  private var tunFd: ParcelFileDescriptor? = null
  private var packetRouter: TunPacketRouter? = null
  private var dropbearEngine: DropbearEngine? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    dropbearEngine = DropbearEngine(applicationContext)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_DISCONNECT -> {
        manualDisconnect.set(true)
        stopTunnelAndService("Desconectado pelo utilizador.")
        return START_NOT_STICKY
      }
      ACTION_CONNECT -> {
        manualDisconnect.set(false)
        val modeName = intent.getStringExtra(EXTRA_MODE) ?: VpnMode.AUTO_SNI.name
        val mode = runCatching { VpnMode.valueOf(modeName) }.getOrDefault(VpnMode.AUTO_SNI)
        val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
        promoteToForeground("A inicializar motor Extreme Speed V2...")
        startConnectionFlow(mode, profileId)
        return START_STICKY
      }
    }
    return START_NOT_STICKY
  }

  override fun onRevoke() {
    manualDisconnect.set(true)
    stopTunnelAndService("Permissão VPN revogada pelo sistema Android.")
    super.onRevoke()
  }

  override fun onDestroy() {
    teardownActiveTransport()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun startConnectionFlow(mode: VpnMode, profileId: Long) {
    connectionJob?.cancel()
    teardownActiveTransport()
    GvpnEngineState.resetTrafficCounters()
    GvpnEngineState.setMode(mode)

    connectionJob =
      serviceScope.launch {
        var attempt = 0
        while (isActive && !manualDisconnect.get()) {
          attempt++
          val outcome =
            when (mode) {
              VpnMode.AUTO_SNI -> connectAutoSni()
              VpnMode.MANUAL_SNI -> connectManualSni(profileId)
              VpnMode.SSH_DROPBEAR -> connectSshDropbear(profileId)
            }

          if (manualDisconnect.get()) break

          if (outcome.connected) {
            attempt = 0
            startTelemetryTicker()
            // Wait until unexpected disconnect triggers reconnection or job is cancelled
            while (isActive && !manualDisconnect.get() && GvpnEngineState.telemetry.value.phase == ConnectionPhase.CONNECTED) {
              delay(1000L)
            }
            if (manualDisconnect.get()) break
          }

          if (!outcome.shouldAutoReconnect || manualDisconnect.get() || attempt >= 5) {
            if (!manualDisconnect.get()) {
              GvpnEngineState.updateTelemetry {
                it.copy(
                  phase = ConnectionPhase.ERROR,
                  statusDetail = outcome.message,
                )
              }
              GvpnEngineState.log("ENGINE", outcome.message, LogLevel.ERROR)
              promoteToForeground("Erro: ${outcome.message}")
            }
            break
          }

          val backoffMs = (attempt * 2000L).coerceAtMost(8000L)
          GvpnEngineState.updateTelemetry {
            it.copy(
              phase = ConnectionPhase.RECONNECTING,
              statusDetail = "Ligação perdida (${outcome.message}). A reconectar em ${backoffMs / 1000}s...",
            )
          }
          GvpnEngineState.log(
            "RECONNECT",
            "Tentativa de reconexão #$attempt em ${backoffMs / 1000}s...",
            LogLevel.WARN,
          )
          delay(backoffMs)
          teardownActiveTransport()
        }
      }
  }

  private data class ConnectAttemptResult(
    val connected: Boolean,
    val shouldAutoReconnect: Boolean,
    val message: String,
  )

  private suspend fun connectAutoSni(): ConnectAttemptResult {
    val db = GvpnDatabase.getInstance(applicationContext)
    val dao = db.sniProfileDao()
    GvpnEngineState.updateTelemetry {
      it.copy(
        phase = ConnectionPhase.PROBING_INFRASTRUCTURE,
        statusDetail = "Modo AUTO: a verificar perfis SNI elegíveis e compatibilidade de túnel...",
      )
    }
    GvpnEngineState.log("AUTO-SNI", "A procurar perfis SNI compatíveis...", LogLevel.INFO)

    val savedProfiles = dao.getAllOnce().filter { it.isAutoEligible }
    val candidates = mutableListOf<SniProfileEntity>()
    candidates.addAll(savedProfiles)

    // Probe saved profiles first
    var bestProfile: SniProfileEntity? = null
    var bestLatency = Long.MAX_VALUE
    var bestProtocol = "---"

    for (profile in candidates) {
      if (manualDisconnect.get()) return ConnectAttemptResult(false, false, "Cancelado")
      GvpnEngineState.log(
        "AUTO-SNI",
        "A testar SNI '${profile.sniHost}' -> ${profile.gatewayHost}:${profile.gatewayPort}...",
        LogLevel.INFO,
      )
      val res = TlsSniVerifier.probeProfile(profile) { protect(it) }
      dao.update(
        profile.copy(
          lastLatencyMs = res.latencyMs,
          lastTunnelCompatible = res.endToEndVerified,
          lastValidationMessage = res.diagnosticMessage,
          lastValidatedAt = System.currentTimeMillis(),
        )
      )
      GvpnEngineState.log(
        "AUTO-SNI",
        "${profile.name}: ${res.diagnosticMessage}",
        if (res.endToEndVerified) LogLevel.SUCCESS else LogLevel.WARN,
      )
      if (res.endToEndVerified && res.latencyMs < bestLatency) {
        bestLatency = res.latencyMs
        bestProfile = profile
        bestProtocol = "${res.tlsProtocol} (${res.cipherSuite})"
      }
    }

    // If no saved SNI profile verified end-to-end, try real free public infrastructure discovery!
    if (bestProfile == null) {
      GvpnEngineState.log(
        "DISCOVERY",
        "Nenhum perfil guardado confirmou túnel real. A consultar infraestrutura pública gratuita...",
        LogLevel.INFO,
      )
      val discovery = FreeInfraDiscovery.discoverAndProbe(maxCandidates = 10)
      GvpnEngineState.log(
        "DISCOVERY",
        discovery.summaryMessage,
        if (discovery.compatibleCount > 0) LogLevel.SUCCESS else LogLevel.WARN,
      )
      val verifiedNode = discovery.nodes.firstOrNull { it.tunnelCompatible }
      if (verifiedNode != null) {
        val discoveredEntity =
          SniProfileEntity(
            name = "Auto-Public ${verifiedNode.host}",
            sniHost = verifiedNode.host,
            gatewayHost = verifiedNode.host,
            gatewayPort = verifiedNode.port,
            transportType = TlsTransportType.HTTP_CONNECT_TLS,
            verifyHostname = false,
            lastLatencyMs = verifiedNode.latencyMs,
            lastTunnelCompatible = true,
            lastValidationMessage = verifiedNode.statusDetail,
            lastValidatedAt = System.currentTimeMillis(),
          )
        val savedId = dao.insert(discoveredEntity)
        bestProfile = discoveredEntity.copy(id = savedId)
        bestLatency = verifiedNode.latencyMs ?: 0L
        bestProtocol = "TLS + HTTP CONNECT"
      }
    }

    if (bestProfile == null) {
      return ConnectAttemptResult(
        connected = false,
        shouldAutoReconnect = false,
        message =
          "Nenhum destino SNI/TLS compatível com encaminhamento VPN real foi encontrado. Um handshake TLS simples não basta; adicione um gateway TLS compatível ou use SSH Dropbear.",
      )
    }

    return establishTlsTunnel(bestProfile, bestLatency, bestProtocol, autoReconnect = true)
  }

  private suspend fun connectManualSni(profileId: Long): ConnectAttemptResult {
    val dao = GvpnDatabase.getInstance(applicationContext).sniProfileDao()
    val profile =
      (if (profileId > 0L) dao.getById(profileId) else null)
        ?: dao.getAllOnce().firstOrNull()
        ?: return ConnectAttemptResult(
          connected = false,
          shouldAutoReconnect = false,
          message = "Nenhum perfil SNI selecionado. Crie um perfil na aba TLS / MULTI-SNI.",
        )

    GvpnEngineState.updateTelemetry {
      it.copy(
        phase = ConnectionPhase.TLS_HANDSHAKE,
        activeProfileName = profile.name,
        activeSniOrHost = "${profile.sniHost} -> ${profile.gatewayHost}:${profile.gatewayPort}",
        statusDetail = "A negociar TLS com SNI '${profile.sniHost}' e a validar túnel real...",
      )
    }
    GvpnEngineState.log(
      "MANUAL-SNI",
      "A testar perfil '${profile.name}' (SNI=${profile.sniHost}, Gateway=${profile.gatewayHost}:${profile.gatewayPort})",
      LogLevel.INFO,
    )

    val probe = TlsSniVerifier.probeProfile(profile) { protect(it) }
    dao.update(
      profile.copy(
        lastLatencyMs = probe.latencyMs,
        lastTunnelCompatible = probe.endToEndVerified,
        lastValidationMessage = probe.diagnosticMessage,
        lastValidatedAt = System.currentTimeMillis(),
      )
    )

    if (!probe.endToEndVerified) {
      return ConnectAttemptResult(
        connected = false,
        shouldAutoReconnect = false,
        message = probe.diagnosticMessage,
      )
    }

    return establishTlsTunnel(
      profile = profile,
      latencyMs = probe.latencyMs,
      negotiatedProto = "${probe.tlsProtocol} · ${probe.cipherSuite}",
      autoReconnect = true,
    )
  }

  private suspend fun establishTlsTunnel(
    profile: SniProfileEntity,
    latencyMs: Long,
    negotiatedProto: String,
    autoReconnect: Boolean,
  ): ConnectAttemptResult {
    GvpnEngineState.updateTelemetry {
      it.copy(
        phase = ConnectionPhase.VERIFYING_REAL_TUNNEL,
        statusDetail = "A estabelecer interface TUN e a confirmar IP externo através do túnel...",
      )
    }

    val dialer = TlsSniTunnelDialer(profile) { sock -> protect(sock) }
    val externalIp = fetchExternalIpThroughDialer(dialer)
    if (externalIp == null) {
      return ConnectAttemptResult(
        connected = false,
        shouldAutoReconnect = autoReconnect,
        message = "Falha na verificação final de conectividade real através do túnel TLS.",
      )
    }

    val pfd =
      buildTunInterface(sessionName = "G-VPN TLS (${profile.sniHost})")
        ?: return ConnectAttemptResult(
          connected = false,
          shouldAutoReconnect = false,
          message = "Não foi possível abrir o descritor TUN Android (permissão VPN necessária).",
        )

    tunFd = pfd
    val router =
      TunPacketRouter(
        tunFd = pfd,
        dialer = dialer,
        scope = serviceScope,
        onCriticalTransportFailure = { reason ->
          GvpnEngineState.updateTelemetry {
            it.copy(phase = ConnectionPhase.RECONNECTING, statusDetail = reason)
          }
        },
      )
    packetRouter = router
    router.start()

    GvpnEngineState.connectedSinceMs = System.currentTimeMillis()
    GvpnEngineState.updateTelemetry {
      it.copy(
        phase = ConnectionPhase.CONNECTED,
        activeProfileName = profile.name,
        activeSniOrHost = "${profile.sniHost} (${profile.gatewayHost}:${profile.gatewayPort})",
        negotiatedProtocol = negotiatedProto,
        tunnelIp = "10.88.0.2",
        externalIp = externalIp,
        latencyMs = latencyMs,
        statusDetail = "Túnel TLS/SNI operacional. Tráfego encaminhado e verificado.",
        sshAuthState = "Modo TLS/SNI",
        dropbearPid = null,
      )
    }
    GvpnEngineState.log(
      "TUNNEL",
      "CONECTADO [TLS/SNI] · IP externo: $externalIp · Latência: ${latencyMs}ms",
      LogLevel.SUCCESS,
    )
    promoteToForeground("Conectado (${profile.sniHost}) · IP $externalIp")
    return ConnectAttemptResult(true, autoReconnect, "OK")
  }

  private suspend fun connectSshDropbear(profileId: Long): ConnectAttemptResult {
    val dao = GvpnDatabase.getInstance(applicationContext).sshProfileDao()
    val profile =
      (if (profileId > 0L) dao.getById(profileId) else null)
        ?: dao.getAllOnce().firstOrNull()
        ?: return ConnectAttemptResult(
          connected = false,
          shouldAutoReconnect = false,
          message = "Nenhum perfil SSH Dropbear selecionado. Configure o servidor na aba SSH / DROPBEAR.",
        )

    GvpnEngineState.updateTelemetry {
      it.copy(
        phase = ConnectionPhase.SSH_HANDSHAKE,
        activeProfileName = profile.name,
        activeSniOrHost = "${profile.username}@${profile.host}:${profile.port}",
        negotiatedProtocol = "SSH-2.0 Dropbear 2026.94",
        keepAliveSeconds = profile.keepAliveSeconds,
        sshAuthState = "A iniciar handshake Dropbear...",
        statusDetail = "A ligar ao servidor SSH Dropbear ${profile.host}:${profile.port}...",
      )
    }

    val engine = dropbearEngine ?: DropbearEngine(applicationContext).also { dropbearEngine = it }
    GvpnEngineState.updateTelemetry {
      it.copy(
        phase = ConnectionPhase.SSH_AUTHENTICATING,
        sshAuthState = "A autenticar (${profile.authMethod.label})...",
      )
    }

    val sessionResult =
      engine.startAndVerifySession(
        profile = profile,
        scope = serviceScope,
        onUnexpectedExit = { reason ->
          GvpnEngineState.log("DROPBEAR", "Dropbear interrompido: $reason", LogLevel.ERROR)
          GvpnEngineState.updateTelemetry {
            it.copy(
              phase =
                if (profile.autoReconnect && !manualDisconnect.get()) ConnectionPhase.RECONNECTING
                else ConnectionPhase.ERROR,
              sshAuthState = "Desconectado",
              statusDetail = reason,
            )
          }
        },
      )

    dao.update(
      profile.copy(
        lastLatencyMs = if (sessionResult.success) sessionResult.latencyMs else profile.lastLatencyMs,
        lastAuthStatus = sessionResult.authState,
      )
    )

    if (!sessionResult.success) {
      return ConnectAttemptResult(
        connected = false,
        shouldAutoReconnect = profile.autoReconnect,
        message = sessionResult.errorMessage ?: sessionResult.authState,
      )
    }

    GvpnEngineState.updateTelemetry {
      it.copy(
        phase = ConnectionPhase.VERIFYING_REAL_TUNNEL,
        sshAuthState = sessionResult.authState,
        dropbearPid = sessionResult.pid,
        statusDetail = "Dropbear autenticado. A criar interface TUN e encaminhar tráfego...",
      )
    }

    val pfd =
      buildTunInterface(sessionName = "G-VPN Dropbear (${profile.host})")
        ?: return ConnectAttemptResult(
          connected = false,
          shouldAutoReconnect = false,
          message = "Não foi possível abrir o descritor TUN Android (permissão VPN necessária).",
        )

    tunFd = pfd
    val dialer = DropbearSocks5Dialer(sessionResult.socksPort)
    val router =
      TunPacketRouter(
        tunFd = pfd,
        dialer = dialer,
        scope = serviceScope,
        onCriticalTransportFailure = { reason ->
          GvpnEngineState.updateTelemetry {
            it.copy(phase = ConnectionPhase.RECONNECTING, statusDetail = reason)
          }
        },
      )
    packetRouter = router
    router.start()

    val extIp = sessionResult.externalIp ?: "Verificado (via Dropbear)"
    GvpnEngineState.connectedSinceMs = System.currentTimeMillis()
    GvpnEngineState.updateTelemetry {
      it.copy(
        phase = ConnectionPhase.CONNECTED,
        activeProfileName = profile.name,
        activeSniOrHost = "${profile.username}@${profile.host}:${profile.port}",
        negotiatedProtocol = "Dropbear SSH-2.0 (SOCKS5 direct-tcpip)",
        tunnelIp = "10.88.0.2",
        externalIp = extIp,
        latencyMs = sessionResult.latencyMs,
        keepAliveSeconds = profile.keepAliveSeconds,
        sshAuthState = sessionResult.authState,
        dropbearPid = sessionResult.pid,
        statusDetail = "Túnel SSH Dropbear ativo e verificado fim-a-fim.",
      )
    }
    GvpnEngineState.log(
      "TUNNEL",
      "CONECTADO [SSH DROPBEAR PID=${sessionResult.pid ?: "?"}] · IP externo: $extIp · ${sessionResult.latencyMs}ms",
      LogLevel.SUCCESS,
    )
    promoteToForeground("Dropbear Conectado · IP $extIp")
    return ConnectAttemptResult(true, profile.autoReconnect, "OK")
  }

  private fun fetchExternalIpThroughDialer(dialer: UpstreamDialer): String? {
    var sock: Socket? = null
    return try {
      sock = dialer.openTcpTunnel("api.ipify.org", 80)
      sock.soTimeout = 5000
      val out = sock.getOutputStream()
      out.write(
        "GET / HTTP/1.1\r\nHost: api.ipify.org\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
      )
      out.flush()
      val resp = sock.getInputStream().readBytes().toString(Charsets.UTF_8)
      val body = resp.substringAfter("\r\n\r\n", "").trim()
      body.takeIf { it.matches(Regex("^[0-9a-fA-F.:]{7,45}$")) }
    } catch (_: Exception) {
      null
    } finally {
      runCatching { sock?.close() }
    }
  }

  private fun buildTunInterface(sessionName: String): ParcelFileDescriptor? {
    return runCatching {
      val builder =
        Builder()
          .setSession(sessionName)
          .setMtu(1500)
          .addAddress("10.88.0.2", 32)
          .addRoute("0.0.0.0", 0)
          .addDnsServer("1.1.1.1")
          .addDnsServer("8.8.8.8")
      // Critical: exclude our own app sockets (Dropbear + TLS sockets) from TUN to prevent routing loops
      runCatching { builder.addDisallowedApplication(packageName) }
      builder.establish()
    }.getOrNull()
  }

  private fun startTelemetryTicker() {
    tickerJob?.cancel()
    tickerJob =
      serviceScope.launch {
        var lastTick = System.currentTimeMillis()
        while (isActive && GvpnEngineState.telemetry.value.phase == ConnectionPhase.CONNECTED) {
          delay(1000L)
          val now = System.currentTimeMillis()
          GvpnEngineState.sampleSpeedTick(now - lastTick)
          lastTick = now
          val snap = GvpnEngineState.telemetry.value
          if (snap.phase == ConnectionPhase.CONNECTED) {
            promoteToForeground(
              "↓ ${formatRate(snap.downloadBytesPerSec)} · ↑ ${formatRate(snap.uploadBytesPerSec)} · ${snap.externalIp}"
            )
          }
        }
      }
  }

  private fun teardownActiveTransport() {
    tickerJob?.cancel()
    tickerJob = null
    packetRouter?.stop()
    packetRouter = null
    dropbearEngine?.stop()
    runCatching { tunFd?.close() }
    tunFd = null
  }

  private fun stopTunnelAndService(reason: String) {
    connectionJob?.cancel()
    connectionJob = null
    teardownActiveTransport()
    GvpnEngineState.resetTrafficCounters()
    GvpnEngineState.updateTelemetry {
      it.copy(
        phase = ConnectionPhase.DISCONNECTED,
        externalIp = "---",
        connectedSeconds = 0L,
        downloadBytesPerSec = 0L,
        uploadBytesPerSec = 0L,
        activeFlows = 0,
        sshAuthState = "Desconectado",
        dropbearPid = null,
        statusDetail = reason,
      )
    }
    GvpnEngineState.log("ENGINE", reason, LogLevel.INFO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    stopSelf()
  }

  private fun promoteToForeground(statusText: String) {
    val openAppIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val disconnectIntent =
      PendingIntent.getService(
        this,
        1,
        buildDisconnectIntent(this),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val notification: Notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("G-VPN · Extreme Speed V2")
        .setContentText(statusText)
        .setContentIntent(openAppIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(0, getString(R.string.btn_disconnect), disconnectIntent)
        .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.notif_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = getString(R.string.notif_channel_desc)
        }
      getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
  }

  private fun formatRate(bytesPerSec: Long): String =
    when {
      bytesPerSec >= 1_048_576L -> String.format(java.util.Locale.US, "%.2f MB/s", bytesPerSec / 1_048_576.0)
      bytesPerSec >= 1024L -> String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
      else -> "$bytesPerSec B/s"
    }
}
