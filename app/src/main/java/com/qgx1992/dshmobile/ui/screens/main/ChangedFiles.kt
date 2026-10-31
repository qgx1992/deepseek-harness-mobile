package com.qgx1992.dshmobile.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qgx1992.dshmobile.R
import com.qgx1992.dshmobile.core.session.ChatNode
import com.qgx1992.dshmobile.core.session.ToolCallNode
import com.qgx1992.dshmobile.core.session.ToolResultNode
import com.qgx1992.dshmobile.core.session.lineDiff
import com.qgx1992.dshmobile.core.text.DiffFile
import com.qgx1992.dshmobile.core.text.DiffHunk
import com.qgx1992.dshmobile.core.text.DiffLine
import com.qgx1992.dshmobile.core.text.parse
import com.qgx1992.dshmobile.core.wire.WireJson
import com.qgx1992.dshmobile.ui.components.DiffView
import com.qgx1992.dshmobile.ui.components.DsBottomSheet
import com.qgx1992.dshmobile.ui.components.DsIconButton
import com.qgx1992.dshmobile.ui.components.FeatherIcons
import com.qgx1992.dshmobile.ui.components.LocalFileOpener
import com.qgx1992.dshmobile.ui.components.clickableWithHold
import com.qgx1992.dshmobile.ui.theme.DsAnimations
import com.qgx1992.dshmobile.ui.theme.DsShapes
import com.qgx1992.dshmobile.ui.theme.DsTheme
import com.qgx1992.dshmobile.ui.theme.DsType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The files turn [turn] changed, in the order it first touched them.
 *
 * `workspace/changes` carries only `{turn}`: it is the harness saying "this turn's file changes
 * are settled", and the web client answers it by listing what the turn's write and edit calls
 * produced. Only calls whose result came back without an error count, so a refused or failed edit
 * does not show up as a change.
 */
internal fun changedFiles(nodes: List<ChatNode>, turn: Int): List<String> {
    val succeeded = nodes.asSequence()
        .filterIsInstance<ToolResultNode>()
        .filter { it.turn == turn && !it.isError }
        .mapTo(HashSet()) { it.callId }
    return nodes.asSequence()
        .filterIsInstance<ToolCallNode>()
        .filter { it.turn == turn && it.callId in succeeded }
        .filter { classifyTool(it.name).let { v -> v == ToolRowVariant.Write || v == ToolRowVariant.Edit } }
        .mapNotNull { call -> changedPath(call.arguments) }
        .distinct()
        .toList()
}

