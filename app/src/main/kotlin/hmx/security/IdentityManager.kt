package hmx.security

import android.content.Context
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import hmx.data.control.ControlClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.SecureRandom

data class Identity(
    val deviceId: String,
    val name: String,
    val wgPrivateKeyHex: String,
    val wgPublicKeyBase64: String,
    val secret: String,
)

class IdentityManager(private val context: Context, private val client: ControlClient) {

    private val vault: SecretVault = runCatching { KeystoreVault(context) }.getOrElse { MemoryVault() }

    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    suspend fun ensure(nameOverride: String? = null): Identity = withContext(Dispatchers.IO) {
        _identity.value?.let { return@withContext it }

        val storedId = vault.get("device_id")?.decodeToString()
        val storedSecret = vault.get("device_secret")?.decodeToString()
        val storedPub = vault.get("wg_public_key_b64")?.decodeToString()
        val storedPriv = vault.get("wg_private_key_hex")?.decodeToString()

        if (storedId != null && storedSecret != null && storedPriv != null && storedPub != null) {
            val id = Identity(
                deviceId = storedId,
                name = vault.get("device_name")?.decodeToString() ?: "HMX device",
                wgPrivateKeyHex = storedPriv,
                wgPublicKeyBase64 = storedPub,
                secret = storedSecret,
            )
            client.credentialsProvider = { id.deviceId to id.secret }
            _identity.value = id
            return@withContext id
        }

        val secret = randomHex(32)
        // Real WireGuard keypair via the gateway-native AAR (wireguard-go keygen).
        val kp = hmxgateway.Hmxgateway.generateKeyPair()!!
        val privHex = kp.privHex
        val pubB64 = java.util.Base64.getEncoder()
            .encodeToString(kp.pubHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val name = nameOverride ?: "HMX ${android.os.Build.MODEL}".take(40)

        val deviceId = client.register(name, pubB64, secret)
        vault.put("device_id", deviceId.encodeToByteArray())
        vault.put("device_secret", secret.encodeToByteArray())
        vault.put("wg_private_key_hex", privHex.encodeToByteArray())
        vault.put("wg_public_key_b64", pubB64.encodeToByteArray())
        vault.put("device_name", name.encodeToByteArray())

        val id = Identity(deviceId, name, privHex, pubB64, secret)
        client.credentialsProvider = { id.deviceId to id.secret }
        _identity.value = id
        id
    }

    fun fingerprintPrefix(): String? =
        _identity.value?.wgPublicKeyBase64?.take(8)

    companion object {
        fun randomHex(bytes: Int): String {
            val raw = ByteArray(bytes)
            SecureRandom().nextBytes(raw)
            return raw.joinToString("") { "%02x".format(it) }
        }
    }
}
