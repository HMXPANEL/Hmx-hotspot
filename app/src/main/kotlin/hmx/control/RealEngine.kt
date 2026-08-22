package hmx.control

import hmx.core.error.AppError
import hmx.core.logging.HmxLog
import hmx.data.control.ControlClient
import hmx.data.control.mapRpcError
import hmx.data.control.str
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
import hmx.gateway.GatewayEngineHost
import hmx.security.IdentityManager
import hmx.security.PairingCode
import hmx.security.PairingCodeInfo
import hmx.vpn.TunnelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

class RealEngine(
    private val scope: CoroutineScope,
    val identityManager: IdentityManager,
    private val controlClient: ControlClient,
    private val manualEndpoint: () -> String?,
) {
    val provider = ProviderMachine()
    val client = ClientMachine()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    var thisDeviceName: String = "HMX device"
        private set

    private var currentPairingSessionId: String? = null
    private var currentPairingCode: PairingCodeInfo? = null
    private var pendingRequestId: String? = null
    private var pendingRequesterPubKey: String? = null
    private var pendingRequesterName: String? = null
    private var claimedSessionId: String? = null
    private var approved: Map<String, String>? = null
    private var openTunnelSessionId: String? = null
    private var pollJob: Job? = null
    private var statsJob: Job? = null

    fun newPairingCode(): PairingCodeInfo {
        val t = System.currentTimeMillis()
        return PairingCodeInfo(PairingCode.generate(), t, t + PairingCode.TTL_MS)
    }

    fun startSharing() {
        scope.launch {
            try {
                withTimeout(45_000) { identityManager.ensure() }
            } catch (e: Exception) {
                HmxLog.e("Share", e) { "identity/init failed" }
                provider.fail(if (e is kotlinx.coroutines.TimeoutCancellationException) AppError.TIMEOUT else mapRpcError(e))
                return@launch
            }
            thisDeviceName = identityManager.identity.value?.name ?: thisDeviceName
            provider.prepare()
            val code = newPairingCode()
            try {
                val row = controlClient.rpc("hmx_create_pairing_session",
                    mapOf("p_code_hash" to pairingHash(code.code), "p_ttl_seconds" to "300"))
                currentPairingSessionId = row.str("id")
                currentPairingCode = code
                provider.startAdvertising(code)
                startRequestPolling()
            } catch (e: Exception) { provider.fail(mapRpcError(e)) }
        }
    }

    private fun startRequestPolling() {
        pollJob?.cancel(); pollJob = null
        pollJob = scope.launch {
            while (provider.state.value is ProviderState.Advertising) {
                try {
                    val rows = controlClient.rpcArray("hmx_pending_requests")
                    if (rows.isNotEmpty()) {
                        val r = rows.first()
                        pendingRequestId = r.str("request_id")
                        pendingRequesterPubKey = r.str("requester_pubkey")
                        pendingRequesterName = r.str("requester_name")
                        provider.requestPairing(
                            r.str("requester_name") ?: "unknown",
                            r.str("requester_pubkey_prefix") ?: "????????",
                        )
                        return@launch
                    }
                } catch (_: Exception) { }
                delay(2500)
            }
        }
    }

    fun approvePeer() {
        val reqId = pendingRequestId ?: return
        scope.launch {
            try {
                val res = withTimeout(30_000) {
                    controlClient.rpc("hmx_respond_request",
                        mapOf("p_request_id" to reqId, "p_approve" to true))
                }
                if (res.str("status") != "approved") { provider.rejectPeer(); return@launch }
                val octet = res.str("net_octet")
                val peerId = res.str("peer_id")!!
                val name = res.str("provider_name") ?: ""
                val sess = controlClient.rpc("hmx_start_tunnel_session",
                    mapOf("p_peer_id" to peerId, "p_mode" to "direct"))
                openTunnelSessionId = sess.str("id")

                val priv = identityManager.ensure().wgPrivateKeyHex
                val port = 40000 + (0..20000).random()
                GatewayEngineHost.start(priv, port, pendingRequesterPubKey ?: "", "10.66.$octet.2")
                    .onFailure { provider.fail(AppError.TUNNEL_FAILED) }

                provider.approvePeer(pendingRequesterName ?: "device")
                startGatewayStats()
                pollJob?.cancel()
            } catch (e: Exception) { provider.fail(mapRpcError(e)) }
        }
    }

    fun rejectPeer() {
        val reqId = pendingRequestId ?: return
        scope.launch {
            try { controlClient.rpc("hmx_respond_request", mapOf("p_request_id" to reqId, "p_approve" to "false")) } catch (_: Exception) {}
            provider.rejectPeer()
            startRequestPolling()
        }
    }

    fun regenerateCode() {
        val old = currentPairingSessionId
        scope.launch {
            try { old?.let { controlClient.rpc("hmx_cancel_session", mapOf("p_session_id" to it)) } } catch (_: Exception) {}
            val code = newPairingCode()
            try {
                val row = controlClient.rpc("hmx_create_pairing_session",
                    mapOf("p_code_hash" to pairingHash(code.code), "p_ttl_seconds" to "300"))
                currentPairingSessionId = row.str("id")
                currentPairingCode = code
                provider.refreshCode(code)
                startRequestPolling()
            } catch (e: Exception) { provider.fail(mapRpcError(e)) }
        }
    }

    fun stopSharing() {
        pollJob?.cancel(); statsJob?.cancel()
        GatewayEngineHost.stop()
        scope.launch {
            openTunnelSessionId?.let { runCatching { controlClient.rpc("hmx_end_tunnel_session", mapOf("p_session_id" to it, "p_reason" to "user")) } }
            currentPairingSessionId?.let { runCatching { controlClient.rpc("hmx_cancel_session", mapOf("p_session_id" to it)) } }
            provider.stop()
        }
    }

    // ---------- user side ----------
    fun connectWithCode(codeInput: String?) {
        scope.launch {
            try { identityManager.ensure() } catch (e: Exception) { client.fail(AppError.NO_INTERNET); return@launch }
            client.scanStarted()
            delay(300)
            try {
                val res = controlClient.rpc("hmx_claim_session", mapOf("p_code_hash" to pairingHash(codeInput ?: "")))
                claimedSessionId = res.str("request_id")
                client.deviceFound(res.str("provider_name") ?: "?", res.str("provider_pubkey_prefix") ?: "????????")
                startStatusPolling()
            } catch (e: Exception) { client.fail(mapRpcError(e)) }
        }
    }

    private fun startStatusPolling() {
        scope.launch {
            val sid = claimedSessionId ?: return@launch
            var ticks = 0
            while (client.state.value is ClientState.Scanning ||
                   client.state.value is ClientState.DeviceFound) {
                delay(2000); ticks++
                if (ticks > 150) { client.fail(AppError.TIMEOUT); return@launch }
                try {
                    val st = controlClient.rpc("hmx_request_status", mapOf("p_session_id" to sid))
                    when (st.str("status")) {
                        "pending" -> continue
                        "approved" -> {
                            approved = st.toMap().mapValues { it.value.toString() }
                            client.vpnPermissionNeeded()
                            return@launch
                        }
                        "rejected" -> { client.fail(AppError.PAIRING_REJECTED); return@launch }
                    }
                } catch (e: Exception) { client.fail(mapRpcError(e)); return@launch }
            }
        }
    }

    fun grantVpnPermission() { client.vpnGranted() }
    fun denyVpnPermission() { client.vpnDenied() }

    fun confirmConnect() {
        scope.launch {
            val ap = approved ?: run { client.fail(AppError.UNKNOWN); return@launch }
            client.tunnelStarting()
            TunnelController.init(context())
            val endpoint = manualEndpoint()?.takeIf { it.isNotBlank() }
            val cfg = hmx.vpn.WgPeerConfig(
                privateKeyHex = identityManager.ensure().wgPrivateKeyHex,
                peerPublicKeyBase64 = ap["provider_pubkey"]!!,
                ownInnerIp = ap["user_inner_ip"]!!,
                peerInnerIp = ap["provider_inner_ip"]!!,
                endpoint = endpoint,
            )
            val up = TunnelController.up(context(), cfg)
            if (up.isFailure) {
                client.fail(if (endpoint == null) AppError.HANDSHAKE_FAILED else AppError.TUNNEL_FAILED)
                return@launch
            }
            client.probeStarted()
            val hs = awaitHandshake(20_000)
            if (hs == null) {
                client.fail(AppError.HANDSHAKE_FAILED)
                return@launch
            }
            try {
                val s = controlClient.rpc("hmx_start_tunnel_session", mapOf("p_peer_id" to ap["peer_id"]!!, "p_mode" to "direct"))
                openTunnelSessionId = s.str("id")
            } catch (_: Exception) { }
            client.connected(ap["provider_name"] ?: "provider", ConnectionMode.DIRECT)
            startClientStats()
        }
    }

    private suspend fun awaitHandshake(timeoutMs: Long): Long? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            TunnelController.lastHandshakeMs()?.let { return it }
            delay(500)
        }
        return null
    }

    private fun startClientStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (client.state.value is ClientState.Connected || client.state.value is ClientState.Reconnecting) {
                delay(1000)
                TunnelController.rxTxBytes()?.let { (rx, tx) -> client.updateStats(TrafficStats(rx, tx)) }
            }
        }
    }

    fun disconnect() {
        statsJob?.cancel()
        scope.launch {
            TunnelController.down()
            openTunnelSessionId?.let { runCatching { controlClient.rpc("hmx_end_tunnel_session", mapOf("p_session_id" to it, "p_reason" to "user")) } }
            openTunnelSessionId = null
            client.disconnect()
        }
    }

    fun acknowledgeError() {
        if (client.state.value is ClientState.Failed) client.reset()
        if (provider.state.value is ProviderState.Failed) provider.reset()
    }

    fun refreshLists() {
        scope.launch {
            try {
                _devices.value = controlClient.rpcArray("hmx_my_peers").map { p ->
                    Device(
                        id = p.str("peer_id") ?: UUID.randomUUID().toString(),
                        name = p.str("other_name") ?: "device",
                        role = if (p.str("my_role") == "provider") DeviceRole.PROVIDER else DeviceRole.USER,
                        createdAtMs = 0L, lastSeenAtMs = 0L,
                        status = if (p.str("status") == "active") DeviceStatus.ONLINE else DeviceStatus.REVOKED,
                        networkType = NetworkType.UNKNOWN,
                    )
                }
                _sessions.value = controlClient.rpcArray("hmx_list_sessions").map { s ->
                    Session(
                        id = s.str("id") ?: "", peerName = s.str("other_name") ?: "",
                        role = if (s.str("my_role") == "provider") DeviceRole.PROVIDER else DeviceRole.USER,
                        startedAtMs = parseTs(s.str("started_at")),
                        endedAtMs = s.str("ended_at")?.let(::parseTs),
                        bytesUp = 0, bytesDown = 0,
                        mode = ConnectionMode.valueOf((s.str("mode") ?: "direct").uppercase()),
                        endReason = s.str("end_reason")?.let { runCatching { EndReason.valueOf(it.uppercase()) }.getOrNull() },
                    )
                }
            } catch (_: Exception) { }
        }
    }

    private fun parseTs(iso: String?): Long = runCatching {
        java.time.Instant.parse(iso).toEpochMilli()
    }.getOrDefault(0L)

    lateinit var contextProvider: () -> android.content.Context

    private fun context(): android.content.Context = contextProvider()

    private fun pairingHash(code: String): String = hmx.data.control.ControlClient.sha256Hex(code)

    private fun startGatewayStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (provider.state.value is ProviderState.SharingConnected) {
                delay(1000)
                GatewayEngineHost.rxTxBytes()?.let { (rx, tx) -> provider.updateStats(TrafficStats(rx, tx)) }
            }
        }
    }

    fun revokePeerById(peerId: String) {
        scope.launch {
            try { controlClient.rpc("hmx_revoke_peer", mapOf("p_peer_id" to peerId)); refreshLists() } catch (_: Exception) {}
        }
    }
}
