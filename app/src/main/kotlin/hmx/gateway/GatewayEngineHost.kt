package hmx.gateway

import android.content.Context
import hmx.core.logging.HmxLog

/**
 * Hosts the REAL userspace gateway AAR (wireguard-go + gVisor netstack from
 * gateway-native CI) so an approved peer can complete a real WireGuard
 * handshake against this phone in Phase 3B. Internet forwarding through it
 * remains Phase 6/3C work and must not be claimed before it exists.
 */
object GatewayEngineHost {

    private var gw: hmxgateway.Gateway? = null

    fun start(
        privateKeyHex: String,
        listenPort: Int,
        peerPublicKeyBase64: String,
        peerInnerIp: String,
        ownInnerIp: String = "10.66.X.1",
        dnsUpstream: String = "1.1.1.1:53",
    ): Result<Unit> = runCatching {
        check(gw == null) { "gateway already running" }
        val cfg = hmxgateway.Config()
        cfg.privateKeyHex = privateKeyHex
        cfg.listenPort = listenPort
        cfg.innerIPv4 = ownInnerIp
        cfg.dnsUpstream = dnsUpstream
        cfg.mtu = 1280
        cfg.hardLimitBytes = 0L
        val gateway = hmxgateway.Hmxgateway.start(cfg)
        gateway.addPeer(b64ToHex(peerPublicKeyBase64), "$peerInnerIp/32")
        gw = gateway
        HmxLog.i("Gateway") { "userspace gateway listening on $listenPort" }
    }.onFailure {
        gw = null
        HmxLog.e("Gateway", it) { "start failed" }
    }

    fun stop() {
        runCatching { gw?.stop() }
        gw = null
    }

    fun rxTxBytes(): Pair<Long, Long>? = gw?.stats()?.let { it.rxBytes to it.txBytes }
    fun isRunning(): Boolean = gw != null

    private fun b64ToHex(b64: String): String =
        java.util.Base64.getDecoder().decode(b64).joinToString("") { "%02x".format(it) }
}
