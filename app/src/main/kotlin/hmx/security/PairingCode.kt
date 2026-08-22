package hmx.security

import java.security.SecureRandom

object PairingCode {

    const val LENGTH = 8
    const val TTL_MS = 5 * 60 * 1000L
    const val MAX_ATTEMPTS = 5

    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun generate(random: SecureRandom = SecureRandom()): String {
        val sb = StringBuilder(LENGTH)
        repeat(LENGTH) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        return sb.toString()
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
        return code.all { ALPHABET.indexOf(it) >= 0 }
    }

    fun format(code: String): String =
        if (code.length == LENGTH) code.substring(0, 4) + "-" + code.substring(4) else code

    fun isExpired(createdAtMs: Long, nowMs: Long): Boolean = nowMs - createdAtMs >= TTL_MS
}
