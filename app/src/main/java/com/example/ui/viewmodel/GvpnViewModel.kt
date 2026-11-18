package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.GvpnDatabase
import com.example.data.model.ConnectionPhase
import com.example.data.model.DiscoveredNode
import com.example.data.model.LogLevel
import com.example.data.model.SniProfileEntity
import com.example.data.model.SshProfileEntity
import com.example.data.model.TelemetrySnapshot
import com.example.data.model.TerminalLogEntry
import com.example.data.model.TlsTransportType
import com.example.data.model.VpnMode
import com.example.data.repository.GvpnRepository
import com.example.vpn.discovery.FreeInfraDiscovery
import com.example.vpn.service.GvpnVpnService
import com.example.vpn.speed.GvpnEngineState
import com.example.vpn.tls.TlsSniVerifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GvpnTab(val label: String) {
  TLS_MULTI_SNI("TLS / MULTI-SNI"),
  SSH_DROPBEAR("SSH / DROPBEAR"),
  DEV_TERMINAL(">_ TERMINAL DEV"),
}

data class GvpnUiState(
  val selectedTab: GvpnTab = GvpnTab.TLS_MULTI_SNI,
  val selectedMode: VpnMode = VpnMode.AUTO_SNI,
  val selectedSniProfileId: Long? = null,
  val selectedSshProfileId: Long? = null,
  val probingSniProfileId: Long? = null,
  val isDiscovering: Boolean = false,
  val discoverySummary: String? = null,
  val discoveredNodes: List<DiscoveredNode> = emptyList(),
  val devTerminalUnlocked: Boolean = false,
  val dropbearBinaryReady: Boolean = false,
  val dropbearBinaryPath: String = "",
)

class GvpnViewModel(application: Application) : AndroidViewModel(application) {
  private val repository: GvpnRepository

  val sniProfiles: StateFlow<List<SniProfileEntity>>
  val sshProfiles: StateFlow<List<SshProfileEntity>>
  val telemetry: StateFlow<TelemetrySnapshot> = GvpnEngineState.telemetry
  val logs: StateFlow<List<TerminalLogEntry>> = GvpnEngineState.logs

  private val _uiState = MutableStateFlow(GvpnUiState())
  val uiState: StateFlow<GvpnUiState> = _uiState.asStateFlow()

  private var previousPhase: ConnectionPhase = ConnectionPhase.DISCONNECTED

  init {
    val db = GvpnDatabase.getInstance(application)
    repository = GvpnRepository(db.sniProfileDao(), db.sshProfileDao())

    sniProfiles =
      repository.sniProfiles.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

    sshProfiles =
      repository.sshProfiles.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

    val dropbearFile =
      java.io.File(application.applicationInfo.nativeLibraryDir, "libdropbear.so")
    _uiState.update {
      it.copy(
        dropbearBinaryReady = dropbearFile.exists(),
        dropbearBinaryPath = dropbearFile.absolutePath,
      )
    }

    // Keep default selected IDs synchronized with Room lists
    viewModelScope.launch {
      sniProfiles.collectLatest { list ->
        val current = _uiState.value.selectedSniProfileId
        if (list.isNotEmpty() && (current == null || list.none { it.id == current })) {
          _uiState.update { it.copy(selectedSniProfileId = list.first().id) }
        }
      }
    }

    viewModelScope.launch {
      sshProfiles.collectLatest { list ->
        val current = _uiState.value.selectedSshProfileId
        if (list.isNotEmpty() && (current == null || list.none { it.id == current })) {
          _uiState.update { it.copy(selectedSshProfileId = list.first().id) }
        }
      }
    }

    // Unlock and auto-open Termux Dev Terminal ONLY after a REAL verified connection
    viewModelScope.launch {
      telemetry.collectLatest { snap ->
        if (
          snap.phase == ConnectionPhase.CONNECTED &&
            previousPhase != ConnectionPhase.CONNECTED
        ) {
          _uiState.update {
            it.copy(
              devTerminalUnlocked = true,
              selectedTab = GvpnTab.DEV_TERMINAL,
            )
          }
        }
        previousPhase = snap.phase
      }
    }
  }

  fun selectTab(tab: GvpnTab) {
    _uiState.update { it.copy(selectedTab = tab) }
    if (tab == GvpnTab.SSH_DROPBEAR && !telemetry.value.phase.isConnectingOrActive) {
      selectMode(VpnMode.SSH_DROPBEAR)
    } else if (tab == GvpnTab.TLS_MULTI_SNI && _uiState.value.selectedMode == VpnMode.SSH_DROPBEAR && !telemetry.value.phase.isConnectingOrActive) {
      selectMode(VpnMode.AUTO_SNI)
    }
  }

  fun selectMode(mode: VpnMode) {
    _uiState.update {
      val nextTab =
        when (mode) {
          VpnMode.SSH_DROPBEAR -> GvpnTab.SSH_DROPBEAR
          VpnMode.AUTO_SNI, VpnMode.MANUAL_SNI ->
            if (it.selectedTab == GvpnTab.SSH_DROPBEAR) GvpnTab.TLS_MULTI_SNI else it.selectedTab
        }
      it.copy(selectedMode = mode, selectedTab = nextTab)
    }
    if (!telemetry.value.phase.isConnectingOrActive) {
      GvpnEngineState.setMode(mode)
    }
  }

  fun selectSniProfile(id: Long) {
    _uiState.update { it.copy(selectedSniProfileId = id) }
  }

