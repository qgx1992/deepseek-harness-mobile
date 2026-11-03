package com.qgx1992.dshmobile.data

import com.qgx1992.dshmobile.core.git.GitChange
import com.qgx1992.dshmobile.core.git.GitOutput
import com.qgx1992.dshmobile.core.git.GitScripts
import com.qgx1992.dshmobile.core.git.GitShellDialect
import com.qgx1992.dshmobile.core.git.GitSnapshot
import com.qgx1992.dshmobile.core.wire.DshApiClient
import com.qgx1992.dshmobile.core.wire.RemoteStream
import com.qgx1992.dshmobile.core.wire.RemoteStreamException
import com.qgx1992.dshmobile.core.wire.RemoteStreamMux
import com.qgx1992.dshmobile.core.wire.RpcError
import com.qgx1992.dshmobile.core.wire.RpcResult
import com.qgx1992.dshmobile.core.wire.TerminalInputLedger
import com.qgx1992.dshmobile.core.wire.decodeFromJsonElement
import com.qgx1992.dshmobile.core.wire.dto.TerminalCreateRequest
import com.qgx1992.dshmobile.core.wire.dto.TerminalEnvironment
import com.qgx1992.dshmobile.core.wire.dto.TerminalFrame
import com.qgx1992.dshmobile.core.wire.dto.TerminalShell
import com.qgx1992.dshmobile.core.wire.dto.WorkspaceFileRange
import com.qgx1992.dshmobile.core.wire.dto.WorkspaceFileText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/** A git question the host could not answer: no terminal, git missing, or nothing came back. */
class GitProbeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** How far the probe has got, so the tab can say what it is waiting for instead of just spinning. */
internal enum class GitProbeStage { Preparing, Terminal, Typing, Waiting, Reading }

/**
 * Reads a repository on the host by running `git` in a terminal it opens, and reading the result
 * back with `workspaceFiles`.
 *
 * The harness publishes no git endpoint — not one of its 144 methods touches a working tree — so
 * there is nothing to call. What it does publish is a terminal it will type into on our behalf and
 * a file reader that accepts an absolute path, and those two together are enough: the shell writes
 * status, log and root into one file, the client reads that file as text, and
 * [com.qgx1992.dshmobile.core.git.GitOutput] turns it into values.
 *
 * The terminal's own output is deliberately ignored. It is a terminal screen — escape sequences,
 * prompt redraws, the user's aliases — and reading a repository out of that would make a git tab
 * depend on someone's shell theme. The file is the interface.
 *
 * Which shell that is matters, and not in a cosmetic way: a Windows host with no `defaultShell`
 * configured starts `cmd.exe` (terminal-controller's own fallback), so the command has to match the
 * shell the host actually opened. [pickShell] asks for the most capable shell discovery offers and
 * [GitScripts.dialectFor] writes the matching one-liner.
 */
