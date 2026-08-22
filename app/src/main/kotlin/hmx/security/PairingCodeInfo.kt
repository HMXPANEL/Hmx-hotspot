package hmx.security

data class PairingCodeInfo(
    val code: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
    fun formatted(): String = PairingCode.format(code)
}

data class DeviceFingerprint(val value: String) {
    fun short(): String = value.take(8)
}