  fun selectSshProfile(id: Long) {
    _uiState.update { it.copy(selectedSshProfileId = id) }
  }

  fun saveSniProfile(profile: SniProfileEntity) {
    viewModelScope.launch {
      val savedId = repository.saveSniProfile(profile)
      _uiState.update { it.copy(selectedSniProfileId = savedId) }
      GvpnEngineState.log("SNI-DB", "Perfil SNI '${profile.name}' guardado (${profile.sniHost}).", LogLevel.INFO)
    }
  }

  fun deleteSniProfile(profile: SniProfileEntity) {
    viewModelScope.launch {
      repository.deleteSniProfile(profile)
      GvpnEngineState.log("SNI-DB", "Perfil SNI '${profile.name}' eliminado.", LogLevel.WARN)
    }
  }

  fun saveSshProfile(profile: SshProfileEntity) {
    viewModelScope.launch {
      val savedId = repository.saveSshProfile(profile)
      _uiState.update { it.copy(selectedSshProfileId = savedId) }
      GvpnEngineState.log(
        "SSH-DB",
        "Perfil Dropbear '${profile.name}' guardado (${profile.username}@${profile.host}:${profile.port}).",
        LogLevel.INFO,
      )
    }
  }

  fun deleteSshProfile(profile: SshProfileEntity) {
    viewModelScope.launch {
      repository.deleteSshProfile(profile)
      GvpnEngineState.log("SSH-DB", "Perfil Dropbear '${profile.name}' eliminado.", LogLevel.WARN)
    }
  }

  fun probeSniProfile(profile: SniProfileEntity) {
    viewModelScope.launch {
      _uiState.update { it.copy(probingSniProfileId = profile.id) }
      GvpnEngineState.log(
        "TLS-TEST",
        "A testar TLS + SNI (${profile.sniHost}) -> ${profile.gatewayHost}:${profile.gatewayPort}...",
        LogLevel.INFO,
      )
      val res = TlsSniVerifier.probeProfile(profile)
      repository.saveSniProfile(
        profile.copy(
          lastLatencyMs = res.latencyMs,
          lastTunnelCompatible = res.endToEndVerified,
          lastValidationMessage = res.diagnosticMessage,
          lastValidatedAt = System.currentTimeMillis(),
        )
      )
      GvpnEngineState.log(
        "TLS-TEST",
        "${profile.name}: ${res.diagnosticMessage}",
        if (res.endToEndVerified) LogLevel.SUCCESS else LogLevel.WARN,
      )
      _uiState.update { it.copy(probingSniProfileId = null) }
    }
  }

  fun runFreeInfrastructureDiscovery() {
    if (_uiState.value.isDiscovering) return
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isDiscovering = true,
          discoverySummary = "A consultar diretórios públicos gratuitos e a testar compatibilidade real de túnel...",
        )
      }
      GvpnEngineState.log(
        "DISCOVERY",
        "Iniciada descoberta de infraestrutura pública gratuita (sem contas pagas)...",
        LogLevel.INFO,
      )
      val report = FreeInfraDiscovery.discoverAndProbe(maxCandidates = 12)
      GvpnEngineState.log(
        "DISCOVERY",
        report.summaryMessage,
        if (report.compatibleCount > 0) LogLevel.SUCCESS else LogLevel.WARN,
      )
      _uiState.update {
        it.copy(
          isDiscovering = false,
          discoveredNodes = report.nodes,
          discoverySummary = report.summaryMessage,
        )
      }
    }
  }

  fun importDiscoveredNodeAsSni(node: DiscoveredNode, customSni: String = node.host) {
    val profile =
      SniProfileEntity(
        name = "Público ${node.host}:${node.port}",
        sniHost = customSni.ifBlank { node.host },
        gatewayHost = node.host,
        gatewayPort = node.port,
        transportType = TlsTransportType.HTTP_CONNECT_TLS,
        verifyHostname = false,
        isAutoEligible = true,
        lastLatencyMs = node.latencyMs,
        lastTunnelCompatible = node.tunnelCompatible,
        lastValidationMessage = node.statusDetail,
        lastValidatedAt = System.currentTimeMillis(),
      )
    saveSniProfile(profile)
  }

  fun startVpnConnection(context: Context) {
    val state = _uiState.value
    val mode = state.selectedMode
    val profileId =
      when (mode) {
        VpnMode.AUTO_SNI -> -1L
        VpnMode.MANUAL_SNI -> state.selectedSniProfileId ?: -1L
        VpnMode.SSH_DROPBEAR -> state.selectedSshProfileId ?: -1L
      }

    if (mode == VpnMode.MANUAL_SNI && profileId <= 0L) {
      GvpnEngineState.updateTelemetry {
        it.copy(
          phase = ConnectionPhase.ERROR,
          statusDetail = "Selecione ou crie um perfil SNI na aba TLS / MULTI-SNI para usar o Modo Manual.",
        )
      }
      return
    }

    if (mode == VpnMode.SSH_DROPBEAR && profileId <= 0L) {
      GvpnEngineState.updateTelemetry {
        it.copy(
          phase = ConnectionPhase.ERROR,
          statusDetail = "Guarde e selecione um servidor SSH na aba SSH / DROPBEAR.",
        )
      }
      return
    }

    val intent = GvpnVpnService.buildConnectIntent(context, mode, profileId)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      ContextCompat.startForegroundService(context, intent)
    } else {
      context.startService(intent)
    }
  }

  fun stopVpnConnection(context: Context) {
    context.startService(GvpnVpnService.buildDisconnectIntent(context))
  }
}
