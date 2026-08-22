package hmx.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hmx.core.error.AppError
import hmx.domain.logic.DataLimits
import hmx.domain.logic.LimitStatus
import hmx.ui.theme.HmxColors

enum class LiveState { OFFLINE, CONNECTING, ONLINE, ERROR }

@Composable
fun StatusPill(state: LiveState, modifier: Modifier = Modifier) {
    val (color, label) = when (state) {
        LiveState.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant to "● OFFLINE"
        LiveState.CONNECTING -> MaterialTheme.colorScheme.secondary to "● CONNECTING"
        LiveState.ONLINE -> HmxAccentColor() to "● ONLINE"
        LiveState.ERROR -> MaterialTheme.colorScheme.error to "● ERROR"
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun HmxAccentColor(): Color = MaterialTheme.colorScheme.primary

@Composable
fun StateOrb(state: LiveState, size: Int = 120, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "pulse",
    )
    val base = when (state) {
        LiveState.ONLINE -> HmxAccentColor()
        LiveState.ERROR -> MaterialTheme.colorScheme.error
        LiveState.CONNECTING -> MaterialTheme.colorScheme.secondary
        LiveState.OFFLINE -> MaterialTheme.colorScheme.outline
    }
    Box(modifier = modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size((size * (if (state == LiveState.CONNECTING) pulse else 0.92f)).dp)
                .alpha(if (state == LiveState.OFFLINE) 0.35f else 0.9f)
                .background(base.copy(alpha = 0.16f), CircleShape),
        )
        Box(Modifier.size((size / 2).dp).background(base, CircleShape))
    }
}

@Composable
fun MetricCard(title: String, value: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = if (accent) HmxAccentColor() else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, modifier = modifier.height(52.dp), enabled = enabled) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(48.dp)) {
        Text(text)
    }
}

@Composable
fun EmptyStateView(icon: String, title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            SecondaryButton(actionLabel, onAction)
        }
    }
}

@Composable
fun ErrorStateView(error: AppError, onAction: () -> Unit, onDismiss: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StateOrb(LiveState.ERROR, size = 96)
        Spacer(Modifier.height(18.dp))
        Text(error.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            error.explanation,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(error.actionLabel, onAction, modifier = Modifier.fillMaxWidth())
        if (onDismiss != null) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Back", onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun SkeletonBlock(height: Int, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
    )
}

@Composable
fun CodeDisplay(code: String, modifier: Modifier = Modifier) {
    Text(
        text = code.chunked(4).joinToString("-"),
        style = MaterialTheme.typography.headlineMedium,
        color = HmxAccentColor(),
        modifier = modifier,
    )
}

@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun DataUsageBar(totalBytes: Long, limitBytes: Long, warningPct: Int, modifier: Modifier = Modifier) {
    val pct = DataLimits.usedPct(totalBytes, limitBytes).coerceAtLeast(2)
    val status = DataLimits.evaluate(totalBytes, limitBytes, warningPct)
    val barColor = when (status) {
        is LimitStatus.Exceeded -> MaterialTheme.colorScheme.error
        is LimitStatus.Warning -> HmxColors.Warning
        LimitStatus.Ok -> HmxAccentColor()
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(DataLimits.formatBytes(totalBytes), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${DataLimits.formatBytes(limitBytes)} limit",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.extraSmall),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(pct / 100f)
                    .height(8.dp)
                    .background(barColor, MaterialTheme.shapes.extraSmall),
            )
        }
    }
}
