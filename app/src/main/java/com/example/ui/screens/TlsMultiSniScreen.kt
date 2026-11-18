package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.DiscoveredNode
import com.example.data.model.SniProfileEntity
import com.example.data.model.TlsTransportType
import com.example.data.model.VpnMode

@Composable
fun TlsMultiSniScreen(
  profiles: List<SniProfileEntity>,
  selectedProfileId: Long?,
  probingProfileId: Long?,
  selectedMode: VpnMode,
  isDiscovering: Boolean,
  discoverySummary: String?,
  discoveredNodes: List<DiscoveredNode>,
  onSelectProfile: (Long) -> Unit,
  onSaveProfile: (SniProfileEntity) -> Unit,
  onDeleteProfile: (SniProfileEntity) -> Unit,
  onProbeProfile: (SniProfileEntity) -> Unit,
  onRunDiscovery: () -> Unit,
  onImportDiscovered: (DiscoveredNode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  var editingProfile by remember { mutableStateOf<SniProfileEntity?>(null) }
  var showEditorDialog by remember { mutableStateOf(false) }

  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Action Row: + Novo Perfil SNI & Descoberta Gratuita
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Button(
        onClick = {
          editingProfile = null
          showEditorDialog = true
        },
        colors =
          ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
          ),
        modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("add_sni_button"),
      ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Novo SNI", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
      }

      OutlinedButton(
        onClick = onRunDiscovery,
        enabled = !isDiscovering,
        border = BorderStroke(1.dp, colors.secondary),
        modifier =
          Modifier.weight(1.25f).minimumInteractiveComponentSize().testTag("run_discovery_button"),
      ) {
        if (isDiscovering) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = colors.secondary,
          )
        } else {
          Icon(
            Icons.Filled.Radar,
            contentDescription = null,
            tint = colors.secondary,
            modifier = Modifier.size(18.dp),
          )
        }
        Spacer(Modifier.width(6.dp))
        Text(
          text = if (isDiscovering) "A PROCURAR..." else "Descoberta Pública",
          style = MaterialTheme.typography.labelMedium,
          color = colors.secondary,
        )
      }
    }

    // Mode Hint Banner
    Surface(
      shape = RoundedCornerShape(10.dp),
      color = colors.surfaceVariant,
      border = BorderStroke(1.dp, colors.outline),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text =
          if (selectedMode == VpnMode.AUTO_SNI) {
            "MODO AUTO: Testa todos os perfis SNI elegíveis + infraestrutura pública gratuita e liga-se apenas ao melhor túnel verificado fim-a-fim."
          } else {
            "MODO MANUAL SNI: Utiliza exclusivamente o perfil SNI selecionado abaixo, validando TLS, certificado e encaminhamento real."
          },
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(10.dp),
      )
    }

    // Discovery Report Card (if triggered)
    if (discoverySummary != null || discoveredNodes.isNotEmpty()) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceVariant,
        border = BorderStroke(1.dp, colors.secondary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              Icons.Filled.Public,
              contentDescription = null,
              tint = colors.secondary,
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = "DESCOBERTA PÚBLICA GRATUITA",
              style = MaterialTheme.typography.labelMedium,
              color = colors.secondary,
              fontWeight = FontWeight.Bold,
            )
          }
          if (discoverySummary != null) {
            Text(
              text = discoverySummary,
              style = MaterialTheme.typography.labelSmall,
              color = colors.onSurface,
            )
          }
          discoveredNodes.take(6).forEach { node ->
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = colors.surface,
              border =
                BorderStroke(
                  1.dp,
                  if (node.tunnelCompatible) colors.primary else colors.outline,
                ),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "${node.host}:${node.port} (${node.country})",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (node.tunnelCompatible) colors.primary else colors.onSurface,
                    fontWeight = FontWeight.Bold,
                  )
                  Text(
                    text = "${node.source} · ${node.latencyMs ?: "--"}ms · ${node.statusDetail}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                  )
                }
                if (node.tunnelCompatible) {
                  TextButton(onClick = { onImportDiscovered(node) }) {
                    Text("Importar", color = colors.primary)
                  }
                }
              }
            }
          }
        }
      }
    }

    // Profiles List
    if (profiles.isEmpty()) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceVariant,
        border = BorderStroke(1.dp, colors.outline),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            Icons.Filled.NetworkCheck,
            contentDescription = null,
            tint = colors.secondary,
            modifier = Modifier.size(36.dp),
          )
          Text(
            text = "Sem perfis SNI/TLS configurados",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
          )
          Text(
            text =
              "Adicione um ou mais domínios SNI com o respetivo gateway TLS compatível, ou execute a Descoberta Pública Gratuita.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
          )
        }
      }
    } else {
      profiles.forEach { profile ->
        val isSelected = profile.id == selectedProfileId
        val isProbing = profile.id == probingProfileId
        SniProfileCard(
          profile = profile,
          isSelected = isSelected,
          isProbing = isProbing,
          onSelect = { onSelectProfile(profile.id) },
          onProbe = { onProbeProfile(profile) },
          onEdit = {
            editingProfile = profile
            showEditorDialog = true
          },
          onDelete = { onDeleteProfile(profile) },
        )
      }
    }
  }

  if (showEditorDialog) {
    SniProfileEditorDialog(
      initial = editingProfile,
      onDismiss = { showEditorDialog = false },
      onSave = { saved ->
        onSaveProfile(saved)
        showEditorDialog = false
      },
    )
  }
}

