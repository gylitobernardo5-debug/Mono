package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ConnectionPhase
import com.example.data.model.TelemetrySnapshot
import com.example.data.model.VpnMode
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale

@Composable
fun TerminalDashboardHeader(
  telemetry: TelemetrySnapshot,
  selectedMode: VpnMode,
  selectedTargetLabel: String,
  onSelectMode: (VpnMode) -> Unit,
  onTogglePower: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val isConnected = telemetry.phase == ConnectionPhase.CONNECTED
  val isBusy = telemetry.phase.isConnectingOrActive && !isConnected
  val isError = telemetry.phase == ConnectionPhase.ERROR

  val accentColor by
    animateColorAsState(
      targetValue =
        when {
          isConnected -> colors.primary
          isBusy -> colors.secondary
          isError -> colors.error
          else -> colors.secondary
        },
      label = "accentColor",
    )

  val infinite = rememberInfiniteTransition(label = "powerRing")
  val sweepAngle by
    infinite.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
      label = "ringRotation",
    )

  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Top Brand Strip
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Shield,
          contentDescription = "G-VPN",
          tint = colors.primary,
          modifier = Modifier.size(22.dp),
        )
        Column {
          Text(
            text = "G-VPN // EXTREME V2",
            style = MaterialTheme.typography.titleLarge,
            color = colors.primary,
          )
          Text(
            text = "GYLITO BERNARDO · DEV MURIBA MC",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
          )
        }
      }

      Surface(
        shape = RoundedCornerShape(6.dp),
        color =
          when {
            isConnected -> colors.primaryContainer
            isError -> colors.errorContainer
            else -> colors.secondaryContainer
          },
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.7f)),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Bolt,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(14.dp),
          )
          Text(
            text =
              when {
                isConnected -> "TUNEL ATIVO"
                isBusy -> "A LIGAR"
                isError -> "FALHA"
                else -> "PRONTO"
              },
            style = MaterialTheme.typography.labelSmall,
            color = accentColor,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }

    // 3 Independent Mode Selector Pills
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      VpnMode.entries.forEach { mode ->
        val selected = mode == selectedMode
        val modeTag =
          when (mode) {
            VpnMode.AUTO_SNI -> "mode_auto"
            VpnMode.MANUAL_SNI -> "mode_manual_sni"
            VpnMode.SSH_DROPBEAR -> "mode_ssh_dropbear"
          }
        Surface(
          modifier =
            Modifier.weight(1f)
              .minimumInteractiveComponentSize()
              .clip(RoundedCornerShape(8.dp))
              .testTag(modeTag)
              .clickable(enabled = !telemetry.phase.isConnectingOrActive) { onSelectMode(mode) },
          shape = RoundedCornerShape(8.dp),
          color = if (selected) colors.secondaryContainer else colors.surfaceVariant,
          border =
            BorderStroke(
              width = if (selected) 1.5.dp else 1.dp,
              color = if (selected) colors.secondary else colors.outline,
            ),
        ) {
          Box(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = mode.label,
              style = MaterialTheme.typography.labelMedium,
              color = if (selected) colors.secondary else colors.onSurfaceVariant,
              fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }

    // Power Button + Live Status Banner Row
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = colors.surfaceVariant,
      border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          // Big LIGAR / DESLIGAR Power Button
          Box(
            modifier =
              Modifier.size(92.dp)
                .clip(CircleShape)
                .testTag("vpn_power_button")
                .clickable { onTogglePower() },
            contentAlignment = Alignment.Center,
          ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
              drawCircle(
                color = accentColor.copy(alpha = 0.22f),
                style = Stroke(width = 4.dp.toPx()),
              )
              if (isBusy || isConnected) {
                drawArc(
                  color = accentColor,
                  startAngle = sweepAngle,
                  sweepAngle = if (isConnected) 240f else 110f,
                  useCenter = false,
                  style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
              }
            }
            Surface(
              shape = CircleShape,
              color = if (isConnected) colors.primaryContainer else colors.surface,
              border = BorderStroke(1.5.dp, accentColor),
              modifier = Modifier.size(72.dp),
            ) {
              Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
              ) {
                Icon(
                  imageVector = Icons.Filled.PowerSettingsNew,
                  contentDescription =
                    if (telemetry.phase.isConnectingOrActive) "Desligar VPN" else "Ligar VPN",
                  tint = accentColor,
                  modifier = Modifier.size(26.dp),
                )
                Text(
                  text =
                    if (telemetry.phase.isConnectingOrActive) "DESLIGAR" else "LIGAR",
                  style = MaterialTheme.typography.labelSmall,
                  color = accentColor,
                  fontWeight = FontWeight.Bold,
                )
              }
            }
          }

          // Connection State & Real Reason
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              text = "ESTADO DA CONEXÃO",
              style = MaterialTheme.typography.labelSmall,
              color = colors.onSurfaceVariant,
            )
            Text(
              text = telemetry.phase.label,
              style = MaterialTheme.typography.titleMedium,
              color = accentColor,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.testTag("connection_phase_text"),
            )
            Text(
              text = telemetry.statusDetail,
              style = MaterialTheme.typography.labelSmall,
              color = colors.onSurface,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        // Telemetry Matrix (Time, DL, UL, IP, SNI/Host, Mode)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            TelemetryCell(
              label = "TEMPO",
              value = formatDuration(telemetry.connectedSeconds),
              accent = colors.primary,
              modifier = Modifier.weight(1f),
            )
            TelemetryCell(
              label = "DOWNLOAD",
              value = "↓ ${formatRate(telemetry.downloadBytesPerSec)}",
              accent = colors.primary,
              modifier = Modifier.weight(1f),
            )
            TelemetryCell(
              label = "UPLOAD",
              value = "↑ ${formatRate(telemetry.uploadBytesPerSec)}",
              accent = colors.secondary,
              modifier = Modifier.weight(1f),
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            TelemetryCell(
              label = "IP ATUAL",
              value = telemetry.externalIp,
              accent = colors.secondary,
              modifier = Modifier.weight(1f),
            )
            TelemetryCell(
              label = if (selectedMode == VpnMode.SSH_DROPBEAR) "SERVIDOR SSH" else "SNI SELECIONADO",
              value =
                if (telemetry.phase == ConnectionPhase.CONNECTED) telemetry.activeSniOrHost
                else selectedTargetLabel,
              accent = colors.onSurface,
              modifier = Modifier.weight(1.3f),
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            TelemetryCell(
              label = "MODO",
              value = selectedMode.label,
              accent = colors.secondary,
              modifier = Modifier.weight(1f),
            )
            TelemetryCell(
              label = "EXTREME ENGINE",
              value =
                "BUF ${telemetry.bufferSizeBytes / 1024}KB · ${telemetry.activeFlows} FLUXOS",
              accent = colors.primary,
              modifier = Modifier.weight(1.3f),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun TelemetryCell(
  label: String,
  value: String,
  accent: androidx.compose.ui.graphics.Color,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(8.dp),
    color = colors.surface,
    border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.65f)),
  ) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
        maxLines = 1,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = value,
        fontFamily = JetBrainsMonoFamily,
        style = MaterialTheme.typography.labelMedium,
        color = accent,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

fun formatDuration(totalSeconds: Long): String {
  val h = totalSeconds / 3600
  val m = (totalSeconds % 3600) / 60
  val s = totalSeconds % 60
  return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

fun formatRate(bytesPerSec: Long): String =
  when {
    bytesPerSec >= 1_048_576L ->
      String.format(Locale.US, "%.2f MB/s", bytesPerSec / 1_048_576.0)
    bytesPerSec >= 1024L ->
      String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
    else -> "$bytesPerSec B/s"
  }
