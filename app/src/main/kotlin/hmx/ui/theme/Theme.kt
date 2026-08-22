package hmx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object HmxColors {
    val Background = Color(0xFF0A0C0B)
    val Surface = Color(0xFF111412)
    val SurfaceHigh = Color(0xFF191D1A)
    val Outline = Color(0xFF262C28)
    val Accent = Color(0xFF00E87A)
    val AccentDim = Color(0xFF0E5C38)
    val TextPrimary = Color(0xFFECF2EE)
    val TextSecondary = Color(0xFF9AA69F)
    val Danger = Color(0xFFFF5470)
    val Warning = Color(0xFFFFC24B)
}

private val DarkScheme = darkColorScheme(
    primary = HmxColors.Accent,
    onPrimary = Color(0xFF04150C),
    primaryContainer = HmxColors.AccentDim,
    onPrimaryContainer = HmxColors.TextPrimary,
    secondary = HmxColors.SurfaceHigh,
    onSecondary = HmxColors.TextPrimary,
    background = HmxColors.Background,
    onBackground = HmxColors.TextPrimary,
    surface = HmxColors.Surface,
    onSurface = HmxColors.TextPrimary,
    surfaceVariant = HmxColors.SurfaceHigh,
    onSurfaceVariant = HmxColors.TextSecondary,
    outline = HmxColors.Outline,
    error = HmxColors.Danger,
    onError = Color.White,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00894A),
    onPrimary = Color.White,
    background = Color(0xFFF6F8F6),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE4EBE6),
    onBackground = Color(0xFF101410),
    onSurface = Color(0xFF101410),
    error = Color(0xFFB3274A),
)

private val HmxTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 30.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
)

private val HmxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun HmxTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = HmxTypography,
        shapes = HmxShapes,
        content = content,
    )
}
