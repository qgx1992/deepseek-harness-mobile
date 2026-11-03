package com.qgx1992.dshmobile.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qgx1992.dshmobile.R
import com.qgx1992.dshmobile.core.git.GitChange
import com.qgx1992.dshmobile.core.git.GitCommit
import com.qgx1992.dshmobile.core.git.GitHead
import com.qgx1992.dshmobile.core.git.GitSnapshot
import com.qgx1992.dshmobile.core.wire.DshApiClient
import com.qgx1992.dshmobile.core.wire.RemoteStreamMux
import com.qgx1992.dshmobile.data.GitProbe
import com.qgx1992.dshmobile.ui.components.DsButton
import com.qgx1992.dshmobile.ui.components.DsButtonSize
import com.qgx1992.dshmobile.ui.components.DsButtonVariant
import com.qgx1992.dshmobile.ui.components.DsIconButton
import com.qgx1992.dshmobile.ui.components.FeatherIcons
import com.qgx1992.dshmobile.ui.components.SectionHeader
import com.qgx1992.dshmobile.ui.theme.DsSpacing
import com.qgx1992.dshmobile.ui.theme.DsTheme
import com.qgx1992.dshmobile.ui.theme.DsType
import kotlinx.coroutines.CancellationException

private val MonoCaption = DsType.caption11.copy(fontFamily = DsType.codeFont)
private val MonoBody = DsType.small13.copy(fontFamily = DsType.codeFont)

/** What the tab is showing: an answer, a question in flight, or the reason there is no answer. */
internal sealed interface GitTabState {
    data object Loading : GitTabState

    data class Ready(val snapshot: GitSnapshot) : GitTabState

    data class Failed(val message: String) : GitTabState
}

private sealed interface DiffState {
    data object Idle : DiffState

    data object Loading : DiffState

    data class Ready(val text: String) : DiffState

    data class Failed(val message: String) : DiffState
}

/**
 * The working tree of the session's project, read from the host rather than from the harness.
 *
 * The harness has no git endpoint — nothing among its published methods touches a working tree — so
 * this tab asks the host's own shell to run git and reads the answer back (see [GitProbe]). The
 * consequence worth knowing while reading it: the answer is a snapshot, not a live feed. It is taken
 * when the tab opens and when you ask for it again, and a commit the agent makes afterwards shows up
 * on the next ask, not by itself.
 *
 * Read-only by design. Staging, committing and discarding all belong to the agent, which is already
 * sitting in that repository with better error handling than a phone button could offer.
 */
