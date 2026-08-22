package hmx.mock

import hmx.core.error.AppError
import hmx.domain.logic.ClientMachine
import hmx.domain.logic.ClientState
import hmx.domain.logic.ProviderMachine
import hmx.domain.logic.ProviderState
import hmx.domain.model.ConnectionMode
import hmx.domain.model.Device
import hmx.domain.model.DeviceRole
import hmx.domain.model.DeviceStatus
import hmx.domain.model.EndReason
import hmx.domain.model.NetworkType
import hmx.domain.model.Session
import hmx.domain.model.TrafficStats
import hmx.security.PairingCode
import hmx.security.PairingCodeInfo
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class MockScenario {
    HAPPY_PATH,
    PAIRING_EXPIRED,
    PAIRING_REJECTED,
    VPN_DENIED,
    HANDSHAKE_FAIL,
    PROBE_FAIL,
    PROVIDER_OFFLINE,
}

class MockHmxEngine(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val provider = ProviderMachine(now)
    val client = ClientMachine(now)

    @Volatile
    var scenario: MockScenario = MockScenario.HAPPY_PATH

    private val _sessions = MutableStateFlow(seedSessions())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _devices = MutableStateFlow(seedDevices())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    val thisDeviceName = "HMX Phone B"

    private var statsJob: Job? = null
    private var currentSessionId: String? = null

    fun newPairingCode(): PairingCodeInfo {
        val t = now()
        return PairingCodeInfo(PairingCode.generate(), t, t + PairingCode.TTL_MS)
    }

    // ---------- provider flow ----------

    fun startSharing() {
        scope.launch {
            provider.prepare()
            delay(700)
            provider.startAdvertising(newPairingCode())
            if (scenario == MockScenario.HAPPY_PATH) {
                delay(2500)
                simulateIncomingPairing("Pixel 8", "9F3CA21B")
                delay(2000)
                Unit
            }
        }
    }

    fun simulateIncomingPairing(name: String, fingerprint: String) {
        provider.requestPairing(name, fingerprint)
    }

    fun rejectPeer() {
        provider.rejectPeer()
    }

    fun approvePeer() {
        openSession(peerName = connectedPeerName(), role = DeviceRole.PROVIDER, mode = ConnectionMode.DIRECT)
        provider.approvePeer(connectedPeerName())
        startStatsTicker { stats -> provider.updateStats(stats) }
    }

    fun regenerateCode() {
        if (provider.state.value is ProviderState.Advertising) {
            provider.refreshCode(newPairingCode())
        }
    }

    fun stopSharing() {
        closeActiveSession(EndReason.USER)
        stopStatsTicker()
        provider.stop()
    }

    // ---------- client flow ----------

    fun connectWithCode(codeInput: String?) {
        scope.launch {
            client.scanStarted()
            delay(500)
            when (scenario) {
                MockScenario.PAIRING_EXPIRED -> client.fail(AppError.PAIRING_EXPIRED)
                MockScenario.PROVIDER_OFFLINE -> client.fail(AppError.PROVIDER_OFFLINE)
                else -> client.deviceFound(providerName(), providerFingerprint())
            }
        }
    }

    fun confirmConnect() {
        scope.launch {
            client.beginAuth()
            delay(600)
            when (scenario) {
                MockScenario.PAIRING_REJECTED -> {
                    client.fail(AppError.PAIRING_REJECTED)
                    return@launch
                }
                else -> client.vpnPermissionNeeded()
            }
            delay(400)
            when (scenario) {
                MockScenario.VPN_DENIED -> {
                    client.vpnDenied()
                    return@launch
                }
                else -> client.vpnGranted()
            }
            client.tunnelStarting()
            delay(900)
            when (scenario) {
                MockScenario.HANDSHAKE_FAIL, MockScenario.PROVIDER_OFFLINE -> {
                    client.fail(
                        if (scenario == MockScenario.HANDSHAKE_FAIL) AppError.HANDSHAKE_FAILED else AppError.PROVIDER_OFFLINE,
                    )
                    return@launch
                }
                else -> client.probeStarted()
            }
            delay(800)
            when (scenario) {
                MockScenario.PROBE_FAIL -> {
                    client.fail(AppError.PROBE_FAILED)
                    return@launch
                }
                else -> {
                    openSession(providerName(), DeviceRole.USER, ConnectionMode.DIRECT)
                    client.connected(providerName())
                    startStatsTicker { stats -> client.updateStats(stats) }
                }
            }
        }
    }

    fun grantVpnPermission() {
        client.vpnGranted()
    }

    fun denyVpnPermission() {
        client.vpnDenied()
    }

    fun disconnect() {
        closeActiveSession(EndReason.USER)
        stopStatsTicker()
        client.disconnect()
    }

    fun acknowledgeError() {
        when (client.state.value) {
            is ClientState.Failed -> client.reset()
            else -> Unit
        }
        if (provider.state.value is ProviderState.Failed) provider.reset()
    }

    fun resetAll() {
        stopStatsTicker()
        closeActiveSession(EndReason.ERROR, silent = true)
        provider.stop()
        provider.reset()
        client.reset()
        scenario = MockScenario.HAPPY_PATH
    }

    fun forceNetworkChange() {
        if (client.state.value is ClientState.Connected) {
            client.reconnect("Wi-Fi lost")
            scope.launch {
                delay(2200)
                client.recovered()
            }
        }
    }

    // ---------- helpers ----------

    private fun providerName() = "HMX Phone A"
    private fun providerFingerprint() = "7A11C0DE"
    private fun connectedPeerName() = "Pixel 8"

    private fun startStatsTicker(update: (TrafficStats) -> Unit) {
        stopStatsTicker()
        statsJob = scope.launch {
            var rx = 0L
            var tx = 0L
            while (true) {
                delay(1000)
                rx += (180_000..420_000).random().toLong()
                tx += (20_000..90_000).random().toLong()
                update(TrafficStats(rx, tx))
                tickOpenSession(rx, tx)
            }
        }
    }

    private fun stopStatsTicker() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun openSession(peerName: String, role: DeviceRole, mode: ConnectionMode): Session {
        val s = Session(id = UUID.randomUUID().toString(), peerName = peerName, role = role, startedAtMs = now())
        currentSessionId = s.id
        _sessions.value = listOf(s) + _sessions.value
        return s
    }

    private fun tickOpenSession(rx: Long, tx: Long) {
        val id = currentSessionId ?: return
        _sessions.value = _sessions.value.map {
            if (it.id == id && it.isActive) it.copy(bytesUp = tx, bytesDown = rx) else it
        }
    }

    private fun closeActiveSession(reason: EndReason, silent: Boolean = false) {
        val id = currentSessionId ?: return
        currentSessionId = null
        _sessions.value = _sessions.value.map {
            if (it.id == id && it.isActive) it.copy(endedAtMs = now(), endReason = reason) else it
        }
    }

    private fun seedDevices(): List<Device> = listOf(
        Device(
            id = "self", name = "HMX Phone B", role = DeviceRole.USER,
            createdAtMs = now() - 86_400_000L * 3, lastSeenAtMs = now(),
            status = DeviceStatus.ONLINE, networkType = NetworkType.WIFI,
        ),
    )

    private fun seedSessions(): List<Session> {
        val t = now()
        return listOf(
            Session("s3", "Pixel 8", DeviceRole.PROVIDER, t - 3_600_000L * 26, t - 3_600_000L * 25, 412_000_000, 38_000_000, ConnectionMode.DIRECT, EndReason.USER),
            Session("s2", "Pixel 8", DeviceRole.USER, t - 3_600_000L * 50, t - 3_600_000L * 49, 96_000_000, 610_000_000, ConnectionMode.DIRECT, EndReason.USER),
            Session("s1", "Pixel 8", DeviceRole.PROVIDER, t - 3_600_000L * 74, t - 3_600_000L * 73, 1_240_000_000, 88_000_000, ConnectionMode.RELAY, EndReason.NETWORK),
        )
    }
}