@Composable
private fun SniProfileCard(
  profile: SniProfileEntity,
  isSelected: Boolean,
  isProbing: Boolean,
  onSelect: () -> Unit,
  onProbe: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val borderColor =
    when {
      isSelected -> colors.primary
      profile.lastTunnelCompatible == true -> colors.secondary.copy(alpha = 0.7f)
      else -> colors.outline
    }

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = if (isSelected) colors.primaryContainer.copy(alpha = 0.35f) else colors.surfaceVariant,
    border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
    modifier =
      Modifier.fillMaxWidth()
        .testTag("sni_profile_card_${profile.id}")
        .clickable { onSelect() },
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = profile.name,
            style = MaterialTheme.typography.titleMedium,
            color = if (isSelected) colors.primary else colors.onSurface,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = "SNI: ${profile.sniHost}  |  GW: ${profile.gatewayHost}:${profile.gatewayPort}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondary,
          )
          Text(
            text =
              "${profile.transportType.label} · ${if (profile.isAutoEligible) "AUTO ON" else "APENAS MANUAL"}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
          )
        }

        if (isSelected) {
          Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "Perfil selecionado",
            tint = colors.primary,
            modifier = Modifier.size(22.dp),
          )
        }
      }

      if (!profile.lastValidationMessage.isNullOrBlank()) {
        val ok = profile.lastTunnelCompatible == true
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = if (ok) colors.primaryContainer else colors.surface,
          border = BorderStroke(1.dp, if (ok) colors.primary else colors.tertiary.copy(alpha = 0.6f)),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning,
              contentDescription = null,
              tint = if (ok) colors.primary else colors.tertiary,
              modifier = Modifier.size(16.dp),
            )
            Text(
              text =
                "${profile.lastLatencyMs?.let { "${it}ms · " } ?: ""}${profile.lastValidationMessage}",
              style = MaterialTheme.typography.labelSmall,
              color = if (ok) colors.primary else colors.onSurface,
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedButton(
          onClick = onProbe,
          enabled = !isProbing,
          border = BorderStroke(1.dp, colors.secondary),
          modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
        ) {
          if (isProbing) {
            CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              strokeWidth = 2.dp,
              color = colors.secondary,
            )
            Spacer(Modifier.width(6.dp))
            Text("A testar...", style = MaterialTheme.typography.labelSmall, color = colors.secondary)
          } else {
            Text(
              "Testar TLS + Túnel",
              style = MaterialTheme.typography.labelSmall,
              color = colors.secondary,
            )
          }
        }

        IconButton(onClick = onEdit, modifier = Modifier.minimumInteractiveComponentSize()) {
          Icon(Icons.Filled.Edit, contentDescription = "Editar SNI", tint = colors.onSurface)
        }
        IconButton(onClick = onDelete, modifier = Modifier.minimumInteractiveComponentSize()) {
          Icon(Icons.Filled.Delete, contentDescription = "Eliminar SNI", tint = colors.error)
        }
      }
    }
  }
}

