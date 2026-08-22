package hmx

import hmx.core.error.AppError
import hmx.domain.logic.ClientMachine
import hmx.domain.logic.ClientState
import hmx.domain.model.ConnectionMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientMachineTest {

    @Test
    fun `full happy path`() {
        var fakeNow = 1L
        val m = ClientMachine { fakeNow }
        m.scanStarted()
        m.deviceFound("HMX Phone A", "7A11C0DE")
        m.beginAuth()
        m.vpnPermissionNeeded()
        m.vpnGranted()
        m.tunnelStarting()
        m.probeStarted()
        fakeNow = 42L
        m.connected("HMX Phone A", ConnectionMode.DIRECT)
        val s = m.state.value as ClientState.Connected
        assertEquals(42L, s.sinceMs)
    }

    @Test
    fun `vpn denied fails with typed error`() = runTest {
        val m = ClientMachine()
        m.scanStarted(); m.deviceFound("A", "F"); m.beginAuth(); m.vpnPermissionNeeded()
        m.vpnDenied()
        assertEquals(ClientState.Failed(AppError.VPN_PERMISSION_DENIED), m.state.first())
        m.reset()
        assertEquals(ClientState.Idle, m.state.value)
    }

    @Test
    fun `reconnect and recovered returns to connected with fresh clock`() {
        var fakeNow = 100L
        val m = ClientMachine { fakeNow }
        happyTo(m, 100L)
        fakeNow = 500L
        m.reconnect("wifi lost")
        assertTrue(m.state.value is ClientState.Reconnecting)
        fakeNow = 900L
        m.recovered()
        val s = m.state.value as ClientState.Connected
        assertEquals(900L, s.sinceMs)
    }

    @Test
    fun `disconnect from connected goes idle`() {
        val m = ClientMachine()
        happyTo(m, 10L)
        m.disconnect()
        assertEquals(ClientState.Idle, m.state.value)
    }

    private fun happyTo(m: ClientMachine, since: Long) {
        m.scanStarted()
        m.deviceFound("A", "F")
        m.beginAuth()
        m.vpnPermissionNeeded()
        m.vpnGranted()
        m.tunnelStarting()
        m.probeStarted()
        m.connected("A")
    }
}
