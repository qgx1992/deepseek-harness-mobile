package com.qgx1992.dshmobile.ui.screens.main

import com.qgx1992.dshmobile.core.session.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The four work-details levels, as the desktop Web client defines them.
 *
 * The values are not taste: each one is a row of the desktop's table (foldCompletedTurns,
 * stepGrouping, liveProcessDetail, settledReasoningPreview), and a mismatch would show up as a
 * transcript that looks almost right. These tests pin the table itself, then the behaviour each
 * row implies.
 */
class TranscriptDetailTest {
    private fun call(seq: Long) = ToolCallNode(seq, "c$seq", "read", "{}", 1, 1)
    private fun user(seq: Long, text: String = "hello") =
        UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", text)), "user")
    private fun answer(seq: Long, text: String = "done") =
        AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("text", text)))
    private fun reasoning(seq: Long, text: String = "thinking it over") =
        AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("reasoning", text)))

    private fun fold(
        nodes: List<ChatNode>,
        turnStarts: List<Long> = listOf(1),
        running: Boolean = false,
        detail: TranscriptDetail,
    ) = foldProcessRows(groupToolRows(nodes), turnStarts, running, detail)

    // ---- the table itself -------------------------------------------------------------------

    @Test fun `the four levels fold what the desktop folds`() {
        assertTrue(TranscriptDetail.COMPACT.foldsCompletedTurns)
        assertTrue(TranscriptDetail.STANDARD.foldsCompletedTurns)
        assertTrue(TranscriptDetail.DETAILED.foldsCompletedTurns)
        // Verbose asks for the work row by row, settled or not.
        assertFalse(TranscriptDetail.VERBOSE.foldsCompletedTurns)
    }

    @Test fun `only compact and standard fold the turn that is still running`() {
        assertTrue(TranscriptDetail.COMPACT.foldsLiveTurn)
        assertTrue(TranscriptDetail.STANDARD.foldsLiveTurn)
        assertFalse(TranscriptDetail.DETAILED.foldsLiveTurn)
        assertFalse(TranscriptDetail.VERBOSE.foldsLiveTurn)
    }

    @Test fun `only compact withholds the reasoning preview`() {
        assertFalse(TranscriptDetail.COMPACT.previewsReasoning)
        assertTrue(TranscriptDetail.STANDARD.previewsReasoning)
        assertTrue(TranscriptDetail.DETAILED.previewsReasoning)
        assertTrue(TranscriptDetail.VERBOSE.previewsReasoning)
    }

    @Test fun `only detailed opens settled groups before they are tapped`() {
        assertFalse(TranscriptDetail.COMPACT.expandsHistoryGroups)
        assertFalse(TranscriptDetail.STANDARD.expandsHistoryGroups)
        assertTrue(TranscriptDetail.DETAILED.expandsHistoryGroups)
        assertTrue(TranscriptDetail.VERBOSE.expandsHistoryGroups)
    }

    // ---- stored ids -------------------------------------------------------------------------

    @Test fun `stored ids round-trip`() {
        TranscriptDetail.entries.forEach { level ->
            assertSame(level, TranscriptDetail.of(level.id))
        }
    }

    @Test fun `an unknown or absent preference falls back to detailed`() {
        assertSame(TranscriptDetail.DETAILED, TranscriptDetail.of(null))
        assertSame(TranscriptDetail.DETAILED, TranscriptDetail.of(""))
        assertSame(TranscriptDetail.DETAILED, TranscriptDetail.of("nonsense"))
    }

    @Test fun `the desktop's older names still read as detailed`() {
        assertSame(TranscriptDetail.DETAILED, TranscriptDetail.of("normal"))
        assertSame(TranscriptDetail.DETAILED, TranscriptDetail.of("expanded"))
    }

    @Test fun `a stored id is read without regard to case or padding`() {
        assertSame(TranscriptDetail.COMPACT, TranscriptDetail.of(" Compact "))
        assertSame(TranscriptDetail.VERBOSE, TranscriptDetail.of("VERBOSE"))
    }

    @Test fun `detailed is the default`() {
        assertSame(TranscriptDetail.DETAILED, TranscriptDetail.DEFAULT)
    }

    // ---- behaviour --------------------------------------------------------------------------

    @Test fun `compact keeps a settled turn's reasoning as a preview`() {
        val rows = fold(
            listOf(user(2), reasoning(3, "first line\nsecond line"), call(4), answer(5)),
            detail = TranscriptDetail.COMPACT,
        )
        val process = rows[1] as TranscriptRow.Process
        assertNull(process.preview)
        assertFalse(process.groupsExpanded)
    }

    @Test fun `standard previews the last thought in one line`() {
        val rows = fold(
            listOf(user(2), reasoning(3, "first line\nsecond line"), call(4), answer(5)),
            detail = TranscriptDetail.STANDARD,
        )
        val process = rows[1] as TranscriptRow.Process
        assertEquals("first line", process.preview)
        // Standard still opens nothing by itself: the preview is the whole of its shortcut.
        assertFalse(process.groupsExpanded)
    }

    @Test fun `a long thought is cut to the preview limit`() {
        val long = "x".repeat(400)
        val rows = fold(
            listOf(user(2), reasoning(3, long), call(4), answer(5)),
            detail = TranscriptDetail.STANDARD,
        )
        val preview = (rows[1] as TranscriptRow.Process).preview
        assertNotNull(preview)
        assertTrue(preview!!.length <= 141)
        assertTrue(preview.endsWith("…"))
    }

    @Test fun `detailed opens a settled turn's groups`() {
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), answer(5)),
            detail = TranscriptDetail.DETAILED,
        )
        val process = rows[1] as TranscriptRow.Process
        assertTrue(process.groupsExpanded)
        assertNotNull(process.preview)
    }

    @Test fun `a live turn has no preview in any level`() {
        val streaming = AssistantMessageNode(5, "a5", 1, 1, listOf(ChatBlock("text", "writing"))).copy(streaming = true)
        listOf(TranscriptDetail.COMPACT, TranscriptDetail.STANDARD).forEach { level ->
            val rows = fold(
                listOf(user(2), reasoning(3), call(4), streaming),
                running = true,
                detail = level,
            )
            val process = rows[1] as TranscriptRow.Process
            assertNull("$level previewed a running turn", process.preview)
            assertFalse("$level opened a running turn's groups", process.groupsExpanded)
        }
    }

    @Test fun `verbose folds nothing`() {
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), answer(5)),
            detail = TranscriptDetail.VERBOSE,
        )
        assertEquals(4, rows.size)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }

    @Test fun `verbose keeps adjacent calls apart`() {
        // Grouping is what verbose turns off, so two calls in a row stay two cards.
        val grouped = groupToolRows(listOf(call(4), call(5)))
        assertEquals(1, grouped.size)
        assertTrue(grouped.first() is TranscriptRow.Tools)

        val direct = directRows(listOf(call(4), call(5)))
        assertEquals(2, direct.size)
        assertTrue(direct.all { it is TranscriptRow.Message })
    }
}
