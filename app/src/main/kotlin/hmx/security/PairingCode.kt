package hmx.security

import java.security.SecureRandom

object PairingCode {

    const val PREFIX = "HMX"
    const val SUFFIX_LEN = 3
    const val LENGTH = PREFIX.length + SUFFIX_LEN // 6 alphanumeric chars; display adds '-' -> HMX-XXX
    const val TTL_MS = 5 * 60 * 1000L
    const val MAX_ATTEMPTS = 5

    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    // ponytail: 31^3 (~29k) entropy ceiling is accepted per product spec; server-side
    // attempt limiting / rate cap on hmx_claim_session is the upgrade path if needed.
    fun generate(random: SecureRandom = SecureRandom()): String {
        val sb = StringBuilder(SUFFIX_LEN)
        repeat(SUFFIX_LEN) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        return PREFIX + sb
    }

    fun normalize(input: String): String {
        val mapped = input.uppercase()
            .replace('O', '0')
            .replace('I', '1')
            .replace('L', '1')
            .filter { it.isLetterOrDigit() }
        return mapped
    }

    fun isValid(code: String): Boolean {
        if (code.length != LENGTH) return false
        if (!code.startsWith(PREFIX)) return false
        return code.drop(PREFIX.length).all { ALPHABET.indexOf(it) >= 0 }
    }

    fun format(code: String): String =
        if (code.length == LENGTH && code.startsWith(PREFIX)) PREFIX + "-" + code.drop(PREFIX.length) else code

    fun isExpired(createdAtMs: Long, nowMs: Long): Boolean = nowMs - createdAtMs >= TTL_MS
}
