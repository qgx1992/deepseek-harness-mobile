package com.qgx1992.dshmobile.ui.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qgx1992.dshmobile.R
import com.qgx1992.dshmobile.ui.components.DsBottomSheet
import com.qgx1992.dshmobile.ui.theme.DsSpacing
import com.qgx1992.dshmobile.ui.theme.DsTheme
import com.qgx1992.dshmobile.ui.theme.DsType

/**
 * Every turn of the session, newest first, so a reader can go back to the fifth thing they asked
 * without paging through a hundred screens of replies to find it.
 *
 * This is the one list that is not limited to the loaded window: the `turnOutline` projection the
 * harness folds keeps a name for every turn from the moment it opens, which is what makes the
 * oldest turns reachable at all. A turn the transcript has not paged in yet still lists here, and
 * picking it makes the transcript fetch back to it before scrolling — the sheet reports the turn's
 * start sequence and never touches history itself.
 *
 * Where the host composes no outline, the caller passes only the loaded turns; the sheet reads the
 * same either way, so the control degrades to a window-sized navigator instead of disappearing.
 *
 * Rows name a turn by number and show its own two previews. They are deliberately short: this is a
 * table of contents, and the answer is a tap away in the transcript.
 */
@Composable
internal fun TurnRailSheet(
    entries: List<TurnEntry>,
    onPick: (TurnEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DsTheme.colors
    var query by remember { mutableStateOf("") }
    // Newest first: the turn a reader wants is far more often one of the last few than the first.
    val ordered = remember(entries) { entries.asReversed() }
    val shown = remember(ordered, query) {
        if (query.isBlank()) ordered else ordered.filter { turnMatches(query, it) }
    }

    DsBottomSheet(
        title = stringResource(R.string.chat_turn_rail_title),
        subtitle = if (entries.isEmpty()) null else entries.size.toString(),
        onDismiss = onDismiss,
    ) {
        if (entries.size > SEARCH_FROM) {
            ChatSearchField(query, { query = it })
        }
        when {
            entries.isEmpty() -> Text(
                text = stringResource(R.string.chat_turn_rail_empty),
                style = DsType.caption11,
                color = colors.labelTertiary,
                modifier = Modifier.padding(vertical = DsSpacing.small),
            )
            shown.isEmpty() -> Text(
                text = stringResource(R.string.chat_turn_rail_no_match),
                style = DsType.small13,
                color = colors.labelTertiary,
                modifier = Modifier.padding(vertical = DsSpacing.small),
            )
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                items(shown, key = { it.turn }) { entry ->
                    TurnRailRow(entry = entry, onClick = { onPick(entry) })
                }
            }
        }
    }
}

@Composable
private fun TurnRailRow(entry: TurnEntry, onClick: () -> Unit) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = DsSpacing.small),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.trajectory_turn, entry.turn),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
            Spacer(Modifier.height(DsSpacing.tiny))
            Text(
                // A turn opened by an image or a slash command has no prompt text of its own, and
                // the host's outline leaves it empty too; the number above already names it.
                text = entry.prompt.ifEmpty { stringResource(R.string.chat_turn_rail_no_prompt) },
                style = DsType.small13,
                color = if (entry.prompt.isEmpty()) colors.labelTertiary else colors.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.response.isNotEmpty()) {
                Spacer(Modifier.height(DsSpacing.tiny))
                Text(
                    text = entry.response,
                    style = DsType.xsmall12,
                    color = colors.labelSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Whether a turn answers [query].
 *
 * The number is matched as typed, so "12" finds turn twelve directly, and the previews match the
 * same way the prompt search does — every word has to appear somewhere in the row.
 */
internal fun turnMatches(query: String, entry: TurnEntry): Boolean {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = "${entry.turn} ${entry.prompt} ${entry.response}".lowercase()
    return words.all { haystack.contains(it) }
}

private const val SEARCH_FROM = 8
