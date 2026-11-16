package com.qgx1992.dshmobile.core

/** Verified upstream baseline for the mobile wire implementation. */
object DshCore {
    /**
     * The harness release this client's DTOs and call shapes were ported from and verified
     * against.
     *
     * Display-only, and it has no version to compare itself against: 0.1.2 removed
     * `host.describe`, so the harness does not tell a client what it is. Where a shape used to
     * differ between releases this client read the difference off the wire rather than off a
     * version string, and there is no version-shaped branch in the client at all — see
     * `docs/COMPATIBILITY.md`.
     *
     * 0.1.3 moved the baseline for the second time in a row that the older wire cannot follow:
     * session format v2 replaced per-token durable events with one settlement per model attempt,
     * so a client that does not opt into the live assistant stream never sees a reply being
     * written.
     *
     * 0.1.7 is the third: session format v4 flattened the `tool/result` message, and several
     * endpoints this client read moved onto projections or streams. The client still reads the
     * 0.1.6 shapes wherever it can tell them apart on the wire.
     *
     * 0.2.0 moved the baseline without changing anything this client sends or reads. Hosts on
     * 0.1.7-rc.x and 0.1.6 still work as they did with client 0.12.0.
     * 0.2.1 adds automation/plugin management and call-keyed timed questions.
     *
     * 0.2.1-rc.2 is the first baseline this client *writes into* a stream: the terminal moved from
     * `terminal/write` and `terminal/resize` calls onto the uplink of one `terminal/attach` stream.
     * Hosts before 0.2.1-alpha.3 still get the follow-and-call terminal.
     *
     * 0.2.2-rc.3 is the current baseline. 0.2.2-rc.1 folded `schedule/update` and `schedule/delete`
     * into one `schedule/edit` (named `patch`, `expectedUpdatedAt`, Host-level `schedule/list`) and
     * moved `session/attachment` onto the whole stored reference; both changes carry a
     * `capability-unavailable` fallback to the 0.2.1 shapes. The 0.2.2 line also batched the
     * `session/control` projection deltas: one frame now carries a `changes` array of `{key, val}`
     * entries instead of a single `{key, value}`, so this client reads the batch and still reads the
     * old single shape. rc.3 itself (a chat-import plugin plus a Windows updater fix) added no wire
     * change.
     */
    const val PROTOCOL_BASELINE = "0.2.2-rc.3"
    const val PROTOCOL_COMMIT = "38a6a5c965aa99ab525161efb7cc5cf1fe62fced"
}
