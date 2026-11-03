package com.qgx1992.dshmobile.core.git

/**
 * What one `git status`/`git log` probe found, as porcelain text turned into values.
 *
 * The harness exposes no git endpoint — not in 0.2.1-rc.2, not anywhere in the 144 methods it
 * publishes — so the client asks the host's own shell to run `git` and reads the answer back as a
 * file. That makes the parsing here the only thing standing between the tab and the text git
 * writes, and it is deliberately the whole of the logic: [GitOutput] is pure, and
 * `GitOutputTest` feeds it captured output from real repositories rather than mocks.
 */
data class GitSnapshot(
    /** Absolute path of the repository root, as the host printed it. */
    val root: String? = null,
    val head: GitHead? = null,
    val changes: List<GitChange> = emptyList(),
    val commits: List<GitCommit> = emptyList(),
    /** `fatal: not a git repository …`, `git: command not found` — shown as it came, never guessed at. */
    val error: String? = null,
) {
    val isRepository: Boolean get() = error == null && (root != null || head != null)

    val hasChanges: Boolean get() = changes.isNotEmpty()
}

/** The `## …` line of `git status --short --branch`. */
data class GitHead(
    /** The branch name, or `HEAD` when the repository is checked out at a commit. */
    val label: String,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val detached: Boolean = false,
    /** A repository with no commits yet; git prints `## No commits yet on main`. */
    val unborn: Boolean = false,
)

/**
 * One uncommitted entry, kept as the two porcelain columns rather than as a flattened state.
 *
 * `MM` (staged edit plus a further edit), `R ` (staged rename) and ` M` (unstaged edit) are
 * different things to someone deciding what to commit, and a single enum would have to lie about
 * one of them.
 */
data class GitChange(
    val index: Char,
    val worktree: Char,
    val path: String,
    /** The old path of a rename or copy, when porcelain reported one. */
    val originalPath: String? = null,
) {
    val untracked: Boolean get() = index == '?' && worktree == '?'
    val conflicted: Boolean
        get() = index == 'U' || worktree == 'U' ||
            (index == 'A' && worktree == 'A') ||
            (index == 'D' && worktree == 'D')

    /** Staged work: the index column moved away from the tree. */
    val staged: Boolean get() = !untracked && index != ' '

    /** Work that is still only in the working tree. */
    val unstaged: Boolean get() = !untracked && worktree != ' '

    /** The porcelain letters, for a two-character badge — `??` for an untracked file. */
    val code: String get() = "$index$worktree"
}

/** One row of `git log --format='%h%x1f%s%x1f%ar%x1f%an'`; time and author are blank under cmd. */
data class GitCommit(
    val shortHash: String,
    val subject: String,
    /** Git's own relative date — `3 days ago` — which needs no clock of ours to stay true. */
    val relativeTime: String = "",
    val author: String = "",
)

/**
 * Reads the probe file the host's shell wrote.
 *
 * Sections are marked with [STATUS], [LOG] and [ROOT] so one file can carry all three, and one
 * atomic rename at the end means the file only ever appears complete. Two encodings have to be
 * survived: PowerShell's `Set-Content -Encoding utf8` writes a BOM and CRLF under 5.1 and no BOM
 * under 7, while a POSIX shell writes plain UTF-8 with LF.
 */
object GitOutput {
    const val STATUS = "@@STATUS@@"
    const val LOG = "@@LOG@@"
    const val ROOT = "@@ROOT@@"

    /** Sentinels git itself can never print, used to split the combined probe file. */
    private val MARKERS = setOf(STATUS, LOG, ROOT)

    /** Strip what the two shells add around the text the parser cares about. */
    fun normalize(text: String): String =
        text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

    /** Parse the whole probe file. Missing sections are treated as empty, never as an error. */
    fun parse(text: String): GitSnapshot {
        val sections = split(normalize(text))
        val status = sections[STATUS].orEmpty()
        val log = sections[LOG].orEmpty()
        val rootLines = sections[ROOT].orEmpty()

        failure(status, log, rootLines)?.let { return GitSnapshot(error = it) }

        return GitSnapshot(
            root = rootLines.firstOrNull { it.isNotBlank() }?.trim()?.takeIf { it.isNotEmpty() },
            head = status.firstOrNull { it.startsWith("## ") }?.let { parseHead(it.removePrefix("## ")) },
            changes = status.mapNotNull(::parseChange),
            commits = log.mapNotNull(::parseCommit),
        )
    }

