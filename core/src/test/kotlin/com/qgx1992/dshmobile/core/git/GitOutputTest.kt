package com.qgx1992.dshmobile.core.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe file is the only thing the Git tab ever reads, so these tests are built from output
 * captured from real repositories rather than from invented strings: a repo with an upstream and
 * two commits ahead, a staged rename, a deleted file, an unstaged edit and an untracked file —
 * plus the same text as PowerShell writes it (BOM and CRLF) and as a directory that is not a
 * repository reports it.
 */
class GitOutputTest {
    private val us = "\u001F"

    private fun logLine(hash: String, subject: String, whenText: String, author: String = "Fixture Author") =
        "$hash$us$subject$us$whenText$us$author"

    private val statusSection = listOf(
        GitOutput.STATUS,
        "## main...origin/main [ahead 2]",
        " M beta.txt",
        "R  delta.txt -> epsilon.txt",
        " D gamma.txt",
        "?? .dsh-mobile-git/git.ready",
        "?? fresh.txt",
    ).joinToString("\n")

    private val probe = statusSection + "\n" + listOf(
        GitOutput.LOG,
        logLine("5ee92e9", "Local change two", "2 minutes ago"),
        logLine("d5efcad", "Local change one", "2 minutes ago"),
        logLine("e1bdd31", "First commit", "2 minutes ago"),
        GitOutput.ROOT,
        "C:/Users/Administrator/AppData/Local/Temp/grepo",
        "",
    ).joinToString("\n")

    @Test
    fun `reads the sections the host wrote`() {
        val snapshot = GitOutput.parse(probe)

        assertNull(snapshot.error)
        assertTrue(snapshot.isRepository)
        assertEquals("C:/Users/Administrator/AppData/Local/Temp/grepo", snapshot.root)
    }

    @Test
    fun `reads the branch line with its upstream and divergence`() {
        val head = GitOutput.parse(probe).head!!

        assertEquals("main", head.label)
        assertEquals("origin/main", head.upstream)
        assertEquals(2, head.ahead)
        assertEquals(0, head.behind)
        assertFalse(head.detached)
    }

    @Test
    fun `reads every kind of uncommitted change`() {
        val changes = GitOutput.parse(probe).changes

        assertEquals(listOf("beta.txt", "epsilon.txt", "gamma.txt", "fresh.txt"), changes.map { it.path })
        assertEquals(" M", changes[0].code)
        assertTrue(changes[0].unstaged)
        assertFalse(changes[0].staged)
        assertEquals("R ", changes[1].code)
        assertEquals("delta.txt", changes[1].originalPath)
        assertTrue(changes[1].staged)
        assertEquals(" D", changes[2].code)
        assertEquals("??", changes[3].code)
        assertTrue(changes[3].untracked)
    }

    @Test
    fun `drops the probe's own files from the change list`() {
        // The probe writes into the working tree, so git reports its files as untracked while it
        // runs; a tab that listed them would show a change the user did not make.
        val listed = GitOutput.parse(probe).changes.map { it.path }

        assertFalse(listed.any { it.startsWith(GitScripts.TEMP_DIR) })
    }

    @Test
    fun `reads the commits with git's own relative dates`() {
        val commits = GitOutput.parse(probe).commits

        assertEquals(3, commits.size)
        assertEquals(GitCommit("5ee92e9", "Local change two", "2 minutes ago", "Fixture Author"), commits[0])
        assertEquals("First commit", commits[2].subject)
    }

    @Test
    fun `reads the same file as PowerShell writes it`() {
        // 5.1's `Set-Content -Encoding utf8` adds a BOM and CRLF; 7 adds neither. One reader for both.
        val text = "\uFEFF" + probe.replace("\n", "\r\n")

        assertEquals(GitOutput.parse(probe), GitOutput.parse(text))
    }

    @Test
    fun `reports a directory that is not a repository as an error`() {
        val text = listOf(
            GitOutput.STATUS,
            "fatal: not a git repository (or any of the parent directories): .git",
            GitOutput.LOG,
            "fatal: not a git repository (or any of the parent directories): .git",
            GitOutput.ROOT,
            "fatal: not a git repository (or any of the parent directories): .git",
        ).joinToString("\n")

        val snapshot = GitOutput.parse(text)

        assertEquals("fatal: not a git repository (or any of the parent directories): .git", snapshot.error)
        assertFalse(snapshot.isRepository)
        assertTrue(snapshot.changes.isEmpty())
    }

