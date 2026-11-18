package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.SshAuthMethod
import com.example.data.model.SshProfileEntity
import com.example.data.model.TelemetrySnapshot

@Composable
fun SshDropbearScreen(
  profiles: List<SshProfileEntity>,
  selectedProfileId: Long?,
  telemetry: TelemetrySnapshot,
  dropbearReady: Boolean,
  onSelectProfile: (Long) -> Unit,
  onSaveProfile: (SshProfileEntity) -> Unit,
  onDeleteProfile: (SshProfileEntity) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.colorScheme
  val context = LocalContext.current

  var editingId by remember { mutableStateOf(0L) }
  var profileName by remember { mutableStateOf("") }
  var host by remember { mutableStateOf("") }
  var port by remember { mutableStateOf("22") }
  var username by remember { mutableStateOf("") }
  var authMethod by remember { mutableStateOf(SshAuthMethod.PASSWORD) }
  var password by remember { mutableStateOf("") }
  var showPassword by remember { mutableStateOf(false) }
  var privateKeyContent by remember { mutableStateOf("") }
  var keepAliveSeconds by remember { mutableStateOf("25") }
  var autoReconnect by remember { mutableStateOf(true) }
  var validationError by remember { mutableStateOf<String?>(null) }

  // Zero-permission SAF OpenDocument picker for importing SSH private keys
  val keyPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      if (uri != null) {
        runCatching {
          context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.onSuccess { content ->
          if (!content.isNullOrBlank()) {
            privateKeyContent = content
            authMethod = SshAuthMethod.PRIVATE_KEY
          }
        }
      }
    }

  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Exclusive Dropbear Engine Badge + Auth Status
    Surface(
      shape = RoundedCornerShape(10.dp),
      color = colors.surfaceVariant,
      border =
        BorderStroke(
          1.dp,
          if (dropbearReady) colors.primary.copy(alpha = 0.6f) else colors.error,
        ),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            Icons.Filled.Terminal,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = "IMPLEMENTAÇÃO SSH EXCLUSIVA: DROPBEAR 2026.94",
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
            fontWeight = FontWeight.Bold,
          )
        }
        Text(
          text =
            "Estado Auth: ${telemetry.sshAuthState}" +
              (telemetry.dropbearPid?.let { " · PID=$it" } ?: "") +
              if (dropbearReady) " · Binário nativo verificado" else " · Binário ausente!",
          style = MaterialTheme.typography.labelSmall,
          color = colors.onSurfaceVariant,
        )
      }
    }

    // Dropbear Configuration Form
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = colors.surfaceVariant,
      border = BorderStroke(1.dp, colors.outline),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = if (editingId == 0L) "NOVA CONFIGURAÇÃO SSH DROPBEAR" else "EDITAR PERFIL DROPBEAR",
          style = MaterialTheme.typography.labelMedium,
          color = colors.secondary,
          fontWeight = FontWeight.Bold,
        )

        OutlinedTextField(
          value = profileName,
          onValueChange = { profileName = it },
          label = { Text("Nome do perfil") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("ssh_name_input"),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host / IP SSH") },
            singleLine = true,
            modifier = Modifier.weight(1.8f).testTag("ssh_host_input"),
          )
          OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            label = { Text("Porta") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f).testTag("ssh_port_input"),
          )
        }

        OutlinedTextField(
          value = username,
          onValueChange = { username = it },
          label = { Text("Nome de utilizador") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("ssh_user_input"),
        )

        // Auth method selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SshAuthMethod.entries.forEach { method ->
            val active = authMethod == method
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = if (active) colors.secondaryContainer else colors.surface,
              border = BorderStroke(1.dp, if (active) colors.secondary else colors.outline),
              modifier =
                Modifier.weight(1f)
                  .minimumInteractiveComponentSize()
                  .clickable { authMethod = method },
            ) {
              Row(
                modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector =
                    if (method == SshAuthMethod.PASSWORD) Icons.Filled.Lock else Icons.Filled.Key,
                  contentDescription = null,
                  tint = if (active) colors.secondary else colors.onSurfaceVariant,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                  text = method.label,
                  style = MaterialTheme.typography.labelSmall,
                  color = if (active) colors.secondary else colors.onSurfaceVariant,
                  fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                )
              }
            }
          }
        }

        if (authMethod == SshAuthMethod.PASSWORD) {
          OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Palavra-passe") },
            singleLine = true,
            visualTransformation =
              if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
              IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                  imageVector =
                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                  contentDescription = "Mostrar/Ocultar palavra-passe",
                )
              }
            },
            modifier = Modifier.fillMaxWidth().testTag("ssh_password_input"),
          )
        } else {
          OutlinedTextField(
            value = privateKeyContent,
            onValueChange = { privateKeyContent = it },
            label = { Text("Chave Privada (OpenSSH / Dropbear PEM)") },
            minLines = 3,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth().testTag("ssh_key_input"),
          )
          OutlinedButton(
            onClick = { keyPickerLauncher.launch(arrayOf("*/*")) },
            border = BorderStroke(1.dp, colors.secondary),
            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize(),
          ) {
            Icon(Icons.Filled.UploadFile, contentDescription = null, tint = colors.secondary)
            Spacer(Modifier.width(6.dp))
            Text("Importar Ficheiro de Chave", color = colors.secondary)
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = keepAliveSeconds,
            onValueChange = { keepAliveSeconds = it.filter { c -> c.isDigit() } },
            label = { Text("KeepAlive (s)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
          )
          Row(
            modifier = Modifier.weight(1.3f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text("Reconexão Auto", style = MaterialTheme.typography.labelSmall)
            Switch(checked = autoReconnect, onCheckedChange = { autoReconnect = it })
          }
        }

        if (validationError != null) {
          Text(validationError!!, style = MaterialTheme.typography.labelSmall, color = colors.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = {
              val portInt = port.toIntOrNull()
              val ka = keepAliveSeconds.toIntOrNull()?.coerceIn(5, 120) ?: 25
              if (host.isBlank() || username.isBlank() || portInt == null || portInt !in 1..65535) {
                validationError = "Informe Host, Porta (1-65535) e Utilizador SSH válidos."
                return@Button
              }
              if (authMethod == SshAuthMethod.PASSWORD && password.isEmpty()) {
                validationError = "Informe a palavra-passe SSH."
                return@Button
              }
              if (authMethod == SshAuthMethod.PRIVATE_KEY && privateKeyContent.isBlank()) {
                validationError = "Cole ou importe a chave privada SSH."
                return@Button
              }
              validationError = null
              onSaveProfile(
                SshProfileEntity(
                  id = editingId,
                  name = profileName.trim().ifEmpty { "${username.trim()}@${host.trim()}" },
                  host = host.trim(),
                  port = portInt,
                  username = username.trim(),
                  authMethod = authMethod,
                  password = password,
                  privateKeyContent = privateKeyContent,
                  keepAliveSeconds = ka,
                  autoReconnect = autoReconnect,
                )
              )
              editingId = 0L
              profileName = ""
              host = ""
              port = "22"
              username = ""
              password = ""
              privateKeyContent = ""
            },
            colors =
              ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
              ),
            modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("save_ssh_profile_button"),
          ) {
            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
              text = if (editingId == 0L) "Guardar SSH Dropbear" else "Atualizar Perfil",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
            )
          }

          if (editingId != 0L) {
            OutlinedButton(
              onClick = {
                editingId = 0L
                profileName = ""
                host = ""
                port = "22"
                username = ""
                password = ""
                privateKeyContent = ""
              },
              modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
              Text("Novo")
            }
          }
        }
      }
    }

    // Saved SSH Dropbear Profiles
    if (profiles.isNotEmpty()) {
      Text(
        text = "PERFIS DROPBEAR GUARDADOS (${profiles.size})",
        style = MaterialTheme.typography.labelMedium,
        color = colors.secondary,
      )
      profiles.forEach { profile ->
        val selected = profile.id == selectedProfileId
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = if (selected) colors.primaryContainer.copy(alpha = 0.35f) else colors.surfaceVariant,
          border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) colors.primary else colors.outline),
          modifier =
            Modifier.fillMaxWidth()
              .testTag("ssh_profile_card_${profile.id}")
              .clickable { onSelectProfile(profile.id) },
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Text(
                  text = profile.name,
                  style = MaterialTheme.typography.titleMedium,
                  color = if (selected) colors.primary else colors.onSurface,
                  fontWeight = FontWeight.Bold,
                )
                if (selected) {
                  Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selecionado",
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                  )
                }
              }
              Text(
                text = "${profile.username}@${profile.host}:${profile.port} · ${profile.authMethod.label} · KA ${profile.keepAliveSeconds}s",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondary,
              )
              if (!profile.lastAuthStatus.isNullOrBlank()) {
                Text(
                  text =
                    "Auth: ${profile.lastAuthStatus}" +
                      (profile.lastLatencyMs?.let { " · ${it}ms" } ?: ""),
                  style = MaterialTheme.typography.labelSmall,
                  color = colors.onSurfaceVariant,
                )
              }
            }
            Row {
              IconButton(
                onClick = {
                  editingId = profile.id
                  profileName = profile.name
                  host = profile.host
                  port = profile.port.toString()
                  username = profile.username
                  authMethod = profile.authMethod
                  password = profile.password
                  privateKeyContent = profile.privateKeyContent
                  keepAliveSeconds = profile.keepAliveSeconds.toString()
                  autoReconnect = profile.autoReconnect
                },
                modifier = Modifier.minimumInteractiveComponentSize(),
              ) {
                Icon(Icons.Filled.Edit, contentDescription = "Editar perfil SSH")
              }
              IconButton(
                onClick = { onDeleteProfile(profile) },
                modifier = Modifier.minimumInteractiveComponentSize(),
              ) {
                Icon(Icons.Filled.Delete, contentDescription = "Eliminar perfil SSH", tint = colors.error)
              }
            }
          }
        }
      }
    }
  }
}
