package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.core.session.*
import com.labteto.dshmobile.ui.components.FeatherIcons
import com.labteto.dshmobile.ui.components.StateDot
import com.labteto.dshmobile.ui.components.StateDotState
import com.labteto.dshmobile.ui.components.formatDurationMs
import com.labteto.dshmobile.ui.theme.DsColors
import com.labteto.dshmobile.ui.theme.DsShapes
import com.labteto.dshmobile.ui.theme.DsTheme
import com.labteto.dshmobile.ui.theme.DsType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.labteto.dshmobile.R

internal sealed interface TranscriptRow {
    val seq: Long
    data class Message(val node: ChatNode) : TranscriptRow { override val seq get() = node.seq }
    data class Tools(val calls: List<ToolCallNode>) : TranscriptRow { override val seq get() = calls.first().seq }
    data class Context(val nodes: List<ChatNode>) : TranscriptRow { override val seq get() = nodes.first().seq }

    /**
     * What a turn did before it answered, folded into one row.
     *
     * The prompt stays visible above this and the reply below it; the reasoning panels, context
     * disclosures and tool groups in between are one tap away instead of a wall of collapsed
     * headers. See [foldProcessRows] for exactly what gets folded.
     *
     * [live] marks the turn that is still running: its row is the only thing standing for the work
     * in progress, so it carries a moving dot and counts what it is holding. [tools], [thinking]
     * and [contexts] are those counts — numbers only, so the row needs no new translations.
     */
    data class Process(
        val rows: List<TranscriptRow>,
        val steps: Int,
        val live: Boolean = false,
        val tools: Int = 0,
        val thinking: Int = 0,
        val contexts: Int = 0,
    ) : TranscriptRow {
        override val seq get() = rows.first().seq
    }
}

/** Group only adjacent renderable calls; a visible message/disclosure is a boundary. */
internal fun groupToolRows(nodes: List<ChatNode>): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    val calls = mutableListOf<ToolCallNode>()
    fun flush() { if (calls.isNotEmpty()) { rows.add(TranscriptRow.Tools(calls.toList())); calls.clear() } }
    val contexts = mutableListOf<ChatNode>()
    fun flushContext() { if (contexts.isNotEmpty()) { rows.add(TranscriptRow.Context(contexts.toList())); contexts.clear() } }
    nodes.forEach {
        val isContext = (it is UserMessageNode && it.isInjectedContext && it.sourceKind != REWIND_SOURCE_KIND) ||
            (it is OtherNode && it.type in setOf("system/message", "request/context"))
        when {
            it is ToolCallNode -> { flushContext(); calls.add(it) }
            isContext -> { flush(); contexts.add(it) }
            else -> { flush(); flushContext(); rows.add(TranscriptRow.Message(it)) }
        }
    }
    flushContext()
    flush()
    return rows
}

/**
 * Fold the work a turn did before it answered into one [TranscriptRow.Process].
 *
 * Collapsed, a step still costs a row. A turn that thinks, reads four files, thinks again and then
 * answers therefore leaves a stack of collapsed headers between the prompt and the reply — folded
 * ones, but on a phone that stack still fills the screen. Everything between the turn's prompt and
 * the message the turn settled on belongs to one row instead: prompt, folded process, answer.
 *
 * [turnStarts] is the session's `turn/start` sequences, ascending. They are read from the unfiltered
 * nodes because a turn start draws no row of its own, so the rows alone cannot say where a turn
 * begins.
 *
 * Local mod: the turn that is still [running] folds too. It used to be left expanded ("its steps are
 * happening in front of the reader"), which is exactly the wall this row exists to remove: on a
 * long run the transcript was nothing but the prompt followed by a screen of live cards. The reply
 * being written still stays outside the fold, so the reader watches the answer arrive rather than
 * the machinery around it; the process row carries a moving dot instead of the steps it holds.
 *
 * A turn with no prompt (only harness-injected context before the first question) is left alone —
 * there is nothing for the row to sit under.
 */
internal fun foldProcessRows(
    rows: List<TranscriptRow>,
    turnStarts: List<Long>,
    running: Boolean = false,
): List<TranscriptRow> {
    if (rows.isEmpty() || turnStarts.isEmpty()) return rows
    val folded = mutableListOf<TranscriptRow>()
    var index = 0
    while (index < rows.size) {
        val turn = turnIndexOf(rows[index].seq, turnStarts)
        var end = index + 1
        while (end < rows.size && turnIndexOf(rows[end].seq, turnStarts) == turn) end++
        val slice = rows.subList(index, end)
        val isLast = end >= rows.size
        folded += if (turn < 0) slice else foldTurnProcess(slice, live = running && isLast)
        index = end
    }
    return folded
}