    /** Parse one `## ` payload, with the prefix already removed. */
    fun parseHead(raw: String): GitHead? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        if (line.startsWith("HEAD (no branch)")) return GitHead(label = "HEAD", detached = true)
        if (line.startsWith("No commits yet on ")) {
            return GitHead(label = line.removePrefix("No commits yet on ").trim(), unborn = true)
        }

        val bracket = BRACKET.find(line)
        val branchPart = if (bracket == null) line else line.substring(0, bracket.range.first)
        val flags = bracket?.groupValues?.get(1).orEmpty()
        val parts = branchPart.split("...", limit = 2)
        // `[gone]` means the branch was tracking an upstream that no longer exists: git still
        // prints the old name, but showing it as the upstream would promise a branch that is there.
        val upstream = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() && !flags.contains("gone") }
        return GitHead(
            label = parts[0].trim(),
            upstream = upstream,
            ahead = AHEAD.find(flags)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            behind = BEHIND.find(flags)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Parse one `--short` line, or null for anything that is not one: the `##` header, an
     * `!!` line (only printed with `--ignored`), or blank padding.
     */
    fun parseChange(line: String): GitChange? {
        if (line.length < 4) return null
        // The `## branch` header is only two characters of "code", so it would parse if not refused.
        if (line.startsWith("## ")) return null
        val index = line[0]
        val worktree = line[1]
        if (line[2] != ' ') return null
        if (index == '!' && worktree == '!') return null
        val raw = line.substring(3)
        val arrow = raw.indexOf(ARROW)
        val original = if (arrow > 0) raw.substring(0, arrow) else null
        val path = if (arrow > 0) raw.substring(arrow + ARROW.length) else raw
        // The probe writes its own output inside the working tree, so its files show up as
        // untracked while it runs. Dropping them by name keeps the list honest without hiding a
        // directory the user might genuinely have.
        if (path == GitScripts.TEMP_DIR || path.startsWith(GitScripts.TEMP_DIR + "/")) return null
        return GitChange(index = index, worktree = worktree, path = path, originalPath = original)
    }

    /** Parse one `%h%x1f%s%x1f%ar%x1f%an` line, or a plain `--pretty=oneline` line from cmd. */
    fun parseCommit(line: String): GitCommit? {
        if (line.isBlank()) return null
        val parts = line.split(UNIT_SEPARATOR)
        if (parts.size < 4) {
            // cmd expands `%ar%` as a variable before git ever sees it, so its probe asks for
            // `--pretty=oneline` instead: "1a2b3c4 subject". Time and author are unknown there.
            val trimmed = line.trim()
            val space = trimmed.indexOf(' ')
            if (space <= 0) return null
            return GitCommit(
                shortHash = trimmed.substring(0, space),
                subject = trimmed.substring(space + 1).trim(),
            )
        }
        return GitCommit(
            shortHash = parts.first().trim(),
            // A subject containing the separator would otherwise be silently truncated; join the
            // middle back together so the row still says what the commit says.
            subject = parts.subList(1, parts.size - 2).joinToString("$UNIT_SEPARATOR").trim(),
            relativeTime = parts[parts.size - 2].trim(),
            author = parts.last().trim(),
        )
    }

    /**
     * `git diff` text as the pane shows it: normalized line endings, no stray trailing blank, and
     * without the `warning: LF will be replaced by CRLF` line git puts above a diff on a machine
     * with `core.autocrlf` set — it describes the checkout, not the change being shown.
     */
    fun parseDiff(text: String): String = normalize(text)
        .trimEnd('\n')
        .lineSequence()
        .dropWhile { it.startsWith("warning:") }
        .joinToString("\n")

    private fun split(text: String): Map<String, MutableList<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed in MARKERS) {
                current = out.getOrPut(trimmed) { mutableListOf() }
            } else {
                current?.add(if (line.endsWith("\r")) line.dropLast(1) else line)
            }
        }
        return out
    }

    private fun failure(status: List<String>, log: List<String>, root: List<String>): String? =
        (status + log + root).firstOrNull { isFailureLine(it) }?.trim()

    private fun isFailureLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("fatal:") ||
            trimmed.startsWith("error: ") ||
            trimmed.contains("not a git repository") ||
            trimmed.contains("command not found") ||
            trimmed.contains("not recognized as an internal or external command")
    }

    private const val UNIT_SEPARATOR = "\u001F"
    private const val ARROW = " -> "
    private val AHEAD = Regex("""ahead (\d+)""")
    private val BEHIND = Regex("""behind (\d+)""")
    private val BRACKET = Regex("""\s\[([^\]]*)]\s*$""")
}
