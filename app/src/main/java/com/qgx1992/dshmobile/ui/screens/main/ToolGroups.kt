package com.qgx1992.dshmobile.ui.screens.main

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qgx1992.dshmobile.core.session.*
import com.qgx1992.dshmobile.ui.components.FeatherIcons
import com.qgx1992.dshmobile.ui.components.StateDot
import com.qgx1992.dshmobile.ui.components.StateDotState
import com.qgx1992.dshmobile.ui.components.TimelineNode
import com.qgx1992.dshmobile.ui.components.formatDurationMs
import com.qgx1992.dshmobile.ui.theme.DsColors
import com.qgx1992.dshmobile.ui.theme.DsShapes
import com.qgx1992.dshmobile.ui.theme.DsTheme
import com.qgx1992.dshmobile.ui.theme.DsType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.qgx1992.dshmobile.R

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
     *
     * [preview] is the last thought of a finished turn, one line, shown under the header while the
     * row is closed — the desktop's reasoning preview. Null in modes that do not keep one.
     * [groupsExpanded] is where the steps behind the fold start: open in the modes that treat
     * history as already-written, closed in the ones that only want the shape of the work.
     */
    data class Process(
        val rows: List<TranscriptRow>,
        val steps: Int,
        val live: Boolean = false,
        val tools: Int = 0,
        val thinking: Int = 0,
        val contexts: Int = 0,
        val preview: String? = null,
        val groupsExpanded: Boolean = false,
    ) : TranscriptRow {
        override val seq get() = rows.first().seq
    }
}

/**
 * One row per node, in order, with nothing grouped — what the verbose work-details level asks for.
 *
 * Grouping is a courtesy for reading a long transcript quickly; verbose is the level chosen when a
 * turn went wrong and the order of the evidence is the point, so even adjacent tool calls stay
 * separate cards here.
 */
internal fun directRows(nodes: List<ChatNode>): List<TranscriptRow> = nodes.map { TranscriptRow.Message(it) }

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
 * [detail] is the reader's work-details level. Only [TranscriptDetail.DETAILED] and above let a
 * running turn show its steps; [TranscriptDetail.VERBOSE] — the default — folds nothing at all, so
 * a turn's work is listed the way the desktop lists it and this fold never runs.
 *
 * A turn with no prompt (only harness-injected context before the first question) is left alone —
 * there is nothing for the row to sit under.
 *
 * [timing] decides *when* a settled turn folds; see [CollapseTiming]. It never affects a running
 * turn — that one is [TranscriptDetail.foldsLiveTurn]'s business — and it only ever holds the newest
 * turn open, because that is the one whose next prompt has not been written yet.
 */
