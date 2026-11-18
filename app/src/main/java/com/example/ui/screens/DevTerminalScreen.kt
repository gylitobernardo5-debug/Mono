package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.LogLevel
import com.example.data.model.TelemetrySnapshot
import com.example.data.model.TerminalLogEntry
import com.example.ui.components.formatDuration
import com.example.ui.components.formatRate
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.delay

@Composable
fun DevTerminalScreen(
  telemetry: TelemetrySnapshot,
  logs: List<TerminalLogEntry>,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val bootLines = remember {
    listOf(
      "termux@gvpn:~/extreme-speed-v2 $ ./init_dev_console --verify-tunnel",
      "[BOOT] Motor Extreme Speed Engine V2 inicializado com sucesso.",
      "GYLITO BERNARDO",
      "DEV",
      "FOI UM DIA NORMAL...",
      "",
      "DESENVOLVEDOR:",
      "GYLITO BERNARDO MAULA",
      "",
      "APOIO TÉCNICO:",
      "DEV MURIBA MC",
      "",
      "APOIO E DESENVOLVIMENTO:",
      "876699965",
      "",
      "Obrigado por utilizar o G-VPN! Túnel real verificado e ativo.",
    )
  }

  var visibleLineCount by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) {
    for (i in 1..bootLines.size) {
      delay(75L)
      visibleLineCount = i
    }
  }

  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("dev_terminal_screen"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Authentic Termux Window Chrome
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = colors.background,
      border = BorderStroke(1.5.dp, colors.primary),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              Icons.Filled.Terminal,
              contentDescription = null,
              tint = colors.primary,
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = "TERMUX :: G-VPN DEV TERMINAL",
              fontFamily = JetBrainsMonoFamily,
              style = MaterialTheme.typography.labelMedium,
              color = colors.primary,
              fontWeight = FontWeight.Bold,
            )
          }
          Text(
            text = "SESSION: LIVE",
            fontFamily = JetBrainsMonoFamily,
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondary,
          )
        }

        Spacer(Modifier.height(4.dp))

        bootLines.take(visibleLineCount).forEach { line ->
          AnimatedVisibility(visible = true, enter = fadeIn()) {
            val lineColor =
              when {
                line.startsWith("GYLITO") || line == "DEV" || line == "FOI UM DIA NORMAL..." ->
                  colors.primary
                line.startsWith("DESENVOLVEDOR") ||
                  line.startsWith("APOIO") ||
                  line == "876699965" ||
                  line == "DEV MURIBA MC" -> colors.secondary
                line.startsWith("Obrigado") -> colors.primary
                line.startsWith("termux@") -> colors.tertiary
                else -> colors.onSurface
              }
            Text(
              text = line,
              fontFamily = JetBrainsMonoFamily,
              style = MaterialTheme.typography.bodyMedium,
              color = lineColor,
              fontWeight =
                if (line.startsWith("GYLITO") || line == "876699965" || line == "DEV MURIBA MC") {
                  FontWeight.Bold
                } else {
                  FontWeight.Normal
                },
            )
          }
        }
      }
    }

    // Real Verified Connection Telemetry Dump
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = colors.surfaceVariant,
      border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.6f)),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = "$ cat /proc/gvpn/real_tunnel_info",
          fontFamily = JetBrainsMonoFamily,
          style = MaterialTheme.typography.labelMedium,
          color = colors.secondary,
          fontWeight = FontWeight.Bold,
        )
        TerminalInfoLine("ESTADO", telemetry.phase.label)
        TerminalInfoLine("MODO", telemetry.mode.label)
        TerminalInfoLine("PROTOCOLO", telemetry.negotiatedProtocol)
        TerminalInfoLine("DESTINO / SNI", telemetry.activeSniOrHost)
        TerminalInfoLine("IP EXTERNO REAL", telemetry.externalIp)
        TerminalInfoLine("IP TUN LOCAL", telemetry.tunnelIp)
        TerminalInfoLine("LATÊNCIA", "${telemetry.latencyMs ?: "--"} ms")
        TerminalInfoLine("TEMPO CONECTADO", formatDuration(telemetry.connectedSeconds))
        TerminalInfoLine(
          "TRÁFEGO",
          "↓ ${formatRate(telemetry.downloadBytesPerSec)} | ↑ ${formatRate(telemetry.uploadBytesPerSec)}",
        )
        TerminalInfoLine(
          "MOTOR EXTREME",
          "Buffer ${telemetry.bufferSizeBytes / 1024} KB · Fluxos ativos: ${telemetry.activeFlows}",
        )
      }
    }

    // Live Terminal Logs
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = colors.background,
      border = BorderStroke(1.dp, colors.outline),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = "$ tail -f /var/log/gvpn_engine.log",
          fontFamily = JetBrainsMonoFamily,
          style = MaterialTheme.typography.labelMedium,
          color = colors.primary,
          fontWeight = FontWeight.Bold,
        )
        if (logs.isEmpty()) {
          Text(
            text = "[--:--:--] A aguardar eventos do motor...",
            fontFamily = JetBrainsMonoFamily,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
          )
        } else {
          logs.takeLast(30).forEach { entry ->
            val levelColor =
              when (entry.level) {
                LogLevel.SUCCESS -> colors.primary
                LogLevel.INFO -> colors.secondary
                LogLevel.WARN -> colors.tertiary
                LogLevel.ERROR -> colors.error
              }
            Text(
              text = "[${entry.timestamp}] [${entry.tag}] ${entry.message}",
              fontFamily = JetBrainsMonoFamily,
              style = MaterialTheme.typography.labelSmall,
              color = levelColor,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun TerminalInfoLine(key: String, value: String) {
  val colors = MaterialTheme.colorScheme
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(
      text = "$key:",
      fontFamily = JetBrainsMonoFamily,
      style = MaterialTheme.typography.labelSmall,
      color = colors.onSurfaceVariant,
    )
    Text(
      text = value,
      fontFamily = JetBrainsMonoFamily,
      style = MaterialTheme.typography.labelSmall,
      color = colors.primary,
      fontWeight = FontWeight.Bold,
    )
  }
}
