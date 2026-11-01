package com.qgx1992.dshmobile.ui.screens.main

import com.qgx1992.dshmobile.core.session.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The second work-details axis: how much folds is [TranscriptDetail], when it folds is
 * [CollapseTiming].
 */
class CollapseTimingTest {
    private fun call(seq: Long) = ToolCallNode(seq, "c$seq", "read", "{}", 1, 1)
    private fun user(seq: Long, text: String = "hello") =
        UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", text)), "user")
    private fun answer(seq: Long, text: String = "done") =
        AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("text", text)))
    private fun reasoning(seq: Long, text: String = "thinking it over") =
        AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("reasoning", text)))

    /** Two finished turns: the first at seq 1, the second at seq 6. */
    private fun twoTurns(): List<ChatNode> = listOf(
        user(2), reasoning(3), call(4), answer(5),
        user(7), reasoning(8), call(9), answer(10),
    )
    private val twoStarts = listOf(1L, 6L)

    private fun fold(
        nodes: List<ChatNode>,
        turnStarts: List<Long>,
        running: Boolean = false,
        detail: TranscriptDetail = TranscriptDetail.DETAILED,
        timing: CollapseTiming = CollapseTiming.DEFAULT,
    ) = foldProcessRows(groupToolRows(nodes), turnStarts, running, detail, timing)

    // --- the axis itself -------------------------------------------------------------------

    @Test fun `every id round-trips`() {
        CollapseTiming.entries.forEach { timing ->
            assertSame(timing, CollapseTiming.of(timing.id))
        }
    }

    @Test fun `unrecognised values fall back to completion`() {
        assertSame(CollapseTiming.COMPLETION, CollapseTiming.of(null))
        assertSame(CollapseTiming.COMPLETION, CollapseTiming.of(""))
        assertSame(CollapseTiming.COMPLETION, CollapseTiming.of("nonsense"))
        assertSame(CollapseTiming.NEXT_INPUT, CollapseTiming.of(" Next-Input "))
    }

    @Test fun `completion always folds a settled turn`() {
        assertTrue(CollapseTiming.COMPLETION.foldsSettledTurn(hasNewerTurn = true))
        assertTrue(CollapseTiming.COMPLETION.foldsSettledTurn(hasNewerTurn = false))
    }

    @Test fun `next-input folds only once a newer turn exists`() {
        assertTrue(CollapseTiming.NEXT_INPUT.foldsSettledTurn(hasNewerTurn = true))
        assertFalse(CollapseTiming.NEXT_INPUT.foldsSettledTurn(hasNewerTurn = false))
    }

    // --- what the transcript does with it --------------------------------------------------

    @Test fun `completion folds both finished turns`() {
        val rows = fold(twoTurns(), twoStarts, timing = CollapseTiming.COMPLETION)
        assertEquals(6, rows.size)
        assertTrue(rows[1] is TranscriptRow.Process)
        assertTrue(rows[4] is TranscriptRow.Process)
    }

    @Test fun `next-input keeps the newest turn open`() {
        val rows = fold(twoTurns(), twoStarts, timing = CollapseTiming.NEXT_INPUT)
        // prompt · fold · answer, then the newest turn with its steps still on screen.
        assertEquals(7, rows.size)
        assertTrue("the older turn folds", rows[1] is TranscriptRow.Process)
        assertEquals(5L, rows[2].seq)
        assertEquals(listOf(7L, 8L, 9L, 10L), rows.drop(3).map { it.seq })
        assertTrue("the newest turn keeps its steps", rows.drop(3).none { it is TranscriptRow.Process })
    }

    @Test fun `next-input folds nothing while the session has one turn`() {
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), answer(5)),
            turnStarts = listOf(1L),
            timing = CollapseTiming.NEXT_INPUT,
        )
        assertEquals(4, rows.size)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }

    @Test fun `next-input still folds the running turn when the level asks for it`() {
        // A live turn is not "settled", so holding it open is not this axis' business: compact and
        // standard fold it, and the fold keeps the reply being written.
        val streaming = AssistantMessageNode(5, "a5", 1, 1, listOf(ChatBlock("text", "writing")))
            .copy(streaming = true)
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), streaming),
            turnStarts = listOf(1L),
            running = true,
            detail = TranscriptDetail.COMPACT,
            timing = CollapseTiming.NEXT_INPUT,
        )
        val process = rows[1] as TranscriptRow.Process
        assertTrue(process.live)
        assertEquals(5L, rows[2].seq)
    }

    @Test fun `timing changes nothing at a level that does not fold`() {
        val rows = fold(
            twoTurns(),
            twoStarts,
            detail = TranscriptDetail.VERBOSE,
            timing = CollapseTiming.COMPLETION,
        )
        assertEquals(8, rows.size)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }
}
