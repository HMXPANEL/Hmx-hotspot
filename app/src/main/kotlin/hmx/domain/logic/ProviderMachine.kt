package hmx.domain.logic

import hmx.core.error.AppError
import hmx.domain.model.TrafficStats
import hmx.security.PairingCodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ProviderState {
    data object Idle : ProviderState
    data object Preparing : ProviderState
    data class Advertising(val code: PairingCodeInfo) : ProviderState
    data class PeerAuthenticating(
        val deviceName: String,
        val fingerprint: String,
        val underlying: PairingCodeInfo,
    ) : ProviderState

    data class SharingConnected(
        val stats: TrafficStats = TrafficStats(),
        val sinceMs: Long = 0L,
        val peerName: String = "",
        val mode: String = "direct",
    ) : ProviderState

    data object Disconnecting : ProviderState
    data class Failed(val error: AppError) : ProviderState
}

class ProviderMachine(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<ProviderState>(ProviderState.Idle)
    val state: StateFlow<ProviderState> = _state.asStateFlow()

    private var lastAdvertising: PairingCodeInfo? = null

    fun prepare() {
        when (_state.value) {
            is ProviderState.Idle, is ProviderState.Failed ->
                _state.value = ProviderState.Preparing
            else -> Unit
        }
    }

    fun startAdvertising(code: PairingCodeInfo) {
        if (_state.value is ProviderState.Preparing) {
            lastAdvertising = code
            _state.value = ProviderState.Advertising(code)
        }
    }

    fun refreshCode(code: PairingCodeInfo) {
        if (_state.value is ProviderState.Advertising) {
            lastAdvertising = code
            _state.value = ProviderState.Advertising(code)
        }
    }

    fun requestPairing(deviceName: String, fingerprint: String) {
        val current = _state.value
        if (current is ProviderState.Advertising) {
            _state.value = ProviderState.PeerAuthenticating(deviceName, fingerprint, current.code)
        }
    }

    fun approvePeer(peerName: String, mode: String = "direct") {
        if (_state.value is ProviderState.PeerAuthenticating) {
            _state.value = ProviderState.SharingConnected(
                stats = TrafficStats(),
                sinceMs = now(),
                peerName = peerName,
                mode = mode,
            )
        }
    }

    fun rejectPeer() {
        val current = _state.value
        if (current is ProviderState.PeerAuthenticating) {
            lastAdvertising?.let { _state.value = ProviderState.Advertising(it) }
        }
    }

    fun updateStats(stats: TrafficStats) {
        val current = _state.value
        if (current is ProviderState.SharingConnected && current.stats != stats) {
            _state.value = current.copy(stats = stats)
        }
    }

    fun stop() {
        when (_state.value) {
            is ProviderState.SharingConnected -> {
                _state.value = ProviderState.Disconnecting
                _state.value = ProviderState.Idle
            }
            is ProviderState.Advertising, is ProviderState.PeerAuthenticating, is ProviderState.Preparing ->
                _state.value = ProviderState.Idle
            else -> Unit
        }
    }

    fun fail(error: AppError) {
        if (_state.value !is ProviderState.Idle) {
            _state.value = ProviderState.Failed(error)
        }
    }

    fun reset() {
        if (_state.value is ProviderState.Failed) {
            _state.value = ProviderState.Idle
        }
    }
}
