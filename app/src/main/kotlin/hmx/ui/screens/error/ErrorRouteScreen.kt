package hmx.ui.screens.error

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hmx.core.error.AppError
import hmx.di.rememberEngine
import hmx.ui.components.ErrorStateView

@Composable
fun ErrorRouteScreen(errorKey: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    val engine = rememberEngine()
    val error = AppError.entries.firstOrNull { it.name == errorKey } ?: AppError.UNKNOWN

    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        ErrorStateView(
            error = error,
            onAction = {
                engine.acknowledgeError()
                onAction()
            },
            onDismiss = {
                engine.acknowledgeError()
                onDismiss()
            },
        )
    }
}
