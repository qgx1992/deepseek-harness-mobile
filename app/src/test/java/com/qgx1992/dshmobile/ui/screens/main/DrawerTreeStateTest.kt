package com.qgx1992.dshmobile.ui.screens.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer's workspace groups: what you answered wins, and only the groups you never touched fall
 * back to the current session.
 */
class DrawerTreeStateTest {

    private val saved = mapOf("folded-by-me" to true, "opened-by-me" to false)

    @Test
    fun `an untouched group opens when it holds the session on screen`() {
        assertFalse(DrawerTreeState.isCollapsed(emptyMap(), "ws-a", holdsCurrent = true))
    }

    @Test
    fun `an untouched group folds when the session on screen lives elsewhere`() {
        assertTrue(DrawerTreeState.isCollapsed(emptyMap(), "ws-b", holdsCurrent = false))
    }

    @Test
    fun `opening a group keeps it open after you move to another workspace`() {
        assertFalse(DrawerTreeState.isCollapsed(saved, "opened-by-me", holdsCurrent = false))
    }

    @Test
    fun `folding a group keeps it folded even once it holds the session on screen`() {
        assertTrue(DrawerTreeState.isCollapsed(saved, "folded-by-me", holdsCurrent = true))
    }

    @Test
    fun `an answer about one group says nothing about another`() {
        assertTrue(DrawerTreeState.isCollapsed(saved, "ws-c", holdsCurrent = false))
        assertFalse(DrawerTreeState.isCollapsed(saved, "ws-c", holdsCurrent = true))
    }

    @Test
    fun `only the groups widened past the preview are wide`() {
        val widened = setOf("ws-a")
        assertTrue(DrawerTreeState.isWidened(widened, "ws-a"))
        assertFalse(DrawerTreeState.isWidened(widened, "ws-b"))
        assertFalse(DrawerTreeState.isWidened(emptySet(), "ws-a"))
    }
}
