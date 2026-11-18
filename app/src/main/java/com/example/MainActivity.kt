package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.ui.GvpnAppScreen
import com.example.ui.theme.GvpnTheme
import com.example.ui.viewmodel.GvpnViewModel

class MainActivity : ComponentActivity() {
  private val viewModel: GvpnViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      GvpnTheme {
        val context = LocalContext.current

        val notificationPermLauncher =
          rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

        val vpnPrepareLauncher =
          rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
              viewModel.startVpnConnection(context)
            }
          }

        LaunchedEffect(Unit) {
          if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
              ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
              ) != PackageManager.PERMISSION_GRANTED
          ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
          }
        }

        GvpnAppScreen(
          viewModel = viewModel,
          onRequestStartVpn = {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
              vpnPrepareLauncher.launch(prepareIntent)
            } else {
              viewModel.startVpnConnection(context)
            }
          },
          onRequestStopVpn = { viewModel.stopVpnConnection(context) },
        )
      }
    }
  }
}
