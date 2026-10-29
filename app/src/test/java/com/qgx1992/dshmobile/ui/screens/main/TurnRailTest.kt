package com.qgx1992.dshmobile.ui.screens.main

import com.qgx1992.dshmobile.core.session.AssistantMessageNode
import com.qgx1992.dshmobile.core.session.ChatBlock
import com.qgx1992.dshmobile.core.session.ChatNode
import com.qgx1992.dshmobile.core.session.ToolCallNode
import com.qgx1992.dshmobile.core.session.TurnStartNode
import com.qgx1992.dshmobile.core.session.UserMessageNode
import com.qgx1992.dshmobile.core.wire.dto.TurnOutlineEntryView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRailTest {

    private fun user(seq: Long, text: String, sourceKind: String? = "user") =
        UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", text)), sourceKind)

    private fun assistant(seq: Long, text: String, streaming: Boolean = false) =
        AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("text", text)), streaming = streaming)

    private fun toolCall(seq: Long) = ToolCallNode(seq, "c$seq", "read", "{}", 1, 1)

    private fun outline(turn: Int, seq: Long, prompt: String = "", response: String = "") =
        TurnOutlineEntryView(turn = turn, seq = seq, prompt = prompt, response = response)

    @Test
    fun `lists loaded turns oldest first with their own previews`() {
        val nodes: List<ChatNode> = listOf(
            TurnStartNode(1, 1),
            user(2, "first"),
            assistant(3, "answer"),
            TurnStartNode(4, 2),
            user(5, "second"),
            assistant(6, "answer again"),
        )

        val entries = turnEntries(nodes, running = false)

        assertEquals(listOf(1, 2), entries.map { it.turn })
        assertEquals(listOf(1L, 4L), entries.map { it.seq })
        assertEquals(listOf("first", "second"), entries.map { it.prompt })
        assertEquals(listOf("answer", "answer again"), entries.map { it.response })
        assertTrue(entries.all { it.loaded })
    }

    @Test
    fun `the outline adds every turn the window has not reached`() {
        // The window holds the last turn only; the outline names the whole session.
        val nodes: List<ChatNode> = listOf(
            TurnStartNode(90, 12),
            user(91, "latest"),
        )
        val outline = listOf(
            outline(1, 1, "oldest", "done"),
            outline(5, 40, "middle", "done too"),
            outline(12, 90, "latest", ""),
        )

        val entries = turnEntries(nodes, running = false, outline = outline)

        assertEquals(listOf(1, 5, 12), entries.map { it.turn })
        assertFalse(entries[0].loaded)
        assertFalse(entries[1].loaded)
        assertTrue(entries[2].loaded)
    }

    @Test
    fun `the loaded window wins the previews it can offer`() {
        val nodes: List<ChatNode> = listOf(
            TurnStartNode(1, 1),
            user(2, "what the reader actually typed"),
            assistant(3, "what the model actually said"),
        )
        val outline = listOf(outline(1, 1, "truncated prompt", "truncated reply"))

        val entries = turnEntries(nodes, running = false, outline = outline)

        assertEquals("what the reader actually typed", entries.single().prompt)
        assertEquals("what the model actually said", entries.single().response)
    }

    @Test
    fun `a loaded turn with no prompt text borrows the outline copy`() {
        // A turn opened by an image alone records no text, but the host still has a preview.
        val nodes: List<ChatNode> = listOf(
            TurnStartNode(1, 1),
            UserMessageNode(2, "u2", listOf(ChatBlock("image", null)), "user"),
        )
        val outline = listOf(outline(1, 1, "look at this"))

        assertEquals("look at this", turnEntries(nodes, running = false, outline = outline).single().prompt)
    }

    @Test
    fun `no outline still lists the loaded turns`() {
        val nodes: List<ChatNode> = listOf(
            TurnStartNode(1, 1),
            user(2, "only turn"),
        )

        val entries = turnEntries(nodes, running = false, outline = null)

        assertEquals(listOf(1), entries.map { it.turn })
        assertTrue(entries.single().loaded)
    }

    @Test
    fun `injected context never stands in for a prompt`() {
        val nodes: List<ChatNode> = listOf(
            TurnStartNode(1, 1),
            user(2, "skill body", sourceKind = "skill"),
            user(3, "the real question"),
        )

        assertEquals("the real question", turnEntries(nodes, running = false).single().prompt)
    }

    @Test
    fun `a rewind marker is not a prompt`() {
        val nodes: List<ChatNode> = listOf(
            TurnStartNode(1, 1),
            user(2, "rewound", sourceKind = "rewind"),
            user(3, "the real question"),
        )

        assertEquals("the real question", turnEntries(nodes, running = false).single().prompt)
    }

    @Test
    fun `a streaming reply does not preview`() {
        val nodes: List<ChatNode> = listOf(
            TurnStartNode(1, 1),
            user(2, "ask"),
            assistant(3, "half an ans", streaming = true),
        )

        assertEquals("", turnEntries(nodes, running = true).single().response)
    }

    @Test
    fun `tool calls are not turns`() {
        val nodes: List<ChatNode> = listOf(
            TurnStartNode(1, 1),
            user(2, "run it"),
            toolCall(3),
            assistant(4, "ran it"),
        )

        val entries = turnEntries(nodes, running = false)

        assertEquals(1, entries.size)
        assertEquals("ran it", entries.single().response)
    }

    @Test
    fun `a turn picks the earliest row it produced`() {
        // A turn starts on an event that draws nothing, so the exact sequence is never a row.
        val rows = listOf(
            TranscriptRow.Message(assistant(9, "newest")),
            TranscriptRow.Message(assistant(6, "second turn reply")),
            TranscriptRow.Message(user(5, "second turn")),
            TranscriptRow.Message(assistant(3, "first turn reply")),
            TranscriptRow.Message(user(2, "first turn")),
        )

        // The turn starts at sequence 4, which draws nothing; its prompt (5) is the first row it
        // produced, and the reply below that belongs to the turn before it.
        assertEquals(2, jumpRowIndex(rows, turnSeq = 4))
    }

    @Test
    fun `an exact sequence still lands on its own row`() {
        val rows = listOf(
            TranscriptRow.Message(assistant(9, "newest")),
            TranscriptRow.Message(user(5, "second turn")),
        )

        assertEquals(1, jumpRowIndex(rows, turnSeq = 5))
    }

    @Test
    fun `a turn newer than every row has nowhere to land`() {
        val rows = listOf(TranscriptRow.Message(user(2, "first turn")))

        assertNull(jumpRowIndex(rows, turnSeq = 99))
    }

    @Test
    fun `search matches the turn number and both previews`() {
        val entry = TurnEntry(turn = 12, seq = 90, prompt = "port it", response = "ported", loaded = true)

        assertTrue(turnMatches("12", entry))
        assertTrue(turnMatches("port", entry))
        assertTrue(turnMatches("ported", entry))
        assertTrue(turnMatches("port 12", entry))
        assertFalse(turnMatches("missing", entry))
    }
}
