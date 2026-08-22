package hmx.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hmx.di.rememberEngine
import hmx.domain.logic.ClientState
import hmx.domain.logic.DataLimits
import hmx.domain.logic.LimitStatus
import hmx.domain.logic.ProviderState
import hmx.ui.components.LiveState
import hmx.ui.components.MetricCard
import hmx.ui.components.SectionHeader
import hmx.ui.components.StatusPill

@Composable
fun HomeScreen(
    openProvider: () -> Unit,
    openUser: () -> Unit,
) {
    val engine = rememberEngine()
    val provider by engine.provider.state.collectAsState()
    val client by engine.client.state.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("HMX", style = MaterialTheme.typography.headlineLarge)
        }

        Spacer(Modifier.height(8.dp))
        when {
            provider is ProviderState.SharingConnected -> StatusPill(LiveState.ONLINE)
            client is ClientState.Connected -> StatusPill(LiveState.ONLINE)
            else -> StatusPill(LiveState.OFFLINE)
        }

        Spacer(Modifier.height(18.dp))
        RoleCard(
            title = "SHARE MY INTERNET",
            body = "Let another device use my internet connection.",
            statusLine = when (val p = provider) {
                is ProviderState.SharingConnected -> "Sharing with ${p.peerName.ifEmpty { "device" }}"
                is ProviderState.Advertising -> "Waiting for a device…"
                else -> null
            },
            onClick = openProvider,
        )
        Spacer(Modifier.height(14.dp))
        RoleCard(
            title = "USE INTERNET",
            body = "Connect to someone's internet remotely.",
            statusLine = when (val c = client) {
                is ClientState.Connected -> "Connected to ${c.providerName}"
                is ClientState.Reconnecting -> "Reconnecting…"
                else -> null
            },
            onClick = openUser,
        )
    }

}

@Composable
private fun RoleCard(title: String, body: String, statusLine: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(20.dp).fillMaxWidth().clickable(onClick = onClick)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (statusLine != null) {
                Spacer(Modifier.height(8.dp))
                Text(statusLine, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
