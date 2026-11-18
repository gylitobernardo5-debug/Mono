package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ConnectionPhase
import com.example.data.model.VpnMode
import com.example.vpn.speed.GvpnEngineState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  private val resetRule =
    object : ExternalResource() {
      override fun before() {
        GvpnEngineState.resetTrafficCounters()
        GvpnEngineState.updateTelemetry {
          it.copy(
            phase = ConnectionPhase.DISCONNECTED,
            mode = VpnMode.AUTO_SNI,
            externalIp = "---",
            statusDetail = "Pronto",
          )
        }
      }
    }

  private val composeRule = createAndroidComposeRule<MainActivity>()

  @get:Rule val ruleChain: RuleChain = RuleChain.outerRule(resetRule).around(composeRule)

  @Test
  fun appNameMatchesGvpn() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("G-VPN", context.getString(R.string.app_name))
  }

  @Test
  fun dashboardDisplaysModePillsAndPowerButton() {
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("vpn_power_button").assertIsDisplayed()
    composeRule.onNodeWithTag("mode_auto").assertIsDisplayed()
    composeRule.onNodeWithTag("mode_manual_sni").assertIsDisplayed()
    composeRule.onNodeWithTag("mode_ssh_dropbear").assertIsDisplayed()
  }

  @Test
  fun switchingToSshDropbearTabShowsDropbearEngine() {
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("mode_ssh_dropbear").performClick()
    composeRule.waitForIdle()
    composeRule
      .onNodeWithText("IMPLEMENTAÇÃO SSH EXCLUSIVA: DROPBEAR 2026.94")
      .performScrollTo()
      .assertIsDisplayed()
    composeRule.onNodeWithTag("ssh_host_input").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("ssh_port_input").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("ssh_user_input").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun addingSniProfilePersistsAndDisplaysInMultiSniList() {
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("add_sni_button").performScrollTo().performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("sni_name_input").performTextInput("CDN Teste")
    composeRule.onNodeWithTag("sni_host_input").performTextInput("sni.exemplo.org")
    composeRule.onNodeWithTag("sni_gateway_input").performTextInput("198.51.100.10")
    composeRule.onNodeWithTag("confirm_save_sni_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("CDN Teste").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun devTerminalUnlocksOnRealConnectedPhaseWithRequiredCredits() {
    composeRule.waitForIdle()
    GvpnEngineState.updateTelemetry {
      it.copy(
        phase = ConnectionPhase.CONNECTED,
        mode = VpnMode.SSH_DROPBEAR,
        externalIp = "203.0.113.45",
        activeSniOrHost = "user@203.0.113.45:22",
        negotiatedProtocol = "Dropbear SSH-2.0",
        latencyMs = 42L,
      )
    }
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(2500L)
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("dev_terminal_screen").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("GYLITO BERNARDO").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("DEV").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("FOI UM DIA NORMAL...").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("GYLITO BERNARDO MAULA").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("DEV MURIBA MC").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("876699965").performScrollTo().assertIsDisplayed()
  }
}
