# DSH Mobile 0.13.0 validation

Status: local validation complete; JVM tests, lint, debug assembly, real-host
conformance, Android instrumentation and UX verification passed within the limits
recorded below. Updated October 8, 2026.
Delivered app version: `0.13.0`, version code: `1300`; debug application ID:
`com.qgx1992.dshmobile.debug`. Minimum Android API: 26.

Referenced `build/` reports, screenshots and logs are local validation artifacts
that are not in the repository.

## Target and build

Harness release `0.2.1-alpha.1`, pinned to
[`5badb15009ae1756c3afe0ae0cef1faafc290ccc`](https://github.com/deepseek-ai/deepseek-harness/commit/5badb15009ae1756c3afe0ae0cef1faafc290ccc).
This matches `DshCore.PROTOCOL_BASELINE`, `DshCore.PROTOCOL_COMMIT` and
`core/src/test/resources/protocol/5badb15.json`. The earlier `4878cda.json` fixture
is retained. The previous baseline for the legacy run is `0.2.0-rc.1`, at commit
`4878cdabd87d4041bdaff61d04c966883b9fd07a`.

Validation used a local checkout of the pinned harness (`0.2.1-alpha.1`, `5badb15`)
and a local `0.2.0-rc.1` checkout. The conformance suite launches a real host
with isolated state and uses a deterministic model. CI has a separate job that
builds the pinned harness on ubuntu-latest and runs `:conformance:test`. Its first run
failed three model-turn tests because Linux session persistence needs the harness's
native flock addon, which `build:lib` does not build. With `pnpm run build:native-system`
added to the job, all 12 tests passed on CI.

### Functional validation before the UX fixes

Result ledger: `build/validation-0.13.0/gradle-results.md`.
These results precede the UX fixes and are not the latest build totals.
Test totals include skips; the app's 264 tests comprise 261 passes and three skips.
Evidence paths in this table are relative to `build/validation-0.13.0/`.

| Suite or task | Passed | Skipped | Failed | Evidence |
| --- | --- | --- | --- | --- |
| Core JVM (`:core:test`) | 250 | 0 | 0 | `round-H-stream/` JUnit XML |
| Mock harness JVM (`:mock-harness:test`) | 27 | 0 | 0 | `round-H-stream/` JUnit XML |
| App JVM (`:app:testDebugUnitTest`) | 261 | 3 | 0 | `round-H-stream/` JUnit XML |
| Pinned 0.2.1-alpha.1 conformance | 12 | 0 | 0 | `pinned-after-F/`, result ledger |
| Legacy 0.2.0-rc.1 conformance | 8 | 4 | 0 | `legacy-after-F/`, result ledger |
| Android instrumentation | 18 | 0 | 0 | `round-H-connected/` JUnit XML, `round-H-connected.log` |

- JVM total: **538 passed, three skipped, zero failures or errors**. Pinned
  `EventTypeClassificationTest` also passed separately: two tests, no skips or failures,
  recorded in `event-classification-after-F/`.
- Lint (`:app:lintDebug`): **zero errors, 153 warnings**, five more warnings than the
  supplied 0.12.1 baseline of 148. No string-format count, type or validity issues;
  all 11 connection-error translations match the one-address argument.
- Debug app and instrumentation APK assembly passed in `round-H-stream-build.log`.
  The delivered app is version `0.13.0` / `1300`, 24,062,910 bytes. This is a debug
  artifact; no release build or release-signing result is claimed.
- Connected tests passed on an Android 11 (API 30) x86_64 emulator:
  18 passed, zero failures, errors or skips. This includes 15 existing UI regressions
  and three `ComposerNarrowLayoutTest` cases for portrait, landscape and an unbounded
  parent. Only that device appears in the test report; the APK checksum was unchanged
  after instrumentation.
- The three app skips are the existing external-relay tests for self-signed identity,
  pairing/traffic and an unanswered relay address. `DSH_RELAY_SRC` was unset and the
  fallback relay checkout was absent. These are missing-environment skips, not passes.
- The four legacy skips are expected: the three `Harness021ConformanceTest` methods
  and the timed-question conformance method require new surfaces and are excluded by
  `DSH_LEGACY_CONFORMANCE=true`. The other eight conformance tests passed.
- The pre-UX pinned and legacy conformance runs followed the core review fixes.
  Source comparisons verified that subsequent UI changes left the 63 core main/conformance
  source files unchanged through the pre-UX validation build. Their results carry forward
  as protocol evidence, not a device test of later UI changes. The latest UX build
  results are recorded in the next section.

## UX review and fixes

The UX review in `build/validation-0.13.0/ux-findings.md` recorded 24 findings:
17 major and seven minor. Fixes were implemented in two sets. Set A covered Automation,
Plugins and their entry points; Set B covered questions, references, model search and
document properties. The fix-status sections record the implemented changes for UX1-UX24.

Automation now formats schedules and run times for the device locale, provides controls
for each timing kind, and distinguishes refresh from retry. Plugins leads with inventory
and separates installation, updates and operation details into sheets. Timed questions
keep their countdown in the card and retain question/answer context when reopened.
Reference labels hide session URIs; model and reference searches show no-match states.
The changes also add labeled switches and buttons and allocate 48dp targets to timing,
question-header and reference actions.

Round 2 isolated technical identifiers for left-to-right display in RTL layouts without
changing stored text, reference offsets or wire values. Inactive/completed Automation
tasks now show Last run when delivery data exists, rather than an upcoming run. Small
warning text uses a separate theme token: light-mode `#9C5700` on white measures
**5.56:1**, up from 2.79:1. This is a contrast calculation for the token pair, not a
device or TalkBack verdict. Focusing an answer in the expanded sheet now pauses the
countdown using that sheet's window focus; losing the app window releases the pause.

The latest result ledger, `build/validation-0.13.0/gradle-results.md`, under
UX round 2 and its final answer-sheet focus fix, records the following results:

| Suite | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: |
| Core JVM | 250 | 0 | 0 |
| Mock harness JVM | 27 | 0 | 0 |
| App JVM | 287 | 3 | 0 |
| JVM total | **564** | **3** | **0** |

The three skips remain the optional external-relay tests described above. Lint passed
with **0 errors / 149 warnings**, four fewer than the pre-UX build's 153 and one more
than the 0.12.1 baseline of 148;
`build/validation-0.13.0/ux-lint-delta.md` records the comparison.
Debug app and instrumentation APKs both assembled. The final rerun log is
`build/validation-0.13.0/ux-round-2-focus-build.log`; JUnit/lint snapshots are in
`build/validation-0.13.0/ux-round-2/`.

Final connected tests passed **20 of 20**, with zero failures, errors or skips, on
an Android 11 (API 30) x86_64 emulator. This includes two `QuestionWindowFocusTest`
cases for inline and expanded-sheet focus, window loss/regain and disposal, plus
all three `ComposerNarrowLayoutTest` cases. An initial run passed 19 of 20; an
obsolete exact-text selector was updated to use the reference action's accessible
description without changing app code. The final log is
`build/validation-0.13.0/ux-round-2-connected.log`, with XML in `ux-round-2/connected/`.
The APK checksum remained unchanged after instrumentation.

Fresh real-harness conformance passed on the final protocol sources: pinned
`0.2.1-alpha.1` passed **12 of 12**, with no skips; `EventTypeClassificationTest`
passed **2 of 2**; legacy `0.2.0-rc.1` passed **8**, with **4 expected skips**.
All had zero failures or errors. The later answer-sheet focus fix changed no
core/conformance sources. Logs are `ux-round-2-conformance-pinned.log` and
`ux-round-2-conformance-legacy.log` under `build/validation-0.13.0/`, with XML
in `ux-round-2/conformance-pinned/` and `ux-round-2/conformance-legacy/`.
Local runs require pnpm **11.7.0** on PATH, matching CI. An initial pinned run
using pnpm 9 passed 11 tests and failed plugin installation with
`ERR_PNPM_ADDING_TO_ROOT`; rerunning with 11.7.0 passed all 12.

The final UX report, `build/ux-0.13.0/after/UX-VERIFY.md`, records **UX1-UX24 PASS**
against a real `0.2.1-alpha.1` harness. The screenshot matrix at
`build/ux-0.13.0/after/MATRIX.md` contains **241 screenshot/UIAutomator dump pairs**
across light/dark themes, English/Thai/Arabic (RTL) and font scales 1.0/1.3.
Four full combinations cover 42 screens each: light English, dark English,
light Arabic and light English at 1.3. Three reduced combinations cover 10 each;
43 additional English captures document focused checks and retries.

The final question-focus and affected follow-up checks used APK `3432e93b`;
unchanged-screen evidence from `d86b6a40` carries forward. Per-image provenance
is recorded in `build/ux-0.13.0/after/provenance.json`; the full final APK hash is
listed under Local delivery. PASS applies to each finding's recorded evidence,
not every possible input or error branch.

The accessibility audit at `build/ux-0.13.0/after/a11y-audit.md` records semantic
labels and target measurements; descendant labels and clipped bounds require
interpretation. TalkBack speech and traversal were not tested because the image
has no TalkBack. The UX run did not exercise pnpm 11 build-script blocking;
approval identity was verified with a seeded pending policy. Some error branches
were source-reviewed rather than fault-injected. History paging used 30 seeded
records, read as 25 plus five, not 30 executed deliveries.

### Earlier partial evidence: not this round's result

The following observations concern earlier snapshots, are superseded by the current
result ledger, and are not included in this round's passing totals.

| Recorded check | Earlier observation |
| --- | --- |
| Pre-upgrade unit reports | Core: 235 tests, no failures; mock harness: 27, no failures; app: 210 reported, 3 skipped, no failures |
| Pre-upgrade lint | 148 warnings on the unchanged 0.12.1 checkout |
| Later local lint/build work | `:app:lintDebug` followed by `BUILD SUCCESSFUL` |
| API 30 UI regressions | `OK (15 tests)` on a fresh emulator |
| Later conformance attempt | 12 tests completed, 1 failed; timed-question test named in the failure |
| Follow-up conformance attempt | 1 test completed, 1 failed |

An earlier API 37 run encountered an Espresso tooling incompatibility. The API 30
instrumentation pass is not proof of this round's real-host end-to-end flows.

## Protocol and regression coverage

The following implementation and test sources describe the coverage. Execution totals
are recorded above; the Android observations below establish which flows ran through UI.

| Surface | Implementation and regression sources |
| --- | --- |
| Proxy roots and host identity | `HarnessUrl.kt`, `HostConfig.kt`, `HostsStore.kt`, `ProxyTransportTest.kt`, `HostInputTest.kt` |
| Timed questions and late answers | `QuestionSessions.kt`, `ModernQuestions.kt`, `QuestionSessionsTest.kt`, `TimedQuestionConformanceTest.kt` |
| Automation catalog, list, history, update and delete | `HarnessFeatures.kt`, `Harness021Api.kt`, `AutomationTiming.kt`, `Harness021ConformanceTest.kt` |
| Plugin installation, updates, toggles, removal and recovery | `PluginManagerScreen.kt`, `PluginOperations.kt`, `Harness021Api.kt`, `Harness021ConformanceTest.kt` |
| Creator draft using the `cordis` preset | `PluginManagerScreen.kt`, `HarnessFeatures.kt` |
| Persisted draft text and file/folder/session references | `ReferencePicker.kt`, `ComposerRepository.kt`, `SessionStore.kt`, `Harness021UiModelTest.kt` |
| Streamed tool argument previews and shell/diff matching | `PartialArguments.kt`, `ToolRowModel.kt`, `ToolCardMapping.kt`, `Harness021Test.kt` |
| Model search, YAML properties and multiline goals | `SheetModels.kt`, `DocumentFrontmatter.kt`, `Docks.kt`, `Harness021UiModelTest.kt` |
| New strings in 11 locales | `app/src/main/res/values*/strings.xml` |
| Baseline, fixtures, pinned conformance CI and YAML parser dependency | `DshCore.kt`, `PinnedProtocolFixtureTest.kt`, `.github/workflows/ci.yml`, `app/build.gradle.kts` |

Older `0.2.0-rc.1`, `0.1.7-rc.x` and `0.1.6-alpha.x` wire fallbacks remain in the
client. The explicit legacy run sets `DSH_LEGACY_CONFORMANCE=true`; the two test
classes for new 0.2.1 surfaces skip in that mode. This does not constitute a fresh
validation of every older release.

Capability detection now hides controls only for missing capabilities or unavailable
methods. On the real `0.2.0-rc.1` host, `schedule/catalog` returned 404: Automation
disappeared from both entry points while chat, sessions and legacy questions worked.
Regression tests establish that HTTP 403, authentication and transport failures remain
visible; the old-host UI check does not itself exercise every error class.

### Defects found and fixed during validation

The review record in `build/validation-0.13.0/review-findings.md` retains the original
findings and their fix status. All R1-R5 findings are fixed; its opening count describes
the earlier review snapshot. The result ledger records execution of the regression tests.

| Finding | Correction and evidence |
| --- | --- |
| R1: relay pairing discarded proxy prefixes | Health, claims, redirects and saved relay identity preserve the deployment path. `RelayProxyTest` and `HostInputTest` pass; a live relay was not exercised. |
| R2: capability detection hid authorization failures | Only missing-capability errors hide controls. Feature-availability and timed-question tests cover visible 403/auth/transport errors; the old-host E2E check covers actual missing-method gating. |
| R3: later plugin mutations erased unresolved installs | Pending installs persist independently by host/request ID; unknown receipts remain unresolved until explicit dismissal. Regression tests cover persistence, migration and receipt ordering; E2E confirms reattachment without a duplicate install. |
| R4: a late answer receipt restored queued state after settlement | Newer question/inbox projections win over an older receipt, and settled cards clear queued state. Ordering regression tests pass; E2E late answers display settled. |
| R5: the proxy connection heading displayed port 0 | Connection headings show the complete attempted endpoint, including the proxy path. Host-input tests cover the reported address, URLs and IPv6; localized format checks pass. |
| Automation search, status filter and Rules/Records views were missing | Added these controls and checked search, active/inactive filtering, rules and real delivery records in E2E d7. |
| Composer controls were squeezed on narrow or rotated screens | Compact reference chips and a reserved action row keep send and other controls usable. E2E h2/h3 submitted prompts with multiline Thai and references. |
| References inserted after non-space text lost metadata on later edits | Insertion adds the separator and adjusts reference/cursor offsets. E2E f1 retained all three reference types through edits and relaunch. |
| Goal actions ignored the `{ goal }` projection envelope | Current goal references now come from the wrapped projection, with legacy bare forms retained. Regression tests pass; E2E g7 saved a multiline Thai goal and cleared it. This defect predates 0.13.0. |
| Folder reference chips reused a stale listing | Opening a folder clears the previous directory cache. E2E f2 shows the correct directory and a fresh list request. |
| Streamed tool arguments were filtered out before rendering | Filtering and rendering share the preview predicate; the durable card replaces the provisional preview. Final-APK E2E g3 shows incomplete arguments followed by one shell card. |

## Live direct connection on Android

The E2E report at `build/e2e-0.13.0/E2E-REPORT.md` records **42 PASS, zero FAIL,
zero NOT RUN**, with screenshots, UI dumps and per-check APK provenance. Tests ran on
an Android 11 (API 30) x86_64 emulator against both real harness
checkouts above. Real `llm-mock-server` turns supplied deterministic model responses.
The app exchanged a launch-token URL for the host session; no token is included here.
Direct connections used `10.0.2.2:3190`; the reverse proxy used `10.0.2.2:3192/dsh/`
for HTTP and WebSocket traffic. The legacy host used port 3193.

The pre-UX E2E APK had SHA-256
`fc0dfa9d30bd6860d8d40cc10cc5ee82e1bf20ae760ae574c82061b38a8b6d9a`.
Smoke tests on that APK cover launch, a real prompt,
streaming arguments, the durable shell card and a reference preview. Passing evidence
from earlier APKs carries forward for unchanged paths;
affected composer, reference, goal and streaming paths were rechecked after fixes.
The full report identifies each evidence build; not all 42 checks were rerun on the
pre-UX binary. These 42 results are not a rerun of the post-fix UX APK.

| Flow | Observed result |
| --- | --- |
| a: Direct sign-in and sessions | PASS: authenticated session drawer loaded without an auth error. |
| b: Reverse-proxy path and host isolation | PASS: sign-in, sessions, streaming, photo/document uploads and file preview through `/dsh/`; direct and prefix hosts retained separate drafts. |
| c: Timed questions | PASS: countdown, editing pause, timeout continuation and an accepted late answer displayed as settled. |
| d: Automation | PASS: drawer/Details entry, prepared draft sent to create a real task, interval/daily/weekly/cron edits with time zone, search/status filter, Rules/Records and stale-save conflict without overwriting the second client's edit. |
| e: Plugins | PASS: versions, local bundle install, toggles, explicit version update, restart notice, uninstall and visible failure. Force-stop recovery called `waitForInstall` with the original request and did not reinstall. |
| f: Drafts and references | PASS: file/folder/session references and Thai text survived editing, navigation and force-stop/relaunch; each chip opened its intended target. |
| g: Chat and related controls | PASS: real shell/diff cards, partial argument preview, model search, valid YAML properties, malformed YAML source fallback, multiline goal edit/clear and an unsent Creator draft using `cordis`. |
| h: Input, layout and reconnect | PASS: Thai input, usable controls and real sends at 720×1280 and landscape; automatic reconnect after the host restarted on the same port. |
| i: Older host | PASS: real 0.2.0-rc.1 sign-in, sessions, chat and questions; Automation hidden after the host's missing-method response. |

Automation deletion and additional schedule shapes have real-host conformance coverage;
the UI matrix above does not claim those extra actions. Plugin cancellation semantics and
question receipt races have regression/conformance coverage rather than a separate UI
verdict here. The install-recovery UI observed an unknown outcome after the host discarded
its completed receipt; it proves reattachment without duplication, not replay of success.

## Validation limits

- Live relay use, physical devices and paid model inference were not validated.
  UX coverage is limited to the locale/theme/font combinations above on API 30,
  not every supported language or Android version. The three external-relay
  tests skipped; proxy routing tests and direct-host E2E are not a live-relay substitute.
- Composer text and reference metadata persist locally; pending attachments, upload
  state and preview panels remain in memory. Do not report attachment persistence
  from the text/reference serialization checks.
- Creator and New Automation stage drafts. Creating an actual plugin or scheduled
  task requires submitting that draft and the host completing the work.
- Local results say nothing about release signing; the release APK is built and signed by the tag workflow.

### Setup notes

- The harness sandbox could not set workspace ACLs on the repository drive
  (`SetNamedSecurityInfoW`, access denied). Running the harness from a user-owned
  temporary directory let the real tools run.
  This was a harness/test-environment problem, not an app defect.
- API 30 DocumentsUI ignored item taps. Keyboard navigation with Tab/DPAD and SPACE
  selected the photo and document through the real picker; uploads were not substituted
  with API calls.
- The earlier API 37 attempt hit an Espresso tooling incompatibility. API 30 was used
  for this round's UI work; API 37 instrumentation compatibility is not established.
- Photo prompts required the mock model catalog to declare image input. Plugin lifecycle
  checks used an offline local package; they do not establish public-registry availability.
- The E2E report records server/model/proxy cleanup and restored device settings. The
  emulator remained running for the subsequent connected-test run.

## Local delivery

- Device-verified debug APK (UX checks and the 20 connected tests): SHA-256
  `3432e93b5e97728d9f46c65d1433f694e8c0d2dd37c449a3da81f642e2fa508f`.
- After that, four PR review findings were fixed: timed questions now mark their session
  as needing action, a plugin install only blocks its own host, relay discovery keeps a
  proxy prefix for pairing, and the last opened session is remembered per full endpoint.
  Core was unchanged. The JVM suite then passed 569 tests with the same 3 optional skips,
  lint stayed at 0 errors and 149 warnings, and CI's build and conformance jobs passed.
- Final debug APK: `build/delivery/DSH-Mobile-0.13.0-debug.apk`, 24,150,310 bytes, SHA-256:

  ```text
  cef05e370f630f89cc562fc25fecf6c6a52ddc1315fd695ef3de2d20b9053a5d
  ```

  The public release APK is built and signed separately by the tag workflow.

- Checksum: `build/delivery/SHA256SUMS-0.13.0.txt`.
  Archived JUnit XML and lint XML live under `build/validation-0.13.0/`, with final
  unit/lint evidence in `ux-round-2/`.
- Native screenshots, UI dumps, runtime logs and cleanup records are indexed from the
  `build/e2e-0.13.0/E2E-REPORT.md`.
- Build/test/lint commands, earlier failed attempts, fixes and final outcomes are indexed
  from the `build/validation-0.13.0/gradle-results.md`.

Build outputs and disposable-host logs are not tracked in Git. Do not include host
launch credentials in reports or delivery artifacts.
