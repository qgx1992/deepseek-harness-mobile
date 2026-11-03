package com.qgx1992.dshmobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind a pinned header, checked without a screen: which panel owns the copy at the
 * transcript's top edge, and when the copy goes away.
 */
class PinnedHeaderTest {

    @Test
    fun `a panel under the top edge pins`() {
        assertTrue(pinsHeader(viewportTop = 0f, top = -400f, bottom = 120f))
    }

    @Test
    fun `a panel entirely above the top edge does not pin`() {
        assertFalse(pinsHeader(viewportTop = 0f, top = -400f, bottom = -1f))
    }

    @Test
    fun `a panel entirely below the top edge does not pin`() {
        assertFalse(pinsHeader(viewportTop = 0f, top = 1f, bottom = 500f))
    }

    @Test
    fun `a panel whose header sits exactly at the top edge does not pin yet`() {
        // The real header is still fully visible at that point; a second copy would double it.
        assertFalse(pinsHeader(viewportTop = 0f, top = 0f, bottom = 500f))
    }

    @Test
    fun `nothing pins until the viewport has been measured`() {
        assertFalse(pinsHeader(viewportTop = Float.NaN, top = -400f, bottom = 120f))
    }

    @Test
    fun `the copy follows the panel that is under the edge`() {
        val state = PinnedHeaderState()
        state.placeViewport(0f)
        val first = Any()
        val second = Any()

        state.report(first, top = -50f, bottom = 300f, title = "Thought process", onCollapse = {})
        assertSame(first, state.pinned?.owner)

        // The next panel reaching the edge takes the copy over rather than piling a second one up.
        state.report(second, top = -20f, bottom = 200f, title = "Thought process", onCollapse = {})
        assertSame(second, state.pinned?.owner)

        // The panel above, now entirely out of sight, must not withdraw the copy the next one owns.
        state.report(first, top = -400f, bottom = -10f, title = "Thought process", onCollapse = {})
        assertSame(second, state.pinned?.owner)
    }

    @Test
    fun `a panel that leaves or collapses withdraws its own copy and no one else's`() {
        val state = PinnedHeaderState()
        state.placeViewport(0f)
        val panel = Any()

        state.report(panel, top = -80f, bottom = 200f, title = "Thinking", onCollapse = {})
        assertSame(panel, state.pinned?.owner)

        state.clear(Any())
        assertSame("a stranger must not clear the copy", panel, state.pinned?.owner)

        state.report(panel, top = -80f, bottom = 200f, title = "Thinking", onCollapse = {})
        state.clear(panel)
        assertNull(state.pinned)

        // And a panel that scrolls fully out of sight reports itself out of the edge.
        state.report(panel, top = -600f, bottom = -10f, title = "Thinking", onCollapse = {})
        assertNull(state.pinned)
    }

    @Test
    fun `the pinned copy runs whatever toggle the panel hands it this frame`() {
        val state = PinnedHeaderState()
        state.placeViewport(0f)
        val panel = Any()
        var stale = 0
        var latest = 0

        state.report(panel, top = -80f, bottom = 200f, title = "Thinking", onCollapse = { stale++ })
        val copy = state.pinned
        state.report(panel, top = -90f, bottom = 200f, title = "Thinking", onCollapse = { latest++ })

        // One copy throughout, and it calls the latest toggle rather than the one it was born with.
        assertSame(copy, state.pinned)
        state.pinned?.onCollapse?.invoke()
        assertEquals(0, stale)
        assertEquals(1, latest)
    }
}
