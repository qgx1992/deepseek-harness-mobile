package com.qgx1992.dshmobile.core.text

/**
 * Line-level diffs parsed out of the patch text a tool call carried.
 *
 * `workspace/changes` says which files a turn touched and nothing more; the wire has no diff
 * method, so a review view has to read the patch text out of the tool arguments — which is what the
 * desktop client's deliverables view does too. Two shapes reach this parser:
 *
 * - a unified diff: `--- a/x`, `+++ b/x`, `@@ -1,2 +1,3 @@ heading`, with `Binary files ... differ`
 *   and `GIT binary patch` for files whose content is not text;
 * - an `apply_patch` script: `*** Begin Patch`, `*** Update File: x`, `*** Add File: x`,
 *   `*** Delete File: x`, `*** End Patch`, with `@@` sections whose headers may or may not carry
 *   old/new line numbers.
 *
 * Line numbers are derived, never guessed. A hunk whose source states no position — an
 * `apply_patch` section with a bare `@@`, a replacement region replayed from a tool result — is
 * parsed with position 0, and the view leaves that gutter blank instead of printing a number the
 * text never said. The one headerless case where the position *is* known is `*** Add File:`, whose
 * new lines really do start at 1.
 *
 * [parse] never throws: unreadable text yields an empty list. The whole client holds that
 * invariant — unknown data must not crash a session — and patch text is the least trustworthy input
 * it handles.
 */

/** One file's changes. [oldPath] is set only when the patch renames the file. */
data class DiffFile(
    val path: String,
    val oldPath: String? = null,
    val hunks: List<DiffHunk> = emptyList(),
    val binary: Boolean = false,
)

/**
 * One hunk. [oldStart] and [newStart] are the positions the patch states, or 0 when it states none
 * (see the file comment); [oldLines]/[newLines] count the lines the hunk covers. [heading] is the
 * function/section label a `@@` line carries after its closing `@@`.
 */
data class DiffHunk(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val heading: String? = null,
    val lines: List<DiffLine> = emptyList(),
)

/**
 * One line of a hunk. [Context] carries both positions, [Add] the new one, [Remove] the old one; a
 * position of 0 means the patch text did not state it.
 */
sealed interface DiffLine {
    val text: String

    data class Context(override val text: String, val oldNo: Int, val newNo: Int) : DiffLine

    data class Add(override val text: String, val newNo: Int) : DiffLine

    data class Remove(override val text: String, val oldNo: Int) : DiffLine
}

/** Parses [text] as an `apply_patch` script or a unified diff; empty list for anything else. */
fun parse(text: String): List<DiffFile> {
    if (text.isBlank()) return emptyList()
    val lines = normalizedLines(text)
    if (lines.isEmpty()) return emptyList()
    // Both formats are tried, in the order their own markers suggest: a script that carries
    // `***` markers but no readable file section still gets a second chance as a unified diff.
    val applied = runCatching { parseApplyPatch(lines) }.getOrDefault(emptyList())
    if (applied.isNotEmpty()) return applied
    return runCatching { parseUnified(lines) }.getOrDefault(emptyList())
}

// ============================================================================================
// Shared plumbing
// ============================================================================================

/** CRLF and lone-CR endings folded to `\n`, minus the empty tail a trailing newline leaves. */
private fun normalizedLines(text: String): List<String> {
    val unified = if (text.contains("\r\n")) text.replace("\r\n", "\n") else text
    val raw = unified.replace('\r', '\n').split('\n').map { it }
    // `split` on a string ending in a newline yields a trailing "" that is not a line. Dropping it
    // matters: an empty line inside a hunk body reads as a context line, and a phantom one at EOF
    // would inflate the last hunk's counts.
    return if (raw.isNotEmpty() && raw.last().isEmpty()) raw.dropLast(1) else raw
}

/** A file being built. [addedStart] seeds numbering for `*** Add File:` (a new file starts at 1). */
private class FileDraft(
    var path: String,
    var oldPath: String? = null,
    val addedStart: Int? = null,
) {
    val hunks = ArrayList<DiffHunk>()
    var binary = false

    fun toFile(): DiffFile = DiffFile(
        path = path,
        oldPath = oldPath?.takeIf { it != path },
        hunks = hunks.toList(),
        binary = binary,
    )
}

/** A hunk being built; counts the header stated are kept, absent ones are counted from the body. */
private class HunkDraft(
    val oldStart: Int,
    val newStart: Int,
    val oldCount: Int?,
    val newCount: Int?,
    val heading: String?,
) {
    val lines = ArrayList<DiffLine>()

    fun toHunk(): DiffHunk = DiffHunk(
        oldStart = oldStart,
        oldLines = oldCount ?: lines.count { it !is DiffLine.Add },
        newStart = newStart,
        newLines = newCount ?: lines.count { it !is DiffLine.Remove },
        heading = heading,
        lines = lines.toList(),
    )
}

