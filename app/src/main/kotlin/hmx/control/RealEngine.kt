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
    private val hardLimitBytes: suspend () -> Long = { 0L },
) {
    /** Bounded retry policy for automatic recovery (Phase 5). */
    private val retry = hmx.domain.logic.RetryPolicy(maxAttempts = 3)
    private var lastClientCfg: hmx.vpn.WgPeerConfig? = null
    private var lastProviderName: String = "provider"
    private var netCallback: Any? = null
    val provider = ProviderMachine()
    val client = ClientMachine()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    var thisDeviceName: String = "HMX device"
        private set

    init {
        // Phase 4: keep a foreground service alive exactly while a real session is active.
        scope.launch {
            kotlinx.coroutines.flow.combine(provider.state, client.state) { p, c -> p to c }
                .collect { (p, c) ->
                    val ctx = contextProvider()
                    val pActive = p is hmx.domain.logic.ProviderState.Advertising ||
                        p is hmx.domain.logic.ProviderState.SharingConnected
                    val cActive = c !is hmx.domain.logic.ClientState.Idle &&
                        c !is hmx.domain.logic.ClientState.Failed &&
                        c !is hmx.domain.logic.ClientState.Disconnecting
                    when {
                        pActive -> hmx.service.HmxSessionService.start(ctx, "Sharing internet — waiting/approved")
                        cActive -> hmx.service.HmxSessionService.start(ctx, "Connected session active")
                        else -> hmx.service.HmxSessionService.stop(ctx)
                    }
                }
        }
    }

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
                    mapOf("p_code_hash" to pairingHash(code.code), "p_ttl_seconds" to 300))
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
            var waitedMs = 0L
            while (provider.state.value is ProviderState.Advertising && waitedMs < PairingCode.TTL_MS + 30_000) {
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
                } catch (e: Exception) { HmxLog.w("Pairing") { "poll error: ${e.message}" } }
                delay(2500); waitedMs += 2500
            }
            if (provider.state.value is ProviderState.Advertising && waitedMs >= PairingCode.TTL_MS + 30_000) {
                provider.fail(AppError.TIMEOUT)
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
                val endpoint = "${localWifiIp() ?: ""}:$port".removePrefix(":")
                runCatching { controlClient.rpc("hmx_set_peer_endpoint", mapOf("p_peer_id" to peerId, "p_endpoint" to endpoint)) }
                    .onFailure { HmxLog.w("Pairing") { "endpoint publish failed" } }
                HmxLog.i("Gateway") { "gateway starting on $port for peer $peerId" }
                HmxLog.i("Gateway") { "GATEWAY_START port=$port" }
                val limit = hardLimitBytes()
                cachedDataLimit = limit
                HmxLog.i("Share") { "SESSION_START limit=${limit / (1024 * 1024)}MB" }
                // Phase 6: discover public endpoint BEFORE binding the gateway port.
                val lan = localWifiIp()
                var pub: Pair<String, Int>? = null
                for (srv in hmx.net.StunDefaults.servers) {
                    pub = hmx.net.StunClient.discoverPublicEndpoint(srv, localPort = port)
                    if (pub != null) break
                }
                val cands = buildList {
                    if (lan != null) add(hmx.net.NetworkCandidate(hmx.net.CandidateType.HOST, lan, port))
                    if (pub != null) add(hmx.net.NetworkCandidate(hmx.net.CandidateType.SERVER_REFLEXIVE, pub.first, pub.second))
                }
                runCatching { controlClient.rpc("hmx_set_candidates", mapOf("p_peer_id" to peerId, "p_candidates" to "[${cands.joinToString(",") { c -> c.toJson() }}]")) }
                    .onFailure { HmxLog.w("Traversal") { "candidate publish failed" } }
                HmxLog.i("Gateway") { "GATEWAY_START port=$port srflx=${pub?.let { "${it.first}:${it.second}" } ?: "none"}" }
                val gwOk = GatewayEngineHost.start(
                    priv, port, pendingRequesterPubKey ?: "", "10.66.$octet.2",
                    ownInnerIp = "10.66.$octet.1",
                    hardLimitBytes = limit,
                )
                if (gwOk.isFailure) {
                    HmxLog.e("Gateway") { "GATEWAY_START_FAILED — aborting approval" }
                    runCatching { controlClient.rpc("hmx_end_tunnel_session", mapOf("p_session_id" to sess.str("id").orEmpty(), "p_reason" to "gateway_start_failed")) }
                    openTunnelSessionId = null
                    provider.fail(AppError.TUNNEL_FAILED)
                    return@launch
                }
                HmxLog.i("Gateway") { "GATEWAY_STARTED" }
                provider.approvePeer(pendingRequesterName ?: "device")
                startGatewayStats()
                pollJob?.cancel()
            } catch (e: Exception) { provider.fail(mapRpcError(e)) }
        }
    }

    fun rejectPeer() {
        val reqId = pendingRequestId ?: return
        scope.launch {
            try { controlClient.rpc("hmx_respond_request", mapOf("p_request_id" to reqId, "p_approve" to false)) } catch (_: Exception) {}
            provider.rejectPeer()
            startRequestPolling()
        }
    }

    fun regenerateCode() {
        val old = currentPairingSessionId
        scope.launch {
            try { old?.let { controlClient.rpc("hmx_cancel_session", mapOf("p_session_id" to it)) } } catch (e: Exception) { HmxLog.w("Share") { "old session cancel failed: ${e.message}" } }
            val code = newPairingCode()
            try {
                val row = controlClient.rpc("hmx_create_pairing_session",
                    mapOf("p_code_hash" to pairingHash(code.code), "p_ttl_seconds" to 300))
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

    private companion object {
        const val DIRECT_ATTEMPT_TIMEOUT_MS = 10_000L
        const val RELAY_TIMEOUT_MS = 15_000L
    }

    fun confirmConnect() {
        scope.launch {
            val ap = approved ?: run { client.fail(AppError.UNKNOWN); return@launch }
            client.tunnelStarting()
            TunnelController.init(context())
            val privateKeyHex = identityManager.ensure().wgPrivateKeyHex

            fun baseCfg(endpoint: String?) = hmx.vpn.WgPeerConfig(
                privateKeyHex = privateKeyHex,
                peerPublicKeyBase64 = ap["provider_pubkey"]!!,
                ownInnerIp = ap["user_inner_ip"]!!,
                peerInnerIp = ap["provider_inner_ip"]!!,
                endpoint = endpoint,
            )
            val manual = manualEndpoint()?.takeIf { it.isNotBlank() }

            // Ordered: manual override > HOST > SRFLX > stored endpoint fallback > RELAY.
            val providerCands = hmx.net.CandidateSelector.select(
                hmx.net.NetworkCandidate.fromJsonList(ap["provider_candidates"])
            ).map { it.endpoint() }.toMutableList()
            manual?.let { providerCands.add(0, it) }
            ap["provider_endpoint"]?.takeIf { it.isNotBlank() }?.let { if (it !in providerCands) providerCands.add(it) }

            HmxLog.i("Share") { "DIRECT_CONNECT attempts=${providerCands.size}" }
            for ((idx, ep) in providerCands.withIndex()) {
                HmxLog.i("Share") { "DirectConnecting candidate=$idx" }
                TunnelController.down()
                if (TunnelController.up(context(), baseCfg(ep)).isFailure) continue
                if (awaitHandshake(DIRECT_ATTEMPT_TIMEOUT_MS) == null) { HmxLog.w("WireGuard") { "handshake timeout candidate=$idx" }; continue }
                if (!probeInternet()) { HmxLog.w("Network") { "probe failed candidate=$idx" }; continue }
                finishConnect(ap, baseCfg(ep), hmx.domain.model.ConnectionMode.DIRECT)
                return@launch
            }
            if (manual == null && providerCands.isEmpty()) {
                TunnelController.down()
                if (TunnelController.up(context(), baseCfg(null)).isSuccess &&
                    awaitHandshake(DIRECT_ATTEMPT_TIMEOUT_MS) != null && probeInternet()) {
                    finishConnect(ap, baseCfg(null), hmx.domain.model.ConnectionMode.DIRECT)
                    return@launch
                }
            }

            connectViaRelay(ap, ::baseCfg)
        }
    }

    private suspend fun connectViaRelay(
        ap: Map<String, String>,
        baseCfg: (String?) -> hmx.vpn.WgPeerConfig,
    ) {
        val peerId = ap["peer_id"] ?: run { client.fail(AppError.CONNECTION_LOST); return }
        HmxLog.i("Share") { "RELAY_CONNECTING" }
        try {
            val rel = controlClient.rpc("hmx_allocate_relay", mapOf("p_peer_id" to peerId))
            val token = rel.str("token")
            val provEp = rel.str("provider_endpoint")
            if (token == null || provEp.isNullOrBlank()) throw IllegalStateException("relay allocation incomplete")
            val relayEp = hmx.net.TraversalConfig.relayAddress
            java.net.DatagramSocket().use { sock ->
                sock.soTimeout = 4000
                val dst = java.net.InetSocketAddress(
                    java.net.InetAddress.getByName(relayEp.substringBefore(":")),
                    relayEp.substringAfter(":").toInt(),
                )
                fun roundtrip(payload: ByteArray): Boolean {
                    sock.send(java.net.DatagramPacket(payload, payload.size, dst))
                    val buf = ByteArray(64)
                    val p = java.net.DatagramPacket(buf, buf.size)
                    runCatching { sock.receive(p) }.getOrElse { return false }
                    return String(buf, 0, p.length).startsWith("HMXRELAY_")
                }
                if (!roundtrip("HMXRELAY1 $token".toByteArray())) throw IllegalStateException("relay register failed")
                if (!roundtrip("HMXALLOC $token $provEp".toByteArray())) throw IllegalStateException("relay alloc failed")
            }
            TunnelController.down()
            if (TunnelController.up(context(), baseCfg(relayEp)).isFailure) throw IllegalStateException("tunnel up failed")
            if (awaitHandshake(RELAY_TIMEOUT_MS) == null) throw IllegalStateException("handshake via relay timeout")
            if (!probeInternet()) throw IllegalStateException("probe via relay failed")
            HmxLog.i("Share") { "RELAY_CONNECTED" }
            finishConnect(ap, baseCfg(relayEp), hmx.domain.model.ConnectionMode.RELAY)
        } catch (e: Exception) {
            HmxLog.w("Share") { "relay failed: ${e.message}" }
            client.fail(AppError.PROVIDER_OFFLINE)
        }
    }

    private suspend fun finishConnect(
        ap: Map<String, String>,
        cfg: hmx.vpn.WgPeerConfig,
        mode: hmx.domain.model.ConnectionMode,
    ) {
        lastClientCfg = cfg
        lastProviderName = ap["provider_name"] ?: "provider"
        HmxLog.i("Network") { "CONNECTIVITY_CHECK mode=$mode" }
        try {
            controlClient.rpc("hmx_start_tunnel_session", mapOf("p_peer_id" to ap["peer_id"]!!, "p_mode" to mode.name.lowercase()))
        } catch (_: Exception) {}
        client.connected(lastProviderName, mode)
        startClientStats()
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

    /**
     * Phase 5: bounded automatic recovery after tunnel loss / network change.
     * Retries up to maxAttempts with exponential backoff; final failure surfaces CONNECTION_LOST.
     */
    fun recoverConnection() {
        val cfg = lastClientCfg ?: return
        if (client.state.value !is hmx.domain.logic.ClientState.Connected &&
            client.state.value !is hmx.domain.logic.ClientState.Reconnecting) return
        scope.launch {
            HmxLog.i("Share") { "RECONNECT_START attempts=${retry.maxAttempts}" }
            client.reconnect("network changed")
            for (attempt in 1..retry.maxAttempts) {
                TunnelController.down()
                delay(retry.backoffMs(attempt))
                val up = TunnelController.up(context(), cfg)
                if (up.isSuccess && awaitHandshake(15_000) != null && probeInternet()) {
                    HmxLog.i("Share") { "RECONNECT_SUCCESS attempt=$attempt" }
                    client.recovered()
                    startClientStats()
                    return@launch
                }
                HmxLog.w("Share") { "reconnect attempt $attempt failed" }
            }
            HmxLog.w("Share") { "RECONNECT_FAILED" }
            TunnelController.down()
            client.fail(AppError.CONNECTION_LOST)
        }
    }

    /** Phase 5: detect Android network changes; refresh endpoint-dependent state. */
    fun registerNetworkMonitoring() {
        if (netCallback != null) return
        runCatching {
            val cm = context().getSystemService(android.net.ConnectivityManager::class.java)
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    HmxLog.i("Share") { "NETWORK_CHANGED available" }
                    recoverConnection()
                }
                override fun onLost(network: android.net.Network) {
                    HmxLog.w("Share") { "NETWORK_CHANGED lost" }
                    recoverConnection()
                }
            }
            netCallback = cb
            cm.registerDefaultNetworkCallback(cb)
        }.onFailure { HmxLog.w("Share") { "net monitor unavailable: ${it.message}" } }
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

    /** Re-evaluate FGS need (called from Activity.onResume after a background-blocked start). */
    fun resyncForegroundService(context: android.content.Context) {
        val p = provider.state.value
        val c = client.state.value
        val pActive = p is hmx.domain.logic.ProviderState.Advertising ||
            p is hmx.domain.logic.ProviderState.SharingConnected
        val cActive = c !is hmx.domain.logic.ClientState.Idle &&
            c !is hmx.domain.logic.ClientState.Failed &&
            c !is hmx.domain.logic.ClientState.Disconnecting
        if (pActive) hmx.service.HmxSessionService.start(context, "Sharing internet — waiting/approved")
        else if (cActive) hmx.service.HmxSessionService.start(context, "Connected session active")
        else hmx.service.HmxSessionService.stop(context)
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
            } catch (e: Exception) { HmxLog.w("Control") { "refreshLists failed: ${e.message}" } }
        }
    }

    private fun parseTs(iso: String?): Long = runCatching {
        java.time.Instant.parse(iso).toEpochMilli()
    }.getOrDefault(0L)

    lateinit var contextProvider: () -> android.content.Context

    private fun context(): android.content.Context = contextProvider()

    /** Best-effort local Wi-Fi/LAN IPv4 for same-network direct handshakes. */
    private fun localWifiIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    /** Real internet check routed through the active VPN tunnel. */
    private fun probeInternet(): Boolean = runCatching {
        val conn = java.net.URL("http://connectivitycheck.gstatic.com/generate_204").openConnection()
            as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try { conn.responseCode == 204 || conn.responseCode == 200 } finally { conn.disconnect() }
    }.onFailure { HmxLog.w("Network") { "probe error: ${it.message}" } }.getOrDefault(false)

    private fun pairingHash(code: String): String = hmx.data.control.ControlClient.sha256Hex(code)

    private fun startGatewayStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (provider.state.value is ProviderState.SharingConnected) {
                delay(1000)
                if (GatewayEngineHost.limitReached()) {
                    HmxLog.w("Gateway") { "DATA_LIMIT_REACHED — stopping forwarding" }
                    GatewayEngineHost.stop()
                    runCatching { controlClient.rpc("hmx_end_tunnel_session", mapOf("p_session_id" to openTunnelSessionId.orEmpty(), "p_reason" to "data_limit")) }
                    openTunnelSessionId = null
                    provider.fail(AppError.DATA_LIMIT_REACHED)
                    return@launch
                }
                GatewayEngineHost.rxTxBytes()?.let { (rx, tx) -> provider.updateStats(TrafficStats(rx, tx)) }
            }
        }
    }

    @Volatile var cachedDataLimit: Long = 0L
        private set

    fun currentDataLimit(): Long = cachedDataLimit

    fun revokePeerById(peerId: String) {
        scope.launch {
            try { controlClient.rpc("hmx_revoke_peer", mapOf("p_peer_id" to peerId)); refreshLists() } catch (_: Exception) {}
        }
    }
}