internal class GitProbe(
    private val api: DshApiClient,
    private val mux: RemoteStreamMux,
    private val sessionId: String,
    private val onStage: (GitProbeStage) -> Unit = {},
) {
    /** Everything the tab needs, in one round trip to the host. */
    suspend fun snapshot(cwd: String): GitSnapshot = withContext(Dispatchers.IO) {
        if (cwd.isBlank()) return@withContext GitSnapshot(error = "No working directory for this session")
        val text = ask(cwd, GitScripts.readyPath(cwd)) { dialect ->
            GitScripts.probeCommand(cwd, dialect)
        }
        GitOutput.parse(text)
    }

    /** One file's diff — the same terminal, a second question. */
    suspend fun diff(cwd: String, change: GitChange): String = withContext(Dispatchers.IO) {
        val text = ask(cwd, GitScripts.tempDir(cwd) + "/diff.ready") { dialect ->
            GitScripts.diffCommand(cwd, change.path, change.untracked, dialect)
        }
        GitOutput.parseDiff(text)
    }

    /**
     * Type one command, wait for its file, and report why if it never arrives.
     *
     * The whole exchange is bounded, and a timeout is turned into a [GitProbeException] rather than
     * left as a cancellation: a `TimeoutCancellationException` is a `CancellationException`, so a
     * tab that merely waited too long used to be indistinguishable from a tab the user left, and
     * the screen stayed on "loading" forever. Only a caller that genuinely cancelled still sees a
     * cancellation (the [ensureActive] below re-throws that case).
     */
    private suspend fun ask(
        cwd: String,
        readyPath: String,
        command: (GitShellDialect) -> String,
    ): String {
        onStage(GitProbeStage.Preparing)
        val shells = shells()
        val shell = pickShell(shells, cwd)
        val dialect = GitScripts.dialectFor(shell?.path, shell?.name, cwd)
        onStage(GitProbeStage.Terminal)
        return withTerminal(shell) { typist ->
            val answer: String? = try {
                withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                    onStage(GitProbeStage.Typing)
                    typist.type(command(dialect))
                    onStage(GitProbeStage.Waiting)
                    awaitTextOrNull(readyPath)
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            }
            if (answer == null) throw GitProbeException(describe(shell, shells, dialect, typist))
            onStage(GitProbeStage.Reading)
            // Cleanup runs before the terminal closes and never fails the read: the answer is
            // already in hand by this point. Typing the command is not the same as the shell having
            // run it, so wait for the answer file to go away — otherwise the probe leaves its
            // scratch directory behind in the user's own working tree.
            runCatching {
                typist.type(GitScripts.cleanupCommand(cwd, dialect))
                awaitGone(readyPath)
            }
            answer
        }
    }

    /** Why the probe came back empty, in the terms someone reading a phone screen can act on. */
    private fun describe(
        shell: TerminalShell?,
        shells: List<TerminalShell>,
        dialect: GitShellDialect,
        typist: Typist,
    ): String = buildString {
        append("git did not answer on this host after ")
        append(TOTAL_TIMEOUT_MS / 1000).append("s")
        append(" (shell: ").append(shell?.path ?: "none discovered").append(" · ").append(dialect.name)
        append(" · input ").append(if (typist.isWritable) "accepted" else "not accepted")
        if (shells.isNotEmpty() && shell == null) append(" · discovered: ").append(shells.joinToString { it.name.orEmpty() })
        val tail = typist.tail()
        append(")").append(if (tail.isBlank()) " · the terminal said nothing" else " · the terminal said: $tail")
    }

    /**
     * Open a terminal, run [block], and always close both the attachment and the terminal again.
     *
     * The terminal is created rather than reused: the user may have panels open on their own
     * shells, and typing a git command into one of those would be an intrusion, not a feature.
     */
    private suspend fun <T> withTerminal(shell: TerminalShell?, block: suspend (Typist) -> T): T =
        coroutineScope {
            val env = api.terminalEnvironment(sessionId).valueOr { "This host has no terminal: ${it.message}" }
            val created = api.terminalCreate(
                sessionId,
                TerminalCreateRequest(id = UUID.randomUUID().toString(), cols = 200, rows = 50, shellPath = shell?.path),
            ).valueOr { "This host would not open a terminal: ${it.message}" }
            val stream = mux.open("terminal/attach", api.terminalAttachArgs(sessionId, created.id))
            val typist = Typist(stream, env, this)
            try {
                block(typist)
            } finally {
                typist.close()
                runCatching { api.terminalClose(sessionId, created.id) }
            }
        }

    private suspend fun shells(): List<TerminalShell> =
        (api.terminalShells(sessionId) as? RpcResult.Ok)?.value.orEmpty()

    /**
     * The shell most likely to run a one-liner correctly.
     *
     * Discovery puts the configured or platform default first, and on Windows that default is
     * `cmd.exe` — the one shell whose `%` handling breaks a git format string and whose `&` chaining
     * has no error handling. PowerShell and `sh` are both better at this, so they win when the host
     * lists them, and cmd is the honest last resort rather than the silent first choice.
     */
    private fun pickShell(shells: List<TerminalShell>, cwd: String): TerminalShell? =
        shells.minByOrNull { rank(GitScripts.dialectFor(it.path, it.name, cwd)) }

    private fun rank(dialect: GitShellDialect): Int = when (dialect) {
        GitShellDialect.PowerShell -> 0
        GitShellDialect.Posix -> 1
        GitShellDialect.Cmd -> 2
    }

    /**
     * Poll for the file the shell is writing.
     *
     * The shell renames its temporary file when the command finishes, so a missing file means
     * "still running" and a present one is always complete.
     */
    private suspend fun awaitTextOrNull(path: String): String? {
        repeat(POLL_ATTEMPTS) { attempt ->
            if (api.workspaceFileStat(sessionId, path) is RpcResult.Ok) return readText(path)
            if (attempt < POLL_ATTEMPTS - 1) delay(POLL_INTERVAL_MS)
        }
        return null
    }

    /** Best effort: wait until the probe's scratch file is gone, then stop caring. */
    private suspend fun awaitGone(path: String, attempts: Int = 8) {
        repeat(attempts) { attempt ->
            if (api.workspaceFileStat(sessionId, path) is RpcResult.Err) return
            if (attempt < attempts - 1) delay(POLL_INTERVAL_MS)
        }
    }

    /** Read the whole file, page by page: a long diff does not fit in one page. */
    private suspend fun readText(path: String): String {
        val text = StringBuilder()
        var offset = 1
        repeat(MAX_PAGES) {
            val page: WorkspaceFileText = api
                .workspaceFileRead(sessionId, path, WorkspaceFileRange(offset = offset, limit = PAGE_LINES))
                .valueOr { "Could not read the git result: ${it.message}" }
            text.append(page.text)
            if (page.eof) return text.toString()
            if (!page.text.endsWith("\n")) text.append('\n')
            offset += page.lines.coerceAtLeast(1)
        }
        return text.toString()
    }

    /**
     * Types into the terminal the way the host requires: numbered commands on the attachment's
     * uplink, released by its acks, with the reply's own screen kept only for error messages.
     */
    private class Typist(
        private val stream: RemoteStream,
        env: TerminalEnvironment,
        scope: CoroutineScope,
    ) : AutoCloseable {
        private val ledger = TerminalInputLedger(env.maxInputBytes, env.maxBufferedBytes)
        private val mutex = Mutex()
        private val queued = ArrayDeque<String>()
        // A command longer than one input window has to travel in pieces; the ledger keeps their
        // order, so the shell sees the same bytes either way.
        private val chunkSize = (env.maxInputBytes - 1).coerceIn(256, 8192)
        private val screen = StringBuilder()
        private var writable = false
        private var failure: Throwable? = null
        private var idle: CompletableDeferred<Unit>? = null
        private val job: Job = scope.launch { receive() }

        val isWritable: Boolean get() = writable

        /** The last of what the terminal drew, for the message a failed probe shows. */
        fun tail(): String = synchronized(screen) {
            screen.toString().trim().takeLast(SCREEN_TAIL_CHARS)
        }

        /** Type one command and wait until the host has taken every byte of it. */
        suspend fun type(command: String) {
            if (command.isEmpty()) return
            val waiter = CompletableDeferred<Unit>()
            mutex.withLock {
                failure?.let { throw it }
                val chunks = command.chunked(chunkSize).toMutableList()
                // `\r` and not `\n`: the carriage return is what a terminal sends for Enter.
                chunks[chunks.lastIndex] = chunks.last() + "\r"
                chunks.forEach { queued.addLast(it) }
                idle = waiter
                pump()
            }
            val sent = withTimeoutOrNull(ACK_TIMEOUT_MS) { waiter.await() }
            mutex.withLock { failure?.let { throw it } }
            if (sent == null) {
                throw GitProbeException(
                    "The host stopped taking terminal input after ${ACK_TIMEOUT_MS / 1000}s " +
                        "(shell accepted input: $writable)",
                )
            }
        }

        override fun close() {
            job.cancel()
            stream.cancel()
        }

        private suspend fun receive() {
            var ended: Throwable? = null
            while (true) {
                val raw: JsonElement = try {
                    stream.receive()
                } catch (e: RemoteStreamException) {
                    ended = e
                    null
                } catch (e: CancellationException) {
                    throw e
                } ?: break
                val frame = decodeFromJsonElement(TerminalFrame.serializer(), raw)
                record(frame)
                mutex.withLock { apply(frame) }
            }
            mutex.withLock {
                failure = failure ?: ended ?: GitProbeException("The host closed the terminal")
                idle?.complete(Unit)
                idle = null
            }
        }

        /** Keep a little of what the shell printed, so a failure can quote it. */
        private fun record(frame: TerminalFrame) {
            val text = frame.screen ?: frame.data ?: return
            if (text.isEmpty()) return
            synchronized(screen) {
                if (frame.screen != null) {
                    screen.setLength(0)
                }
                screen.append(stripEscapes(text))
                if (screen.length > SCREEN_TAIL_CHARS * 2) screen.delete(0, screen.length - SCREEN_TAIL_CHARS * 2)
            }
        }

        /** Frame handling straight from the duplex terminal contract in [TerminalInputLedger]. */
        private fun apply(frame: TerminalFrame) {
            when (frame.type) {
                "snapshot" -> {
                    ledger.window = frame.inputWindow
                    writable = frame.writable == true && frame.info?.state == "running"
                }
                "state" -> {
                    val running = frame.info?.state == "running"
                    writable = running && (frame.writable ?: writable)
                }
                "ack" -> if (!ledger.acknowledge(frame.sequence ?: -1L)) {
                    failure = GitProbeException("The terminal lost track of the input order")
                }
                else -> return
            }
            pump()
        }

        private fun pump() {
            if (writable) {
                while (queued.isNotEmpty()) {
                    if (!ledger.write(queued.first())) break
                    queued.removeFirst()
                }
                for (command in ledger.drain()) {
                    if (!stream.send(command)) failure = GitProbeException("The terminal stopped accepting input")
                }
            }
            if (failure != null || (queued.isEmpty() && ledger.inFlight == 0 && ledger.waiting == 0)) {
                idle?.complete(Unit)
                idle = null
            }
        }

        private companion object {
            const val ACK_TIMEOUT_MS = 10_000L
            const val SCREEN_TAIL_CHARS = 400

            /** Terminal output is full of cursor moves; an error message is not the place for them. */
            val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

            fun stripEscapes(text: String): String = text.replace(ANSI, "").replace('\r', '\n')
        }
    }

    private companion object {
        const val POLL_ATTEMPTS = 40
        const val POLL_INTERVAL_MS = 250L
        const val PAGE_LINES = 800
        const val MAX_PAGES = 25
        const val TOTAL_TIMEOUT_MS = 25_000L
    }
}

/** Unwrap a success, or fail with the host's own words. */
private inline fun <T> RpcResult<T>.valueOr(message: (RpcError) -> String): T = when (this) {
    is RpcResult.Ok -> value
    is RpcResult.Err -> throw GitProbeException(message(error))
}
