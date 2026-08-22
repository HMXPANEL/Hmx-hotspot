package hmx.domain.model

enum class DeviceRole { PROVIDER, USER }

enum class NetworkType { WIFI, CELLULAR, UNKNOWN }

enum class ConnectionMode { DIRECT, RELAY }

enum class DeviceStatus { ONLINE, OFFLINE, REVOKED }

data class Device(
    val id: String,
    val name: String,
    val role: DeviceRole,
    val createdAtMs: Long,
    val lastSeenAtMs: Long,
    val status: DeviceStatus,
    val networkType: NetworkType,
)

data class TrafficStats(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

enum class EndReason { USER, PEER, NETWORK, LIMIT, ERROR }

data class Session(
    val id: String,
    val peerName: String,
    val role: DeviceRole,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val bytesUp: Long = 0L,
    val bytesDown: Long = 0L,
    val mode: ConnectionMode = ConnectionMode.DIRECT,
    val endReason: EndReason? = null,
) {
    val isActive: Boolean get() = endedAtMs == null
}

data class HmxSettings(
    val deviceName: String = "HMX Phone",
    val autoConnect: Boolean = false,
    val dailyLimitBytes: Long = 5L * 1024 * 1024 * 1024,
    val warningThresholdPct: Int = 90,
    val hardLimitEnabled: Boolean = true,
    val blockQuic443: Boolean = false,
)
