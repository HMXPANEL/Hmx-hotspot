package hmx.ui.screens.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hmx.ui.components.PrimaryButton
import hmx.ui.components.SecondaryButton
import hmx.ui.components.StateOrb
import hmx.ui.components.LiveState
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(onDone: (Boolean) -> Unit) {
    var appeared by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (appeared) 1f else 0f, tween(650), label = "splash")

    LaunchedEffect(Unit) {
        appeared = true
        delay(1100)
        val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as hmx.HmxApplication
        onDone(app.container.settingsRepository.isOnboardingDone())
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(alpha)) {
            StateOrb(LiveState.OFFLINE, size = 96)
            Spacer(Modifier.height(22.dp))
            Text("HMX", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "REMOTE INTERNET",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Secure • Private • Simple",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Internet that follows you.", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            "Share your internet with a trusted device, even when you're far apart. " +
                "One phone shares. The other connects through a secure tunnel.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Get started", onGetStarted, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun RoleSelectScreen(onProvider: () -> Unit, onUser: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("How do you want to use HMX?", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        RoleCard(
            title = "SHARE MY INTERNET",
            body = "Let another device use my internet connection.",
            onClick = onProvider,
        )
        Spacer(Modifier.height(14.dp))
        RoleCard(
            title = "USE INTERNET",
            body = "Connect to someone's internet remotely.",
            onClick = onUser,
        )
    }
}

@Composable
private fun RoleCard(title: String, body: String, onClick: () -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.padding(22.dp).fillMaxWidth().clickable(onClick = onClick),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
