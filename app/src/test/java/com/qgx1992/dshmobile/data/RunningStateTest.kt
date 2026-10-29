package com.qgx1992.dshmobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the open session reads as running.
 *
 * The bug this pins down: a session finished while its follow stream was not listening — the
 * reader had opened another session, or the app was in the background — so its `turn/end` was
 * never folded. The stale `true` written by `turn/start` then outranked the transcript forever,
 * and reopening the session (whose fresh snapshot carried the whole turn, end included) still
 * showed 运行中 until the process restarted.
 */
class RunningStateTest {

    @Test
    fun `a complete window outranks a stale remembered value`() {
        // Reopened session: the window folds to idle, the old live value still says running.
        assertFalse(resolveRunning(folded = false, remembered = true, authoritative = true))
    }

    @Test
    fun `a complete window can also say running`() {
        // A turn is genuinely in flight: the window has its start and no end.
        assertTrue(resolveRunning(folded = true, remembered = false, authoritative = true))
    }

    @Test
    fun `between snapshots the live value still wins`() {
        // `turn/start` marks the session running before its event is appended, so the fold is one
        // frame behind. Trusting the fold here would flash "idle" at the start of every turn.
        assertTrue(resolveRunning(folded = false, remembered = true, authoritative = false))
        assertFalse(resolveRunning(folded = true, remembered = false, authoritative = false))
    }

    @Test
    fun `nothing remembered falls back to the fold`() {
        assertFalse(resolveRunning(folded = false, remembered = null, authoritative = false))
        assertTrue(resolveRunning(folded = true, remembered = null, authoritative = false))
    }
}
