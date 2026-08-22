package hmx.ui.screens.activity

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hmx.di.rememberEngine
import hmx.domain.logic.DataLimits
import hmx.ui.components.EmptyStateView
import hmx.ui.components.StatRow

@Composable
fun ActivityScreen() {
    val engine = rememberEngine()
    val sessions by engine.sessions.collectAsState()
    LaunchedEffect(Unit) { engine.refreshLists() }

    val active = sessions.firstOrNull { it.isActive }
    val past = sessions.filter { !it.isActive }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Activity", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        Text("Current session", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        if (active == null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp)) {
                    Text("No active session", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Start sharing or connect to a provider to see live traffic here.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp)) {
                    StatRow("Peer", active.peerName)
                    StatRow("Download", DataLimits.formatBytes(active.bytesDown))
                    StatRow("Upload", DataLimits.formatBytes(active.bytesUp))
                    StatRow("Total", DataLimits.formatBytes(active.bytesDown + active.bytesUp))
                    StatRow("Mode", active.mode.name.lowercase())
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Session history", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (past.isEmpty()) {
            EmptyStateView(
                icon = "≡",
                title = "No sessions yet",
                body = "Past connections will be listed here with data usage and duration.",
            )
        } else {
            past.forEach { s ->
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(14.dp).fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                            Text("${s.role.name.lowercase().replaceFirstChar { it.uppercase() }} · ${s.peerName}", style = MaterialTheme.typography.titleMedium)
                            Text(DataLimits.formatDuration((s.endedAtMs ?: 0) - s.startedAtMs), style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            "${DataLimits.formatBytes(s.bytesDown)} down · ${DataLimits.formatBytes(s.bytesUp)} up · ${s.mode.name.lowercase()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
