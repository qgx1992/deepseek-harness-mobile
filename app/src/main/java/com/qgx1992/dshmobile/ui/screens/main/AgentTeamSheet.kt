package com.qgx1992.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import com.qgx1992.dshmobile.core.wire.dto.AgentTeamView
import com.qgx1992.dshmobile.core.wire.dto.TeamMemberView
import com.qgx1992.dshmobile.core.wire.dto.TeamTaskView
import com.qgx1992.dshmobile.ui.components.DisclosureRow
import com.qgx1992.dshmobile.ui.components.DsBottomSheet
import com.qgx1992.dshmobile.ui.components.DsCard
import com.qgx1992.dshmobile.ui.components.DsPill
import com.qgx1992.dshmobile.ui.components.EmptyHero
import com.qgx1992.dshmobile.ui.components.FeatherIcons
import com.qgx1992.dshmobile.ui.components.SectionHeader
import com.qgx1992.dshmobile.ui.components.StateDot
import com.qgx1992.dshmobile.ui.theme.DsSpacing
import com.qgx1992.dshmobile.ui.theme.DsTheme
import com.qgx1992.dshmobile.ui.theme.DsType

/**
 * The lead session's agent team: who is on it, what is on the shared board, and a way into each
 * member's own session.
 *
 * Read-only on purpose. The board is compare-and-set on `TeamTaskView.revision`, and the lead's own
 * `team_task_*` tools are its only writer — the desktop client draws the same line. A phone that
 * offered assign/complete/create would either lose the race against the lead's next write or, worse,
 * win it and hand the lead a revision it never asked for. Reading is the whole value here: the panel
 * can be opened and refreshed at any moment without changing the team's state.
 *
 * A sheet rather than a page, because the roster and the board are what the user glances at and
 * dismisses: the conversation behind them is what they came back to, and a full-screen page would
 * make closing cost the same as leaving the chat.
 */
@Composable
internal fun AgentTeamSheet(
    team: AgentTeamView,
    onDismiss: () -> Unit,
    onOpenMember: (sessionId: String) -> Unit,
) {
    val colors = DsTheme.colors
    // The host rewrites the projection on every board write, so a new list instance is the signal
    // that the board changed. Keying the sort on it keeps an unrelated recomposition (a theme swap,
    // a sizing pass) from redoing work that cannot have changed.
    val board = remember(team.tasks) { teamBoardOrder(team.tasks) }
    DsBottomSheet(title = stringResource(R.string.team_title), onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                // The same cap as the subagent sheet: the panel scrolls inside the sheet instead of
                // growing past the conversation, which keeps the title and the scrim in reach.
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DsSpacing.medium),
        ) {
            team.failure?.let { failure ->
                // Coloured text on a normal card, not an error-filled card: the card is the grouping
                // device for this sheet, and a red surface at the top would out-shout the roster and
                // board that the user might still want to read.
                DsCard {
                    Text(
                        stringResource(R.string.team_failed, failure),
                        style = DsType.std14,
                        color = colors.error,
                    )
                }
            }
            if (team.members.isNotEmpty()) {
                SectionHeader(stringResource(R.string.team_members))
                Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                    team.members.forEach { member -> TeamMemberCard(member, onOpenMember) }
                }
            }
            // A team that never started has no board to offer a header for — the failure card above
            // is the whole story, and an "empty board" line under it would imply the board had been
            // looked at. Should the host ever project tasks alongside a failure, the board wins.
            if (team.failure == null || board.isNotEmpty()) {
                SectionHeader(stringResource(R.string.team_board))
                if (board.isEmpty()) {
                    EmptyHero(stringResource(R.string.team_board_empty), null, showPreview = false)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(DsSpacing.small)) {
                        board.forEach { task -> TeamTaskCard(task) }
                    }
                }
            }
        }
    }
}

/**
 * One member of the roster.
 *
 * Only an `active` member is a target: a provisioning member's id is not addressable yet, and a
 * failed one has nothing behind it. Letting the card be inert is the honest way to say that — a tap
 * that opened an empty session would teach the user that the panel lies.
 */
