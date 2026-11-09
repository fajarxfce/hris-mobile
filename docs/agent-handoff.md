# Agent continuation — HRIS Mobile

Updated 9 November 2026, 11:36 UTC. This file describes current work. Historical
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
| Mobile original | feat/chat-send-timing-20261107 / Room4e7be081 + current joined-room change | Both source gates passed; native87 pending |
| Mobile isolated CI | feat/pinned-inspector-ci-20261109 / db9287a9 | Pushed; verify42609635524 active |
| Inspector original | feat/matrix-rust-bindings-20261107 / f8ca3d6 | Verified public SDK6 integrated/pushed |
| Inspector isolated | feat/sdk6-distribution-20261109 / a6b8236 | Pushed; SDK6 distribution accepted |
| Starter | feat/network-inspector-20261107 / 6157db1 | Pushed; generic worker/identity ports accepted |
| Platform original | feat/communications / 85e5fe8f | Pushed; runtime unchanged |
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
Original Mobile/inspector input freeze is released. Check owned-heavy-processes.py before new work.

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
Do not rebuild or overwrite release assets. APK86 used the verified local mirror while
its original inspector still had the SDK5 catalog. The original inspector now f8ca3d6
integrates the verified public SDK6 source; current Room gates use that public catalog.

Inspector source742a2d1: CI42591111996 passes226 portable/Android host and69 Apple
simulator cases; downloaded artifact digests and XML verified. Apple Matrix native
binding and capture overhead remain open. Mobile consumer jobs pin that exact source
in all nine jobs across five workflows in the isolated CI worktree. First consumer
Apple CI42594287486 passed1,777 cases and Xcode app build; Windows also passed.
Web42599865437 at6c6aa4ca now passes the optimized production distribution in23m35.39s.
Its two artifacts, all26 web file hashes and95 memory samples are verified. Earlier
runner shutdown causes remain unclassified; launcher RSS is not compiler/Binaryen RSS.

Full verify failures were traced to strict SBOM checksums (eight official artifacts
verified), a cancellation test observing a separately owned coroutine too early,
and inconsistent notification navigation fixture data. A prior desktop SIGABRT remains
unclassified; bounded native backtraces are retained. Fifth verify42604884828 at3657ada9
failed2 of5,969 artifact XML cases. Its digest is verified. The test initially seeded
three conversations but published one and raced synchronization; the first wait-only
correction was insufficient. A document denial test clicked while Refresh was disabled
because mock HTTP completion preceded the owning acquisition's completion.

Isolated db9287a9 makes the navigation fixture's three-row list explicit while retaining
the other callers' default, and waits for enabled Refresh before the denial action.
All exact row/redaction/recovery assertions remain. Pinned formatting/source review
pass; local `fixtures-1` is running in that checkout (session68869). Sixth full hosted
verify42609635524/job129134456501 is active. No production behavior or timeout changed.
[Isolated checkpoint and evidence](https://github.com/fajarxfce/hris-mobile/blob/db9287a9/docs/agent-handoff.md).
These hosted runs do not contain root Room dispatch or the joined-room change. No
application distribution workflow was dispatched. Integration/Starter ports follow
completed job and artifact review, after the current87 source/artifact proof is stable.

## Current Room dispatch

The current increment adds one bounded original Room attempt through existing domain
repositories, exact durable uncertainty before HTTP, normalized mark readback,
cancellation-independent receipt capture and retained global denial. No replacement,
poll loop, SDK delivery or product route is introduced. Preparation and resolution
remain separate accepted operations. [Contract and evidence](collaboration-space-room-dispatch.md).

All jobs are reaped: focused34481 passes76 fresh cases in263.919s; full30025 passes5,963
fresh cases (5,364 desktop,599 Android host) in902.068s, zero failures/errors/skips and
release lint0; actual Chromium97424 passes74 cases in115.038s, including18 replay and11
actual IndexedDB storage cases. Root check, Android release compile, Wasm/iOS source,
format/architecture/Detekt pass. All4,532 app inputs and inspector inputs stayed fixed.
These new cases have not yet executed on Apple. The Room increment is pushed as4e7be081.

## Joined-room acquisition and next APK87

The current source moves raw joined-room acquisition into its datasource. SDK-local
Joined rooms retain their returned handle; other states close their lookup before
one existing join. Cancellation/failed membership/disposal/late rejection preserve
resource ownership. Current HRIS admission, queues, keys and transaction IDs are
unchanged. [Contract and exact evidence](collaboration-joined-room-entry.md).

RED36143 retained the previous unconditional join and failed its one expected case;
this is an equivalent extracted operation, not an unmodified application checkout.
Focused41563 passes611 actual Android host cases in71.209s, including10 raw adapter
and2 actual parent/child tests. Full3347 passes5,975 fresh cases (5,364 desktop,611 host)
in1,290.936s; zero failures/errors/skips/lint. Root check, Android release compilation,
Wasm/iOS source and quality pass. All4,535 app and inspector inputs stay fixed; all
jobs reaped. Focused uses build86 parameters, full uses87. No native speedup claimed yet.

Packaging scripts are prepared in `.work/release-phone-2026-11-09-build87`; they require
this accepted source pushed, exact full fingerprints and public SDK6. No APK87 built
yet. `.work/joined-room-entry-native-2026-11-09` holds22 parsed prepared scripts and6
classifier checks. It will clone the COMPLETE STOPPED native86 sender, preserve7
originals, upgrade86→87, run the retained four-phase/paired-refresh gate, and send only
one new Joined87 original. New passive room_join classification must show no join
between the new Send and observed completion. Current worker control ON is checked
separately from the historical deployment's OFF record. No clone, boot or Send yet.

Keep root app/inspector inputs fixed through packaging and native acceptance. The
current one-heavy slot is the isolated CI fixture gate68869; after it is reaped,
package87, verify its SDK/signature/backup rules, run native proof, then notify and
install-only to the authorized phone. Phone86 and latest stopped native86 remain
current until those checks actually finish. Never resend originals or Draft66.

## Next implementation work

1. Native86 is accepted and installed. Preserve its seven originals and record latency
   work separately. One message sample is not a p95 measurement. One observed send takes2,983ms to server; the encrypted send HTTP header takes145ms.
   Stage traces show setup522ms and timeline entry704ms including room acquisition509ms.
   The SDK join method always sends HTTP. The new raw-room reuse source now passes
   its host/full gates above; actual before/after native measurement remains required.
2. Finish public consumer CI, preserve first failures and fix actual causes. Once86
   is complete, integrate verified inspector/CI source into the original checkout.
   Port the generic pinned workflow setup/checksums to Starter after Mobile proof.
   HRIS selected-runtime registry policy does not belong in Starter.
3. Continue organization chat after the accepted original Room dispatch: durable
   published binding/projection and exact LEGACY metadata validation,
   original SDK delivery/history, workers and product routing. Existing Prepare and
   Resolve room use cases are accepted; do not recreate them. The group backend is in
   the separate worktree; original Platform does not contain V117–138.
4. Continue rich message actions/media/stickers/voice, encrypted Drive/iCloud recovery,
   native MatrixRTC call lifecycle, LiveKit meetings and the remaining PRD. The source
   of scope is Platform docs/collaboration/prd.md and delivery.json. No group is complete.

Keep this current file concise and replace stale status instead of appending another
full history. Commit measurements with implementation; historical details stay in Git.