/** Which turn a sequence belongs to, or -1 for events before the first `turn/start`. */
private fun turnIndexOf(seq: Long, turnStarts: List<Long>): Int {
    var low = 0
    var high = turnStarts.size - 1
    var found = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (turnStarts[mid] <= seq) { found = mid; low = mid + 1 } else high = mid - 1
    }
    return found
}

/**
 * One turn's rows, with the stretch between its prompt and its answer folded.
 *
 * [live] is the turn currently running: it has no settled answer yet, so the fold stops at the
 * reply being written (or at the end, while the model is still thinking) instead of requiring one.
 */
private fun foldTurnProcess(rows: List<TranscriptRow>, live: Boolean = false): List<TranscriptRow> {
    val promptIndex = rows.indexOfFirst { row ->
        val node = (row as? TranscriptRow.Message)?.node
        node is UserMessageNode && !node.isInjectedContext && !node.isRewindMarker
    }
    if (promptIndex < 0) return rows
    // The last message with text is the answer; anything the turn wrote earlier was narration on the
    // way to it, which is exactly what the fold is for. A live turn also counts the reply still
    // being streamed — that is the answer arriving, and it has to stay on screen.
    val answerIndex = rows.indexOfLast { row ->
        val node = (row as? TranscriptRow.Message)?.node
        node is AssistantMessageNode && (node.streaming || node.plainText.isNotBlank())
    }
    // Nothing to separate from the prompt yet: while live, everything the turn has done so far is
    // the process, and the fold runs to the end of the slice.
    val stop = when {
        answerIndex > promptIndex -> answerIndex
        live -> rows.size
        else -> return rows
    }
    val folded = mutableListOf<TranscriptRow>()
    folded += rows.subList(0, promptIndex + 1)
    var run = mutableListOf<TranscriptRow>()
    var tools = 0
    var thinking = 0
    var contexts = 0
    fun flush() {
        if (run.isEmpty()) return
        folded += if (run.size > 1) {
            val steps = run.sumOf { if (it is TranscriptRow.Tools) it.calls.size else 1 }
            TranscriptRow.Process(
                rows = run.toList(),
                steps = steps,
                live = live,
                tools = tools,
                thinking = thinking,
                contexts = contexts,
            )
        } else {
            // A single step is its own row already; the counts ride along with it only when the
            // turn needs the process row at all.
            run.first()
        }
        run = mutableListOf()
        tools = 0
        thinking = 0
        contexts = 0
    }
    for (index in promptIndex + 1 until stop) {
        val row = rows[index]
        // Local mod: injected context folds with the steps now. It used to keep a row of its own
        // ("its own disclosure with its own header"), but a context-heavy turn still stacked one
        // header per skill or system note between the prompt and the reply. ProcessRow already
        // renders a Context step through the same ContextGroupRow, so the disclosure survives one
        // tap deeper instead of claiming a row of its own.
        //
        // A user message is never a step. Steering lands inside the running turn, not in a turn of
        // its own, so a reader who corrected the agent halfway would find their own words folded
        // away, and the history sheet's jump to them would land on nothing. The fold splits around
        // it instead: what led up to the correction, the correction, what followed it.
        val keep = (row as? TranscriptRow.Message)?.node is UserMessageNode
        if (keep) {
            flush()
            folded += row
        } else {
            run += row
            when (row) {
                is TranscriptRow.Tools -> tools += row.calls.size
                is TranscriptRow.Context -> contexts += row.nodes.size
                is TranscriptRow.Message -> if (row.node is AssistantMessageNode) thinking++
                else -> Unit
            }
        }
    }
    flush()
    folded += rows.subList(stop, rows.size)
    return folded
}

