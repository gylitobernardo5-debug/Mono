package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class VpnMode(val label: String) {
  AUTO_SNI("AUTO"),
  MANUAL_SNI("MANUAL SNI"),
  SSH_DROPBEAR("SSH DROPBEAR"),
}

enum class TlsTransportType(val label: String) {
  HTTP_CONNECT_TLS("HTTP CONNECT / TLS"),
  SOCKS5_TLS("SOCKS5 / TLS"),
  WEBSOCKET_TLS("WebSocket Relay / TLS"),
}

enum class SshAuthMethod(val label: String) {
  PASSWORD("Palavra-passe"),
  PRIVATE_KEY("Chave Privada"),
}

enum class ConnectionPhase(val label: String, val isConnectingOrActive: Boolean) {
  DISCONNECTED("DESCONECTADO", false),
  PROBING_INFRASTRUCTURE("A PROCURAR TÚNEL COMPATÍVEL...", true),
  TLS_HANDSHAKE("NEGOCIAÇÃO TLS + SNI...", true),
  SSH_HANDSHAKE("DROPBEAR: CONEXÃO SSH...", true),
  SSH_AUTHENTICATING("DROPBEAR: A AUTENTICAR...", true),
  VERIFYING_REAL_TUNNEL("A CONFIRMAR TRÁFEGO REAL NO TÚNEL...", true),
  CONNECTED("CONECTADO · TÚNEL VERIFICADO", true),
  RECONNECTING("A RECONECTAR AUTOMATICAMENTE...", true),
  ERROR("FALHA REAL DETETADA", false),
}

enum class LogLevel {
  INFO,
  SUCCESS,
  WARN,
  ERROR,
}

@Entity(tableName = "sni_profiles")
data class SniProfileEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val sniHost: String,
  val gatewayHost: String,
  val gatewayPort: Int = 443,
  val transportType: TlsTransportType = TlsTransportType.HTTP_CONNECT_TLS,
  val verifyHostname: Boolean = true,
  val customHeader: String = "",
  val isAutoEligible: Boolean = true,
  val lastLatencyMs: Long? = null,
  val lastTunnelCompatible: Boolean? = null,
  val lastValidationMessage: String? = null,
  val lastValidatedAt: Long? = null,
)

@Entity(tableName = "ssh_profiles")
data class SshProfileEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val host: String,
  val port: Int = 22,
  val username: String,
  val authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
  val password: String = "",
  val privateKeyContent: String = "",
  val keepAliveSeconds: Int = 25,
  val autoReconnect: Boolean = true,
  val lastLatencyMs: Long? = null,
  val lastAuthStatus: String? = null,
)

data class DiscoveredNode(
  val host: String,
  val port: Int,
  val source: String,
  val country: String = "--",
  val supportsTls: Boolean = false,
  val tunnelCompatible: Boolean = false,
  val latencyMs: Long? = null,
  val statusDetail: String = "A aguardar teste",
)

data class TelemetrySnapshot(
  val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
  val mode: VpnMode = VpnMode.AUTO_SNI,
  val activeProfileName: String = "---",
  val activeSniOrHost: String = "---",
  val negotiatedProtocol: String = "---",
  val tunnelIp: String = "10.88.0.2",
  val externalIp: String = "---",
  val connectedSeconds: Long = 0L,
  val downloadBytesPerSec: Long = 0L,
  val uploadBytesPerSec: Long = 0L,
  val totalDownloadBytes: Long = 0L,
  val totalUploadBytes: Long = 0L,
  val latencyMs: Long? = null,
  val activeFlows: Int = 0,
  val keepAliveSeconds: Int = 25,
  val bufferSizeBytes: Int = 16384,
  val statusDetail: String = "Motor pronto. Selecione um perfil compatível ou execute descoberta.",
  val sshAuthState: String = "Inativo",
  val dropbearPid: Int? = null,
)

data class TerminalLogEntry(
  val id: Long,
  val timestamp: String,
  val tag: String,
  val level: LogLevel,
  val message: String,
)
