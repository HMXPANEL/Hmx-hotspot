package hmx

import hmx.core.error.AppError
import hmx.domain.logic.ProviderMachine
import hmx.domain.logic.ProviderState
import hmx.security.PairingCodeInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMachineTest {

    private fun code(t: Long = 1000L) = PairingCodeInfo("ABCD2345", t, t + 300_000)

    @Test
    fun `idle to preparing to advertising`() {
        val m = ProviderMachine()
        assertEquals(ProviderState.Idle, m.state.value)
        m.prepare()
        assertEquals(ProviderState.Preparing, m.state.value)
        m.startAdvertising(code())
        assertTrue(m.state.value is ProviderState.Advertising)
    }

    @Test
    fun `advertising rejects invalid transition directly from idle`() {
        val m = ProviderMachine()
        m.startAdvertising(code())
        assertEquals(ProviderState.Idle, m.state.value)
    }

    @Test
    fun `peer approval moves to connected with stats`() {
        var fakeNow = 5000L
        val m = ProviderMachine { fakeNow }
        m.prepare(); m.startAdvertising(code()); m.requestPairing("Pixel 8", "9F3CA21B")
        assertTrue(m.state.value is ProviderState.PeerAuthenticating)
        fakeNow = 9000L
        m.approvePeer("Pixel 8")
        val s = m.state.value as ProviderState.SharingConnected
        assertEquals(9000L, s.sinceMs)
        m.updateStats(hmx.domain.model.TrafficStats(10, 5))
        assertEquals(10L, (m.state.value as ProviderState.SharingConnected).stats.rxBytes)
    }

    @Test
    fun `reject returns to advertising`() = runTest {
        val m = ProviderMachine()
        m.prepare(); m.startAdvertising(code()); m.requestPairing("Pixel 8", "FP")
        m.rejectPeer()
        assertTrue(m.state.value is ProviderState.Advertising)
    }

    @Test
    fun `stop from connected lands on idle`() {
        val m = ProviderMachine()
        m.prepare(); m.startAdvertising(code()); m.requestPairing("P", "F"); m.approvePeer("P")
        m.stop()
        assertEquals(ProviderState.Idle, m.state.value)
    }

    @Test
    fun `failure then reset`() = runTest {
        val m = ProviderMachine()
        m.prepare()
        m.fail(AppError.NO_INTERNET)
        assertEquals(ProviderState.Failed(AppError.NO_INTERNET), m.state.first())
        m.reset()
        assertEquals(ProviderState.Idle, m.state.value)
    }
}