@Composable
private fun SniProfileEditorDialog(
  initial: SniProfileEntity?,
  onDismiss: () -> Unit,
  onSave: (SniProfileEntity) -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  var name by remember { mutableStateOf(initial?.name ?: "") }
  var sniHost by remember { mutableStateOf(initial?.sniHost ?: "") }
  var gatewayHost by remember { mutableStateOf(initial?.gatewayHost ?: "") }
  var gatewayPort by remember { mutableStateOf((initial?.gatewayPort ?: 443).toString()) }
  var customHeader by remember { mutableStateOf(initial?.customHeader ?: "") }
  var transport by remember {
    mutableStateOf(initial?.transportType ?: TlsTransportType.HTTP_CONNECT_TLS)
  }
  var verifyHostname by remember { mutableStateOf(initial?.verifyHostname ?: true) }
  var autoEligible by remember { mutableStateOf(initial?.isAutoEligible ?: true) }
  var errorMsg by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = colors.surface,
    title = {
      Text(
        text = if (initial == null) "Adicionar Perfil SNI / TLS" else "Editar Perfil SNI / TLS",
        style = MaterialTheme.typography.titleMedium,
        color = colors.primary,
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Nome do perfil") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("sni_name_input"),
        )
        OutlinedTextField(
          value = sniHost,
          onValueChange = { sniHost = it },
          label = { Text("Domínio SNI (ClientHello)") },
          placeholder = { Text("ex: sni.seudominio.com") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("sni_host_input"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = gatewayHost,
            onValueChange = { gatewayHost = it },
            label = { Text("Gateway IP/Host") },
            singleLine = true,
            modifier = Modifier.weight(1.7f).testTag("sni_gateway_input"),
          )
          OutlinedTextField(
            value = gatewayPort,
            onValueChange = { gatewayPort = it.filter { ch -> ch.isDigit() } },
            label = { Text("Porta") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).testTag("sni_port_input"),
          )
        }
        Text("Transporte do Túnel", style = MaterialTheme.typography.labelSmall, color = colors.secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          TlsTransportType.entries.forEach { t ->
            val active = t == transport
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = if (active) colors.secondaryContainer else colors.surfaceVariant,
              border = BorderStroke(1.dp, if (active) colors.secondary else colors.outline),
              modifier = Modifier.weight(1f).clickable { transport = t },
            ) {
              Text(
                text = t.label.substringBefore(" /"),
                style = MaterialTheme.typography.labelSmall,
                color = if (active) colors.secondary else colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
              )
            }
          }
        }
        OutlinedTextField(
          value = customHeader,
          onValueChange = { customHeader = it },
          label = { Text("Cabeçalho extra opcional (ex: Proxy-Authorization)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Validar certificado/hostname", style = MaterialTheme.typography.labelSmall)
          Switch(checked = verifyHostname, onCheckedChange = { verifyHostname = it })
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Incluir no Modo AUTO", style = MaterialTheme.typography.labelSmall)
          Switch(checked = autoEligible, onCheckedChange = { autoEligible = it })
        }
        if (errorMsg != null) {
          Text(errorMsg!!, style = MaterialTheme.typography.labelSmall, color = colors.error)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val portInt = gatewayPort.toIntOrNull()
          val cleanSni = sniHost.trim()
          val cleanGw = gatewayHost.trim().ifEmpty { cleanSni }
          if (cleanSni.isEmpty() || cleanGw.isEmpty() || portInt == null || portInt !in 1..65535) {
            errorMsg = "Informe um SNI válido, gateway e porta entre 1 e 65535."
            return@Button
          }
          onSave(
            SniProfileEntity(
              id = initial?.id ?: 0L,
              name = name.trim().ifEmpty { "SNI $cleanSni" },
              sniHost = cleanSni,
              gatewayHost = cleanGw,
              gatewayPort = portInt,
              transportType = transport,
              verifyHostname = verifyHostname,
              customHeader = customHeader.trim(),
              isAutoEligible = autoEligible,
            )
          )
        },
        modifier = Modifier.testTag("confirm_save_sni_button"),
      ) {
        Text("Guardar")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancelar") }
    },
  )
}
