package hmx.core.logging

import android.util.Log

object HmxLog {

    @Volatile
    var verbose: Boolean = false

    private const val PREFIX = "HMX/"

    private val SECRET_ASSIGNMENT = Regex(
        pattern = "(?i)(private[_ -]?key|public[_ -]?key|preshared[_ -]?key|psk|secret|token|password)\\s*[:=]\\s*\\S+",
    )
    private val LONG_HEX = Regex("\\b[0-9a-fA-F]{40,}\\b")
    private val LONG_BASE64 = Regex("\\b[A-Za-z0-9+/=]{48,}\\b")

    fun d(tag: String, message: () -> String) {
        if (verbose) Log.d(PREFIX + tag, safe(message()))
    }

    fun i(tag: String, message: () -> String) {
        Log.i(PREFIX + tag, safe(message()))
    }

    fun w(tag: String, message: () -> String) {
        Log.w(PREFIX + tag, safe(message()))
    }

    fun e(tag: String, throwable: Throwable? = null, message: () -> String) {
        Log.e(PREFIX + tag, safe(message()), throwable)
    }

    fun safe(message: String): String = message
        .replace(SECRET_ASSIGNMENT) { match -> match.value.substringBefore("=").substringBefore(":") + "=[REDACTED]" }
        .replace(LONG_HEX) { it.value.take(6) + "…" + it.value.takeLast(4) }
        .replace(LONG_BASE64) { it.value.take(6) + "…" + it.value.takeLast(4) }
}
