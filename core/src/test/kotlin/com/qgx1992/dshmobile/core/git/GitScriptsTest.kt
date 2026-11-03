package com.qgx1992.dshmobile.core.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-liners that run git on the host. They were run by hand against real repositories in both
 * shells before being written here — `bash` on this machine and `powershell.exe` 5.1 — and the text
 * they produce is what [GitOutputTest] parses. Pinning the strings keeps a future edit from quietly
 * changing what git is asked.
 */
class GitScriptsTest {
    private val posix = GitShellDialect.Posix
    private val powershell = GitShellDialect.PowerShell

    @Test
    fun `probe asks for status, log and root in one file`() {
        assertEquals(
            "cd '/work/repo' && mkdir -p '/work/repo/.dsh-mobile-git' && " +
                "{ echo '@@STATUS@@'; " +
                "git --no-optional-locks -c core.quotepath=false status --short --branch --untracked-files=all 2>&1; " +
                "echo '@@LOG@@'; " +
                "git -c core.quotepath=false log -n 15 --format='%h%x1f%s%x1f%ar%x1f%an' 2>&1; " +
                "echo '@@ROOT@@'; " +
                "git rev-parse --show-toplevel 2>&1; " +
                "} > '/work/repo/.dsh-mobile-git/git.tmp' && " +
                "mv -f '/work/repo/.dsh-mobile-git/git.tmp' '/work/repo/.dsh-mobile-git/git.ready'",
            GitScripts.probeCommand("/work/repo", posix),
        )
    }

    @Test
    fun `probe under PowerShell writes the same sections`() {
        val command = GitScripts.probeCommand("/work/repo", powershell)

        // PowerShell 5.1 has no `&&`, so the steps are statements, and the array holds the sections.
        assertTrue(command.startsWith("Set-Location -LiteralPath '/work/repo';"))
        assertTrue(command.contains("New-Item -ItemType Directory -Force -Path '/work/repo/.dsh-mobile-git'"))
        assertTrue(command.contains("'@@STATUS@@'"))
        assertTrue(command.contains("'@@LOG@@'"))
        assertTrue(command.contains("'@@ROOT@@'"))
        assertTrue(command.contains("git --no-optional-locks -c core.quotepath=false status --short --branch --untracked-files=all 2>&1"))
        assertTrue(command.contains("Set-Content -Path '/work/repo/.dsh-mobile-git/git.tmp' -Encoding utf8"))
        assertTrue(command.endsWith("Move-Item -Force '/work/repo/.dsh-mobile-git/git.tmp' '/work/repo/.dsh-mobile-git/git.ready'"))
    }

    @Test
    fun `the result file is renamed into place only once complete`() {
        // A reader polls for `git.ready`, so it has to appear atomically or not at all.
        listOf(posix, powershell).forEach { dialect ->
            val command = GitScripts.probeCommand("/work/repo", dialect)
            assertTrue(command.contains("git.tmp"))
            assertTrue(command.indexOf("git.tmp") < command.indexOf("git.ready"))
        }
    }

    @Test
    fun `diff compares against HEAD, and an untracked file against nothing`() {
        val tracked = GitScripts.diffCommand("/work/repo", "beta.txt", untracked = false, dialect = posix)
        val untracked = GitScripts.diffCommand("/work/repo", "fresh.txt", untracked = true, dialect = posix)

        assertTrue(tracked.contains("diff --no-color HEAD -- beta.txt"))
        assertTrue(untracked.contains("diff --no-color --no-index -- /dev/null fresh.txt"))
        assertTrue(tracked.endsWith("mv -f '/work/repo/.dsh-mobile-git/diff.tmp' '/work/repo/.dsh-mobile-git/diff.ready'"))
    }

    @Test
    fun `cleanup removes the probe directory`() {
        assertEquals("rm -rf '/work/repo/.dsh-mobile-git'", GitScripts.cleanupCommand("/work/repo", posix))
        assertTrue(
            GitScripts.cleanupCommand("/work/repo", powershell)
                .startsWith("Remove-Item -LiteralPath '/work/repo/.dsh-mobile-git' -Recurse -Force"),
        )
    }

    @Test
    fun `quotes a path that contains a quote the way each shell wants`() {
        assertEquals("'it'\\''s here'", GitScripts.quotePosix("it's here"))
        assertEquals("'it''s here'", GitScripts.quotePowerShell("it's here"))
    }

    @Test
    fun `recognizes the host's shell`() {
        assertEquals(powershell, GitScripts.dialectFor("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"))
        assertEquals(powershell, GitScripts.dialectFor("/usr/bin/pwsh", "pwsh"))
        assertEquals(posix, GitScripts.dialectFor("/bin/bash", "bash"))
        assertEquals(posix, GitScripts.dialectFor("/bin/sh"))
        assertEquals(posix, GitScripts.dialectFor(null))
    }

    @Test
    fun `trailing separators in the working directory do not double up`() {
        assertEquals("/work/repo/.dsh-mobile-git", GitScripts.tempDir("/work/repo/"))
        assertEquals("/work/repo/.dsh-mobile-git/git.ready", GitScripts.readyPath("/work/repo"))
    }
}