    @Test
    fun `reports a host without git as an error`() {
        val snapshot = GitOutput.parse(
            listOf(
                GitOutput.STATUS,
                "git: command not found",
                GitOutput.LOG,
                "git: command not found",
                GitOutput.ROOT,
                "git: command not found",
            ).joinToString("\n"),
        )

        assertEquals("git: command not found", snapshot.error)
    }

    @Test
    fun `reads the branch line in its other shapes`() {
        assertEquals(GitHead(label = "main"), GitOutput.parseHead("main"))
        assertEquals(GitHead(label = "main", upstream = "origin/main"), GitOutput.parseHead("main...origin/main"))
        assertEquals(
            GitHead(label = "main", upstream = "origin/main", behind = 3),
            GitOutput.parseHead("main...origin/main [behind 3]"),
        )
        assertEquals(
            GitHead(label = "main", upstream = "origin/main", ahead = 2, behind = 1),
            GitOutput.parseHead("main...origin/main [ahead 2, behind 1]"),
        )
        // A branch whose upstream was deleted still has a name, and git says so in brackets.
        assertEquals(GitHead(label = "main", upstream = null), GitOutput.parseHead("main...origin/main [gone]"))
        assertEquals(GitHead(label = "HEAD", detached = true), GitOutput.parseHead("HEAD (no branch)"))
        assertEquals(GitHead(label = "main", unborn = true), GitOutput.parseHead("No commits yet on main"))
        assertNull(GitOutput.parseHead("   "))
    }

    @Test
    fun `reads the change columns, including the combinations`() {
        assertEquals(GitChange('M', 'M', "both.txt"), GitOutput.parseChange("MM both.txt"))
        assertEquals(GitChange('A', ' ', "added.txt"), GitOutput.parseChange("A  added.txt"))
        assertEquals(GitChange('U', 'U', "conflict.txt"), GitOutput.parseChange("UU conflict.txt"))
        assertTrue(GitOutput.parseChange("UU conflict.txt")!!.conflicted)
        assertTrue(GitOutput.parseChange("AA conflict.txt")!!.conflicted)
        assertTrue(GitOutput.parseChange("DD conflict.txt")!!.conflicted)
        assertEquals(GitChange(' ', 'M', "dir with space/name.txt"), GitOutput.parseChange(" M dir with space/name.txt"))
        assertEquals(GitChange('R', ' ', "new name.txt", "old name.txt"), GitOutput.parseChange("R  old name.txt -> new name.txt"))
    }

    @Test
    fun `ignores lines that are not changes`() {
        assertNull(GitOutput.parseChange("## main"))
        assertNull(GitOutput.parseChange("!! build/output.bin"))
        assertNull(GitOutput.parseChange(""))
        assertNull(GitOutput.parseChange(" M"))
    }

    @Test
    fun `keeps a subject that contains the field separator`() {
        val commit = GitOutput.parseCommit(logLine("abc1234", "fix: a b c", "1 day ago"))

        assertEquals("abc1234", commit!!.shortHash)
        assertEquals("fix: a b c", commit.subject)
        assertEquals("1 day ago", commit.relativeTime)
        assertEquals("Fixture Author", commit.author)
    }

    @Test
    fun `reads a commit the cmd probe could only ask for as oneline`() {
        // cmd turns `%ar%` into a variable before git runs, so that probe uses `--pretty=oneline`,
        // which prints "hash subject" and nothing else. The row still has to render.
        val commit = GitOutput.parseCommit("5ee92e9 Local change two")!!

        assertEquals("5ee92e9", commit.shortHash)
        assertEquals("Local change two", commit.subject)
        assertEquals("", commit.relativeTime)
        assertEquals("", commit.author)
    }

    @Test
    fun `shows a diff without the line-ending warning above it`() {
        val text = "\uFEFFwarning: in the working copy of 'beta.txt', LF will be replaced by CRLF\r\n" +
            "diff --git a/beta.txt b/beta.txt\r\n--- a/beta.txt\r\n+++ b/beta.txt\r\n@@ -1 +1 @@\r\n-beta\r\n+beta edited\r\n"

        assertEquals(
            "diff --git a/beta.txt b/beta.txt\n--- a/beta.txt\n+++ b/beta.txt\n@@ -1 +1 @@\n-beta\n+beta edited",
            GitOutput.parseDiff(text),
        )
    }

    @Test
    fun `shows an untracked file as fully added`() {
        val text = "diff --git a/fresh.txt b/fresh.txt\nnew file mode 100644\n--- /dev/null\n+++ b/fresh.txt\n@@ -0,0 +1 @@\n+n"

        assertTrue(GitOutput.parseDiff(text).startsWith("diff --git a/fresh.txt b/fresh.txt"))
    }
}
