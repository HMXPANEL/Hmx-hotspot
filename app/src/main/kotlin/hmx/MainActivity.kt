package hmx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import hmx.navigation.HmxApp
import hmx.ui.theme.HmxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HmxTheme {
                HmxApp()
            }
        }
    }
}
