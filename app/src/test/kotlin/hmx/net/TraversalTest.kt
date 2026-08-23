package hmx.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraversalTest {

    private fun cand(type: CandidateType, addr: String = "1.2.3.4", port: Int = 51820,
                     ageMs: Long = 0, expiresAfter: Long = 600_000) =
        NetworkCandidate(type, addr, port, createdAtMs = System.currentTimeMillis() - ageMs,
            expiresAtMs = System.currentTimeMillis() - ageMs + expiresAfter)

    @Test
    fun `selection prefers direct over relay and drops expired`() {
        val list = listOf(
            cand(CandidateType.RELAY),
            cand(CandidateType.SERVER_REFLEXIVE),
            cand(CandidateType.HOST, expiresAfter = -1), // expired HOST must drop
        )
        val sel = CandidateSelector.select(list)
        assertEquals(CandidateType.SERVER_REFLEXIVE, sel.first().type)
        assertEquals(CandidateType.RELAY, sel.last().type)
        assertEquals(2, sel.size)
    }

    @Test
    fun `malformed candidates rejected`() {
        assertFalse(cand(CandidateType.HOST, port = 0).isWellFormed())
        assertFalse(cand(CandidateType.HOST, addr = "not an ip").isWellFormed())
        assertTrue(cand(CandidateType.HOST, addr = "192.168.1.20").isWellFormed())
    }

    @Test
    fun `stun xor-mapped parse round trip`() {
        val txid = ByteArray(12) { it.toByte() }
        // Build a valid XOR-MAPPED-ADDRESS response for ip 203.0.113.7 port 42153
        val cookie = 0x2112A442
        val rawPort = (42153 xor (cookie shr 16)) and 0xFFFF
        val attr = byteArrayOf(
            0x00, 0x20, 0x00, 0x08,
            0x00, 0x01,
            ((rawPort shr 8) and 0xFF).toByte(), (rawPort and 0xFF).toByte(),
            ((203 xor ((cookie shr 24) and 0xFF)).toByte()),
            ((0 xor ((cookie shr 16) and 0xFF)).toByte()),
            ((113 xor ((cookie shr 8) and 0xFF)).toByte()),
            ((7 xor (cookie and 0xFF)).toByte()),
        )
        val msg = StunClient.buildBindingRequest(txid).copyOf(20 + attr.size)
        attr.copyInto(msg, 20)
        // binding success response type = 0x0101
        msg[0] = 0x01
        msg[1] = 0x01

        val parsed = StunClient.parseXorMappedAddress(msg, msg.size, txid)
        assertEquals("203.0.113.7", parsed?.first)
        assertEquals(42153, parsed?.second)
    }

    @Test
    fun `garbage message parses to null`() {
        assertNull(StunClient.parseXorMappedAddress(ByteArray(30), 30, ByteArray(12)))
        assertNull(StunClient.parseXorMappedAddress(ByteArray(10), 10, ByteArray(12)))
    }
}
