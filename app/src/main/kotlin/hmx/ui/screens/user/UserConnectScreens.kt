package hmx.ui.screens.user

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hmx.core.logging.HmxLog
import hmx.vpn.TunnelController
import hmx.di.rememberEngine
import hmx.domain.logic.ClientState
import hmx.domain.logic.DataLimits
import hmx.ui.components.LiveState
import hmx.ui.components.MetricCard
import hmx.ui.components.PrimaryButton
import hmx.ui.components.SecondaryButton
import hmx.ui.components.StateOrb
import hmx.ui.components.StatRow
import kotlinx.coroutines.delay

@Composable
fun VpnPermissionScreen(onProceed: () -> Unit, onDenied: () -> Unit) {
    val engine = rememberEngine()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            engine.grantVpnPermission()
            onProceed()
        } else {
            engine.denyVpnPermission()
            onDenied()
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Android will ask for VPN permission", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "HMX creates a local VPN interface so this device can reach the approved provider. " +
                "Traffic only flows through the trusted peer you just paired with.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(26.dp))
        PrimaryButton("Open Android VPN dialog", {
            runCatching { TunnelController.init(context) }
            val intent: Intent? = try {
                kotlinx.coroutines.runBlocking { TunnelController.prepareIntent(context) }
            } catch (e: Exception) {
                HmxLog.e("VPN") { "prepareIntent failed: ${e.message}" }
                null
            }
            if (intent != null) {
                launcher.launch(intent)
            }
        }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Not now", {
            engine.denyVpnPermission()
            onDenied()
        }, modifier = Modifier.fillMaxWidth())
    }
}

private val CONNECT_STEPS = listOf(
    "Preparing secure connection",
    "Verifying device",
    "Starting VPN",
    "Connecting",
    "Testing internet",
)

@Composable
fun ConnectingScreen(onConnected: () -> Unit, onError: (String) -> Unit) {
    val engine = rememberEngine()
    val state by engine.client.state.collectAsState()

    LaunchedEffect(Unit) { engine.confirmConnect() }

    LaunchedEffect(state) {
        when (val s = state) {
            is ClientState.Connected -> onConnected()
            is ClientState.Failed -> onError(s.error.name)
            else -> Unit
        }
    }

    val stepIndex = when (state) {
        is ClientState.Authenticating -> 0
        is ClientState.VpnPermissionRequired -> 1
        is ClientState.StartingTunnel -> 2
        is ClientState.Probing -> 4
        else -> -1
    }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StateOrb(LiveState.CONNECTING, size = 110)
        Spacer(Modifier.height(22.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                CONNECT_STEPS.forEachIndexed { i, step ->
                    val done = i < stepIndex || state is ClientState.Connected
                    val active = i == stepIndex
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(step, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                done -> "✓"
                                active -> "…"
                                else -> ""
                            },
                            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectedScreen(onDisconnected: () -> Unit) {
    val engine = rememberEngine()
    val state by engine.client.state.collectAsState()
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(1000)
        }
    }

    LaunchedEffect(state) {
        if (state !is ClientState.Connected && state !is ClientState.Reconnecting) onDisconnected()
    }

    val connected = state as? ClientState.Connected

    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateOrb(LiveState.ONLINE, size = 64)
            Spacer(Modifier.padding(10.dp))
            Column {
                Text("CONNECTED", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "${connected?.providerName ?: ""} • ${connected?.mode?.name?.lowercase() ?: "direct"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Download", DataLimits.formatBytes(connected?.stats?.rxBytes ?: 0), Modifier.weight(1f), accent = true)
            MetricCard("Upload", DataLimits.formatBytes(connected?.stats?.txBytes ?: 0), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp)) {
                StatRow("Total used", DataLimits.formatBytes(connected?.stats?.totalBytes ?: 0))
                StatRow("Session duration", DataLimits.formatDuration((nowTick - (connected?.sinceMs ?: nowTick)).coerceAtLeast(0)))
                StatRow("Provider network", "5G")
            }
        }

        if (state is ClientState.Reconnecting) {
            Spacer(Modifier.height(14.dp))
            Text("Reconnecting…", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.weight(1f))
        PrimaryButton("DISCONNECT", { engine.disconnect() }, modifier = Modifier.fillMaxWidth())
    }
}
