package hmx.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import hmx.HmxApplication
import hmx.mock.MockHmxEngine

@Composable
fun rememberEngine(): MockHmxEngine {
    val context = LocalContext.current
    return remember { (context.applicationContext as HmxApplication).container.engine }
}
