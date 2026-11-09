# Agent continuation — HRIS Mobile

Updated 9 November 2026, 10:32 UTC. This file describes current work. Historical
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
| Mobile original | feat/chat-send-timing-20261107 / 2293ff05 | Pushed; native86 accepted/installed |
| Mobile isolated CI | feat/pinned-inspector-ci-20261109 / 6c6aa4ca | Pushed; verify failure under investigation |
| Inspector original | feat/matrix-rust-bindings-20261107 / 2b849217 | Native freeze released; still SDK5 catalog |
| Inspector isolated | feat/sdk6-distribution-20261109 / a6b8236 | Pushed; SDK6 distribution accepted |
| Starter | feat/network-inspector-20261107 / 6157db1 | Pushed; generic worker/identity ports accepted |
| Platform original | feat/communications / 8769b0d0 | Pushed; runtime unchanged |
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
Phone86 installed at10:25 UTC; exact installed APK digest and existing identity verified.
No launch, UI automation, uninstall or data clearing. [Accepted proof](measurements/collaboration-release86-accepted-2026-11-09.json).

## Native86 accepted and installed; prior failures preserved

Current `.work/selected-runtime-auth-trace-native-2026-11-09` completed all four120s
Home/room/resumed/cold observations:110 complete seconds each, active sync, no setup
requests or rejected admissions in those intervals. Paired refresh200×3 uses returned
credentials; otherAccess0 in both subsequent samples. Parent24→25 renews before expiry.
Six original Sent messages and history exhaustion remain after route/resume/cold.
One new Selected86 102110 (SHA933c96193d5c13b7d7590c159390c2a72ad0596aa3b82b0d64e0668ed9bc1cc8)
is sent once,2983.114ms tap→server, peer process stopped; server now SEVEN originals.
All four jobs38867/18238/25926/91581 completed; three remaining handles reaped after
context restoration, phases completion verified from durable record. Strict traffic,
independent review and release acceptance all PASS; no exemptions were used.365 total
request starts across startup/phase work,0 bootstrap/403. Setup during startup remains
a separate cost; one send sample is not p95. Phone installation53784 reaped PASS25.499s.

Latest COMPLETE STOPPED store is `.work/selected-runtime-auth-trace-native-2026-11-09/sender`
at86, all four userdata/encryption files and full AVD. NEVER recreate from85/84/83,
resend Selected86/the six old originals, or send Draft66 092041. Preserve Bima74 too.
Original Mobile/inspector input freeze is released. Local desktop reproduction is active in this CI worktree; check owned processes.

The first native86 upgrade retained identity before a turn interruption. Its resumed
follow-up had one unclassified otherAccess observation and a stale preflight expecting
five events rather than six, stopping BEFORE Send. Both failures remain preserved in
[the prior observation](measurements/collaboration-release86-retained-observation-2026-11-09.json).
The new passive trace does not reproduce that access mismatch; it does NOT explain it.
Native84's Home failure also remains preserved. No gate was weakened or retried in place.

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
a5d74fa3 verify CI42598801511 passed dependency resolution then FAILED: one Android
cancellation test read a shared Boolean before the separately owned MockEngine coroutine
finished; desktop test process aborted134 during AppFlowTest's catalog recovery case.
Artifact13665023876 SHA0f71440f50071349dd9779628da761f8ea7c108e164d38d65faf63366cbba2b3
verified. The transport test now awaits explicit engine finalization. Local18685 passes10 focused
Android host/Desktop cases in206.420s, format/architecture/detekt and4528 app/library
inputs unchanged. Native abort diagnostics retain bounded cores only until backtraces
are captured. Local desktop reproduction is running; abort cause remains unclassified.
[Exact failed/successful scope](measurements/pinned-inspector-ci-test-recovery-2026-11-09.json). No production retry/cancellation behavior was changed. Web-only CI42597096435 also shut down at Wasm optimization; no final artifact
survived. Cause remains unproved. Next web job bounds Binaryen2cores, Gradle1worker/
3GiB/in-process Kotlin and samples closed kernel memory numbers every15s to console.
The exact125 Binaryen source confirms BINARYEN_CORES. 6c6aa4ca web CI42599865437 is running with those bounded resources and live evidence.
No distribution workflow was dispatched.
Audit actual completed jobs and artifacts; do not infer success from compilation alone.

## Next implementation work

1. Native86 is accepted and installed. Preserve its seven originals and record latency
   work separately. One message sample is not a p95 measurement. Startup request cost and
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
