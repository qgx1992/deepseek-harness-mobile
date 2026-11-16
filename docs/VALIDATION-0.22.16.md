# 0.22.16 validation

## Target

- DeepSeek Harness `dsh-v0.2.2-rc.3`, peeled commit
  `38a6a5c965aa99ab525161efb7cc5cf1fe62fced`.
- Separate source worktree: `D:/A-my project/deepseek-harness-rc3`, installed with the
  declared pnpm `11.28.5` (`pnpm install --frozen-lockfile --prefer-offline`) and `pnpm run
  build:lib` completed, so the checkout's `packages/api/gateway/lib/index.js` is present and the
  real-host conformance harness can boot from source.
- Release target: `0.22.16`; publication has not been performed.
- Baseline pin: `DshCore.PROTOCOL_BASELINE = "0.2.2-rc.3"`,
  `DshCore.PROTOCOL_COMMIT = "38a6a5c965aa99ab525161efb7cc5cf1fe62fced"`.

## Evidence so far

The baseline fixture `protocol/38a6a5c.json` is transcribed from rc.3 and is a sibling of the
0.2.1-rc.2 fixture: the two files differ only in the `release` and `upstreamCommit` header lines.
The contract test that pins the live constants was renamed to `Harness022Rc3ContractTest` and reads
the new fixture; the alpha.1 pinned test still checks its own commit and is unaffected.

The one client-visible wire change since 0.2.1-rc.2 is in `session/control`. rc.2 batched the
projection deltas: rc.3 broadcasts one `projection` frame per committed event carrying a `changes`
array of `{key, val}` entries (`SessionProjectionUpdate.changes`, `types.ts:591`; broadcast in
`control.ts:22-30`) where an older host sent a single `{key, value}` frame, and the control baseline
is now `{projections: {sessionId: {asOfSeq, values}}}` with no separate queue/job maps. The client
`SessionControlFrame.Projection` used to require a single `key`/`value`, so every batched delta
failed to decode and all live projections (the queue dock, the task board, jobs, status) stayed
empty while only the unary reads refreshed. It now holds a `changes: List<ProjectionChange>` and
still decodes the legacy single-key frame; `SessionStore` fans each change out to the same
`userQuestions` / inbox dock / agent-preset / projection-merge paths. `SessionControlFrameChangesTest`
pins the batched, legacy and `value`-spelling shapes.

AgentTeam and named subagents already ship in 0.22.15 (the `agentTeam` projection plus
`subagentCatalog`); rc.3 keeps the task board on `agentTeam` and serves members from
`subagentCatalog`, which the existing DTO reads, so no further wire work is required.

## Release gate

JUnit report totals: core 352 tests, zero failures and zero errors, one skipped; app debug unit 446
tests, zero failures and zero errors, three skipped; conformance 15 tests, zero failures and zero
skips. App debug compile and the conformance test compile pass.

Real-host conformance against the rc.3 source passed all 15 tests. The run first surfaced two
failures — `Harness021ConformanceTest` "automation edits…" (its mock model called `schedule_create`
with a stale `every_seconds` argument rc.3 no longer accepts; the fixture now sends `rrule` +
`target`) and `InboxQueueTest` (which exposed the batched-projection decode bug above) — and both
pass after the fixes.

The version name is taken from the release tag (`DSH_VERSION_NAME`); the local `assembleRelease`
fallback in `app/build.gradle.kts` is unchanged. No release build, signing or upgrade install was
performed in this run; those, and the signed artifact verification, remain required before release.

## Scope

Changes cover the protocol baseline pin to 0.2.2-rc.3, batched `session/control` projection delta
compatibility (restoring the queue dock and every live projection on current harness), the
corresponding fixtures and tests, and the documentation set (`COMPATIBILITY.md`, `PROTOCOL.md`,
`CHANGELOG.md`, the six READMEs, the harness guide and the conformance CI ref). AgentTeam is already
covered by 0.22.15; `DESKTOP-PARITY.md` is left as a historical snapshot.
