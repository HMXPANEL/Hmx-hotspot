package hmx.di

import android.content.Context
import hmx.data.local.SettingsRepository
import hmx.mock.MockHmxEngine
import hmx.security.KeystoreVault
import hmx.security.MemoryVault
import hmx.security.SecretVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository = SettingsRepository(context)

    val vault: SecretVault = runCatching { KeystoreVault(context) }
        .getOrElse { MemoryVault() }

    val engine = MockHmxEngine(appScope)
}
