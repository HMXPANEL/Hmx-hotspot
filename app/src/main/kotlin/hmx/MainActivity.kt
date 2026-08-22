package hmx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import hmx.navigation.HmxApp
import hmx.ui.theme.HmxTheme

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Phase 4: if an FGS start was blocked while backgrounded, retry now in foreground.
        (application as hmx.HmxApplication).container.engine.resyncForegroundService(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HmxTheme {
                HmxApp()
            }
        }
    }
}
