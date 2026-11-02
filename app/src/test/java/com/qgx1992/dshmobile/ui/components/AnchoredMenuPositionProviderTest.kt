package com.qgx1992.dshmobile.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission chip and the send button sit on the composer's bottom edge, so a menu that opens
 * downward from them is a menu under the keyboard. These hold the placement rules the popup relies
 * on: above by default, below only when above does not fit, and never off the window edge.
 */
class AnchoredMenuPositionProviderTest {

    // Pixel numbers of a 1080x2400 phone, the shape this was designed on.
    private val window = IntSize(1080, 2400)
    private val menu = IntSize(600, 520)
    private val marginPx = 12
    private val gapPx = 8
    private val provider = AnchoredMenuPositionProvider(gapPx = gapPx, marginPx = marginPx)

    private fun place(
        anchor: IntRect,
        direction: LayoutDirection = LayoutDirection.Ltr,
        windowSize: IntSize = window,
        contentSize: IntSize = menu,
    ): IntOffset = provider.calculatePosition(
        anchorBounds = anchor,
        windowSize = windowSize,
        layoutDirection = direction,
        popupContentSize = contentSize,
    )

    @Test
    fun `a chip on the bottom edge opens upward`() {
        val anchor = IntRect(left = 200, top = 2100, right = 500, bottom = 2160)
        val position = place(anchor)
        assertEquals(200, position.x)
        // Directly above the chip, one gap clear of its top edge.
        assertEquals(anchor.top - gapPx - menu.height, position.y)
        assertTrue(position.y + menu.height <= anchor.top)
    }

    @Test
    fun `an anchor with no room above opens below`() {
        val anchor = IntRect(left = 200, top = 100, right = 500, bottom = 160)
        val position = place(anchor)
        assertEquals(anchor.bottom + gapPx, position.y)
    }

    @Test
    fun `a menu too tall for either side stays inside the window`() {
        val small = IntSize(1080, 800)
        val tallMenu = IntSize(600, 700)
        val anchor = IntRect(left = 200, top = 350, right = 500, bottom = 410)
        val position = place(anchor, windowSize = small, contentSize = tallMenu)
        // Neither side fits, so it is clamped to the window rather than drawn off its top.
        assertTrue(position.y >= marginPx)
        assertTrue(position.y + tallMenu.height + marginPx <= small.height)
    }

    @Test
    fun `a chip against the right edge is pulled inside the window`() {
        val anchor = IntRect(left = 1000, top = 2100, right = 1060, bottom = 2160)
        val position = place(anchor)
        assertEquals(window.width - menu.width - marginPx, position.x)
        assertTrue(position.x + menu.width + marginPx <= window.width)
    }

    @Test
    fun `right to left lays the menu out from the anchor's right edge`() {
        val anchor = IntRect(left = 700, top = 2100, right = 1060, bottom = 2160)
        val position = place(anchor, direction = LayoutDirection.Rtl)
        assertEquals(anchor.right - menu.width, position.x)
    }

    @Test
    fun `an unresolved anchor falls back to the bottom edge inside the window`() {
        val position = place(IntRect.Zero)
        assertTrue(position.x >= marginPx)
        assertTrue(position.x + menu.width + marginPx <= window.width)
        assertTrue(position.y + menu.height + marginPx <= window.height)
    }
}
