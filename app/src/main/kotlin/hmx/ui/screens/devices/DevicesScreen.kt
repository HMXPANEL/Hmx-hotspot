package hmx.ui.screens.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hmx.di.rememberEngine
import hmx.domain.model.DeviceStatus
import hmx.ui.components.EmptyStateView

@Composable
fun DevicesScreen(openDetails: (String) -> Unit) {
    val engine = rememberEngine()
    val devices by engine.devices.collectAsState()

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Devices", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text("This device", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp)) {
                Text("HMX Phone B", style = MaterialTheme.typography.titleMedium)
                Text("this device • online", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Paired devices", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))

        if (devices.size <= 1) {
            EmptyStateView(
                icon = "⌁",
                title = "No paired devices",
                body = "When someone pairs with this device, it shows up here with full control to rename or revoke.",
            )
        } else {
            devices.drop(1).forEach { d ->
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        Modifier.padding(16.dp).fillParentWidth().clickable { openDetails(d.id) },
                    ) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        val statusText = when (d.status) {
                            DeviceStatus.ONLINE -> "online"
                            DeviceStatus.OFFLINE -> "offline"
                            DeviceStatus.REVOKED -> "revoked"
                        }
                        Text(statusText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun Modifier.fillParentWidth(): Modifier = this.then(Modifier.fillMaxWidth())