@Composable
private fun TeamMemberCard(member: TeamMemberView, onOpenMember: (String) -> Unit) {
    val colors = DsTheme.colors
    val open: (() -> Unit)? = if (member.phase == TeamPhase.ACTIVE) {
        { onOpenMember(member.id) }
    } else {
        null
    }
    DsCard(onClick = open) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            StateDot(teamMemberDot(member.phase))
            Text(
                // The name is an identifier the lead chose (`sidebar-scout`, `lead`), not prose, and
                // it is the one run on this row the bidi algorithm must not be free to move.
                technicalDisplay(member.name),
                style = DsType.std14Strong,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DsPill(stringResource(teamRoleText(member.role)))
        }
        member.error?.let { error ->
            // Host prose explaining a failed start. Unlike the name it reads as a sentence, so it
            // keeps the paragraph direction instead of being isolated.
            Text(error, style = DsType.caption11, color = colors.error)
        }
        teamPhaseText(member.phase)?.let { phase ->
            Text(stringResource(phase), style = DsType.caption11, color = colors.labelTertiary)
        }
        if (open != null) {
            // The card is already the target, so this row only names what the tap does. A Ghost
            // button here would draw a second, smaller target inside the first one.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.tiny),
            ) {
                Text(
                    stringResource(R.string.team_open_member),
                    style = DsType.caption11,
                    color = colors.accent,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.accent,
                    // Decorative: the caption beside it already says where the tap goes.
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * One row of the shared board.
 *
 * Everything shown here is what the panel is allowed to know: the projection. It deliberately omits
 * a claim/complete affordance — see the sheet's own note on compare-and-set — so the only
 * interaction a task card offers is unfolding the technical detail it carries.
 */
@Composable
private fun TeamTaskCard(task: TeamTaskView) {
    val colors = DsTheme.colors
    var scopesOpen by remember(task.id) { mutableStateOf(false) }
    DsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            StateDot(teamTaskDot(task.status, task.ready, task.completed))
            Text(
                // The subject is the lead's own sentence to the board, so it keeps its direction.
                task.subject,
                style = DsType.std14Strong,
                color = colors.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpacing.small),
        ) {
            DsPill(stringResource(teamTaskStatusText(task.status, task.ready, task.completed)))
            Text(
                // The owner is a member name, i.e. an identifier; "Unowned" is a word and is left
                // alone so the badge does not pick up a stray bidi mark around a whole phrase.
                task.ownerName?.let(::technicalDisplay) ?: stringResource(R.string.team_task_unowned),
                style = DsType.caption11,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (task.description.isNotBlank()) {
            Text(
                task.description,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = DsType.std14,
                color = colors.labelSecondary,
            )
        }
        if (task.blockedBy.isNotEmpty()) {
            // One caption line rather than a disclosure: a blocker is `task-N`, so the ids are
            // shorter than any label a disclosure row would need to explain them, and the status
            // pill already carries the word "Blocked" when the wait is real. The caption's only job
            // is to name the predecessor; a fold would cost 48dp and a tap for the same four letters.
            Text(
                stringResource(
                    R.string.team_task_blocked_by,
                    task.blockedBy.joinToString(", ") { technicalDisplay(it) },
                ),
                style = DsType.caption11,
                color = colors.labelTertiary,
            )
        }
        if (task.writeScopeWarnings.isNotEmpty()) {
            // The overlap is advisory, not a failure, so it sits under the card's other facts in the
            // warning ink rather than as a card-level state.
            Text(
                stringResource(R.string.team_task_scope_warning, teamScopeWarning(task.writeScopeWarnings)),
                style = DsType.caption11,
                color = colors.warnText,
            )
        }
        if (task.writeScopes.isNotEmpty()) {
            // The scopes themselves are paths a reader consults rarely compared with the subject, so
            // they fold. The label says "write scopes" rather than "files": these are prefixes the
            // lead reserved, not the files the task ended up touching, and the warning line above
            // uses the same words.
            DisclosureRow(
                title = stringResource(R.string.team_task_write_scopes),
                // The first path names the area; the rest are one tap away. A bare count would be
                // cheaper but would say nothing about which part of the tree the task touches.
                summary = technicalDisplay(task.writeScopes.first()),
                expanded = scopesOpen,
                onToggle = { scopesOpen = !scopesOpen },
                modifier = Modifier.heightIn(min = DsSpacing.touchTarget),
            ) {
                Text(
                    task.writeScopes.joinToString("\n") { technicalDisplay(it) },
                    style = DsType.caption11,
                    color = colors.labelTertiary,
                )
            }
        }
        // The board is compare-and-set, so a card that disagrees with the lead's message is usually
        // a card one revision behind. Id plus revision is the pair a bug report needs to prove or
        // disprove that; the revision is numeric and needs no bidi isolation of its own.
        FeatureDiagnostics("${technicalDisplay(task.id)} r${task.revision}")
    }
}
