package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.VpnMode
import com.example.ui.components.TerminalDashboardHeader
import com.example.ui.screens.DevTerminalScreen
import com.example.ui.screens.SshDropbearScreen
import com.example.ui.screens.TlsMultiSniScreen
import com.example.ui.viewmodel.GvpnTab
import com.example.ui.viewmodel.GvpnViewModel

@Composable
fun GvpnAppScreen(
  viewModel: GvpnViewModel,
  onRequestStartVpn: () -> Unit,
  onRequestStopVpn: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
  val logs by viewModel.logs.collectAsStateWithLifecycle()
  val sniProfiles by viewModel.sniProfiles.collectAsStateWithLifecycle()
  val sshProfiles by viewModel.sshProfiles.collectAsStateWithLifecycle()

  val visibleTabs =
    if (uiState.devTerminalUnlocked) {
      listOf(GvpnTab.TLS_MULTI_SNI, GvpnTab.SSH_DROPBEAR, GvpnTab.DEV_TERMINAL)
    } else {
      listOf(GvpnTab.TLS_MULTI_SNI, GvpnTab.SSH_DROPBEAR)
    }

  if (uiState.selectedTab != GvpnTab.TLS_MULTI_SNI) {
    BackHandler { viewModel.selectTab(GvpnTab.TLS_MULTI_SNI) }
  }

  val selectedTargetLabel =
    when (uiState.selectedMode) {
      VpnMode.AUTO_SNI ->
        sniProfiles.firstOrNull { it.isAutoEligible }?.sniHost ?: "AUTO (Multi-SNI / Público)"
      VpnMode.MANUAL_SNI ->
        sniProfiles.firstOrNull { it.id == uiState.selectedSniProfileId }?.sniHost ?: "Nenhum SNI"
      VpnMode.SSH_DROPBEAR ->
        sshProfiles.firstOrNull { it.id == uiState.selectedSshProfileId }?.let {
          "${it.host}:${it.port}"
        } ?: "Nenhum SSH"
    }

  val colors = MaterialTheme.colorScheme

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val isExpandedWidth = maxWidth >= 720.dp

    Scaffold(
      containerColor = colors.background,
      contentWindowInsets = WindowInsets.safeDrawing,
      bottomBar = {
        if (!isExpandedWidth) {
          NavigationBar(
            containerColor = colors.surface,
            modifier = Modifier.navigationBarsPadding(),
          ) {
            visibleTabs.forEach { tab ->
              val selected = uiState.selectedTab == tab
              NavigationBarItem(
                selected = selected,
                onClick = { viewModel.selectTab(tab) },
                icon = { TabIcon(tab, selected) },
                label = {
                  Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                  )
                },
                colors =
                  NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.primary,
                    selectedTextColor = colors.primary,
                    indicatorColor = colors.primaryContainer,
                    unselectedIconColor = colors.onSurfaceVariant,
                    unselectedTextColor = colors.onSurfaceVariant,
                  ),
                modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}"),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (isExpandedWidth) {
          NavigationRail(
            containerColor = colors.surface,
            modifier = Modifier.fillMaxHeight(),
          ) {
            Spacer(Modifier.height(12.dp))
            visibleTabs.forEach { tab ->
              val selected = uiState.selectedTab == tab
              NavigationRailItem(
                selected = selected,
                onClick = { viewModel.selectTab(tab) },
                icon = { TabIcon(tab, selected) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                colors =
                  NavigationRailItemDefaults.colors(
                    selectedIconColor = colors.primary,
                    selectedTextColor = colors.primary,
                    indicatorColor = colors.primaryContainer,
                    unselectedIconColor = colors.onSurfaceVariant,
                    unselectedTextColor = colors.onSurfaceVariant,
                  ),
                modifier = Modifier.testTag("rail_tab_${tab.name.lowercase()}"),
              )
            }
          }
        }

        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.TopCenter,
        ) {
          Column(
            modifier =
              Modifier.fillMaxWidth()
                .widthIn(max = 840.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            TerminalDashboardHeader(
              telemetry = telemetry,
              selectedMode = uiState.selectedMode,
              selectedTargetLabel = selectedTargetLabel,
              onSelectMode = { viewModel.selectMode(it) },
              onTogglePower = {
                if (telemetry.phase.isConnectingOrActive) {
                  onRequestStopVpn()
                } else {
                  onRequestStartVpn()
                }
              },
            )

            when (uiState.selectedTab) {
              GvpnTab.TLS_MULTI_SNI ->
                TlsMultiSniScreen(
                  profiles = sniProfiles,
                  selectedProfileId = uiState.selectedSniProfileId,
                  probingProfileId = uiState.probingSniProfileId,
                  selectedMode = uiState.selectedMode,
                  isDiscovering = uiState.isDiscovering,
                  discoverySummary = uiState.discoverySummary,
                  discoveredNodes = uiState.discoveredNodes,
                  onSelectProfile = { viewModel.selectSniProfile(it) },
                  onSaveProfile = { viewModel.saveSniProfile(it) },
                  onDeleteProfile = { viewModel.deleteSniProfile(it) },
                  onProbeProfile = { viewModel.probeSniProfile(it) },
                  onRunDiscovery = { viewModel.runFreeInfrastructureDiscovery() },
                  onImportDiscovered = { viewModel.importDiscoveredNodeAsSni(it) },
                )
              GvpnTab.SSH_DROPBEAR ->
                SshDropbearScreen(
                  profiles = sshProfiles,
                  selectedProfileId = uiState.selectedSshProfileId,
                  telemetry = telemetry,
                  dropbearReady = uiState.dropbearBinaryReady,
                  onSelectProfile = { viewModel.selectSshProfile(it) },
                  onSaveProfile = { viewModel.saveSshProfile(it) },
                  onDeleteProfile = { viewModel.deleteSshProfile(it) },
                )
              GvpnTab.DEV_TERMINAL ->
                DevTerminalScreen(
                  telemetry = telemetry,
                  logs = logs,
                )
            }

            Spacer(Modifier.height(24.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun TabIcon(tab: GvpnTab, selected: Boolean) {
  val icon =
    when (tab) {
      GvpnTab.TLS_MULTI_SNI -> if (selected) Icons.Filled.Security else Icons.Outlined.Security
      GvpnTab.SSH_DROPBEAR -> if (selected) Icons.Filled.VpnKey else Icons.Outlined.VpnKey
      GvpnTab.DEV_TERMINAL -> if (selected) Icons.Filled.Terminal else Icons.Outlined.Terminal
    }
  Icon(imageVector = icon, contentDescription = tab.label)
}
