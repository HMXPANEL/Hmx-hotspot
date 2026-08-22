package hmx

import hmx.core.logging.HmxLog
import hmx.domain.logic.DataLimits
import hmx.domain.logic.LimitStatus
import hmx.security.PairingCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {

    @Test
    fun `generated codes are valid and correctly sized`() {
        repeat(200) {
            val c = PairingCode.generate()
            assertEquals(PairingCode.LENGTH, c.length)
            assertTrue(PairingCode.isValid(c))
        }
    }

    @Test
    fun `normalize handles confusables and separators`() {
        assertEquals("0112345Z", PairingCode.normalize("oIl 234-5z"))
        assertTrue(PairingCode.isValid(PairingCode.normalize("abcd2345")))
    }

    @Test
    fun `invalid lengths rejected`() {
        assertFalse(PairingCode.isValid("ABC"))
        assertFalse(PairingCode.isValid("ABCDEFGHJ"))
        assertFalse(PairingCode.isValid("ABCDEFGL")) // L mapped to 1 -> ABCDEFG1 length ok? no: still 8 after map
    }

    @Test
    fun `expiry window is five minutes`() {
        val t = 1_000_000L
        assertFalse(PairingCode.isExpired(t, t))
        assertFalse(PairingCode.isExpired(t, t + PairingCode.TTL_MS - 1))
        assertTrue(PairingCode.isExpired(t, t + PairingCode.TTL_MS))
    }
}

class HmxLogTest {

    @Test
    fun `private keys are redacted`() {
        val msg = "config loaded private_key=abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789 done"
        val safe = HmxLog.safe(msg)
        assertFalse(safe.contains("abcdef01"))
        assertTrue(safe.contains("[REDACTED]"))
    }

    @Test
    fun `long hex fingerprints are truncated`() {
        val key64 = "a".repeat(64)
        val out = HmxLog.safe("peer=$key64")
        assertFalse(out.contains(key64))
        assertTrue(out.contains("aaaaaa…"))
    }

    @Test
    fun `normal text untouched`() {
        assertEquals("session started with Pixel 8", HmxLog.safe("session started with Pixel 8"))
    }
}

class DataLimitsTest {

    @Test
    fun `status thresholds`() {
        assertEquals(LimitStatus.Ok, DataLimits.evaluate(10, 100, 90))
        assertTrue(DataLimits.evaluate(95, 100, 90) is LimitStatus.Warning)
        assertEquals(LimitStatus.Exceeded, DataLimits.evaluate(100, 100, 90))
    }

    @Test
    fun `zero limit disables enforcement`() {
        assertEquals(LimitStatus.Ok, DataLimits.evaluate(Long.MAX_VALUE, 0, 90))
    }

    @Test
    fun `byte formatting`() {
        assertEquals("512 B", DataLimits.formatBytes(512))
        assertEquals("1.0 MB", DataLimits.formatBytes(1L shl 20))
        assertEquals("1.00 GB", DataLimits.formatBytes(1L shl 30))
    }
}
