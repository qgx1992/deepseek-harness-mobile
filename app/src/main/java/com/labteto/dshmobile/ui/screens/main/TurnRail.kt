package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import com.labteto.dshmobile.core.wire.dto.TurnOutlineEntryView

/**
 * One navigable turn: where it starts, and the two previews the picker shows.
 *
 * [loaded] records whether the turn's own events are in the window the transcript holds. A turn
 * named only by the `turnOutline` projection can still be listed — that is the point of the
 * projection — but picking it has to page history in first.
 */
internal data class TurnEntry(
    val turn: Int,
    val seq: Long,
    val prompt: String,
    val response: String,
    val loaded: Boolean,
)

/**
 * The union of the loaded window's turns and the `turnOutline` projection, oldest first.
 *
 * Both sides name the same turns by number, so they merge on it: the loaded window supplies the
 * anchor and the previews (it has the real blocks), and the projection fills in every turn the
 * window has not reached — including its own previews, which the host already shortened. A loaded
 * turn whose own prompt is empty (an image-only or command-only opening) keeps the projection's
 * text instead of showing nothing.
 *
 * A null [outline] — the host composes no such projection — leaves the loaded turns alone, so the
 * picker degrades to a view of the window rather than disappearing.
 */
internal fun turnEntries(
    nodes: List<ChatNode>,
    running: Boolean,
    outline: List<TurnOutlineEntryView>? = null,
): List<TurnEntry> {
    val merged = LinkedHashMap<Int, TurnEntry>()

    for (entry in loadedTurnEntries(nodes, running)) merged[entry.turn] = entry

    outline?.forEach { entry ->
        val loaded = merged[entry.turn]
        merged[entry.turn] = when {
            loaded == null -> TurnEntry(
                turn = entry.turn,
                seq = entry.seq,
                prompt = entry.prompt,
                response = entry.response,
                loaded = false,
            )
            // The window's own blocks are richer, but a turn opened by an image or a slash
            // command has no prompt text at all, and the host's copy is better than a blank row.
            else -> loaded.copy(
                prompt = loaded.prompt.ifEmpty { entry.prompt },
                response = loaded.response.ifEmpty { entry.response },
            )
        }
    }

    return merged.values.sortedBy { it.turn }
}

/** The turns the loaded window already holds, oldest first, with their own previews. */
private fun loadedTurnEntries(nodes: List<ChatNode>, running: Boolean): List<TurnEntry> {
    val starts = nodes.filterIsInstance<TurnStartNode>().sortedBy { it.seq }
    if (starts.isEmpty()) return emptyList()
    val visible = renderableChatNodes(nodes, running)

    return starts.mapIndexed { index, start ->
        val end = starts.getOrNull(index + 1)?.seq ?: Long.MAX_VALUE
        var prompt = ""
        var response = ""
        for (node in visible) {
            if (node.seq < start.seq) continue
            if (node.seq >= end) break
            // A rewind marker is a user-sourced node too, but it says "history ends here" rather
            // than anything the reader typed, and injected context is not their words either.
            if (node is UserMessageNode && prompt.isEmpty() && !node.isInjectedContext && !node.isRewindMarker) {
                prompt = node.displayText().trim()
            }
            // Only a settled reply previews: a streaming one is still being rewritten, and the
            // picker's job is to name a turn, not to follow it.
            if (node is AssistantMessageNode && !node.streaming && node.plainText.isNotBlank()) {
                response = node.plainText.trim()
            }
        }
        TurnEntry(turn = start.turn, seq = start.seq, prompt = prompt, response = response, loaded = true)
    }
}

/**
 * The row a jump to [turnSeq] should land on.
 *
 * A turn starts at its `turn/start` event, which draws nothing, so the exact row never exists.
 * Rows are newest first, which makes the last row at or after the turn start the earliest visible
 * thing the turn produced — exactly where a reader wants to begin. An exact match still wins, so
 * the history picker's own behaviour is unchanged.
 */
internal fun jumpRowIndex(rows: List<TranscriptRow>, turnSeq: Long): Int? {
    val exact = rows.indexOfFirst { it.seq == turnSeq }
    if (exact >= 0) return exact
    return rows.indexOfLast { it.seq >= turnSeq }.takeIf { it >= 0 }
}