internal fun foldProcessRows(
    rows: List<TranscriptRow>,
    turnStarts: List<Long>,
    running: Boolean = false,
    detail: TranscriptDetail = TranscriptDetail.DEFAULT,
    timing: CollapseTiming = CollapseTiming.DEFAULT,
): List<TranscriptRow> {
    if (rows.isEmpty() || turnStarts.isEmpty()) return rows
    // Verbose asks for the work row by row; nothing is folded, and grouping is skipped by the
    // caller (see `ChatTranscript`), so there is not even a group header to fold into.
    if (!detail.foldsCompletedTurns) return rows
    val folded = mutableListOf<TranscriptRow>()
    var index = 0
    while (index < rows.size) {
        val turn = turnIndexOf(rows[index].seq, turnStarts)
        var end = index + 1
        while (end < rows.size && turnIndexOf(rows[end].seq, turnStarts) == turn) end++
        val slice = rows.subList(index, end)
        val isLast = end >= rows.size
        val live = running && isLast && detail.foldsLiveTurn
        folded += if (turn < 0) slice else foldTurnProcess(
            slice,
            live = live,
            detail = detail,
            // A settled turn folds now unless the reader asked for the fold to wait for the next
            // prompt — and only the newest turn is still waiting for one.
            holdOpen = !live && !timing.foldsSettledTurn(hasNewerTurn = !isLast),
        )
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
 *
 * [holdOpen] keeps a settled turn exactly as it was built — steps and all — because the reader's
 * collapse timing says the fold waits for the next prompt. The rows are already grouped by the
 * caller, so returning them untouched is the whole behaviour; nothing here has to know why.
 */
private fun foldTurnProcess(
    rows: List<TranscriptRow>,
    live: Boolean = false,
    detail: TranscriptDetail = TranscriptDetail.DEFAULT,
    holdOpen: Boolean = false,
): List<TranscriptRow> {
    if (holdOpen) return rows
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
                // A settled turn may keep one line of what it was thinking. Not while it runs: the
                // live row's job is to show that something is happening, not to quote it back.
                preview = if (!live && detail.previewsReasoning) reasoningPreview(run) else null,
                groupsExpanded = detail.expandsHistoryGroups && !live,
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

/**
 * The first line of the last reasoning block the folded run holds, or null when it holds none.
 *
 * Only ever the first line and only ever one: a closed process row is a summary, and two lines of
 * thought under a one-line header stopped being a summary. Whitespace is collapsed first, because
 * markdown-shaped reasoning arrives with newlines and indentation that say nothing once it is a
 * single caption.
 */
private fun reasoningPreview(rows: List<TranscriptRow>): String? {
    for (row in rows.asReversed()) {
        val node = (row as? TranscriptRow.Message)?.node as? AssistantMessageNode ?: continue
        val text = node.blocks
            .filter { it.kind == "reasoning" }
            .mapNotNull { it.text }
            .lastOrNull { it.isNotBlank() }
            ?: continue
        val line = text.lineSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: continue
        return if (line.length > PREVIEW_LIMIT) line.take(PREVIEW_LIMIT).trimEnd() + "…" else line
    }
    return null
}

/** Long enough for a sentence of reasoning, short enough to stay a caption on a phone. */
private const val PREVIEW_LIMIT = 140

@Composable
internal fun ToolGroupRow(calls: List<ToolCallNode>, context: ChatNodeContext, initiallyExpanded: Boolean = false) {
    var expanded by remember(calls.first().callId) { mutableStateOf(initiallyExpanded) }
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
internal fun ContextGroupRow(nodes: List<ChatNode>, context: ChatNodeContext, initiallyExpanded: Boolean = false) {
    var expanded by remember(nodes.first().seq) { mutableStateOf(initiallyExpanded) }
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
 * The work a turn did, drawn as a timeline.
 *
 * It used to be a card per step behind one "process" header: a bordered box per tool call, a second
 * box around each run of them, a third around the whole turn. On a phone that is a screen of nested
 * frames around what is really one sequence — and the sequence itself, what came first and what
 * followed it, is exactly what the frames hid. The fold stays, but it now paints a rail: one
 * hairline down the left edge, a dot per step, and the turn's own header as the first dot. Reading
 * it says the order, which is the one thing a stack of cards could not.
 *
 * It opens by default. A fold is for work the reader does not need; the reason to unfold this one is
 * that they do, and a tap per turn was the friction the timeline exists to remove. Tapping the header
 * folds it back to a single line carrying the turn's last thought as a preview. The state is keyed on
 * the first step's `seq`, which does not change when the turn settles, so opening a turn mid-run
 * keeps it open when the answer lands.
 *
 * A turn with more steps than [TIMELINE_OPEN_LIMIT] starts folded: its body is hundreds of rows
 * inside a single lazy item, and opening one of those on a phone is a stutter rather than a read. Its
 * header still counts what it holds, so nothing is hidden from the summary — just not pre-expanded.
 */
@Composable
internal fun ProcessRow(row: TranscriptRow.Process, context: ChatNodeContext) {
    var expanded by rememberSaveable(row.seq) { mutableStateOf(row.steps <= TIMELINE_OPEN_LIMIT) }
    val colors = DsTheme.colors
    val steps = remember(row.rows) { timelineSteps(row.rows) }
    val results = remember(context.nodes) {
        context.nodes.filterIsInstance<ToolResultNode>().associateBy { it.callId }
    }
    Column(Modifier.fillMaxWidth()) {
        TimelineNode(
            first = true,
            last = !expanded,
            dotColor = if (row.live) colors.accent else colors.labelTertiary,
            // The header is a 34dp row, so its one line sits 17dp down, not the 13dp a tool row uses.
            dotCenter = 17.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 34.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                Icon(
                    FeatherIcons.ChevronRight,
                    null,
                    Modifier.size(14.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f },
                    tint = colors.labelTertiary,
                )
            }
        }
        // The closed row can carry one line of what the turn was thinking — the desktop's
        // reasoning preview. It sits under the header, so opening the row is still what shows the
        // work; the preview only says what the work was for.
        if (!expanded) row.preview?.let { preview ->
            Text(
                preview,
                style = DsType.caption11,
                color = colors.labelTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 4.dp, bottom = 8.dp),
            )
        }
        if (expanded) steps.forEachIndexed { index, step ->
            key(step.seq) {
                TimelineNode(
                    last = index == steps.lastIndex,
                    dotColor = stepDot(step, results, context.running, colors),
                ) {
                    TimelineStepBody(step, context, row.groupsExpanded)
                }
            }
        }
    }
}

/**
 * One dot per call: a run of adjacent tool calls is several steps on the rail, not one card with a
 * count in its header. Splitting them here rather than inside the row keeps the layout dumb — every
 * node is a node, and the flattening happens once per row instead of once per recomposition.
 */
private fun timelineSteps(rows: List<TranscriptRow>): List<TranscriptRow> = rows.flatMap { step ->
    when (step) {
        is TranscriptRow.Tools -> step.calls.map { TranscriptRow.Message(it) }
        is TranscriptRow.Process -> emptyList()
        else -> listOf(step)
    }
}

/** Red for a call that failed, accent while one is still running, dim for everything settled. */
private fun stepDot(
    step: TranscriptRow,
    results: Map<String, ToolResultNode>,
    running: Boolean,
    colors: DsColors,
): Color {
    val call = (step as? TranscriptRow.Message)?.node as? ToolCallNode ?: return colors.labelCaption
    val result = results[call.callId]
    return when {
        result?.isError == true -> colors.error
        result == null && running -> colors.accent
        else -> colors.labelCaption
    }
}

/**
 * What sits beside one dot. The spacing lives inside the node so the rail spans it.
 *
 * A tool call is the one node whose body is a bare disclosure row, so it is drawn here rather than
 * through [ChatNodeItem]: that entry point also prints the call's duration as a caption above the
 * row, which inside a timeline is a second line where the dot expects a first one.
 */
@Composable
private fun TimelineStepBody(step: TranscriptRow, context: ChatNodeContext, groupsExpanded: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 1.dp, bottom = 8.dp)) {
        when (step) {
            // A call is one line in the timeline: its own duration is already in the card's summary,
            // and the extra caption above it would push the dot off the row it marks.
            is TranscriptRow.Message -> {
                val node = step.node
                if (node is ToolCallNode) {
                    ToolCallRow(node = node, context = context, compact = true)
                } else {
                    ChatNodeItem(node = node, context = context)
                }
            }
            is TranscriptRow.Context -> ContextGroupRow(step.nodes, context, initiallyExpanded = groupsExpanded)
            is TranscriptRow.Tools -> ToolGroupRow(step.calls, context, initiallyExpanded = groupsExpanded)
            is TranscriptRow.Process -> Unit
        }
    }
}

/** Turns longer than this start folded; see [ProcessRow]. */
private const val TIMELINE_OPEN_LIMIT = 32

/** A small `icon N` count; hidden at zero so the header stays a single quiet line. */
@Composable
private fun HistogramCount(icon: ImageVector, count: Int, colors: DsColors) {
    if (count <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, Modifier.size(11.dp), tint = colors.labelCaption)
        Text(count.toString(), style = DsType.caption11, color = colors.labelTertiary, maxLines = 1)
    }
}
