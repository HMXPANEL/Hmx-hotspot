package hmx.vpn

import android.content.Context
import android.content.Intent
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import hmx.core.logging.HmxLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

data class WgPeerConfig(
    val privateKeyHex: String,
    val peerPublicKeyBase64: String,
    val ownInnerIp: String,
    val peerInnerIp: String,
    val endpoint: String?,
    val listenPort: Int? = null,
) {
    fun toWgQuick(): String = buildString {
        append("[Interface]\n")
        append("PrivateKey = ${hexToBase64(privateKeyHex)}\n")
        append("Address = $ownInnerIp/32\n")
        append("MTU = 1280\n")
        listenPort?.let { append("ListenPort = $it\n") }
        append("\n[Peer]\n")
        append("PublicKey = $peerPublicKeyBase64\n")
        endpoint?.let { append("Endpoint = $it\n") }
        append("AllowedIPs = $peerInnerIp/32\n")
        append("PersistentKeepalive = 25\n")
    }

    companion object {
        fun hexToBase64(hex: String): String =
            java.util.Base64.getEncoder().encodeToString(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }
}

/**
 * Minimal real-WireGuard controller for the Phase 3B handshake test.
 * Full-device routing (routes/DNS for all apps) arrives in Phase 3C; this only
 * brings the tunnel interface up so a REAL handshake can be verified.
 */
object TunnelController {

    @Volatile private var backend: GoBackend? = null
    @Volatile private var activeTunnel: Tunnel? = null

    fun init(context: Context) {
        if (backend == null) {
            backend = GoBackend(context)
        }
    }

    suspend fun prepareIntent(context: Context): Intent? =
        withContext(Dispatchers.IO) { com.wireguard.android.backend.GoBackend.VpnService.prepare(context) }

    suspend fun up(context: Context, cfg: WgPeerConfig, name: String = "hmx"): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val b = backend ?: error("TunnelController.init not called")
                val parsed = Config.parse(ByteArrayInputStream(cfg.toWgQuick().toByteArray()))
                val t = object : Tunnel {
                    override fun getName() = name
                    override fun onStateChange(newState: Tunnel.State) {}
                }
                val state = b.setState(t, Tunnel.State.UP, parsed)
                check(state == Tunnel.State.UP) { "tunnel did not come up" }
                activeTunnel = t
            }.onFailure { HmxLog.w("Tunnel") { "up failed: ${it.message}" } }
        }

    suspend fun down(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val t = activeTunnel ?: return@runCatching
            backend?.setState(t, Tunnel.State.DOWN, null)
            activeTunnel = null
        }
    }

    /** Real handshake evidence: latest handshake timestamp for the single peer. */
    fun lastHandshakeMs(): Long? {
        val t = activeTunnel ?: return null
        val b = backend ?: return null
        val stats = b.getStatistics(t)
        val key = stats.peers.firstOrNull() ?: return null
        val ms = stats.peer(key)?.latestHandshake?.time ?: 0L
        return if (ms > 0) ms else null
    }

    fun rxTxBytes(): Pair<Long, Long>? {
        val t = activeTunnel ?: return null
        val stats = backend?.getStatistics(t) ?: return null
        val key = stats.peers.firstOrNull() ?: return null
        val st = stats.peer(key) ?: return null
        return (st.totalRx to st.totalTx)
    }

    fun isUp(): Boolean = activeTunnel != null
}
