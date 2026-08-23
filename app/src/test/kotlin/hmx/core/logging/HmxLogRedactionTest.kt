package hmx.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 9 gate: sensitive values must never survive HmxLog.safe() at WARN-and-below. */
class HmxLogRedactionTest {

    @Test
    fun `secret assignments are redacted`() {
        val out = HmxLog.safe("config loaded private_key=abcdef0123456789 done")
        assertFalse(out.contains("abcdef"))
        assertTrue(out.contains("private_key=[REDACTED]"))
    }

    @Test
    fun `long hex and base64 are truncated`() {
        val hex = "a".repeat(64)
        val b64 = "Q".repeat(56) + "="
        val out = HmxLog.safe("k=$hex t=$b64")
        assertFalse(out.contains(hex))
        assertFalse(out.contains(b64))
        assertTrue(out.contains("…"))
    }

    @Test
    fun `ipv4 addresses with and without ports are redacted`() {
        assertEquals("gateway on [IP]:51821 up", HmxLog.safe("gateway on 203.0.113.7:51821 up"))
        assertEquals("peer at [IP] reachable", HmxLog.safe("peer at 198.51.100.4 reachable"))
        // loopback diagnostics stay readable for localhost debugging
        assertTrue(HmxLog.safe("relay local 127.0.0.1:51821").contains("[IP]"))
    }

    @Test
    fun `qr payloads are redacted but scheme stays recognizable`() {
        val out = HmxLog.safe("scanned hmx://p/HMXD5H ok")
        assertFalse(out.contains("HMXD5H"))
        assertTrue(out.contains("hmx://p/[REDACTED]"))
    }

    @Test
    fun `normal diagnostics remain readable`() {
        val msg = "SESSION_START limit=1024MB attempt=2 status=pending"
        assertEquals(msg, HmxLog.safe(msg))
    }
}
