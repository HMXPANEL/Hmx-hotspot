package hmx.ui.screens.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hmx.di.rememberEngine
import hmx.domain.logic.DataLimits
import hmx.domain.logic.ProviderState
import hmx.mock.MockHmxEngine
import hmx.security.PairingCodeInfo
import hmx.ui.components.CodeDisplay
import hmx.ui.components.LiveState
import hmx.ui.components.MetricCard
import hmx.ui.components.PrimaryButton
import hmx.ui.components.QrImage
import hmx.ui.components.SectionHeader
import hmx.ui.components.SecondaryButton
import hmx.ui.components.StatRow
import hmx.ui.components.StateOrb
import hmx.ui.components.StatusPill

@Composable
fun ProviderDashboardScreen(onStartSharing: () -> Unit, onOpenSharing: () -> Unit, onBack: () -> Unit) {
    val engine = rememberEngine()
    val state by engine.provider.state.collectAsState()
    val sessions by engine.sessions.collectAsState()

    val sharedTotal = sessions.filter { it.role == hmx.domain.model.DeviceRole.PROVIDER }.sumOf { it.bytesUp + it.bytesDown }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            StateOrb(if (state is ProviderState.SharingConnected) LiveState.ONLINE else LiveState.OFFLINE, size = 84)
            Spacer(Modifier.padding(10.dp))
            Column {
                Text("Internet Sharing", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (state is ProviderState.SharingConnected) "ONLINE" else "OFFLINE",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state is ProviderState.SharingConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            text = if (state is ProviderState.SharingConnected || state is ProviderState.Advertising) "MANAGE SHARING" else "START SHARING",
            onClick = {
                if (state is ProviderState.SharingConnected || state is ProviderState.Advertising) onOpenSharing() else onStartSharing()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Back", onBack, modifier = Modifier.fillMaxWidth())

        SectionHeader("Overview")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp)) {
                StatRow("Current network", "Wi-Fi")
                StatRow("Data shared (all time)", DataLimits.formatBytes(sharedTotal))
            }
        }
    }
}

@Composable
fun PairingScreen(onApproved: () -> Unit, onCancel: () -> Unit, onError: (String) -> Unit) {
    val engine = rememberEngine()
    val state by engine.provider.state.collectAsState()
    var code by remember { mutableStateOf<PairingCodeInfo?>(null) }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        if (state !is ProviderState.Advertising && state !is ProviderState.SharingConnected && state !is ProviderState.PeerAuthenticating) {
            engine.startSharing()
        } else if (code == null) {
            (state as? ProviderState.Advertising)?.let { code = it.code }
        }
    }
    LaunchedEffect(state) {
        when (val s = state) {
            is ProviderState.Advertising -> code = s.code
            is ProviderState.SharingConnected -> onApproved()
            is ProviderState.Failed -> onError(s.error.name)
            else -> Unit
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            nowTick = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text("Start Sharing", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        StatusPill(LiveState.CONNECTING)

        when (val s = state) {
            is ProviderState.Preparing -> {
                Spacer(Modifier.height(28.dp))
                hmx.ui.components.SkeletonBlock(height = 180)
                Spacer(Modifier.height(14.dp))
                hmx.ui.components.SkeletonBlock(height = 56)
            }
            is ProviderState.Advertising -> {
                val c = code ?: s.code
                Spacer(Modifier.height(18.dp))
                QrImage(payload = "hmx://p/${c.code}", size = 210.dp)
                Spacer(Modifier.height(14.dp))
                Text("Connection Code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CodeDisplay(c.formatted())
                val remainSec = ((c.expiresAtMs - nowTick) / 1000).coerceAtLeast(0)
                Text(
                    "Expires in %02d:%02d".format(remainSec / 60, remainSec % 60),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SecondaryButton("New code", { engine.regenerateCode() })
            }
            is ProviderState.PeerAuthenticating -> {
                Spacer(Modifier.height(26.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("Device found", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(s.deviceName, style = MaterialTheme.typography.headlineMedium)
                        Text("Key ${s.fingerprint}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { engine.rejectPeer() }) { Text("Reject") }
                            androidx.compose.material3.Button(onClick = { engine.approvePeer() }) { Text("Approve") }
                        }
                    }
                }
            }
            is ProviderState.SharingConnected -> Unit
            else -> {
                Spacer(Modifier.height(24.dp))
                Text("Preparing…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.weight(1f))
        SecondaryButton("Cancel", onCancel, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun SharingActiveScreen(onStopped: () -> Unit, onDeviceClick: (String) -> Unit) {
    val engine = rememberEngine()
    val state by engine.provider.state.collectAsState()
    var confirmStop by remember { mutableStateOf(false) }

    val connected = state as? ProviderState.SharingConnected
    val advertising = state as? ProviderState.Advertising

    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            StateOrb(LiveState.ONLINE, size = 64)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("INTERNET SHARING", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("ONLINE", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("Connected devices")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.medium) {
            Column(
                Modifier.padding(16.dp).fillMaxWidth(),
            ) {
                Text(connected?.peerName?.ifEmpty { "Pixel 8" } ?: "Pixel 8", style = MaterialTheme.typography.titleMedium)
                Text("direct • since session start", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (advertising != null) {
            SectionHeader("Waiting for a device")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    hmx.ui.components.CodeDisplay(advertising.code.formatted())
                    Spacer(Modifier.height(8.dp))
                    Text("Show this code or QR to the connecting device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    hmx.ui.components.QrImage(payload = "hmx://p/${advertising.code.code}", size = 150.dp)
                }
            }
        }

        SectionHeader("Session")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Download", DataLimits.formatBytes(connected?.stats?.rxBytes ?: 0), Modifier.weight(1f))
            MetricCard("Upload", DataLimits.formatBytes(connected?.stats?.txBytes ?: 0), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        MetricCard("Total shared", DataLimits.formatBytes(connected?.stats?.totalBytes ?: 0), Modifier.fillMaxWidth(), accent = true)

        Spacer(Modifier.weight(1f))
        PrimaryButton("STOP SHARING", { confirmStop = true }, modifier = Modifier.fillMaxWidth())
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop sharing?") },
            text = { Text("The connected device will lose internet immediately.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    engine.stopSharing()
                    onStopped()
                }) { Text("Stop") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep sharing") } },
        )
    }
}

@Composable
fun DeviceDetailsScreen(deviceId: String, onBack: () -> Unit) {
    val engine = rememberEngine()
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("Device details", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp)) {
                StatRow("ID", deviceId.ifEmpty { "pixel-8" })
                StatRow("Name", "Pixel 8")
                StatRow("Status", "Online")
                StatRow("Mode", "Direct")
            }
        }
        Spacer(Modifier.height(16.dp))
        SecondaryButton("Rename", {}, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Revoke access", {
            engine.revokePeerById(deviceId)
            onBack()
        }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        SecondaryButton("Back", onBack, modifier = Modifier.fillMaxWidth())
    }
}

private fun Modifier.androidClickable(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
