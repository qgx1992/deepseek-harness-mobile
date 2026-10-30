package com.qgx1992.dshmobile.ui.screens.main

import com.qgx1992.dshmobile.R
import com.qgx1992.dshmobile.core.wire.dto.AgentTeamView
import com.qgx1992.dshmobile.core.wire.dto.TeamTaskView
import com.qgx1992.dshmobile.ui.components.StateDotState

/*
 * The rules that turn an `agentTeam` projection into words and dots, kept out of the composable so
 * they can be reasoned about (and unit-tested) without a composition.
 *
 * The host owns every word here: the phases arrive as bare strings, the statuses as constants, and
 * this file is the only place that decides what a value means to a reader. Keeping the mapping
 * total — every branch answers, `else` included — is what stops a projection the host extends in a
 * later release from rendering as a blank row on a phone.
 */

/**
 * The phase strings the host writes into `TeamMemberView.phase`.
 *
 * The DTO carries them as strings because the projection is validated host-side; naming them once
 * here is what keeps the panel from comparing the same magic word in two files.
 */
internal object TeamPhase {
    const val PROVISIONING = "provisioning"
    const val ACTIVE = "active"
    const val FAILED = "failed"
}

/**
 * The role badge on a member's row.
 *
 * A member's name is an identifier the lead chose (`sidebar-scout`), so it says nothing about what
 * the member is for; the role is the one word that does, and it is what the reader scans the roster
 * for. Unknown roles fall to teammate rather than to nothing: the lead is a singleton the host
 * marks explicitly, so every other row is by definition somebody's teammate.
 */
internal fun teamRoleText(role: String): Int = when (role) {
    AgentTeamView.ROLE_LEAD -> R.string.team_role_lead
    else -> R.string.team_role_teammate
}

/**
 * The member's phase as a line of text, or null when there is nothing to report.
 *
 * `active` returns null on purpose. A roster where every healthy member carries a line buries the
 * two rows that matter — the one still starting and the one that failed — under a column of noise
 * the reader has to filter. An absent line is how this panel says "fine".
 */
internal fun teamPhaseText(phase: String): Int? = when (phase) {
    TeamPhase.PROVISIONING -> R.string.team_member_provisioning
    TeamPhase.FAILED -> R.string.team_member_failed
    else -> null
}

/**
 * The board's status word for one task.
 *
 * Precedence, and why: `completed` outranks everything else about a task, because once a task is
 * done its blockers are nobody's problem. `in_progress` outranks `!ready` because a member claiming
 * a task can land before the host rewrites the readiness flag of the blocker it just cleared — and
 * a task somebody is visibly working on must not be labelled "blocked" to the reader.
 */
internal fun teamTaskStatusText(status: String, ready: Boolean, completed: Boolean): Int = when {
    completed -> R.string.team_task_completed
    status == TeamTaskView.STATUS_IN_PROGRESS -> R.string.team_task_in_progress
    !ready -> R.string.team_task_blocked
    else -> R.string.team_task_pending
}

/**
 * The same precedence as [teamTaskStatusText], as a dot.
 *
 * Only the leading dot can be seen without reading, so the distinction it draws is coarser: a task
 * that is merely waiting for a turn of the queue gets the neutral idle grey, and only a task whose
 * blockers are actually unmet earns the warning colour.
 */
internal fun teamTaskDot(status: String, ready: Boolean, completed: Boolean): StateDotState = when {
    completed -> StateDotState.Done
    status == TeamTaskView.STATUS_IN_PROGRESS -> StateDotState.Running
    !ready -> StateDotState.Warning
    else -> StateDotState.Idle
}

/**
 * The roster dot answers one question: can this member be opened right now?
 *
 * So `active` is [StateDotState.Done] — the member is finished provisioning, which is the only
 * thing this dot is asked about. A teammate mid-turn is not "running" in this panel's vocabulary:
 * the projection carries no per-member turn state, and a chase animation that never settles would
 * read as a stuck roster rather than a busy one.
 */
internal fun teamMemberDot(phase: String): StateDotState = when (phase) {
    TeamPhase.FAILED -> StateDotState.Error
    TeamPhase.PROVISIONING -> StateDotState.Running
    else -> StateDotState.Done
}

/**
 * The write scopes that go inside `team_task_scope_warning`, each isolated for bidi and joined
 * with an ASCII comma.
 *
 * Not a localized list separator, deliberately: these are paths, not a sentence. The host's
 * `writeScopeWarnings` are the words; this function only assembles the identifiers, and a locale's
 * separator (or a right-to-left comma between two isolated paths) would turn the list back into
 * running text that the reader then has to parse into paths again. The caller wraps the result in
 * the format string, so the label stays translated.
 */
internal fun teamScopeWarning(scopes: List<String>): String =
    scopes.joinToString(", ") { technicalDisplay(it) }

/**
 * Board order: being worked on, then queued, then finished.
 *
 * `sortedBy` is stable, so tasks on the same rung keep the order the host projected — which is the
 * order the lead wrote them in, and the only ordering the board carries meaning with. Re-sorting
 * inside a rung (by id, subject, or owner) would replace the lead's narrative with the phone's
 * alphabet.
 *
 * `deleted` never reaches the projection — the host filters it — so the middle rung is `pending`
 * and any status a future host adds lands there rather than vanishing from a board the reader was
 * told is shared.
 */
internal fun teamBoardOrder(tasks: List<TeamTaskView>): List<TeamTaskView> = tasks.sortedBy { task ->
    when {
        task.inProgress -> 0
        task.completed -> 2
        else -> 1
    }
}
