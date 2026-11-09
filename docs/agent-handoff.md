# Agent continuation — HRIS Mobile

Updated 9 November 2026, 10:11 UTC. This file describes current work. Historical
checkpoints are preserved in [Git at b1630963](https://github.com/fajarxfce/hris-mobile/blob/b1630963/docs/agent-handoff.md).
Do not restart jobs or reapply candidates from that history without checking current state.

## Authorization and working rules

- The user authorized the entire chat, voice/video and meeting plan, long-running
  implementation, an atomic commit and push for each accepted change, and validated
  phone updates. All 54 communications acceptance groups remain open.
- Mobile proves reusable foundations before Starter receives them. Keep HRIS policy
  here. Follow AGENTS.md: domain policy/use-case orchestration, repository mapping,
  datasource I/O, screen state in ViewModels; explicit coroutine/native ownership.
- Notify before installing to `10.77.77.3:5555`. Use the explicit serial and
  `--install-only`; never launch or automate the user's phone UI. Use the owned emulator.
- Run one heavy local build OR emulator. The emulator uses 2560 MiB and two cores.
  Reap owned jobs before another heavy job. Do not kill the live API/worker JVMs.
  Preserve credentials, SDK stores, original messages and unrelated worktree changes.
- No physical iPhone interaction is required, but source/Apple runtime and push/call
  acceptance remain separate. Never describe Android-only proof as Apple acceptance.

## Source and repository state

| Checkout | Branch/source | Current state |
| --- | --- | --- |
| Mobile original | feat/chat-send-timing-20261107 / 2293ff05 | Pushed; frozen for native86 |
| Mobile isolated CI | feat/pinned-inspector-ci-20261109 / b1630963 | Pushed; consumer CI in progress |
| Inspector original | feat/matrix-rust-bindings-20261107 / 2b849217 | Frozen for native86; still SDK5 catalog |
| Inspector isolated | feat/sdk6-distribution-20261109 / a6b8236 | Pushed; SDK6 distribution accepted |
| Starter | feat/network-inspector-20261107 / 6157db1 | Pushed; generic worker/identity ports accepted |
| Platform original | feat/communications / 1c0cb482 | Pushed; runtime unchanged |
| Platform group worktree | feat/communication-space / fdcd783e | Separate unshipped group implementation |

The isolated directories are under `/home/fajar/hris/.worktrees/`:
`mobile-inspector-ci-2026-11-09`, `inspector-sdk6-2026-11-09`, and
`communication-space`. Preserve the inspector worktree's unrelated gradlew.bat delta.

Runtime stays e558a6868399058a2dcdee97acfd0e7fa7f1ef07, schema116.4, readiness control ON.
V117–138 and organization routing are not deployed. An older live-result.json records
control OFF at deployment; the later accepted toggle is the current state.
Crashlytics access is verified; do not ask the user to grant IAM again.

## Accepted runtime fix and exact APK

2293ff05 fixes two publication/selection orderings that prematurely released an
available selected SDK client when its final background borrow closed. The registry
promotes only an available client for the fresh admitted foreground owner. Stale
retention, failed publication, revocation and background-only cleanup stay bounded.
No authority, retry, SDK-opening or synchronization policy is added.

[Source evidence](measurements/collaboration-selected-runtime-publication-2026-11-09.json):
two corrected RED cases fail on the old code; 31 registry cases pass after the fix.
Focused gate passes620 fresh cases (599 Android,21 app); full gate passes693 fresh
with current host reports reused, not added to the focused count. Zero test failures,
errors or skips; root checks, Android compile/lint0, Wasm/iOS source and quality pass.
All4529 app and82 library inputs remained fixed. Source tests use version85; packaging
and actual device proof exercise86. f9cdc708 adds closed activation diagnostics with
privacy, cancellation and no-self-retry tests; it does not change retry behavior.

APK86: `.work/release-phone-2026-11-09-build86/hris-mobile-dev-release-1.0.0-86-arm64.apk`.
SHA256 `4c53315ff2f1f29bc67f572d6f63527622200ad0c6b638ea65746386e03afc73`,
123465103 bytes, nondebug devRelease, unchanged development signer, exact SDK6 native,
16KiB alignment, Firebase/API and compiled backup exclusions verified; lint0.
Initial packaging94519 exhausted its2GiB heap after475.303s; preserved in attempt-1.
Retry7551 with3GiB/one worker passes88.189s; verifiers47872/8311 pass. All reaped.
Phone remains83. No86 phone installation or full native acceptance yet.

## Current native86 observation — no phone acceptance yet

Original `.work/selected-runtime-publication-native-2026-11-09` upgraded85→86,
retaining account/public keys and six originals, then its resumed phase was interrupted.
The full follow-up `.work/selected-runtime-publication-resumed-native-2026-11-09`
retains the same exact APK. All four120s lifecycle intervals pass:110 complete seconds
per phase, active sync, no setup traffic, no bootstrap, no forbidden responses. All six
old Sent messages and history exhaustion survive route/resume/cold. Three refreshes200;
native parent22→23 renews before expiry. Closed startup diagnostics reproduce
RuntimeOpening while published background sync continues.

The overall gate is STILL FAILED: paired sample1 has one unclassified otherAccess
request. The old observer retained only its count, not route/timing/status. Also, the
new-send script still expected5 server events at its first assertion despite actual6,
so it stopped BEFORE input/send. No Selected86 message exists and no old message was
resent. These are separate observation/harness findings, not a claim of application
success or a proved authentication root cause.
[Recorded observation](measurements/collaboration-release86-retained-observation-2026-11-09.json).

All jobs47713/92677/36194/71793 are reaped/stopped. Latest COMPLETE STOPPED store is
`.work/selected-runtime-publication-resumed-native-2026-11-09/sender` at86, all four
userdata/encryption files and full AVD. Never clone an older85/84/83 baseline.

Current proof `.work/selected-runtime-auth-trace-native-2026-11-09` is ACTIVE.
Clone3488 and prepare-counter.py completed; do not repeat either. Owned emulator18238
(PID795640, serial emulator-5562), phases38867, counter25926 and paired observer91581
are running. Original Mobile/inspector inputs remain frozen; no compiler overlap. Its corrected send preflight expects6. Added passive fields
record only closed request route/method, relative timing, known-initial/previously-
returned access classification and response status. Token hashes stay volatile.
Existing strict credential/traffic acceptance is unchanged; seven negative controls
still fail and baseline83 passes. No approval or product policy change is implied.

Local SBOM63401 is reaped. Finish the active proof while preserving input/library freeze.
Inspect all evidence and reaped jobs before accept-quietness.py, review-traffic.py and
accept-release.py. Do not rerun a successful new-send.json. Only actual accepted86
permits the notified build86/install.py --install-only phone update; phone remains83.

Native84's Home failure remains preserved; diagnostic85 did not clear it. The six
originals are Control77 235408, Warm77 235728, Recovery78 005634, Registry82 055959,
Parent83 071123 and History84 083059. Never resend them or Draft66 092041. Preserve
the original Bima74 store even though its AVD version metadata is incomplete.

## Published dependencies and current CI

SDK `26.10.23-hris.6` is [published](https://github.com/fajarxfce/hris-mobile/releases/tag/matrix-sdk-26.10.23-hris.6)
in immutable release438835525. AAR SHA256
a4aa1e2cb3136c0531bb35f7f06df10582c84c1974ce4e5fda7099d0ab533bb3;
Ivy SHA25669ea030963275ce080991e658172934441b73558e9de4d8e7ae3976b2f18558a.
APK stripped ARM64 SHA2564e15c92398521f5f5cd9fbb30cf327019faf32abe5302b64d8b4bdc07d75e9d4.
Do not rebuild or overwrite release assets. Original inspector's SDK5 descriptor404
still requires the verified local mirror for the frozen86 build; that is not public CI.

Inspector742a2d1 uses publicSDK6/strict checksums. CI42591111996 succeeds:226 portable/
Android host cases and69 actual Apple simulator cases, zero failures/errors/skips;
artifact digests/XML verified. Android native fixture compiled, not executed there.
Apple Matrix native binding and capture overhead remain open. a6b8236 records evidence.

Mobile0c771fc5 pins that exact library in all nine consumer jobs across five workflows;
no mirror, no persisted dependency-checkout credentials. CI/control explicitly run
Android collaboration host tests. Existing tooling60 cases and static YAML checks pass.
First application CI42594287486: Windows and Apple succeed. Verified Apple artifact
13664276449 contains1777 tests/195 suites, zero failures/errors/skips, plus successful
Xcode app build. This is not native Matrix Apple binding acceptance.
Web failed when the runner received a shutdown signal during Wasm optimization.
The log does not establish its cause. The new web-only scope repeats no other job
and records /usr/bin/time resource evidence without changing compilation settings.
Verify failed before tests at SBOM resolution: Material3 1.9.0 POM checksum was missing.
Only its official Maven Central/publisher-SHA1-verified POM hash is added in b1630963;
[exact failure evidence](measurements/pinned-inspector-ci-material3-pom-2026-11-09.json).
Corrected verify CI42596014241 failed on the next missing Lifecycle POM. Complete
local inventory66020 passed70.759s and restored metadata; all8 added artifacts were
then verified against official bytes/publisher SHA-1. Strict63401 passes46.014s with
unchanged metadata; the393-component BOM is same-input cache reuse, not fresh execution.
Initial local insertion/strict failure and alias-URL404 are preserved in its measurement.
a5d74fa3 is committed/pushed; verify CI42598801511 is running. Web-only CI42597096435
is still running. No native86/phone86 acceptance has been added.
No distribution workflow was dispatched.
Audit actual completed jobs and artifacts; do not infer success from compilation alone.

## Next implementation work

1. Finish/review native86, deliver the validated APK, and record any unsolved latency
   separately. One message sample is not a p95 measurement. Startup request cost and
   duplicate local acquisition still need measured review; no arbitrary retry patches.
2. Finish public consumer CI, preserve first failures and fix actual causes. Once86
   is complete, integrate verified inspector/CI source into the original checkout.
   Port the generic pinned workflow setup/checksums to Starter after Mobile proof.
   HRIS selected-runtime registry policy does not belong in Starter.
3. Resume organization chat from the current prepared Room intent: bounded original
   dispatch, durable published binding/projection, exact LEGACY metadata validation,
   original SDK delivery/history, workers and product routing. Existing Prepare and
   Resolve room use cases are accepted; do not recreate them. The group backend is in
   the separate worktree; original Platform does not contain V117–138.
4. Continue rich message actions/media/stickers/voice, encrypted Drive/iCloud recovery,
   native MatrixRTC call lifecycle, LiveKit meetings and the remaining PRD. The source
   of scope is Platform docs/collaboration/prd.md and delivery.json. No group is complete.

Keep this current file concise and replace stale status instead of appending another
full history. Commit measurements with implementation; historical details stay in Git.