private fun changedPath(argumentsJson: String): String? {
    val arguments = runCatching { com.qgx1992.dshmobile.core.wire.WireJson.parseToJsonElement(argumentsJson).jsonObject }
        .getOrNull() ?: return null
    return listOf("file_path", "path")
        .firstNotNullOfOrNull { key -> (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        ?.takeIf { it.isNotBlank() }
}

/** The `turn` a `workspace/changes` event closes, or null for a payload this build cannot read. */
internal fun changesTurn(data: kotlinx.serialization.json.JsonElement): Int? =
    ((data as? JsonObject)?.get("turn") as? JsonPrimitive)?.content?.toIntOrNull()

// ============================================================================================
// Where the patch text comes from
// ============================================================================================
//
// `workspaceFiles/changes` carries only `{turn}` and `workspaceFiles` has no diff method at all —
// the harness never ships hunks. The desktop review view answers the event the same way this file
// does: it looks for the patch text the turn's tool calls carried, and parses it on the client.
// Two carriers, in this order:
//
//  1. A tool call's arguments. Some harnesses send a whole patch as one string argument under
//     `patch`, `input` or `diff` — an `apply_patch` script (`*** Begin Patch` / `*** Update File:`)
//     or a plain unified diff (`--- a/x`, `+++ b/x`, `@@`). Parsed by `core.text.parse`.
//  2. A tool result's `meta.diffs` array, one `{path, oldText, newText}` object per file. This is
//     what this build actually receives: `write` and `edit` report the text they replaced and the
//     text they wrote, not a diff. The hunks are then computed here with the same bounded LCS the
//     tool card uses, and their line numbers stay 0 (the view prints nothing) unless the old text
//     is absent — a whole-file write — in which case the new side really does start at line 1.
//
// Matching is by path, as a suffix: a diff may name `core/src/x.kt` where the change list names
// `/d/work/proj/core/src/x.kt` (or the other way around), so the first source whose normalized
// path equals the file's, or is a path-suffix of it, wins. A file with no source at all is shown
// as such by the sheet rather than as an empty panel.

/** Argument keys that carry a patch as one string. */
private val PATCH_ARGUMENT_KEYS = listOf("patch", "input", "diff")

/** Paths compare after dropping quotes, `./`, backslashes and leading/trailing slashes. */
private fun normalizeDiffPath(path: String): String =
    path.trim().trim('"').replace('\\', '/').removePrefix("./").trim('/')

/** Every patch string this call's arguments carry, in the order the keys are declared. */
private fun patchArguments(argumentsJson: String): List<String> {
    val arguments = runCatching { WireJson.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
        ?: return emptyList()
    return PATCH_ARGUMENT_KEYS.mapNotNull { key ->
        (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }
}

/**
 * The diffs a settled call reports in its result metadata: `meta.diffs = [{path, oldText,
 * newText}]`. Entries this build cannot read are skipped, not guessed at.
 */
private fun reportedDiffs(result: ToolResultNode): List<DiffFile> {
    val diffs = (result.meta as? JsonObject)?.get("diffs") as? JsonArray ?: return emptyList()
    val out = ArrayList<DiffFile>(diffs.size)
    for (entry in diffs) {
        val obj = entry as? JsonObject ?: continue
        val path = (obj["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() } ?: continue
        val oldText = (obj["oldText"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val newText = (obj["newText"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
        out += reportedDiff(path, oldText, newText)
    }
    return out
}

/** One `{oldText, newText}` pair as a single-hunk [DiffFile]. */
private fun reportedDiff(path: String, oldText: String?, newText: String): DiffFile {
    val rows = lineDiff(oldText, newText)
    // A write reports no old text: what it wrote starts at line 1 of the file, so numbering from 1
    // is true there. An edit reports only the fragment it replaced, whose offset in the file this
    // build never learns — so it keeps 0 and the gutter stays empty instead of lying.
    val numbered = oldText == null
    var oldNo = 0
    var newNo = 0
    val lines = rows.map { row ->
        when (row.kind) {
            '+' -> DiffLine.Add(row.text, if (numbered) ++newNo else 0)
            '-' -> DiffLine.Remove(row.text, if (numbered) ++oldNo else 0)
            else -> DiffLine.Context(row.text, if (numbered) ++oldNo else 0, if (numbered) ++newNo else 0)
        }
    }
    val hunk = DiffHunk(
        oldStart = if (numbered) 1 else 0,
        oldLines = lines.count { it !is DiffLine.Add },
        newStart = if (numbered) 1 else 0,
        newLines = lines.count { it !is DiffLine.Remove },
        heading = null,
        lines = lines,
    )
    return DiffFile(path = path, oldPath = null, hunks = listOf(hunk), binary = false)
}

/** Every diff this turn's settled tool calls carried, keyed by normalized path. */
private fun turnDiffSources(nodes: List<ChatNode>, turn: Int): Map<String, DiffFile> {
    val results = HashMap<String, ToolResultNode>()
    for (node in nodes) {
        if (node is ToolResultNode && node.turn == turn) results[node.callId] = node
    }
    val calls = nodes.filterIsInstance<ToolCallNode>()
        .filter { it.turn == turn }
        .filter { call -> results[call.callId]?.let { !it.isError } ?: true }
    val out = LinkedHashMap<String, DiffFile>()
    // Patch text first: it states real positions, so it wins over a computed pair for the same file.
    for (call in calls) {
        for (text in patchArguments(call.arguments)) {
            for (file in parse(text)) out.putIfAbsent(normalizeDiffPath(file.path), file)
        }
    }
    for (call in calls) {
        val result = results[call.callId] ?: continue
        for (file in reportedDiffs(result)) out.putIfAbsent(normalizeDiffPath(file.path), file)
    }
    return out
}

/**
 * The diff for [path] in [turn], or null when nothing the turn carried accounts for that file.
 *
 * Suffix matching, not equality: the change list holds workspace-absolute paths while a patch names
 * them relative to the repository, and either side may carry the deeper path.
 */
internal fun fileDiff(nodes: List<ChatNode>, turn: Int, path: String): DiffFile? {
    val want = normalizeDiffPath(path)
    if (want.isEmpty()) return null
    val sources = turnDiffSources(nodes, turn)
    sources[want]?.let { return it }
    return sources.entries
        .firstOrNull { (key, _) -> key.endsWith("/$want") || want.endsWith("/$key") }
        ?.value
}

/**
 * "Changed 2 files", then one pill per file — behind a fold that starts closed.
 *
 * A turn that touched a dozen files used to end with a dozen pills stacked over the reply, which is
 * the one thing the reader is trying to read. The count is the part that belongs in the transcript;
 * the list is reference material, wanted only when the reader goes looking for it, so the row opens
 * on a tap. A pill opens the file in the workspace preview.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChangedFilesRow(
    nodes: List<ChatNode>,
    turn: Int,
    cwd: String?,
    onMention: ((String, Boolean) -> Unit)? = null,
) {
    val files = remember(nodes, turn) { changedFiles(nodes, turn) }
    if (files.isEmpty()) return
    val colors = DsTheme.colors
    val open = LocalFileOpener.current
    var held by remember(turn) { mutableStateOf<String?>(null) }
    // The file whose diff sheet is open, if any.
    var review by remember(turn) { mutableStateOf<String?>(null) }
    // Closed by default, and remembered per turn so opening one turn's list does not open the next.
    var expanded by remember(turn) { mutableStateOf(false) }
    val holdLabel = stringResource(R.string.hold_file_actions)
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = DsAnimations.chevron,
        label = "changedFilesChevron",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = DsShapes.block,
        color = colors.bgLayer1,
        border = BorderStroke(1.dp, colors.borderL2),
    ) {
        Column {
            // The padding sits inside the clickable so the whole header takes the tap, not just the
            // line of text in the middle of it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = rotation },
                )
                Icon(
                    FeatherIcons.Edit3,
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.padding(start = 6.dp).size(14.dp),
                )
                Text(
                    pluralStringResource(R.plurals.chat_changed_files, files.size, files.size),
                    style = DsType.small13Strong,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(DsAnimations.expand) + fadeIn(DsAnimations.fade),
                exit = shrinkVertically(DsAnimations.expand) + fadeOut(DsAnimations.fade),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    files.forEach { path ->
                        Row(
                            Modifier
                                .heightIn(min = 32.dp)
                                .clip(DsShapes.pill)
                                .background(colors.bgModulePlatform)
                                .border(1.dp, colors.borderL3, DsShapes.pill)
                                .clickableWithHold(holdLabel, onHold = { held = path }, role = Role.Button) { review = path }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(FeatherIcons.FileText, null, Modifier.size(13.dp), tint = colors.labelSecondary)
                            Text(
                                technicalDisplay(relativizeToCwd(path, cwd)),
                                style = DsType.small13.copy(fontFamily = DsType.codeFont),
                                color = colors.labelPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    held?.let { path ->
        FileHoldMenu(
            path = mentionPath(path, cwd),
            folder = false,
            onMention = onMention,
            copyPath = path,
            onDismiss = { held = null },
        )
    }
    review?.let { path ->
        val diff = remember(nodes, turn, path) { fileDiff(nodes, turn, path) }
        DsBottomSheet(
            title = path.substringAfterLast('/'),
            onDismiss = { review = null },
            subtitle = technicalDisplay(relativizeToCwd(path, cwd)),
            trailing = {
                DsIconButton(
                    FeatherIcons.ExternalLink,
                    contentDescription = stringResource(R.string.diff_open_file),
                    onClick = { open(path) },
                )
            },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                DiffView(
                    files = listOfNotNull(diff),
                    empty = {
                        Text(
                            stringResource(R.string.diff_no_patch_text),
                            style = DsType.small13,
                            color = colors.labelSecondary,
                        )
                    },
                )
            }
        }
    }
}
