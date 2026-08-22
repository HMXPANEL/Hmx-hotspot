package hmx.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretVault {
    fun put(key: String, plain: ByteArray)
    fun get(key: String): ByteArray?
    fun delete(key: String)
}

class MemoryVault : SecretVault {
    private val map = HashMap<String, ByteArray>()
    private val lock = Any()

    override fun put(key: String, plain: ByteArray) {
        synchronized(lock) { map[key] = plain.copyOf() }
    }

    override fun get(key: String): ByteArray? =
        synchronized(lock) { map[key]?.copyOf() }

    override fun delete(key: String) {
        synchronized(lock) { map.remove(key) }
    }
}

/**
 * Envelope storage: a single AES-256-GCM master key lives in AndroidKeyStore;
 * individual secrets are sealed with it and stored as iv||ciphertext files in
 * app-private storage. Private keys never appear on disk unencrypted and are
 * never logged (see hmx.core.logging.HmxLog).
 */
class KeystoreVault(private val context: Context) : SecretVault {

    private val dir: File get() = File(context.filesDir, "vault").apply { mkdirs() }
    private val lock = Any()

    override fun put(key: String, plain: ByteArray) {
        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey())
            val ct = cipher.doFinal(plain)
            File(dir, key).writeBytes(cipher.iv + ct)
        }
    }

    override fun get(key: String): ByteArray? {
        synchronized(lock) {
            val file = File(dir, key)
            if (!file.exists()) return null
            val blob = file.readBytes()
            if (blob.size <= IV_LEN) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                masterKey(),
                GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN),
            )
            return cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
        }
    }

    override fun delete(key: String) {
        synchronized(lock) { File(dir, key).delete() }
    }

    fun wipe() {
        synchronized(lock) { dir.deleteRecursively() }
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey().also { ks.setKeyEntry(MASTER_ALIAS, it, null, null) }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val MASTER_ALIAS = "hmx-master"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}
