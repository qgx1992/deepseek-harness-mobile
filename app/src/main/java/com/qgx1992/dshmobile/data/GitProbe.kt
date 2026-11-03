package com.qgx1992.dshmobile.data

import com.qgx1992.dshmobile.core.git.GitChange
import com.qgx1992.dshmobile.core.git.GitOutput
import com.qgx1992.dshmobile.core.git.GitScripts
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/** A git question the host could not answer: no terminal, git missing, or nothing came back. */
class GitProbeException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
 */
internal class GitProbe(
    private val api: DshApiClient,
    private val mux: RemoteStreamMux,
    private val sessionId: String,
) {
    /** Everything the tab needs, in one round trip to the host. */
    suspend fun snapshot(cwd: String): GitSnapshot = withContext(Dispatchers.IO) {
        if (cwd.isBlank()) return@withContext GitSnapshot(error = "No working directory for this session")
        val shell = firstShell()
        val dialect = GitScripts.dialectFor(shell?.path, shell?.name)
        withTerminal(shell) { typist ->
            typist.type(GitScripts.probeCommand(cwd, dialect))
            val text = awaitText(GitScripts.readyPath(cwd))
            // Cleanup runs before the terminal closes and never fails the read: the answer is
            // already in hand by this point.
            runCatching { typist.type(GitScripts.cleanupCommand(cwd, dialect)) }
            GitOutput.parse(text)
        }
    }

    /** One file's diff — the same terminal, a second question. */
    suspend fun diff(cwd: String, change: GitChange): String = withContext(Dispatchers.IO) {
        val shell = firstShell()
        val dialect = GitScripts.dialectFor(shell?.path, shell?.name)
        withTerminal(shell) { typist ->
            typist.type(GitScripts.diffCommand(cwd, change.path, change.untracked, dialect))
            val text = awaitText(GitScripts.tempDir(cwd) + "/diff.ready")
            runCatching { typist.type(GitScripts.cleanupCommand(cwd, dialect)) }
            GitOutput.parseDiff(text)
        }
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
                TerminalCreateRequest(id = "git-" + UUID.randomUUID(), cols = 200, rows = 50, shellPath = shell?.path),
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

    /** The host's first advertised shell, which is also the one the probe asks it to start. */
    private suspend fun firstShell(): TerminalShell? =
        (api.terminalShells(sessionId) as? RpcResult.Ok)?.value?.firstOrNull()

    /**
     * Poll for the file the shell is writing.
     *
     * The shell renames its temporary file when the command finishes, so a missing file means
     * "still running" and a present one is always complete. Ten seconds is generous for three git
     * commands on a cold cache and still short enough to end a tab that will never fill.
     */
    private suspend fun awaitText(path: String): String {
        repeat(POLL_ATTEMPTS) { attempt ->
            if (api.workspaceFileStat(sessionId, path) is RpcResult.Ok) return readText(path)
            if (attempt < POLL_ATTEMPTS - 1) delay(POLL_INTERVAL_MS)
        }
        throw GitProbeException("git did not answer within ${POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000} seconds")
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
     * uplink, released by its acks, with the reply's own screen ignored.
     */
    private class Typist(
        private val stream: RemoteStream,
        env: TerminalEnvironment,
        scope: CoroutineScope,
    ) : AutoCloseable {
        private val ledger = TerminalInputLedger(env.maxInputBytes, env.maxBufferedBytes)
        private val mutex = Mutex()
        private val queued = ArrayDeque<String>()
        private var writable = false
        private var failure: Throwable? = null
        private var idle: CompletableDeferred<Unit>? = null
        private val job: Job = scope.launch { receive() }

        /** Type one command and wait until the host has acknowledged every byte of it. */
        suspend fun type(command: String) {
            val waiter = CompletableDeferred<Unit>()
            mutex.withLock {
                failure?.let { throw it }
                queued.addLast(command)
                idle = waiter
                pump()
            }
            withTimeout(ACK_TIMEOUT_MS) { waiter.await() }
            mutex.withLock { failure?.let { throw it } }
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } ?: break
                val frame = decodeFromJsonElement(TerminalFrame.serializer(), raw)
                mutex.withLock { apply(frame) }
            }
            mutex.withLock {
                failure = failure ?: ended ?: GitProbeException("The host closed the terminal")
                idle?.complete(Unit)
                idle = null
            }
        }

        /** Frame handling straight from the duplex terminal contract in [TerminalInputLedger]. */
        private fun apply(frame: TerminalFrame) {
            when (frame.type) {
                "snapshot" -> {
                    ledger.window = frame.inputWindow
                    writable = frame.writable == true && frame.info?.state == "running"
                }
                "state" -> writable = frame.writable == true && frame.info?.state == "running"
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
                    // `\r` and not `\n`: the carriage return is what a terminal sends for Enter.
                    if (!ledger.write(queued.first() + "\r")) break
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
        }
    }

    private companion object {
        const val POLL_ATTEMPTS = 40
        const val POLL_INTERVAL_MS = 250L
        const val PAGE_LINES = 800
        const val MAX_PAGES = 25
    }
}

/** Unwrap a success, or fail with the host's own words. */
private inline fun <T> RpcResult<T>.valueOr(message: (RpcError) -> String): T = when (this) {
    is RpcResult.Ok -> value
    is RpcResult.Err -> throw GitProbeException(message(error))
}
