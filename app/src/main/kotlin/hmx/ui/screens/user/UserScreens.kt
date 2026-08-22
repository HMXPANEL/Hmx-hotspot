package hmx.ui.screens.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hmx.di.rememberEngine
import hmx.domain.logic.ClientState
import hmx.security.PairingCode
import hmx.ui.components.LiveState
import hmx.ui.components.PrimaryButton
import hmx.ui.components.QrImage
import hmx.ui.components.SecondaryButton
import hmx.ui.components.StateOrb

@Composable
fun UseDashboardScreen(onEnterCode: () -> Unit, onScanQr: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StateOrb(LiveState.OFFLINE, size = 90)
        Spacer(Modifier.height(18.dp))
        Text("USE INTERNET", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect to a trusted device that is sharing its internet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(26.dp))
        PrimaryButton("ENTER CODE", onEnterCode, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SecondaryButton("SCAN QR CODE", onScanQr, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun EnterCodeScreen(onConnectedFlow: () -> Unit, onError: (String) -> Unit, onBack: () -> Unit) {
    val engine = rememberEngine()
    val state by engine.client.state.collectAsState()
    var text by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        when (val s = state) {
            is ClientState.DeviceFound -> onConnectedFlow()
            is ClientState.Failed -> onError(s.error.name)
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("Enter pairing code", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Ask the sharing device for its 8-character code.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = PairingCode.normalize(v).take(PairingCode.LENGTH) },
            label = { Text("Code") },
            placeholder = { Text("XXXX-XXXX") },
            singleLine = true,
            isError = text.isNotEmpty() && !PairingCode.isValid(text),
            supportingText = {
                if (text.isNotEmpty() && !PairingCode.isValid(text)) {
                    Text("Use ${PairingCode.LENGTH} characters (0-9, letters without I/L/O/U)")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state is ClientState.Scanning) {
            Spacer(Modifier.height(14.dp))
            hmx.ui.components.SkeletonBlock(height = 52)
            Text("Looking for device…", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            "CONNECT",
            enabled = PairingCode.isValid(PairingCode.normalize(text)),
            onClick = {
                engine.connectWithCode(text)
                if (engine.scenario == hmx.mock.MockScenario.PAIRING_EXPIRED ||
                    engine.scenario == hmx.mock.MockScenario.PROVIDER_OFFLINE
                ) {
                    onError(engine.scenario.name)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Back", onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun ScannerScreen(onCodeScanned: (String) -> Unit, onBack: () -> Unit) {
    // Mock scanner: camera integration arrives with ML Kit in a later phase.
    Column(
        Modifier.fillMaxSize().padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        QrImage(payload = "hmx://p/mock-scan", size = 200)
        Spacer(Modifier.height(16.dp))
        Text("Camera scanning comes with the real pairing phase.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text("[mock] tap connect below to simulate a scan", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(22.dp))
        PrimaryButton("[mock] SCAN DETECTED", { onCodeScanned("MOCKCODE") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Back", onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun DeviceFoundScreen(onConfirmed: () -> Unit, onCancel: () -> Unit) {
    val engine = rememberEngine()
    val state by engine.client.state.collectAsState()

    val found = state as? ClientState.DeviceFound

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Device found", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(found?.name ?: "HMX Phone A", style = MaterialTheme.typography.headlineMedium)
                Text("Network: 5G", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Key ${found?.fingerprint ?: "7A11C0DE"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "This device is sharing its internet connection.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        PrimaryButton("CONNECT", {
            onConfirmed()
        }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SecondaryButton("CANCEL", onCancel, modifier = Modifier.fillMaxWidth())
    }
}