@Composable
internal fun ToolGroupRow(calls: List<ToolCallNode>, context: ChatNodeContext) {
    var expanded by remember(calls.first().callId) { mutableStateOf(false) }
    val colors = DsTheme.colors
    val results = context.nodes.filterIsInstance<ToolResultNode>().associateBy { it.callId }
    val failed = calls.count { results[it.callId]?.isError == true }
    val running = context.running && calls.any { it.callId !in results }
    val starts = calls.mapNotNull { context.eventTimes[it.seq] }
    val ends = calls.mapNotNull { results[it.callId]?.let { r -> context.eventTimes[r.seq] } }
    val elapsed = if (starts.isNotEmpty() && ends.isNotEmpty()) (ends.max() - starts.min()).coerceAtLeast(0) else null
    Column(Modifier.fillMaxWidth().clip(DsShapes.block).background(colors.bgLayer1)
        .border(1.dp, colors.borderL2, DsShapes.block)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(FeatherIcons.Tool, null, Modifier.size(14.dp), tint = colors.labelTertiary)
            Text(stringResource(R.string.chat_tool_group_count, calls.size), style = DsType.small13, color = colors.labelSecondary)
            if (failed > 0) Text(stringResource(R.string.chat_tool_group_failed, failed), style = DsType.caption11, color = colors.error)
            Spacer(Modifier.weight(1f))
            Text(if (running) stringResource(R.string.jobs_running) else elapsed?.let(::formatDurationMs).orEmpty(),
                style = DsType.caption11, color = if (running) colors.accent else colors.labelCaption)
            Icon(FeatherIcons.ChevronRight, null, Modifier.size(14.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f }, tint = colors.labelTertiary)
        }
        if (expanded) Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
            calls.forEach { call -> key(call.callId) { ToolCallRow(call, context, compact = true) } }
        }
    }
}

@Composable
internal fun ContextGroupRow(nodes: List<ChatNode>, context: ChatNodeContext) {
    var expanded by remember(nodes.first().seq) { mutableStateOf(false) }
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().clip(DsShapes.block).background(colors.bgLayer1)
        .border(1.dp, colors.borderL2, DsShapes.block)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(FeatherIcons.Layers, null, Modifier.size(14.dp), tint = colors.labelCaption)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chat_context_title) + " · " + nodes.size, style = DsType.small13, color = colors.labelTertiary)
            Spacer(Modifier.weight(1f))
            Icon(FeatherIcons.ChevronRight, null, Modifier.size(14.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f }, tint = colors.labelCaption)
        }
        if (expanded) Column(Modifier.padding(8.dp)) { nodes.forEach { key(it.seq) { ChatNodeItem(it, context) } } }
    }
}

/**
 * The folded steps of one turn.
 *
 * Deliberately the same shape as the two disclosures above — a 36dp header on `bgLayer1` with a
 * chevron — because it is the same gesture at a larger granularity: expand to see what happened.
 *
 * Local mod: the row now stands in for the running turn as well, so it says which it is. A live row
 * leads with the same moving dot the top bar uses and counts what it is holding (tools, thinking
 * steps, context blocks) as bare numbers beside small icons — no words, so no new translations.
 * The expansion state survives the turn settling: `seq` is the first step's, and that does not
 * change when the answer lands, so a reader who opened the process mid-run is not thrown out of it.
 */
@Composable
internal fun ProcessRow(row: TranscriptRow.Process, context: ChatNodeContext) {
    var expanded by rememberSaveable(row.seq) { mutableStateOf(false) }
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().clip(DsShapes.block).background(colors.bgLayer1)
        .border(1.dp, colors.borderL2, DsShapes.block)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (row.live) {
                StateDot(StateDotState.Running, size = 6.dp)
            } else {
                Icon(FeatherIcons.Activity, null, Modifier.size(14.dp), tint = colors.labelTertiary)
            }
            Text(
                stringResource(R.string.chat_process_title) + " · " +
                    pluralStringResource(R.plurals.trajectory_step_count, row.steps, row.steps),
                style = DsType.small13,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            HistogramCount(FeatherIcons.Tool, row.tools, colors)
            HistogramCount(FeatherIcons.Activity, row.thinking, colors)
            HistogramCount(FeatherIcons.Layers, row.contexts, colors)
            Spacer(Modifier.weight(1f))
            Icon(FeatherIcons.ChevronRight, null, Modifier.size(14.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f }, tint = colors.labelTertiary)
        }
        if (expanded) Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            row.rows.forEach { step ->
                key(step.seq) {
                    when (step) {
                        is TranscriptRow.Message -> ChatNodeItem(node = step.node, context = context)
                        is TranscriptRow.Tools -> ToolGroupRow(calls = step.calls, context = context)
                        is TranscriptRow.Context -> ContextGroupRow(nodes = step.nodes, context = context)
                        is TranscriptRow.Process -> Unit
                    }
                }
            }
        }
    }
}

/** A small `icon N` count; hidden at zero so the header stays a single quiet line. */
@Composable
private fun HistogramCount(icon: ImageVector, count: Int, colors: DsColors) {
    if (count <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, Modifier.size(11.dp), tint = colors.labelCaption)
        Text(count.toString(), style = DsType.caption11, color = colors.labelTertiary, maxLines = 1)
    }
}
