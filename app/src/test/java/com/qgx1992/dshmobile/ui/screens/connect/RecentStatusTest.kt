package com.qgx1992.dshmobile.ui.screens.connect

import com.qgx1992.dshmobile.connection.ConnectionPhase
import com.qgx1992.dshmobile.core.wire.dto.HostDescription
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When a Recent row may say "unreachable".
 *
 * The bug this pins down: the manager leaves `failure` set while it retries, so a single cold-start
 * miss used to grey out the row the app was about to connect to — the screen claimed a healthy
 * harness was unreachable, and auto-connect never ran.
 */
class RecentStatusTest {

    private val authority = "192.168.1.20:3080"
    private val description = HostDescription(home = "/home/you")
    private val probing = mapOf(authority to HostProbe.Probing)

    /** The retrying case: a failure is on screen, but the loop has not given up. */
    @Test
    fun `a failure that is still being retried does not grey the row`() {
        val after = recentStatusAfter(
            current = probing,
            attempted = authority,
            connected = false,
            phase = ConnectionPhase.CONNECTING,
            reached = null,
            description = null,
            owned = false,
            hasFailure = true,
        )
        assertEquals(probing, after)
    }

    /** A relay that refused this device has stopped the loop; that is news the row should carry. */
    @Test
    fun `a terminal failure does grey the row`() {
        val after = recentStatusAfter(
            current = probing,
            attempted = authority,
            connected = false,
            phase = ConnectionPhase.DISCONNECTED,
            reached = null,
            description = null,
            owned = false,
            hasFailure = true,
        )
        assertEquals(mapOf(authority to HostProbe.Unreachable), after)
    }

    /** What the app decides itself (a bad address, a wrong subnet) is not a retry. */
    @Test
    fun `a locally owned failure greys the row even without a phase change`() {
        val after = recentStatusAfter(
            current = probing,
            attempted = authority,
            connected = false,
            phase = ConnectionPhase.CONNECTING,
            reached = null,
            description = null,
            owned = true,
            hasFailure = true,
        )
        assertEquals(probing, after)
    }

    /** Recovering is the point: a row greyed by a probe is repainted by a real connection. */
    @Test
    fun `connecting clears an earlier verdict`() {
        val greyed = mapOf(authority to HostProbe.Unreachable)
        val after = recentStatusAfter(
            current = greyed,
            attempted = authority,
            connected = true,
            phase = ConnectionPhase.CONNECTED,
            reached = authority,
            description = description,
            owned = false,
            hasFailure = false,
        )
        assertEquals(mapOf(authority to HostProbe.Reachable(description)), after)
    }

    /** Reached but not yet described — no home directory read, so nothing to say beyond "alive". */
    @Test
    fun `a connection without a description leaves the row alone`() {
        val after = recentStatusAfter(
            current = probing,
            attempted = authority,
            connected = true,
            phase = ConnectionPhase.CONNECTED,
            reached = authority,
            description = null,
            owned = false,
            hasFailure = false,
        )
        assertEquals(probing, after)
    }
}
