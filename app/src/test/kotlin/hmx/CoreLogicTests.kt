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
        assertEquals("HMX0112345Z", PairingCode.normalize("hmxoIl 234-5z"))
        assertTrue(PairingCode.isValid(PairingCode.generate()))
    }

    @Test
    fun `invalid lengths rejected`() {
        assertFalse(PairingCode.isValid("ABC"))
        assertFalse(PairingCode.isValid("HMX-1234")) // raw form never contains '-'
        assertFalse(PairingCode.isValid("ABCDEF")) // wrong prefix
        assertFalse(PairingCode.isValid("HMXA7K2")) // too long
        assertTrue(PairingCode.isValid("HMXA7K"))
        assertEquals("HMX-A7K", PairingCode.format("HMXA7K"))
            assertEquals("HMX-D5H", PairingCode.format("HMXD5H"))
            assertEquals("HMXD5H", PairingCode.normalize("HMX-D5H"))       // UI representation -> raw
            assertEquals("HMXD5H", PairingCode.normalize("HMX--D5H"))      // double separator stripped
            assertTrue(PairingCode.isValid(PairingCode.normalize("HMX-D5H")))
            assertFalse(PairingCode.isValid(PairingCode.normalize("HMX-D5H-EXTRA")))
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

    @Test
    fun retryPolicyBoundedAndExponential() {
        val r = hmx.domain.logic.RetryPolicy(maxAttempts = 3)
        assertEquals(2000L, r.backoffMs(1))
        assertEquals(4000L, r.backoffMs(2))
        assertEquals(8000L, r.backoffMs(3))
        assertFailsWith<IllegalArgumentException> { r.backoffMs(4) } // bounded: no attempt beyond max
        assertFailsWith<IllegalArgumentException> { hmx.domain.logic.RetryPolicy(maxAttempts = 0) }
    }
