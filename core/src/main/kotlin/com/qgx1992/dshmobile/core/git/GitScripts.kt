package com.qgx1992.dshmobile.core.git

/** Which shell the host's terminal runs, because the two need different one-liners. */
enum class GitShellDialect { Posix, PowerShell }

/**
 * The shell one-liners that ask the host's machine about its repository.
 *
 * Everything git-related travels this way: the harness publishes no git endpoint, so a terminal
 * session is the only way to run a command, and `workspaceFiles/read` with an absolute path is the
 * only clean way to get text back (the terminal's own output is a terminal screen, escape codes and
 * all). Each command writes into [TEMP_DIR], which the reader drops from `git status` output — see
 * [GitOutput.parseChange].
 *
 * Both dialects were run against real repositories before being written down here; the tests
 * assert the exact strings, so an edit that quietly changes what git is asked gets caught.
 */
object GitScripts {
    /** Directory the probe writes into, relative to the session's working directory. */
    const val TEMP_DIR = ".dsh-mobile-git"

    /** Old-to-new marker git prints for a rename: `R  a.txt -> b.txt`. */
    const val RENAME_ARROW = " -> "

    /** Windows shells quote differently from `sh`; nothing else about the two differs. */
    fun dialectFor(shellPath: String?, shellName: String? = null): GitShellDialect {
        val probe = "${shellPath.orEmpty()} ${shellName.orEmpty()}".lowercase()
        return if (probe.contains("pwsh") || probe.contains("powershell")) {
            GitShellDialect.PowerShell
        } else {
            GitShellDialect.Posix
        }
    }

    /** Where the probe keeps its files for this working directory. */
    fun tempDir(cwd: String): String = cwd.trimEnd('/', '\\') + "/" + TEMP_DIR

    /** The result file, in the absolute form `workspaceFiles/read` accepts. */
    fun readyPath(cwd: String): String = tempDir(cwd) + "/git.ready"

    /**
     * Status, log and repository root in one file.
     *
     * The write lands in `git.tmp` and is renamed when complete, so a reader that polls for
     * `git.ready` can never see half a file.
     */
    fun probeCommand(cwd: String, dialect: GitShellDialect): String {
        val dir = tempDir(cwd)
        val tmp = "$dir/git.tmp"
        val ready = "$dir/git.ready"
        val status = "git --no-optional-locks -c core.quotepath=false status --short --branch --untracked-files=all 2>&1"
        val log = "git -c core.quotepath=false log -n 15 --format='%h%x1f%s%x1f%ar%x1f%an' 2>&1"
        val root = "git rev-parse --show-toplevel 2>&1"
        return when (dialect) {
            GitShellDialect.Posix -> buildString {
                append("cd ").append(quotePosix(cwd)).append(" && mkdir -p ").append(quotePosix(dir))
                append(" && { echo '").append(GitOutput.STATUS).append("'; ").append(status)
                append("; echo '").append(GitOutput.LOG).append("'; ").append(log)
                append("; echo '").append(GitOutput.ROOT).append("'; ").append(root)
                append("; } > ").append(quotePosix(tmp))
                append(" && mv -f ").append(quotePosix(tmp)).append(' ').append(quotePosix(ready))
            }
            GitShellDialect.PowerShell -> buildString {
                append("Set-Location -LiteralPath ").append(quotePowerShell(cwd))
                append("; New-Item -ItemType Directory -Force -Path ").append(quotePowerShell(dir)).append(" | Out-Null")
                append("; @('").append(GitOutput.STATUS).append("'; (").append(status).append("); '")
                append(GitOutput.LOG).append("'; (").append(log).append("); '")
                append(GitOutput.ROOT).append("'; (").append(root).append("))")
                // 5.1 writes a BOM here and 7 does not; the reader strips either.
                append(" | Set-Content -Path ").append(quotePowerShell(tmp)).append(" -Encoding utf8")
                append("; Move-Item -Force ").append(quotePowerShell(tmp)).append(' ').append(quotePowerShell(ready))
            }
        }
    }

    /** One file's diff, against `HEAD` so staged and unstaged work show in one picture. */
    fun diffCommand(cwd: String, path: String, untracked: Boolean, dialect: GitShellDialect): String {
        val dir = tempDir(cwd)
        val tmp = "$dir/diff.tmp"
        val ready = "$dir/diff.ready"
        // An untracked file has nothing to be compared against, so `--no-index` against the null
        // device prints it as fully added instead of printing an empty diff.
        val diff = if (untracked) {
            "git --no-optional-locks -c core.quotepath=false diff --no-color --no-index -- /dev/null $path 2>&1"
        } else {
            "git --no-optional-locks -c core.quotepath=false diff --no-color HEAD -- $path 2>&1"
        }
        return when (dialect) {
            GitShellDialect.Posix -> buildString {
                append("cd ").append(quotePosix(cwd)).append(" && mkdir -p ").append(quotePosix(dir))
                append("; { ").append(diff).append("; } > ").append(quotePosix(tmp))
                append("; mv -f ").append(quotePosix(tmp)).append(' ').append(quotePosix(ready))
            }
            GitShellDialect.PowerShell -> buildString {
                append("Set-Location -LiteralPath ").append(quotePowerShell(cwd))
                append("; New-Item -ItemType Directory -Force -Path ").append(quotePowerShell(dir)).append(" | Out-Null")
                append("; @(").append('(').append(diff).append("))")
                append(" | Set-Content -Path ").append(quotePowerShell(tmp)).append(" -Encoding utf8")
                append("; Move-Item -Force ").append(quotePowerShell(tmp)).append(' ').append(quotePowerShell(ready))
            }
        }
    }

    /** Remove the probe directory once the text has been read. */
    fun cleanupCommand(cwd: String, dialect: GitShellDialect): String {
        val dir = tempDir(cwd)
        return when (dialect) {
            GitShellDialect.Posix -> "rm -rf " + quotePosix(dir)
            GitShellDialect.PowerShell -> "Remove-Item -LiteralPath " + quotePowerShell(dir) + " -Recurse -Force -ErrorAction SilentlyContinue"
        }
    }

    /** Wrap a path in single quotes for `sh`, where a quote inside a quote closes it. */
    fun quotePosix(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Wrap a path in single quotes for PowerShell, where a quote inside a quote is doubled. */
    fun quotePowerShell(value: String): String = "'" + value.replace("'", "''") + "'"
}