@Composable
internal fun GitTab(
    api: DshApiClient?,
    mux: RemoteStreamMux?,
    sessionId: String?,
    cwd: String?,
    modifier: Modifier = Modifier,
) {
    val colors = DsTheme.colors
    var asked by remember { mutableIntStateOf(0) }
    var state by remember(api, mux, sessionId, cwd) { mutableStateOf<GitTabState>(GitTabState.Loading) }
    var open by remember { mutableStateOf<GitChange?>(null) }
    var diff by remember { mutableStateOf<DiffState>(DiffState.Idle) }

    // Reading the tree runs three git commands on the host and costs a terminal, so it is done on
    // demand: once when the tab first appears, and again only when the reader asks.
    LaunchedEffect(api, mux, sessionId, cwd, asked) {
        val client = api
        val streams = mux
        if (client == null || streams == null || sessionId.isNullOrBlank() || cwd.isNullOrBlank()) {
            state = GitTabState.Failed(NO_HOST)
            return@LaunchedEffect
        }
        state = GitTabState.Loading
        state = try {
            GitTabState.Ready(GitProbe(client, streams, sessionId).snapshot(cwd))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GitTabState.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    LaunchedEffect(open, api, mux, sessionId, cwd) {
        val change = open
        val client = api
        val streams = mux
        if (change == null || client == null || streams == null || sessionId.isNullOrBlank() || cwd.isNullOrBlank()) {
            diff = DiffState.Idle
            return@LaunchedEffect
        }
        diff = DiffState.Loading
        diff = try {
            DiffState.Ready(GitProbe(client, streams, sessionId).diff(cwd, change))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiffState.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    Box(modifier.fillMaxSize()) {
        val change = open
        if (change != null) {
            GitDiffPane(
                change = change,
                diff = diff,
                onBack = { open = null },
            )
        } else {
            GitOverview(
                state = state,
                cwd = cwd,
                onRefresh = { asked++ },
                onOpenChange = { open = it },
            )
        }
    }
}

@Composable
private fun GitOverview(
    state: GitTabState,
    cwd: String?,
    onRefresh: () -> Unit,
    onOpenChange: (GitChange) -> Unit,
) {
    val colors = DsTheme.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    FeatherIcons.GitBranch,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = state.location(cwd),
                    style = MonoCaption,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                DsIconButton(
                    icon = FeatherIcons.RefreshCw,
                    contentDescription = stringResource(R.string.git_refresh),
                    onClick = onRefresh,
                    iconSize = 16.dp,
                )
            }
        }

        when (state) {
            is GitTabState.Loading -> item(key = "loading") {
                Notice(stringResource(R.string.git_loading))
            }

            is GitTabState.Failed -> {
                val offline = state.message.isBlank()
                item(key = "failed") {
                    FailedNotice(
                        message = if (offline) stringResource(R.string.git_offline) else state.message,
                        onRetry = if (offline) null else onRefresh,
                    )
                }
            }

            is GitTabState.Ready -> {
                val snapshot = state.snapshot
                val error = snapshot.error
                if (error != null) {
                    item(key = "error") { FailedNotice(message = error, onRetry = onRefresh) }
                } else {
                    snapshot.head?.let { head ->
                        item(key = "head") { HeadLine(head) }
                    }
                    item(key = "changes-title") {
                        SectionHeader(
                            title = stringResource(R.string.git_changes),
                            action = snapshot.changes.size.toString(),
                        )
                    }
                    if (snapshot.changes.isEmpty()) {
                        item(key = "changes-empty") { Notice(stringResource(R.string.git_no_changes)) }
                    }
                    items(
                        count = snapshot.changes.size,
                        key = { index -> "c-" + snapshot.changes[index].code + snapshot.changes[index].path },
                    ) { index ->
                        val change = snapshot.changes[index]
                        ChangeRow(change = change, onClick = { onOpenChange(change) })
                    }
                    item(key = "commits-title") {
                        SectionHeader(
                            title = stringResource(R.string.git_commits),
                            action = snapshot.commits.size.toString(),
                        )
                    }
                    if (snapshot.commits.isEmpty()) {
                        item(key = "commits-empty") { Notice(stringResource(R.string.git_no_commits)) }
                    }
                    items(
                        count = snapshot.commits.size,
                        key = { index -> "h-" + snapshot.commits[index].shortHash },
                    ) { index ->
                        CommitRow(snapshot.commits[index])
                    }
                }
            }
        }
    }
}

/** The `##` line: the branch, and what it is ahead of or behind. */
@Composable
private fun HeadLine(head: GitHead) {
    val colors = DsTheme.colors
    val upstream = head.upstream
    val distance = buildString {
        if (head.ahead > 0) append("↑").append(head.ahead)
        if (head.ahead > 0 && head.behind > 0) append(' ')
        if (head.behind > 0) append("↓").append(head.behind)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val label = when {
            head.unborn -> head.label
            head.detached -> stringResource(R.string.git_detached)
            else -> head.label
        }
        Text(label, style = DsType.small13Strong, color = colors.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (upstream != null) {
            Text("→ $upstream", style = MonoCaption, color = colors.labelTertiary, maxLines = 1)
        }
        if (distance.isNotEmpty()) {
            Text(distance, style = MonoCaption, color = colors.accent, maxLines = 1)
        }
        if (head.unborn) {
            Text(stringResource(R.string.git_no_commits_yet), style = MonoCaption, color = colors.labelTertiary, maxLines = 1)
        }
    }
}

/** One uncommitted entry, keeping both porcelain columns: staged and unstaged are different facts. */
@Composable
private fun ChangeRow(change: GitChange, onClick: () -> Unit) {
    val colors = DsTheme.colors
    val codeColor = when {
        change.conflicted -> colors.error
        change.untracked -> colors.labelTertiary
        change.staged -> colors.accent
        else -> colors.labelSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            change.code,
            style = MonoCaption,
            color = codeColor,
            modifier = Modifier.width(18.dp),
            textAlign = TextAlign.Center,
        )
        Column(Modifier.weight(1f)) {
            Text(change.path, style = MonoBody, color = colors.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            change.originalPath?.let { original ->
                Text(
                    stringResource(R.string.git_renamed_from, original),
                    style = MonoCaption,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CommitRow(commit: GitCommit) {
    val colors = DsTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(commit.shortHash, style = MonoCaption, color = colors.labelTertiary)
        Column(Modifier.weight(1f)) {
            Text(commit.subject, style = DsType.small13, color = colors.labelPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Git's own relative date, not one of ours: it stays true on a phone whose clock, time
            // zone or idea of "now" has nothing to do with the host's.
            Text(
                "${commit.author} · ${commit.relativeTime}",
                style = MonoCaption,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One file's diff, one host round trip on open.
 *
 * Rendered line by line rather than as one text block: a diff of a large file is thousands of lines
 * and a single [Text] would lay every one of them out before the first scroll.
 */
@Composable
private fun GitDiffPane(change: GitChange, diff: DiffState, onBack: () -> Unit) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DsButton(
                text = stringResource(R.string.git_back),
                onClick = onBack,
                variant = DsButtonVariant.Outline,
                size = DsButtonSize.Small,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                change.path,
                style = MonoCaption,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.borderL1),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            when (diff) {
                is DiffState.Loading -> item(key = "diff-loading") { Notice(stringResource(R.string.git_diff_loading)) }
                is DiffState.Failed -> item(key = "diff-failed") { FailedNotice(diff.message, onRetry = null) }
                is DiffState.Ready -> {
                    val lines = diff.text.lines()
                    if (diff.text.isBlank()) {
                        item(key = "diff-empty") { Notice(stringResource(R.string.git_diff_empty)) }
                    }
                    items(count = lines.size, key = { index -> "l$index" }) { index ->
                        val line = lines[index]
                        Text(
                            line.ifEmpty { " " },
                            style = MonoCaption,
                            color = when {
                                line.startsWith("+++") || line.startsWith("---") -> colors.labelTertiary
                                line.startsWith("+") -> colors.accent
                                line.startsWith("-") -> colors.error
                                line.startsWith("@@") -> colors.labelSecondary
                                else -> colors.labelPrimary
                            },
                        )
                    }
                }
                DiffState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    val colors = DsTheme.colors
    Text(
        text,
        style = MonoCaption,
        color = colors.labelTertiary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

@Composable
private fun FailedNotice(message: String, onRetry: (() -> Unit)?) {
    val colors = DsTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Text(message.trim(), style = MonoCaption, color = colors.error)
        if (onRetry != null) {
            Spacer(Modifier.height(6.dp))
            DsButton(
                text = stringResource(R.string.git_retry),
                onClick = onRetry,
                variant = DsButtonVariant.Outline,
                size = DsButtonSize.Small,
            )
        }
    }
}

/** Where the answer came from: the repository root once git has said where it is. */
private fun GitTabState.location(cwd: String?): String = when (this) {
    is GitTabState.Ready -> snapshot.root ?: cwd.orEmpty()
    else -> cwd.orEmpty()
}

/**
 * No host to ask: the tab still draws, and says so instead of spinning forever on a session that
 * has no connection. Kept as a marker rather than a sentence so the message stays translatable.
 */
private const val NO_HOST = ""
