package hmx.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import hmx.domain.model.HmxSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map


val MANUAL_ENDPOINT = stringPreferencesKey("manual_endpoint")

private val Context.hmxDataStore by preferencesDataStore(name = "hmx_settings")

class SettingsRepository(private val context: Context) {

    @Volatile var cachedManualEndpoint: String? = null
        private set

    private object Keys {
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val DAILY_LIMIT = longPreferencesKey("daily_limit_bytes")
        val WARNING_PCT = intPreferencesKey("warning_threshold_pct")
        val HARD_LIMIT = booleanPreferencesKey("hard_limit_enabled")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val BLOCK_QUIC = booleanPreferencesKey("block_quic_443")
        val DESIRED_ROLE = stringPreferencesKey("desired_role")
        val MANUAL_ENDPOINT = stringPreferencesKey("manual_endpoint")
    }

    val settings: Flow<HmxSettings> = context.hmxDataStore.data.map { prefs ->
        HmxSettings(
            deviceName = prefs[Keys.DEVICE_NAME] ?: "HMX Phone",
            autoConnect = prefs[Keys.AUTO_CONNECT] ?: false,
            dailyLimitBytes = prefs[Keys.DAILY_LIMIT] ?: 5L * 1024 * 1024 * 1024,
            warningThresholdPct = prefs[Keys.WARNING_PCT] ?: 90,
            hardLimitEnabled = prefs[Keys.HARD_LIMIT] ?: true,
            blockQuic443 = prefs[Keys.BLOCK_QUIC] ?: false,
        )
    }

    suspend fun setDeviceName(value: String) = edit { it[Keys.DEVICE_NAME] = value }
    suspend fun setAutoConnect(value: Boolean) = edit { it[Keys.AUTO_CONNECT] = value }
    suspend fun setDailyLimitBytes(value: Long) = edit { it[Keys.DAILY_LIMIT] = value }
    suspend fun setWarningThresholdPct(value: Int) = edit { it[Keys.WARNING_PCT] = value }
    suspend fun setHardLimitEnabled(value: Boolean) = edit { it[Keys.HARD_LIMIT] = value }
    suspend fun setBlockQuic(value: Boolean) = edit { it[Keys.BLOCK_QUIC] = value }
    suspend fun setDesiredRole(role: String?) = edit { prefs ->
        if (role == null) prefs.remove(Keys.DESIRED_ROLE) else prefs[Keys.DESIRED_ROLE] = role
    }

    suspend fun loadManualEndpoint(): String? =
        context.hmxDataStore.data.map { prefs -> prefs[MANUAL_ENDPOINT] }.first()

    suspend fun setManualEndpoint(value: String?) {
        context.hmxDataStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(MANUAL_ENDPOINT) else prefs[MANUAL_ENDPOINT] = value
        }
        cachedManualEndpoint = value?.takeIf { it.isNotBlank() }
    }

    suspend fun setOnboardingDone() {
        context.hmxDataStore.edit { it[Keys.ONBOARDING_DONE] = true }
    }

    suspend fun isOnboardingDone(): Boolean =
        context.hmxDataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }.first()

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.hmxDataStore.edit(block)
    }
}
