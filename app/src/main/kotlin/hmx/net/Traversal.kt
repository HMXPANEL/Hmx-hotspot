package hmx.net

import hmx.core.logging.HmxLog
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import kotlin.math.min

/** Configurable STUN server (no hardcoded single dependency). */
data class StunServer(val host: String, val port: Int = 3478)

object StunDefaults {
    val servers = listOf(
        StunServer("stun.l.google.com", 19302),
        StunServer("stun1.l.google.com", 19302),
        StunServer("stun.cloudflare.com", 3478),
    )
    const val DISCOVERY_TIMEOUT_MS = 4000
}

enum class CandidateType { HOST, SERVER_REFLEXIVE, RELAY }

/**
 * A connectivity candidate for reaching a device. Only connection-establishment
 * data lives here — never keys or credentials.
 */
data class NetworkCandidate(
    val type: CandidateType,
    val address: String,
    val port: Int,
    val protocol: String = "udp",
    val createdAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long = createdAtMs + 10 * 60 * 1000L,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs

    /** Reject malformed/untrusted endpoint data before use. */
    fun isWellFormed(): Boolean =
        port in 1..65535 &&
            protocol == "udp" &&
            runCatching { InetAddress.getByName(address); true }.getOrDefault(false)

    fun endpoint(): String = "$address:$port"

    companion object {
        const val CURRENT_VERSION = 1

        fun fromJsonList(json: String?): List<NetworkCandidate> = runCatching {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(json ?: "[]")
                as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val t = str("type")?.let { runCatching { CandidateType.valueOf(it) }.getOrNull() }
                    ?: return@mapNotNull null
                NetworkCandidate(
                    type = t,
                    address = str("address") ?: return@mapNotNull null,
                    port = str("port")?.toIntOrNull() ?: return@mapNotNull null,
                    protocol = str("protocol") ?: "udp",
                    createdAtMs = str("createdAtMs")?.toLongOrNull() ?: 0L,
                    expiresAtMs = str("expiresAtMs")?.toLongOrNull() ?: 0L,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun toJson(): String =
        """{"type":"$type","address":"$address","port":$port,"protocol":"$protocol","createdAtMs":$createdAtMs,"expiresAtMs":$expiresAtMs}"""
}

object CandidateSelector {
    /** Direct candidates first; within a class, newest first. Expired/malformed dropped. */
    fun select(candidates: List<NetworkCandidate>, nowMs: Long = System.currentTimeMillis()): List<NetworkCandidate> =
        candidates.asSequence()
            .filter { !it.isExpired(nowMs) && it.isWellFormed() }
            .sortedWith(
                compareBy<NetworkCandidate> { it.type.ordinal }
                    .thenByDescending { it.createdAtMs }
            ).toList()
}

/**
 * Minimal RFC 5389 STUN binding client. Returns the server-reflexive
 * "ip:port" for the local socket, or null.
 */
object StunClient {

    private const val MAGIC_COOKIE = 0x2112A442
    private const val BINDING_REQUEST = 0x0001
    private const val XOR_MAPPED_ADDRESS = 0x0020
    private const val MAPPED_ADDRESS = 0x0001

    /**
     * Bind a socket to [localPort] (0 = ephemeral), query [server], and return
     * the public endpoint mapped for that local port. Socket is closed before
     * returning so the port can be reused by the gateway.
     */
    fun discoverPublicEndpoint(
        server: StunServer,
        localPort: Int = 0,
        timeoutMs: Int = StunDefaults.DISCOVERY_TIMEOUT_MS,
    ): Pair<String, Int>? {
        val socket = DatagramSocket(InetSocketAddress(localPort))
        try {
            socket.soTimeout = timeoutMs
            val txid = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val req = buildBindingRequest(txid)
            val addr = InetSocketAddress(InetAddress.getByName(server.host), server.port)
            socket.send(DatagramPacket(req, req.size, addr))

            val buf = ByteArray(1024)
            val pkt = DatagramPacket(buf, buf.size)
            // Accept a few packets; some servers send other responses first.
            repeat(3) {
                socket.receive(pkt)
                parseXorMappedAddress(buf, pkt.length, txid)?.let { return it }
            }
            return null
        } catch (e: Exception) {
            HmxLog.w("Traversal") { "STUN ${server.host} failed: ${e.message}" }
            return null
        } finally {
            socket.close()
        }
    }

    fun buildBindingRequest(txid: ByteArray): ByteArray {
        val msg = ByteArray(20)
        msg[0] = (BINDING_REQUEST shr 8).toByte(); msg[1] = BINDING_REQUEST.toByte()
        msg[2] = 0; msg[3] = 0 // length
        msg[4] = (MAGIC_COOKIE shr 24).toByte()
        msg[5] = (MAGIC_COOKIE shr 16).toByte()
        msg[6] = (MAGIC_COOKIE shr 8).toByte()
        msg[7] = MAGIC_COOKIE.toByte()
        txid.copyInto(msg, 8)
        return msg
    }

    /** Parse XOR-MAPPED-ADDRESS (fallback MAPPED-ADDRESS). Testable pure function. */
    fun parseXorMappedAddress(msg: ByteArray, len: Int, txid: ByteArray): Pair<String, Int>? {
        if (len < 20) return null
        if (((msg[0].toInt() and 0xFF) shl 8 or (msg[1].toInt() and 0xFF)) != 0x0101) return null
        var off = 20
        while (off + 4 <= min(len, msg.size)) {
            val type = ((msg[off].toInt() and 0xFF) shl 8) or (msg[off + 1].toInt() and 0xFF)
            val size = ((msg[off + 2].toInt() and 0xFF) shl 8) or (msg[off + 3].toInt() and 0xFF)
            if (type == XOR_MAPPED_ADDRESS && size >= 8) {
                val raw = ((msg[off + 4].toInt() and 0xFF) shl 8) or (msg[off + 5].toInt() and 0xFF)
                val port = (raw xor (MAGIC_COOKIE shr 16)) and 0xFFFF
                val b = ByteArray(4)
                for (i in 0 until 4) {
                    b[i] = (msg[off + 8 + i].toInt() xor ((MAGIC_COOKIE shr (24 - 8 * i)) and 0xFF)).toByte()
                }
                val ip = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"
                return ip to port
            }
            if (type == MAPPED_ADDRESS && size >= 8 && msg[off + 5].toInt() != 0) {
                val port = ((msg[off + 6].toInt() and 0xFF) shl 8) or (msg[off + 7].toInt() and 0xFF)
                val ip = "${msg[off + 8].toInt() and 0xFF}.${msg[off + 9].toInt() and 0xFF}.${msg[off + 10].toInt() and 0xFF}.${msg[off + 11].toInt() and 0xFF}"
                return ip to port
            }
            off += 4 + size + ((4 - size % 4) % 4)
        }
        return null
    }
}
