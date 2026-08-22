package hmx.domain.logic

import hmx.core.error.AppError
import hmx.domain.model.ConnectionMode
import hmx.domain.model.TrafficStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ClientState {
    data object Idle : ClientState
    data object Scanning : ClientState
    data class DeviceFound(val name: String, val fingerprint: String) : ClientState
    data object Authenticating : ClientState
    data object VpnPermissionRequired : ClientState
    data object StartingTunnel : ClientState
    data object Probing : ClientState
    data class Connected(
        val providerName: String,
        val mode: ConnectionMode,
        val stats: TrafficStats = TrafficStats(),
        val sinceMs: Long = 0L,
    ) : ClientState

    data class Reconnecting(val reason: String, val providerName: String) : ClientState
    data object Disconnecting : ClientState
    data class Failed(val error: AppError) : ClientState
}

class ClientMachine(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<ClientState>(ClientState.Idle)
    val state: StateFlow<ClientState> = _state.asStateFlow()

    private var lastConnected: ClientState.Connected? = null

    fun scanStarted() {
        if (_state.value is ClientState.Idle || _state.value is ClientState.Failed) {
            _state.value = ClientState.Scanning
        }
    }

    fun deviceFound(name: String, fingerprint: String) {
        if (_state.value is ClientState.Scanning) {
            _state.value = ClientState.DeviceFound(name, fingerprint)
        }
    }

    fun beginAuth() {
        if (_state.value is ClientState.DeviceFound) {
            _state.value = ClientState.Authenticating
        }
    }

    fun vpnPermissionNeeded() {
        when (_state.value) {
            is ClientState.Authenticating ->
                _state.value = ClientState.VpnPermissionRequired
            else -> Unit
        }
    }

    fun vpnGranted() {
        if (_state.value is ClientState.VpnPermissionRequired) {
            _state.value = ClientState.StartingTunnel
        }
    }

    fun vpnDenied() {
        if (_state.value is ClientState.VpnPermissionRequired) {
            _state.value = ClientState.Failed(AppError.VPN_PERMISSION_DENIED)
        }
    }

    fun tunnelStarting() {
        if (_state.value is ClientState.VpnPermissionRequired ||
            _state.value is ClientState.Authenticating
        ) {
            _state.value = ClientState.StartingTunnel
        }
    }

    fun probeStarted() {
        if (_state.value is ClientState.StartingTunnel) {
            _state.value = ClientState.Probing
        }
    }

    fun connected(providerName: String, mode: ConnectionMode = ConnectionMode.DIRECT) {
        when (_state.value) {
            is ClientState.Probing -> {
                val next = ClientState.Connected(providerName, mode, TrafficStats(), now())
                lastConnected = next
                _state.value = next
            }
            else -> Unit
        }
    }

    fun updateStats(stats: TrafficStats) {
        val current = _state.value
        if (current is ClientState.Connected && current.stats != stats) {
            val next = current.copy(stats = stats)
            lastConnected = next
            _state.value = next
        }
    }

    fun reconnect(reason: String) {
        val current = _state.value
        if (current is ClientState.Connected) {
            _state.value = ClientState.Reconnecting(reason, current.providerName)
        }
    }

    fun recovered() {
        val current = _state.value
        if (current is ClientState.Reconnecting && lastConnected != null) {
            _state.value = lastConnected!!.copy(sinceMs = now())
        } else if (current is ClientState.Reconnecting) {
            _state.value = ClientState.Failed(AppError.PROVIDER_OFFLINE)
        }
    }

    fun disconnect() {
        val current = _state.value
        if (current is ClientState.Connected || current is ClientState.Reconnecting) {
            _state.value = ClientState.Disconnecting
            _state.value = ClientState.Idle
        }
    }

    fun fail(error: AppError) {
        if (_state.value !is ClientState.Idle) {
            _state.value = ClientState.Failed(error)
        }
    }

    fun reset() {
        if (_state.value is ClientState.Failed) {
            _state.value = ClientState.Idle
        }
    }
}
