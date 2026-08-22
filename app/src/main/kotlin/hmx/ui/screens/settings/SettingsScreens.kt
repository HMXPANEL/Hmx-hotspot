package hmx.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hmx.data.local.SettingsRepository
import hmx.di.rememberEngine
import hmx.domain.logic.DataLimits
import hmx.ui.components.SectionHeader
import hmx.ui.components.SecondaryButton
import hmx.ui.components.StatRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onSecurity: () -> Unit,
    onData: () -> Unit,
    onNotifications: () -> Unit,
    onDiagnostics: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as hmx.HmxApplication
    val repo = app.container.settingsRepository
    val settings by repo.settings.collectAsState(initial = hmx.domain.model.HmxSettings())
    val scope = remember { CoroutineScope(Dispatchers.IO) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionHeader("General")
        SettingsCard {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Device name", style = MaterialTheme.typography.bodyLarge)
                    Text(settings.deviceName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ToggleRow("Auto connect", "Reconnect when the app starts", settings.autoConnect) { v ->
                scope.launch { repo.setAutoConnect(v) }
            }
        }

        SectionHeader("Data")
        SettingsCard {
            Row(Modifier.fillMaxWidth().padding(14.dp).clickable(onClick = onData), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Data limits", style = MaterialTheme.typography.bodyLarge)
                    Text("${DataLimits.formatBytes(settings.dailyLimitBytes)} daily · warn at ${settings.warningThresholdPct}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionHeader("Security")
        SettingsCard { NavRow("Paired devices & keys", onSecurity) }

        SectionHeader("Notifications")
        SettingsCard { NavRow("Notification preferences", onNotifications) }

        SectionHeader("More")
        SettingsCard {
            NavRow("Diagnostics", onDiagnostics)
            NavRow("About", onAbout)
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(14.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SecurityScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Security", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                StatRow("Device key fingerprint", "B7E2 44A0 91CF 03D2")
                StatRow("Pairing codes", "5 min TTL, single use")
                StatRow("Transport", "WireGuard, ChaCha20-Poly1305")
            }
        }
        Spacer(Modifier.height(14.dp))
        SecondaryButton("Revoke all paired devices", {}, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        SecondaryButton("Back", onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun DataLimitsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as hmx.HmxApplication).container.settingsRepository
    val settings by repo.settings.collectAsState(initial = hmx.domain.model.HmxSettings())
    val scope = remember { CoroutineScope(Dispatchers.IO) }
    var sliderGb by remember(settings.dailyLimitBytes) { mutableFloatStateOf(settings.dailyLimitBytes / (1024f * 1024f * 1024f)) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Data limits", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                Text("Daily limit: ${"%.1f".format(sliderGb)} GB", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = sliderGb,
                    onValueChange = { sliderGb = it },
                    onValueChangeFinished = { scope.launch { repo.setDailyLimitBytes((sliderGb * 1024 * 1024 * 1024).toLong()) } },
                    valueRange = 0.5f..50f,
                )
                Spacer(Modifier.height(10.dp))
                Text("Warning threshold: ${settings.warningThresholdPct}%", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = settings.warningThresholdPct.toFloat(),
                    onValueChange = { scope.launch { repo.setWarningThresholdPct(it.toInt()) } },
                    valueRange = 50f..99f,
                )
                Spacer(Modifier.height(10.dp))
                ToggleRow("Hard limit", "Cut the tunnel when the cap is reached (enforced on provider)", settings.hardLimitEnabled) { v ->
                    scope.launch { repo.setHardLimitEnabled(v) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        SecondaryButton("Back", onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    var peerEvents by remember { mutableStateOf(true) }
    var limitWarnings by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Notifications", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        SettingsCard {
            ToggleRow("Peer events", "When a device connects or disconnects", peerEvents) { peerEvents = it }
            ToggleRow("Data limit warnings", "When usage crosses your threshold", limitWarnings) { limitWarnings = it }
        }
        Spacer(Modifier.weight(1f))
        SecondaryButton("Back", onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val engine = rememberEngine()
    val provider by engine.provider.state.collectAsState()
    val client by engine.client.state.collectAsState()

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                StatRow("Provider state", provider.javaClass.simpleName)
                StatRow("Client state", client.javaClass.simpleName)
                StatRow("MTU", "1280")
                StatRow("Keepalive", "25s")
                StatRow("Mode", "direct (mock)")
            }
        }
        Spacer(Modifier.weight(1f))
        SecondaryButton("Back", onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("About", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                StatRow("Version", "0.1.0-phase2 (mock UI)")
                StatRow("Networking status", "Phase 0 proven — integration pending")
                Text(
                    "HMX Remote Internet is a personal tool for sharing internet between trusted devices over WireGuard.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        SecondaryButton("Back", onBack, modifier = Modifier.fillMaxWidth())
    }
}
