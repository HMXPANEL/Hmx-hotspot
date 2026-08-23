package hmx.di

import android.content.Context
import android.content.Intent
import hmx.control.RealEngine
import hmx.data.control.ControlClient
import hmx.data.local.SettingsRepository
import hmx.security.IdentityManager
import hmx.security.KeystoreVault
import hmx.security.MemoryVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(context: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository = SettingsRepository(context)

    private val vault = runCatching { KeystoreVault(context) }.getOrElse { MemoryVault() }

    val controlClient = ControlClient()

    val identityManager = IdentityManager(context, controlClient)

    val engine = RealEngine(
        scope = appScope,
        identityManager = identityManager,
        controlClient = controlClient,
        manualEndpoint = { settingsRepository.cachedManualEndpoint },
        hardLimitBytes = {
            kotlinx.coroutines.flow.firstOrNull<hmx.domain.model.HmxSettings>(
                settingsRepository.settings
            )?.takeIf { it.hardLimitEnabled }?.dailyLimitBytes ?: 0L
        },
    )

    fun initEngine(context: Context) {
        engine.contextProvider = { context.applicationContext }
        engine.registerNetworkMonitoring()
        hmx.vpn.TunnelController.init(context)
    }
}