/** Running positions inside a file; 0 means the text never stated a position, so it stays 0. */
private class LineCursor(var oldNo: Int = 0, var newNo: Int = 0) {
    fun nextOld(): Int = value(oldNo).also { if (oldNo > 0) oldNo++ }

    fun nextNew(): Int = value(newNo).also { if (newNo > 0) newNo++ }

    private fun value(v: Int): Int = if (v > 0) v else 0
}

private class HunkHeader(
    val oldStart: Int,
    val oldCount: Int?,
    val newStart: Int,
    val newCount: Int?,
    val heading: String?,
)

private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$""")

/** The `@@ -a,b +c,d @@ heading` header, or null for a bare `@@` section marker. */
private fun parseHunkHeader(raw: String): HunkHeader? {
    val m = HUNK_HEADER.find(raw.trimEnd()) ?: return null
    val oldStart = m.groupValues[1].toIntOrNull() ?: return null
    val newStart = m.groupValues[3].toIntOrNull() ?: return null
    return HunkHeader(
        oldStart = oldStart,
        oldCount = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull(),
        newStart = newStart,
        newCount = m.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull(),
        heading = m.groupValues[5].trim().ifEmpty { null },
    )
}

/**
 * A path as a patch header writes it: no `a/` or `b/` prefix, no trailing timestamp column, no
 * surrounding quotes. Null for `/dev/null`, which is a side, not a file.
 */
private fun cleanPatchPath(raw: String): String? {
    var p = raw.trim()
    if (p.isEmpty()) return null
    val tab = p.indexOf('\t')
    if (tab >= 0) p = p.substring(0, tab).trim()
    if (p.length >= 2 && p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length - 1)
    if (p == "/dev/null") return null
    if (p.startsWith("./")) p = p.substring(2)
    if (p.startsWith("a/") || p.startsWith("b/")) p = p.substring(2)
    return p.trim().ifEmpty { null }
}

/** An `apply_patch` path: literal, so only quotes and padding come off. */
private fun cleanApplyPath(raw: String): String? {
    var p = raw.trim()
    if (p.length >= 2 && p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length - 1)
    return p.trim().ifEmpty { null }
}

/** `--- a/x` immediately followed by `+++ b/x`: a file header, not a removed line. */
private fun isFileHeaderPair(lines: List<String>, i: Int): Boolean =
    lines[i].startsWith("--- ") && i + 1 < lines.size && lines[i + 1].startsWith("+++ ")

/** Anything that ends a hunk body in either format. */
private fun stopsBody(lines: List<String>, i: Int): Boolean {
    val raw = lines[i]
    return raw.startsWith("@@") ||
        raw.startsWith("*** ") ||
        raw.startsWith("diff --git ") ||
        raw.startsWith("---") && isFileHeaderPair(lines, i)
}

/**
 * Consumes hunk body lines from [start] into [draft], advancing [cursor], and returns the index of
 * the first line that is not part of the body.
 *
 * The body ends on the next section marker, or as soon as the line counts its header stated are
 * covered — the latter is what keeps a blank separator after the last hunk from being read as a
 * context line. A blank line inside an unterminated body is a context line whose single leading
 * space was trimmed on its way here, which is the common shape of a patch pasted through a chat
 * message.
 */
private fun consumeBody(
    lines: List<String>,
    start: Int,
    draft: HunkDraft,
    cursor: LineCursor,
): Int {
    var i = start
    var oldSeen = 0
    var newSeen = 0
    while (i < lines.size) {
        if (stopsBody(lines, i)) break
        val raw = lines[i]
        when {
            raw.isEmpty() -> {
                draft.lines += DiffLine.Context("", cursor.nextOld(), cursor.nextNew())
                oldSeen++
                newSeen++
            }
            raw[0] == '+' -> {
                draft.lines += DiffLine.Add(raw.substring(1), cursor.nextNew())
                newSeen++
            }
            raw[0] == '-' -> {
                draft.lines += DiffLine.Remove(raw.substring(1), cursor.nextOld())
                oldSeen++
            }
            raw[0] == ' ' -> {
                draft.lines += DiffLine.Context(raw.substring(1), cursor.nextOld(), cursor.nextNew())
                oldSeen++
                newSeen++
            }
            // "\ No newline at end of file" is a note about the line before it, and that line is
            // already recorded with exactly the text it has. Nothing to keep.
            raw[0] == '\\' -> Unit
            else -> break
        }
        i++
        val old = draft.oldCount
        val new = draft.newCount
        if (old != null && new != null && oldSeen >= old && newSeen >= new) break
    }
    return i
}

// ============================================================================================
// Unified diff
// ============================================================================================

private fun parseUnified(lines: List<String>): List<DiffFile> {
    val files = ArrayList<FileDraft>()
    var pendingOld: String? = null
    var havePendingOld = false
    var gitNew: String? = null
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        when {
            raw.startsWith("diff --git ") -> {
                // `diff --git a/x b/x` — only needed for a file whose body is a binary patch,
                // because that body ships no `---`/`+++` pair.
                val rest = raw.removePrefix("diff --git ").trim()
                val at = rest.lastIndexOf(" b/")
                if (at > 0) {
                    pendingOld = cleanPatchPath(rest.substring(0, at))
                    gitNew = cleanPatchPath(rest.substring(at + 1))
                    havePendingOld = true
                } else {
                    gitNew = null
                }
                i++
            }
            raw.startsWith("--- ") -> {
                pendingOld = cleanPatchPath(raw.substring(4))
                havePendingOld = true
                i++
            }
            raw.startsWith("+++ ") -> {
                val newPath = cleanPatchPath(raw.substring(4))
                val old = if (havePendingOld) pendingOld else null
                val path = newPath ?: old
                if (path != null) files += FileDraft(path = path, oldPath = old)
                pendingOld = null
                havePendingOld = false
                gitNew = null
                i++
            }
            raw.startsWith("@@") -> {
                val target = files.lastOrNull()
                val header = parseHunkHeader(raw)
                // A hunk before any file header, or one this build cannot read, is dropped: a
                // half-attached hunk is worse than none.
                if (target == null || header == null) {
                    i++
                } else {
                    val draft = HunkDraft(
                        oldStart = header.oldStart,
                        newStart = header.newStart,
                        oldCount = header.oldCount,
                        newCount = header.newCount,
                        heading = header.heading,
                    )
                    val cursor = LineCursor(header.oldStart, header.newStart)
                    i = consumeBody(lines, i + 1, draft, cursor)
                    target.hunks += draft.toHunk()
                }
            }
            raw.startsWith("Binary files ") -> {
                // `Binary files a/x and b/x differ`
                val body = raw.removePrefix("Binary files ").removeSuffix(" differ")
                val left = body.substringBefore(" and ", "").trim()
                val right = body.substringAfter(" and ", "").trim()
                val path = cleanPatchPath(right) ?: cleanPatchPath(left)
                if (path != null) files += FileDraft(path = path).also { it.binary = true }
                i++
            }
            raw == "GIT binary patch" -> {
                val path = gitNew ?: files.lastOrNull()?.path
                if (path != null) {
                    val existing = files.lastOrNull()?.takeIf { it.path == path }
                    if (existing != null) existing.binary = true else files += FileDraft(path = path).also { it.binary = true }
                }
                i++
            }
            else -> i++
        }
    }
    return files.map { it.toFile() }
}

// ============================================================================================
// apply_patch
// ============================================================================================

private val APPLY_FILE = Regex("""^\*\*\* (Update|Add|Delete) File: (.+)$""")

private fun parseApplyPatch(lines: List<String>): List<DiffFile> {
    val files = ArrayList<FileDraft>()
    var current: FileDraft? = null
    var open: HunkDraft? = null
    var cursor = LineCursor()

    fun closeHunk() {
        val draft = open ?: return
        current?.hunks?.add(draft.toHunk())
        open = null
    }

    fun startHunk(header: HunkHeader?) {
        closeHunk()
        val file = current ?: return
        if (header != null) {
            cursor = LineCursor(header.oldStart, header.newStart)
        } else if (file.addedStart != null) {
            // `*** Add File:` states its own position: line 1 of a file that did not exist.
            cursor = LineCursor(0, file.addedStart)
        }
        open = HunkDraft(
            oldStart = header?.oldStart ?: 0,
            newStart = header?.newStart ?: (file.addedStart ?: 0),
            oldCount = header?.oldCount,
            newCount = header?.newCount,
            heading = header?.heading,
        )
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val marker = APPLY_FILE.find(raw)
        when {
            marker != null -> {
                closeHunk()
                val kind = marker.groupValues[1]
                val path = cleanApplyPath(marker.groupValues[2])
                current = if (path == null) null else FileDraft(
                    path = path,
                    oldPath = if (kind == "Add") null else path,
                    addedStart = if (kind == "Add") 1 else null,
                )
                current?.let { files += it }
                cursor = LineCursor()
                i++
            }
            raw.startsWith("*** Move to: ") -> {
                // A move renames the file the section above it opened.
                val moved = cleanApplyPath(raw.removePrefix("*** Move to: "))
                if (moved != null && current != null) current?.path = moved
                i++
            }
            raw.startsWith("*** Begin Patch") || raw.startsWith("*** End Patch") -> {
                closeHunk()
                if (raw.startsWith("*** End Patch")) current = null
                i++
            }
            // Any other `***` line is a marker this build does not know how to place; it ends the
            // open hunk and is otherwise skipped.
            raw.startsWith("*** ") -> {
                closeHunk()
                i++
            }
            current == null -> i++
            raw.startsWith("@@") -> {
                startHunk(parseHunkHeader(raw))
                i++
            }
            raw.isNotEmpty() && raw[0] == '\\' -> i++
            else -> {
                if (open == null) startHunk(null)
                val draft = open
                i = if (draft == null) i + 1 else consumeBody(lines, i, draft, cursor)
            }
        }
    }
    closeHunk()
    return files.map { it.toFile() }
}
