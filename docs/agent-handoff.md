# Agent continuation — HRIS Mobile

## Current: release 46 installed; history boundary follow-up — 6 November 2026

Release 46 from pushed source **00a51e14d8919a152aae810017528f1947ef37e6** is accepted
and installed on **10.77.77.3:5555**. Normal public SDK 2 dependency, devRelease
46/1.0.0, nondebuggable ARM64, same development signer. APK 121,090,044 bytes,
SHA **11ef605c8f92f69d7d2e9599feec9591bf041c69a331d344225974800df6e8e8**.
The announced install-only update started 13:26:10 UTC and completed in 100.052s.
Device APK hash matches; app identity, first-install time, data and keys remain.
No phone UI was launched. Do not repeat the installation or availability question.
[Release and phone evidence](measurements/collaboration-release46-2026-11-06.json).

Release build 39585 passed in 88.097s with zero lint findings. Its source manifest
matches the accepted normal-dependency gate. Native ARM64 bytes match the separately
accepted SDK 2 fixture after NDK 28.2 stripping. Retained Bima-store acceptance
passed two offline and two online openings with visible messages, no interrupted-save,
older-history or reconnect prompts, and the same process alive after 30s online.
Both original screenshots were inspected. No new messages were sent by this smoke;
physical-phone send latency is not proved by room-entry acceptance. The user answered the optional
feedback: ordering is correct, but sending still remains pending for a long time.
This issue is OPEN. Read-only phone logs show StageMessage timeout then repeated
DeliverMessages QueueUnresolved, continuing after four provider sends returned200
at13:35:46–47 UTC. Do not ask that feedback question again or claim send latency fixed.
[Current investigation](measurements/collaboration-pending-delivery-2026-11-06.json).

Bima emulator 2089464 / startTicks 164786875 stopped at 13:25:39 UTC. Original
userdata/keys remain under .work/demo-chat-device-2026-11-05/bima, now with 46.
No emulator is active. History-domain full gate87727 is the only heavy local job,
.work/history-completion-domain-2026-11-06/full-1; its source inputs are frozen.
Private build, smoke and install evidence is
in .work/release-phone-2026-11-06-build46. Never wipe/reseed the original Bima store.
Announce EVERY future phone installation, select the serial explicitly, use
--install-only, and never drive phone UI.

The normal SDK 2 gate is COMPLETE: first run 57307 took 1055.140s, with 7,026 fresh
tests/909 suites and zero failures/errors/skips. Only formatCheck failed; ktfmt
inserted one blank line in the proof build script. Final run 75313 passed in 81.012s;
all 909 report digests retained their passes, zero newly executed tests, zero lint
findings. Another 155 historical reports are unchanged but not counted as tests run
for this increment. All 4,212 source inputs were stable within each run; the blank
line is their sole difference. [Gate evidence](measurements/collaboration-sdk-baseline-shipping-2026-11-06.json).

SDK release 436496933 / matrix-sdk-26.10.23-hris.2 is PUBLIC prerelease, target
6484d136, published 12:52:23 UTC. All six remote assets and unauthenticated Ivy
metadata are digest-verified. AAR SHA c4df31e057eee5ec3f7986a6a4e06db4f2118e072de879aa7fc247a43d25b162;
Ivy SHA f1c74227ddafee4774e18b31fbb952942c919d0d37c962c24fac6471d80ebbd5.
Do NOT rerun publication scripts or replace assets. The draft-tag and cached draft
asset URL 404s were recovered using verified numeric release identity and refreshed
published metadata; there was no duplicate upload. [SDK evidence](measurements/collaboration-sdk-arm64-baseline-2026-11-06.json).

SDK 2 removes unconditional optional ARM64 crypto flags, retaining +neon and runtime
CPU detection. Build 60362 passed 935.696s; packaging 37494 passed 107.344s. The other
three native ABIs, all nine generated Kotlin files and classes.jar are unchanged.
Internal candidate 45 passed the same retained-store smoke. Actual ARM64 native
recovery passed both Platform fixture cases in 254.246s: three encrypted sends,
zero redactions per case, two process restarts, offline settlement and repeated
no-op. That fixture proves the confirmed-original postcondition, not literal HTTP
loss on the user's phone. Fixture lint has 48 warnings; shipping lint has zero.
Keep old/new SDK outputs separate in .worktrees. Release **44 remains REJECTED**;
never install it. SDK 1 assets stay immutable.

Exact 62d9fba1 CI remains accepted: protocol 42213012559 (14 checks per mode),
HRIS 42213012407 (386 cases/67 suites), control 42213012469 (4,249 portable cases/
461 suites; 1,646 Apple cases/181 suites and iOS host build). Artifact digests are
verified. [CI evidence](measurements/collaboration-confirmed-queue-ci-2026-11-06.json).
SDK CI 42216097534 at 6484d136 also passed, with verified source/manifest/artifact.
Its independently built ARM64 binary differs from the accepted local release;
do not substitute it into SDK 2.

NEXT: audit automatic 00a51e14 CI (do not manually redispatch): native HRIS
42224141387 now includes separate x86_64 and ARM64 jobs with APK ABI assertions;
protocol 42224141654 and control 42224141378 are also running. Preserve precise
artifact hashes and platform scope. Continue all 54 PRD groups after this release.
The history-end increment now has eight uncommitted Kotlin files (new domain reader,
pure completion policy, DI/VM/fixtures and15 common domain regressions). Full gate
87727 is running; do not mutate those inputs until it finishes. Its contract doc is
also updated. Use cases coordinate repositories, never other use cases.

PRIORITY after draining that gate: reproduce the delayed-ACK recovery issue.
CollaborationDeliveryWorker.acquireOriginals currently focuses the first Sent event
even alongside unconfirmed commands. Check a distant newer ACK and alias-changed
bootstrap against that old focus before implementing a latest-head recovery policy.
Also inspect the12s room/15s SDK-window mismatch and receipt publication only at
window completion. These are hypotheses/code gaps, not a proved phone root cause.
Closed diagnostics are in .work/chat-pending-phone-2026-11-06-build46; raw app-only
and provider logs are private. Do not clear data, keys or blindly resend originals.

Starter stays 578d97a; no HRIS queue-policy port belongs there. Platform source stays
4dc8c9d15cde42160f93096325360a7aeff65204; live remains
10485916ee02850cf913793398f7ef3dd1e09afb/schema116.1. Do not replay accepted Forward
publications or the live rollout. Older checkpoints below are historical and do not
override this current release/phone/SDK state.

## Queue recovery integrated; release44 next — 6 November 2026

The application recovery increment is accepted and committed with the public SDK
dependency in this checkpoint. Do not reapply the historical21-file consumer patch.
The new use case owns completion/account/company/device policy; repositories map
raw facts and the Android datasource performs SDK I/O. ViewModels are unchanged.

SDK26.10.23-hris.1 is published as prerelease436429809, target3c0526077ae3bfe1ffc88349dd307ea0b6653384.
All six asset digests match; do not replace them. AAR185,442,691 bytes SHA
de52b51a3bdf346f27dfb66b891f51abf42236f52523e1f99bb9b9b2da6304b5;
Ivy SHA30c35845ae5dd2bd643ab0a0ed69db7d1338cc7ebbf1a76795e5021df692722e.
[Distribution](collaboration-sdk-distribution.md) describes the normal catalog,
exclusive Ivy repository and strict verification; the local candidate override is removed.

Full candidate check90726 passed1066.733s:7,026 fresh tests/909 suites, zero failures,
errors, skips or lint findings. Normal public-dependency check32227 passed77.814s;
all909 report hashes retain that pass, with zero newly executed tests. Only catalog,
settings and verification differ from the earlier4,212-source candidate manifest.
First normal attempt4134 passed Android build/lint but root format/detekt inspected
the downloaded SDK Kotlin under .work. The owned generated main tree moved to
.worktrees/matrix-sdk-queue-android-x86-2026-11-06; no app code or threshold changed.
[Shipping evidence](measurements/collaboration-confirmed-queue-shipping-2026-11-06.json).

Actual Android/HRIS recovery already passed both scenarios, including two process
restarts, offline native settlement and repeated no-op, with exactly3 encrypted
messages and zero redactions per case. The four-ABI distribution's Kotlin classes
and both64-bit JNI files are byte-identical to that accepted consumer. Android debug
packaging also matches the verified SDK after NDK28.2 strip. The workflow now pins
Platform4dc8c9d15cde42160f93096325360a7aeff65204 to execute both recovery scenarios.

Corrected SDK CI42208777389/8791ad52 is SUCCESS1416.9s. Artifact13499911693,
49,962,441 bytes SHA f6fd77cc9947c1b1a4cad525ae7b23785477e7b1ab2f7650c7c8c511da06240a
is digest-verified; sources/Kotlin match and x86 is byte-identical to the published
SDK. Earlier failed validator run42206523839 stays recorded separately. All SDK
builds, packaging and local gates are drained; no emulator or heavy job is active
at11:47UTC. Inspect automatic product CI after pushing this checkpoint; do not
redispatch old release43 runs.

NEXT: build release44 from the pushed application commit using
.work/release-phone-2026-11-06-build44/build-arm64.py (requires full-shipping-2), then
verify-arm64.py and verify-sdk.py. These scripts are prepared;44 has NOT been built
or installed yet. The SDK has no GNU build-id; verify-sdk.py instead compares actual
APK native bytes with the accepted full library processed by NDK28.2 strip. Keep
all source inputs fixed during the build. Then run the retained-data Bima emulator
acceptance and stop only the owned emulator. Existing release variants do NOT enable
R8; do not claim minifier execution. Original Bima data and keys remain intact.

Phone last confirmed42; the11:26UTC ADB reconnect timed out. One availability
question is pending, so do not repeat it. Release43 is accepted and still available
for install-only if the phone returns before44 is ready. Announce EVERY installation,
select10.77.77.3:5555 explicitly, verify the installed signer/version/hash, and never
launch or drive the phone UI. New44 installation scripts still need preparation.

This closes the reproduced worker queue gap, not a phone-specific diagnosis of
all pending messages. Protected uncertain Staging.before entries remain untouched.
Continue all54 PRD groups after current release/CI acceptance. Starter remains
578d97a; no HRIS queue-policy port is needed. Live Platform remains10485916ee02850cf913793398f7ef3dd1e09afb/schema116.1.
Do not replay accepted Forward publications or the live rollout.

## Current priority: send ordering and retained native queue — 6 November 2026

Release43 is ACCEPTED, source46bec39aa599f78f38f8fb639f59b3de080bdf68, pushed.
It fixes the false interrupted-save banner and pending BBB moving above confirmed
AAA. Root/Android/Web checks passed6,983 fresh tests, lint0. Sequence tests cover
AAA–DDD with sequential confirmations and skewed clocks, not arbitrary ACK order.
[Contract](collaboration-chat-send-feedback.md),
[evidence](measurements/collaboration-chat-send-feedback-2026-11-06.json).

Exact nondebuggable arm64 devRelease43/1.0.0 APK88,584,188 bytes, SHA
639a5454496813a9ad4fa70294a4fbb5364df8473658468662a199167fd970bf,
ready in .work/release-phone-2026-11-06-build43. Retained-data Bima emulator passed
first/repeat offline and online room entry; all THREE screenshots were inspected.
Owned emulator350599/startTicks163234107 stopped09:04:32UTC, original userdata and
crypto preserved. No outgoing latency claim. [Release evidence](measurements/collaboration-release43-2026-11-06.json).

PHONE STILL42. Last bounded ADB reconnect about11:26UTC timed out18s. One async
availability question is pending; do not ask again. Once reachable, run43's
preflight.py, announce EVERY installation, then install.py --install-only.
Never launch/drive phone UI, erase keys or rebuild43 just because HEAD advanced.

CI for exact46bec39a is COMPLETE: Native42191546121 has381 cases/66 suites and
all6 result groups; Control42191546066 Apple1,627 cases/179 suites and portable
4,211 cases/457 suites, all zero failures/errors/skips. Artifact hashes verified.
[CI evidence](measurements/collaboration-followup-ci-2026-11-06.json). These runs
accept43, not the new native queue integration. Do not redispatch them.

Long pending: actual application worker + real encrypted stores + FAKE SDK
reproduced a retained native transaction blocking the NEXT message after first
message recovery. Original failing patch is tracked at
 tool/collaboration-sdk-patches/confirmed-queue-repro.patch. This proves an
application recovery gap, not the phone's native root cause. Phone43 diagnostics
will distinguish StageMessage/DeliverMessages; older logs only say Flush/QueueUnresolved.
Do not blindly abort: the SDK can schedule a server redaction for an in-flight send.

The separate SDK patch is TESTED:8 core and19 FFI room cases, exact source hashes,
SQLite reopen, byte/type/transaction matching, cancellation, concurrency and no
send/redaction. tool/collaboration-sdk-patches/confirmed-queue.patch applies AFTER
raw-event.patch to8de215a0aa0026afa5754ae5f05a6d168d1a1d60; SHA
23354aeb43aabe937543355d7255d31527c0a3c78acf0a677f113e9592776872.
The caller must independently prove trusted server acceptance and native ownership.
[SDK evidence](measurements/collaboration-sdk-confirmed-queue-2026-11-06.json).

New queue Android arm64 and x86_64 builds are FINISHED470.596s/447.202s.
All9 Kotlin files match; JNI symbols and16KiB LOAD alignment verified.
arm64 SHA43856af213036df7c2f70dabf7b387a7b60f21dbd79a0a23e69b4a814354a8d3;
x86_64 SHA48f6a14d6e39a332f8d5f8cc75d8924d636951cdbd97bac1cbc96284eac894ad.
[Evidence](measurements/collaboration-sdk-confirmed-queue-android-2026-11-06.json).
New isolated wrapper .worktrees/matrix-kotlin-confirmed-queue contains these paired
ABIs and the verified ARMv7 library below. The old accepted raw-only wrapper/AAR/consumer remains unchanged and DOES
NOT have queue methods. Remaining32-bit and Apple distribution are outstanding.

Application integration remains UNCOMMITTED. Latest application-3/session78573
FINISHED199.194s:45 tests (15 domain,4 mapper,26 foreground/background worker),
4,212 inputs unchanged, zero failures/errors/skips. It accepts the borrowed-timeline
refactor: worker invokes SettleCollaborationCompletedQueue; the use case owns
account/company/device/completion policy, repo maps raw DTOs, datasource does SDK I/O.
No remote room/setup metadata fetch is added. Exact full-content hashes and immutable
completion records prove ownership; referenced uncertain Staging.before entries stay
protected. Reproduction passes2 enqueues/2 sends/1 settlement, repeated work does
nothing. [Evidence](measurements/collaboration-confirmed-queue-application-2026-11-06.json).
Previous44-case pass predates this refactor; application-1 failed task naming before tests.

The full Android candidate is now ACCEPTED at the native recovery boundary.
AAR87653 passed89.489s:96,849,072 bytes SHA
4534e86ed5f15fa036cf5c198cfbf551dbc742ca68d32363e8ba68305ddbb92a,
exact two JNI identities and all raw/queue API declarations verified. No publication.
Main has a TEMPORARY local-candidate Gradle verification entry; shipping catalog
and repository remain unchanged. App source references require this custom SDK.

Consumer60155 passed370.511s/380 fresh Android cases/lint0 errors47 warnings.
Native attempt1 passed the original scenario but failed second-provider login setup
before Android. Attempt2 isolated provider containers per scenario and reached the
retained queue after two restarts, then failed because the offline fixture wrongly
called runtime.enterTimeline (which joins over the network). Both cleanup paths pass.
The existing local runtime.openTimeline fixes that fixture; production code is unchanged.
Consumer44226/build-2 passed80.140s,18 fresh proof cases,362 unchanged data cases
retain their prior pass. The only source delta is HrisProductQueueProof.kt.
Final proof APK316,309,385 bytes SHA
fd265937885b5cada09d95afae9e8bf5eb6e6727c65cf839181a50c612cec914.

Native18481/native-3 FINISHED171.206s: BOTH cases pass,12,454 inputs/APK unchanged,
zero failures/errors/skips. Exact native original survives two process deaths,
settles offline, and repeats without mutation. Each scenario sends exactly3 encrypted
messages and requests zero server redactions. Original scenario stays intact. This
fixture sends identical content through a separate SDK sendRaw transaction while
retaining the paused original; it proves that postcondition, not literal HTTP loss
or the phone root cause. Provider/Spring contexts are isolated per scenario.
Platform compilation/format/architecture passed33.464s then32.279s on final inputs;
Python driver34 cases pass. [All evidence](measurements/collaboration-confirmed-queue-android-consumer-2026-11-06.json)
retains the failed setups separately. Owned native-3 emulator1031697 /
startTicks163846449 stopped10:47:09UTC; native-1 and native-2 are also stopped.
Original Bima data/keys and phone are untouched. No emulator is active.

The21-file application/Android proof candidate is retained in Git as
 tool/collaboration-sdk-patches/confirmed-queue-android-consumer.patch,
basef6532c82d1b5f491e1c848dee0a6ace276e22e71, SHA
a97d23385e7f4a399edd1c0c420c5759d3f6b8dc39eb48b8c6f781bad4b526b7.
An isolated worktree .worktrees/hris-confirmed-queue-patch-check reconstructed every
file exactly. Main still has these21 UNCOMMITTED files; seven new Kotlin files have
intent-to-add only. Do not accidentally commit product code against upstream26.10.23.
[Contract](collaboration-confirmed-queue-recovery.md) explains the ownership bounds.

ARMv7 SDK build37017 FINISHED883.102s. All nine Kotlin files match the accepted
pair; native SHA74b8e8b818602dcc1c76d14ab292dfddfda87bd0b29e1bfb4a25527ee01c6b3a,
98,488,616 bytes. ELF32 ARM and all three entry points verified. The initial local
verifier incorrectly required16KiB for every ABI. Android's requirement applies to
64-bit targets: ARMv7/x86 retain their NDK28 default4KiB alignment. Corrected verifier
accepts the actual ARMv7 binary, still requires16KiB for the accepted arm64 binary,
and rejects a mismatched ABI. No native build flags or binary changed. Three ABIs
are now paired in matrix-kotlin-confirmed-queue; the accepted two-ABI AAR is intact.

Remote SDK run42206523839, sourceb31ff3cd365f88805c71a6b4fbe5b37176fdabd1,
FAILED only at the old ELF32 alignment assertion after compile/generation and both
source fingerprints passed. Artifact13497877820,49,910,307 bytes, SHA
a2d4567567fbc02da71aaeec3acf95f725687e0fbdea67cbcc2369f6d5ddaee6 was downloaded
and digest-verified. Corrected local verifier accepts the actual ELF32 x86 binary:
148,135,532 bytes, SHA6373a13b3dc61fe25d66e580740476158572db3477361fa5ecabf934a1c13353,
all three symbols, four4KiB LOAD segments and all nine Kotlin hashes match.
The failed CI conclusion is preserved separately from this local acceptance. That
verified JNI is now copied to matrix-kotlin-confirmed-queue: all FOUR ABIs are ready.
Corrected remote run42208777389 /8791ad52 started11:19:25UTC; inspect its result later.
No duplicate run is needed. [Android evidence](measurements/collaboration-sdk-confirmed-queue-android-2026-11-06.json).

Full application candidate check90726 FINISHED1066.733s:7,026 fresh tests in909
suites, zero failures/errors/skips, Android lint0 errors0 warnings. Root/Android/Web,
format/architecture/debug assembly pass with all4,212 source inputs unchanged.
[Evidence](measurements/collaboration-confirmed-queue-full-candidate-2026-11-06.json).
This uses the accepted exact-hash TWO-ABI Maven candidate override, not the final
normal shipping dependency. The source freeze is released; app sources have not
changed since this gate. No emulator is running.

Four-ABI packaging49786 FINISHED39.024s using the public packaging driver. Candidate
version26.10.23-hris.1,185,442,691 bytes, SHA
de52b51a3bdf346f27dfb66b891f51abf42236f52523e1f99bb9b9b2da6304b5;
Ivy descriptor SHA30c35845ae5dd2bd643ab0a0ed69db7d1338cc7ebbf1a76795e5021df692722e.
Kotlin classes and both64-bit JNI files are byte-identical to the accepted native
proof. All four native hashes/architectures, API declarations, minSdk21 and upstream
consumer rules are verified. .work/sdk-confirmed-queue-2026-11-06/distribution-four-abi-1
contains the AAR, Ivy descriptor, sources/license, build record and SHA256SUMS.
The actual four-ABI candidate passes isolated Gradle Ivy resolution with strict
verification and Android JNA as AAR (distribution-ivy-probe/session9609). SDK publication and application dependency changes are recorded below.

A PRIVATE Ivy repository prototype resolves the exact two-ABI AAR and all upstream
runtime dependencies with Gradle strict verification. Attempt1 failed before lookup
because of a trailing space in the test dependency version; attempt2/session95305
passed5s after correcting that test string. The isolated probe resolves Android JNA
as an AAR. This proves the repository descriptor layout, not application acceptance.
No publication or shipping dependency change has occurred. The candidate descriptor
and evidence are under .work/sdk-confirmed-queue-2026-11-06/ivy-candidate and ivy-probe.

SDK release436429809 / matrix-sdk-26.10.23-hris.1 is PUBLISHED11:38:58UTC,
prerelease/not latest, target3c0526077ae3bfe1ffc88349dd307ea0b6653384. All six
uploaded asset sizes and GitHub digests match locally verified files. The public
unauthenticated Ivy descriptor matches its strict hash. Do NOT replace these assets.
[Distribution](collaboration-sdk-distribution.md) and the Android measurement contain
publication/source/artifact details. sdk-build.json records the build-time candidate;
its published:false field is historical and must not be edited in place.

Application catalog/settings/strict verification now reference the public custom
coordinate. The temporary local-candidate verification entry is removed. The native
HRIS workflow pin is changed to accepted Platform4dc8c9d15cde42160f93096325360a7aeff65204,
which includes both native restart scenarios. These product/build changes remain
UNCOMMITTED until the normal dependency gate passes. Original21-file patch remains
historical evidence; its old temporary Gradle component is no longer the active input.

Normal shipping check4134 FINISHED172.974s with Android build/lint passing and no
fresh test execution (existing source/class inputs were cached). It failed ONLY root
format/detekt because the downloaded remote SDK Kotlin was extracted under .work
inside the application root. The owned main output is now relocated, hashes intact,
to .worktrees/matrix-sdk-queue-android-x86-2026-11-06/main. Archive/build logs remain
under .work/sdk-confirmed-queue-2026-11-06/remote-x86-42206523839. No application
code or quality threshold was changed to address that inspection-artifact mistake.

ONLY local heavy job: normal shipping retry32227, started11:44UTC,
.work/sdk-confirmed-queue-2026-11-06/full-shipping-2. This is the full root/Android/Web
check with explicit strict verification and NO SDK init override. All app/core/
features/Gradle sources are FROZEN. No emulator is running. SDK packaging49786 and
Ivy probe9609 are complete/drained. Remaining: normal gate acceptance, atomic
application commit/push, nondebuggable release build/emulator validation, install-only
on the available phone, matching CI audit, then continue all54 PRD groups.
The four-ABI packaging/Ivy preparation tool is now executed and accepted; it never
publishes by itself. Existing release variants do NOT enable R8; older notes saying
"minified acceptance" were inaccurate. Validate the actual nondebuggable release
variant and native behavior without introducing a new minification policy.
The pinned builder/manifest/CI workflow are tracked. Apple dependency is unchanged;
no new Swift API is referenced. The Android-only local verification component must
be replaced by the shipping component before accepting a normal dependency build.
Preserve all accepted Forward publications and live rollout; never replay them.

Old raw-only native consumer remains accepted: AAR94d907f139a7484bd687b538d8cfce3e90478daa2ab93b9835d9aa8a606becae;
APK50f728a9613dc3d06253a9c42decd508e399722527e60e4f21067788db67efb7,
provider b5707215cb144219bf2899cc6e5d6884 passed55.429s/two browser cases/cleanup.
[Evidence](measurements/collaboration-sdk-raw-event-consumer-2026-11-06.json).
Original Bima emulator and all fresh consumer emulators are stopped.

Starter578d97a records navigation port e39747c: local71 cases/Apple129 pass.
No HRIS queue-policy port belongs in Starter. Live Platform remains
10485916ee02850cf913793398f7ef3dd1e09afb/schema116.1. All54 PRD groups remain open;
continue the authorized long run after current bugs, with atomic commits/pushes.

## Release 42 delivered; Starter navigation validation active — 6 November 2026

Mobile23ce87fa is pushed. Directional280ms push/pop, RTL mirroring and reduced
motion passed the complete local gate (6,979 fresh tests; unchanged-source debug
packaging repeat passed). Release42 built91.727s/lint0 from exact
23ce87fa70b162a9aab5a5c1a1f6c4a00a5e2095, APK SHA
09eea50a9ad0ef1f3b1294ba2e1fceb4503e418d3d6e795029c205c08376259f.
Eight emulator videos cover first/repeat offline/online entries and Back. All eight
strips/32 sample positions inspected at original size: bubbles remain present
during movement; direction, clipping, final alignment and retained list accepted.
Variable-frame recordings use actual decoded PTS/held frames; no comparison with
old fixed-position fade latency. Final separate retained-data startup confirms nine
rows, Send present, no Older/Retry/Reconnect/crash. Initial immediate-tap probe did
not confirm room entry and is preserved separately; no app change was needed.

PHONE NOW42: announced then installed08:06:02UTC, verified43.702s, same identity,
no erase/uninstall/UI actions. [Delivery evidence](measurements/collaboration-release42-2026-11-06.json).
Do not repeat installation. Owned emulators4113872 and4157622 stopped, original
Bima keys/data retained. All install/capture/review drivers drained. The follow-up
boot wrapper ended143, but exact owned process completed boot and was inspected
then stopped with its recorded PID/startTicks; no unrelated process was touched.

Starter e39747c9b67548e8d2a67f5a1dff9f678722b0fd ports the exact three generic
files. Local check18630 drained PASS272.990s, 71 fresh cases/32 suites, no failures,
errors/skips; root/Android/Web and lint0 errors/44 existing warnings. Apple scope
was dispatched ONCE:42185888342, exact e39747c, now running. Do not re-dispatch.
Mobile CI23ce Native42183953532 running, Control42183953620 pending;
27cff Control42181039404 running. Stable-history ac7668 native/Apple evidence
predates motion. No new phone install or emulator action is needed.

ONLY heavy local job: SDK x86_64 driver95397, child13949/startTicks162936862,
started08:11:15UTC. Output .work/sdk-raw-event-2026-11-06/android-x86_64,
paired generated sources outside Mobile in .worktrees/matrix-sdk-android-x86_64-2026-11-06.
Two Cargo jobs, offline/locked sources,45minute bound. Wait for completion before
another local build/emulator. Verify native ELF, raw API symbol and all nine Kotlin
hashes against arm64, then package the paired candidate and native consumer proof.
Shipping upstream AAR includes arm64-v8a, armeabi-v7a, x86 and x86_64; remaining
32-bit builds and Swift/XCFramework are still required before replacement shipping.
Arm64 AAR970ea462 remains candidate-only, no app dependency/publication.
Prepared consumer patch is tracked in tool/collaboration-sdk-patches; it is NOT
compiled/executed. Isolated Mobile worktree .worktrees/hris-mobile-raw-event-consumer
is based on270efd56 and has only NativeMatrixProbe changed. The patch verifies two
raw decrypted original reads through JNI; it does not prove edit recovery.
Private drivers prepare-x86-aar.py, verify-android-aar-paired.py, prepare-consumer.py
and build-consumer.py are syntax-checked but UNEXECUTED. After x86 verification,
prepare-x86-aar.py adds its exact JNI to the wrapper, package-android-aar.py
package-android-aar-2 builds the two-ABI candidate, then verify/prepare/build.
The consumer uses a private Maven coordinate with artifact/POM hashes added only
to its isolated verification metadata; do not disable dependency verification.
Run existing Platform provider proof with --docker-sudo, explicit owned emulator
and --android-sync-mode classic. No emulator is currently active. Continue
history-pager domain ownership and all54 PRD groups. Platform remains
LIVE10485916/schema116.1. Never replay rollout or accepted Forward publications.


## Directional navigation validated locally; build and inspect release42 next — 6 November 2026

Latest user asks for perceptible Flutter-like in/out page transitions. ScopedNavHost
now slides pages from the trailing side and moves the parent by one third; Back
reverses direction. Default page duration280ms, RTL mirrored, viewport clipped,
reduced motion uses None. Tabs retain their shorter timing. No ViewModel, domain,
repository, acquisition or scope-owner behavior changed. Phone system animation
settings were read only: all1.0; no phone UI was opened or settings changed.

Baseline NavigationOwnershipTest:5 cases/1 expected directional failure42.638s.
Corrected:5 PASS65.613s (LTR/RTL forward+Back/content/reduced motion/restoration/
owner disposal). Full1 passed6,979 fresh cases but packageDevDebug alone failed
IncrementalSplitterRunnable, with no inner cause. Full2 repeated the same command
with stacktrace on identical4,204 inputs, PASS110.402s, tests up-to-date/0 fresh,
lint0; no code/dependency/assertion changes or cleaning to bypass the failure.
Drivers28213/47720/6843/27200 drained. No emulator/heavy job active at checkpoint.
[Contract](navigation-motion.md), [evidence](measurements/navigation-motion-2026-11-06.json).

PHONE STILL41 (e45e6f1e…). Build42 scripts are PREPARED ONLY in
.work/release-phone-2026-11-06-build42. Bind build-arm64.py to successful motion
full2; build from this committed source, verify APK, then use retained Bima emulator
for four first/repeat offline/online entries and four Back recordings. review-progress.py
creates eight strips/32 timed frames: inspect ALL before acceptance. Moving crop
measurements are not comparable with old fade latency. Do not auto-launch an extra
emulator process during install: capture owns the two measured startups. Preserve
all original keys/data; announce EACH phone installation and run --install-only,
never launch or drive phone UI. release-acceptance.json is still false.

Port the three generic files (ScopedNavHost, AppMotion, NavigationOwnershipTest)
to Starter after Android visual acceptance; all three baseline files were identical
there. Then run Starter root/Android/Web checks and its Apple CI scope once, no
Starter phone install. Continue native SDK x86_64/AAR consumer/edit proof and
history-pager domain ownership, then all54 groups. x86_64 driver remains prepared,
not executed; new arm64 SDK candidate970ea462 is not an app dependency.

Previous stable-history ac7668 Native42174041004 AND Control42174041125 now pass.
Apple artifact13485298149 SHA42afe2eb1c5ecd7485c6d45926f0763a090e4894ab60c0fd43a244cd63582ea3
verified1,626 cases/179 suites, no failures/errors/skips; exact ac7668 source.
This is pre-motion Apple evidence. New27cff22a fixes the two-phase notification
fixture and passes local/native checks. PlatformLIVE10485916/schema116.1 unchanged;
never replay deployment or accepted Forward publications.

## Native phases accepted; directional navigation is the next priority — 6 November 2026

Latest user: navigation has no perceptible transition and feels laggy. Host uses
only symmetric180ms fades. Next implement directional push/pop centrally in
ScopedNavHost, preserve prepared chat content/reduced motion, test actual NavHost
forward/Back/RTL/lifetimes, record Android motion, ship next validated build and
port generic changes to Starter. No navigation motion code has changed yet.
Do not place animation/data policy in ViewModels or abandon communications.

Notification fixture now drives both existing production phases. Real-storage
cancellation after transfer/reopen passes; production workers/deadlines unchanged.
Full gate91307 PASS658 fresh /301.911s,4,206 unchanged inputs, app lint0, proof lint
0 errors/47 warnings. Python29 PASS. Native42927 PASS90.721s: three process kills,
both phases complete, one held SessionChanged copy, zero HTTP/message sends and
real inline-reply callbacks. Final identity/review/no-command assertions remain
strict. Emulator3885164/startTicks162648807 stopped07:25:37; original clean-userdata
retained. Drivers14042/42927/91307 drained. No emulator/heavy local job active.
[Contract](collaboration-notification-recovery-phases.md),
[evidence](measurements/collaboration-notification-phases-2026-11-06.json).

ac7668 Native42174041004 PASSED. Verified artifact13484683008 SHA84a86effba95a366d2c5bc133711839891e67802dc3b7247d0e7006a208ccd21
contains six passed/cleaned result groups sharing one APK, including original
history replay, offline recovery and foreground paging. Control42174041125 still
active; newer58a5 runs queued. No duplicate dispatch. Native evidence is in
measurements/collaboration-stable-history-native-2026-11-06.json.

SDK AAR85053 drained PASS164.980s. Candidate50,667,076 bytes SHA691c96bb9a502c8ab5a684259a753bc8017fce61c66cfef2e4dd7963bc02da43;
exact JNI pair and compiled raw-event API verified. Wrapper required no build
changes; its consumer rules and the AAR rules are empty. An incorrect initial
verifier assumption was corrected without changing the artifact. No SDK app
change/publication/consumer acceptance. x86_64 build driver is PREPARED ONLY at
.work/sdk-raw-event-2026-11-06/build-android-x86_64.py; generated files stay outside
Mobile. Resume SDK/consumer proof and history-pager domain ownership after motion.

PHONE41 already installed; no repeat install/phone UI action. All54 PRD groups
active. PlatformLIVE10485916/schema116.1 unchanged; never replay rollout or
accepted Forward sends. Generic Starter motion port follows Mobile validation.

## Release 41 installed; native SDK packaging and recovery fixture next — 6 November 2026

Mobile58a5a6ed is pushed. The corrected release41 APK is NOW INSTALLED on the
phone after explicit advance notice, install-only, zero phone UI actions.
SHAe45e6f1e0af64dc0c0c67106b126ff0fe805405082dba36541c99af7c35d3114,
88,584,188 bytes, exact committed source58a5a6edc2af5f2efe2d774c5a652fcab26bf699.
Build82.202s, lint0, signer/manifest/private API/Firebase/backup/alignment verified.
Four emulator trials: offline first/repeat and online first/repeat, nine rows,
no auth diagnostics or reconnect warning. Header-to-bubble gaps−33/0/−33/0ms;
all8 adjacent frames visually reviewed. Final state has no Retry/older/reconnect
or crash. Emulator3790914/startTicks162563662 stopped07:12:04, data/keys retained.
Installation07:13:09UTC completed/verified in45.806s; same app identity, no data
clearing or uninstall. Drivers95985/8074/33086/87868/48055/87212/59662 drained.
[Public evidence](measurements/collaboration-release41-2026-11-06.json), private
.work/release-phone-2026-11-06-build41-fixed. Do not reinstall this completed update.
Older checkpoints saying phone40 or held41 are superseded.

Current ONLY heavy local job: SDK AAR packaging85053, started07:13, private output
.work/sdk-raw-event-2026-11-06/package-android-aar-1. It builds isolated wrapper
c1664f7 with the paired Oct23 raw-event JNI/Kotlin sources; no publication or app
SDK dependency change. Do not overlap another heavy build. No emulator is active.
If upstream AGP publication-component setup fails, preserve evidence and fix only
isolated wrapper configuration before retrying. Original paired hashes stay frozen.

Native42170117581 failure is a fixture phase omission: input transfer can complete
before interruption leaves the durable reply Pending. Production recovery always
runs the separate reply worker even if input worker retries. Four uncommitted
TEST/DRIVER files now exercise both phases and reject false phase completion; a
new actual-storage application test cancels after protected-input retirement then
reopens into independent reply recovery. Python29 cases PASS; Kotlin/native are
UNVALIDATED. Never relax exact final SessionChanged/no-command/copy assertions or
lengthen production deadlines. No production recovery behavior changed. Validate
these after SDK packaging drains, then commit/push and run actual native fixture.
ac7668 native42174041004/Control42174041125 remain active at this checkpoint.

Continue SDK AAR verification/consumer proof, history-pager domain ownership and
all54 PRD groups. Retained warning UI fix does not establish the cause of the old
interrupted refresh callback. Platform LIVE10485916/schema116.1 remains unchanged;
never replay deployment or accepted Forward sends. No new generic Starter port.

## Retained reconnect feedback fixed; release 41 rebuild next — 6 November 2026

The page no longer turns a retained SDK rejection into generic feedback/Retry
when the route stops or starts connecting. The current SETUP_REQUIRED action and
local-read failures stay visible. No ViewModel, authorization or recovery policy
changed. Baseline regression failed; four focused Compose cases and full root,
Android, browser, proof compile, format and architecture pass. Full gate: 1,114
fresh tests / 369.573s, 4,204 unchanged inputs, lint0. Driver92704 drained.
[Contract](collaboration-chat-reconnect.md),
[evidence](measurements/collaboration-retained-feedback-2026-11-06.json).

Three clean online entries of the retained emulator later showed nine rows and no
parsed auth diagnostics. Read-only server audit showed normal gateway refreshes.
The original transport callback cause is still unestablished. Capture now stops
the preceding process before changing networking; interrupted-refresh acceptance
remains separate. Do not treat the UI correction as a transport recovery fix.

PHONE STILL40. Old APK41 (4dfadc60…) is held and must not be installed. Use the
prepared .work/release-phone-2026-11-06-build41-fixed scripts, bound to successful
reconnect-feedback-full1. Build from the committed source, verify, install only on
the owned Bima emulator, record four first/repeat offline/online trials and inspect
eight adjacent frames. Preserve its original userdata/crypto. Release acceptance
must bind the exact APK hash, no auth diagnostics and no stale prompt before the
explicitly announced install.py --install-only phone delivery. No phone UI actions.
No emulator or heavy local job is active at this checkpoint.

CI919 native42170117581 failed a DIFFERENT assertion: NotificationInputProof
recoverInputs:300. Artifact13482867756 SHA5b7875ddae3222742bb1b0ea11d68e72722ab9f7cdeb7b843c1ee7f2aeedfc47
was downloaded/verified. First worker turn Retry transferred the original input;
second returned Complete while its reviewed copy remained Pending. The exact
checkpoint assertion stays unchanged; investigate transfer-versus-review recovery.
Control91942170117574 passed. ac7668 native42174041004 and Control42174041125 are
still active; no manual duplicate dispatch. Earlier replay failures at history:54
are separate and the ac7668 storage fix needs its native acceptance.

Next: phone delivery, notification recovery diagnosis, paired SDK AAR/consumer
proof, ChatThreadHistoryPager domain ownership and all54 PRD groups. SDK generated
files live outside Mobile; packaging driver remains prepared/unexecuted. Platform
LIVE10485916/schema116.1 unchanged; never replay rollout or accepted Forward sends.
Starter's existing MVI foundation port remains accepted; no new generic port here.

## Release 41 render passed; transient SDK auth recovery under investigation — 6 November 2026

Mobileac7668ae fixes stable history replay, abccff51 records paired SDK outputs;
both are pushed. APK41 from exact abccff516e5303636713f4d84a041cb68a3d5468 built
108.555s, lint0, source unchanged, 88,584,188 bytes. SHA
4dfadc60fdc7c0589b344520cb3cbc7dd33c6d12387f65d0307e3c246d82aa0b. Signer, private API,
Firebase app, backup exclusions, nondebuggable release and16KB zip alignment pass.
The build preflight initially referenced failed full1 and correctly stopped before
building; it was corrected to successful unchanged-source full2. Build86434 drained.

Owned emulator3505788/startTicks162303905 booted24.358s, installed the exact APK in
6.2s without clearing data/keys. Four recordings completed: offline fresh/repeat
and online fresh/repeat, nine rows each, no sends, settings restored. Header-to-
bubble gaps−33/0/0/−33ms. All8 adjacent frames inspected and rendering accepted:
bubbles appear during the transition. HOWEVER online-repeat visibly contains the
contextual reconnect prompt during transition. It has disappeared at the final
hierarchy read, but PID3939 log matching found SoftLogout. Crash/ChatAuthRejected
were absent. The exact auth event details were not archived before stopping.
Do not infer its cause. A similar prompt had occurred in candidate5 previously;
clean first-entry comparisons did not establish its origin.

PHONE STILL40. Do NOT install41 yet: release-acceptance.json is explicitly false,
and install.py now requires that acceptance. Continue session-recovery diagnosis,
using the original retained Bima userdata. The previous final-state shell continued
cleanup/analysis after its auth assertion failed; its final exit0 DOES NOT mean
recovery passed. Capture28654 and inspection17227 drained; emulator stopped at
06:29:15 with all data/keys retained. No emulator/heavy local job is active.
Private evidence/scripts: .work/release-phone-2026-11-06-build41. Announce each phone
installation, use install-only, never launch/drive phone UI. No phone action occurred.

AndroidSdkAuthDelegate records ChatAuthSoftLogout via SdkSessionPersistence on a
Matrix unknown-token callback. The gateway maps UNAUTHENTICATED to401 with
soft_logout=true. No API log entries were present during the capture interval.
Docker access requires sudo -n; live containers hris-dev-api-1/worker-1 remain
healthy and unchanged. Runtime10485916/schema116.1; never replay deployment or
accepted Forward publications. Next trace the captured SDK/native parent renewal
and preserve bounded redacted diagnostic evidence before emulator shutdown.

Native SDK paired files are built/verified but NOT consumed or packaged. The
Kotlin wrapper inputs have been staged; package-android-aar.py is PREPARED ONLY.
Generated source location is outside Mobile (see generated-location.json). Resume
that and ChatThreadHistoryPager domain ownership after recovery/phone delivery;
all54 PRD groups remain active. ac7668ae CI Native42174041004 and Control42174041125
are queued behind919 runs. Platformef8 Control42168678959 and provider42168678967
both PASSED. No manual duplicate runs or additional foundation port needed so far.

## Stable replay accepted; phone release 41 next — 6 November 2026

The current increment fixes encrypted history replay: unchanged originals, retry
aliases and redactions keep their ciphertext/checkpoint revision; mixed batches
write only changed records. The repository checks the same owned checkpoint before
returning a no-op success. No new presentation helper or weakened native assertion.
[Contract](collaboration-chat-history-replay.md),
[evidence](measurements/collaboration-history-replay-2026-11-06.json).

Baseline history-replay1 failed exactly three new cases out of22 in54.429s;
history-replay2 passed22 in59.531s. Full1 passed2,353 fresh tests/448.354s but quality
tasks scanned temporary SDK-generated files. Those unchanged artifacts now live at
.worktrees/matrix-sdk-android-arm64-2026-11-06/main, outside Mobile. Full2 passed
72.886s with all4,204 product source inputs identical; tests were up-to-date and
zero fresh cases are claimed for full2. Android debug/proof compile/browser/root,
format/architecture passed; lint0. All gate drivers drained. Production replay
drafts HAVE BEEN APPLIED; never reapply them.

Prepared rendering91902546 remains accepted in six candidate6 Android trials.
Phone40 remains unchanged and reachable. Release41 scripts are prepared at
.work/release-phone-2026-11-06-build41, NOT EXECUTED. Build from the committed source,
verify APK, then use its owned-emulator scripts on the retained Bima data. Four
first/repeated entry recordings are planned (one offline process and one online).
Inspect frames before marking accepted; announce EACH phone install and run its
install.py --install-only. Never launch/drive phone UI. The release keeps the
existing SDK; native raw-event consumption is a separate pending increment.

SDK native arm64 compilation PASS, initial full generation failed994.615s only
because offline cargo metadata lacked an all-target dependency. Locked metadata
fetch PASS2.869s; retry PASS1.917s, exact initial native binary preserved. Nine
Kotlin files +138,559,720-byte JNI verified, four16KB LOAD segments; all source and
output hashes recorded. Drivers42167/26762/12602 drained. SDK34 also installed.
Generated pair is copied into isolated Kotlin wrapperc1664f7; only generated files
are replaced. No AAR build or product dependency change yet. The packaging driver
.work/sdk-raw-event-2026-11-06/package-android-aar.py is PREPARED ONLY. Source location
is recorded in generated-location.json; do not use the old android-arm64-2/src path.
No emulator/heavy local job is active at this checkpoint. Package/test SDK after
phone delivery, then continue the history-pager layer cleanup and all54 PRD groups.

CI42168989689 also failed exactly HrisProofOriginalHistory.verify:54; new safe
stage says validating_completion. Its snapshot replay assertion is unchanged and
must pass with this fix.91902546 native42170117581 and Control42170117574 run;
5be Control42167279345 PASSED (Apple artifact audited:1,621 cases/179 suites).
No manual duplicate runs. Platform LIVE10485916/schema116.1 stays unchanged;
never replay rollout or accepted Forward publications. Starter foundation port
remains accepted; no new reusable product change in this HRIS storage fix.

## Native raw-event Android build active — 6 November 2026

Prepared room rendering is committed/pushed in91902546. Six Android trials show
bubbles during first/repeated transitions for the prepared conversation, including
two offline process starts. All emulators are stopped and their data/keys retained.
Phone40 remains unchanged; no phone install or UI action occurred in this increment.

NDK28.2.13676358 installed/verified in22.261s. Rust1.96.0 Android arm64/x86_64 targets
installed in3.57s; cargo-ndk4.1.2 installed in33.885s. Initial version verification
used an unsupported direct binary invocation; cargo ndk --version then verified the
existing installation without reinstalling. All installer drivers are drained.
SDK locked arm64 dependency fetch PASS2.418s; host xtask build PASS60.334s, source
hashes unchanged. SDK source stays at8de215a0 plus committed raw-event.patch.

ACTIVE: actual arm64 release SDK compilation driver42167, child3264835/startTicks
162095421, started05:51:01. Metadata/logs in Mobile
.work/sdk-raw-event-2026-11-06/android-arm64; two cargo workers, API21, offline after
locked fetch,45min bound. Do not alter SDK sources or start another heavy local
build/emulator until it drains. This produces paired Kotlin/JNI candidate files,
not a consumed AAR. No native raw-event consumer acceptance yet.

The upstream Kotlin AAR wrapper is cloned, unmodified and UNBUILT at
.worktrees/matrix-kotlin-raw-event, pinned c1664f712bed33488b10f33232fb424faf257d49.
Its Gradle8.14.2 wrapper has a pinned distribution hash; AGP8.13.2/Kotlin1.9.25,
compileSdk34/minSdk21/JNA5.18.1. Override NDK_VERSION to the installed28.2.13676358
when assembling locally. Do not run its publication scripts. No Maven release or
application dependency changed. Review generated Kotlin/JNI/ELF hashes and16KiB
LOAD alignment, package a candidate AAR, then compile and test the owned native
edit/original/missing-key/cold-store workflow before phone delivery.

Hosted:74152 native42163759500 also FAILED;5be native42167279215 was superseded
while pending. CI-only a5f0e06c native42168989689 is running with Platformef8a41b3's
safe failure stages;91902546 native42170117581 and Control42170117574 are pending.
5be protocol42167279220 PASSED both classic/native Android/Web;5be Control42167279345
runs.74152 Control passed portable+Apple. Do not manually duplicate runs.
Platform LIVE remains10485916/schema116.1; ef8a41b3 changes only the test driver/docs.
Starterdd40d24 accepted its reusable port on Apple. Continue all54 PRD groups;
ChatThreadHistoryPager business orchestration is still pending layer cleanup.

## Prepared room first-entry rendering accepted — 6 November 2026

The visible-room preparation increment is committed/pushed in91902546.
[Contract](collaboration-chat-first-frame.md), [evidence](measurements/collaboration-chat-visible-preparation-2026-11-06.json).
The account/company graph prepares up to three visible idle rooms after list
scrolling settles, sharing its existing cache bound. Previously visited rooms take
priority; active entries stay independent. LocalPrepared keeps visible=false and
starts no SDK, provisioning, sends, draft writes or read receipts. Pending reads
continue on entry; failed local reads can retry under the unchanged denial gate.
Other rooms still use the ordinary guarded-read path; do not claim all-room preloading.

Focused preparation1 PASS233/132.714s. Actual Koin/NavHost navigation2 PASS2/44.985s,
including prepared rows at32ms of the first180ms transition. Its initial compile
failure was a waitUntil argument-label mistake, not a relaxed assertion. Full
root/browser/Android/proof compile/lint PASS2,299 fresh/375.906s on4,204 unchanged
inputs; lint0. All those drivers are drained.

Candidate6 arm64 devRelease41 built/linted87.445s, source base a5f0e06c plus this
increment. SHA40a8705e3ac3424ff172351c4433c2924e96d65ca683c61086aac3db50686e31,
88,584,188 bytes, original dev signer, nondebuggable,16KiB aligned and private
configuration/backup checks passed. Six recordings across three fresh processes
(two offline startups, one online) retained nine rows without sends. First-entry
header-to-bubble gaps0/0/−33ms; repeated0/−33/−33ms. All12 adjacent frames inspected:
bubbles are visible during every list/room transition. This accepts the prepared
conversation, not a physical-phone benchmark or every uncached destination.
Final online repeat had no exact reconnect prompt or ChatAuth diagnostics.

Candidate4/5/4 cold-online first-entry comparison also completed: nine rows each,
no exact reconnect prompt or ChatAuth diagnostics. An earlier detector omitted the
localized period; those earlier prompt booleans are INVALID. The corrected first-
entry comparison and candidate6 repeat did not reproduce the old prompt; its exact
original cause is not established. All capture/analysis/verification drivers drained.
Emulator3152948/startTicks161995778 stopped05:41:04; all original data/keys retained.
No emulator or heavy local job is active. Phone40 remains installed and was reachable
at05:30. Announce every phone installation and use install-only; never drive its UI.

Pushed: Mobile762b715a preserves the SDK raw-event patch/evidence; a5f0e06c pins CI
fixtures to Platformef8a41b3 for bounded failure stages. Platform runtime remains
LIVE10485916/schema116.1; never replay rollout or accepted Forward sends. Starter
queued-effect retirement is accepted locally and on Apple in f92b272/dd40d24.
Mobile74152 Control42163759602 passed portable+Apple. Main native42163759500 was
still running at05:31; new CI-only pin will queue its own automatic run. Do not
manually duplicate runs. Old dcfea native42162194348 failed only its main SDK case
with generic RuntimeError; worker/scheduled/notification proofs passed.

NEXT: native SDK artifacts/edit exchange,
phone update and the remaining layer audit/all54 PRD groups. Native raw-event test5
PASS9/32.474s, but Android/Apple bindings are unchanged. The exact patch and public
evidence are in tool/collaboration-sdk-patches; NDK remains absent. The private
android-artifact-next.md is a plan only: no SDK artifact workflow/install/build has
run. Do not reapply stale isolated SDK drafts. ChatThreadHistoryPager orchestration
in presentation still needs an owning domain use case. Do not stop at this milestone.

## Cached room entry accepted; first process opening next — 6 November 2026

The first-frame increment is committed/pushed in74152c0b after CI-only dcfea4f3.
Its final source had4,202 inputs; first-frame-full2 PASS2,280 fresh/368.759s and
first-frame-full1 PASS6,938 fresh/804.331s, zero failures/errors/skips. Lint has zero
issues. All Mobile local drivers and emulators are drained/stopped; data/keys kept.

CURRENT: initial-scope-hint implementation is validated in this increment. Focused thread-hint1 PASS228/131.455s on4,202 unchanged inputs.
Full1 passed2,288 fresh tests but failed native-proof compilation at its obsolete
Entered singleton. The proof-only call is fixed to Entered(); full2 PASS95.034s,
4,202 unchanged inputs, root/browser/Android/lint/native proof compile. Tests were
up-to-date; zero fresh cases claimed for full2. All local drivers are drained.
Eighteen changed Kotlin files are formatted. Candidate5 predates only the separate
proof-app constructor correction, not shipping app code. Its build/lint PASS113.005s
and artifact verification passed; SHA21938aa5443b3eea2d614bee76555946cf58b96e01afd8319434d115492ed5c4.
Six recordings in three fresh processes completed, nine rows throughout, no sends.
All12 adjacent frames inspected. First room gaps200/133/133ms remain AFTER the
fully drawn header; repeated entries have bubbles during transition (0/0/−33ms).
Thus first-opening target is NOT accepted. The final online repeat shows a
contextual reconnect prompt (this copy follows SDK Unauthorized); no exact cause
claimed. Compare cold-online startup against candidate4 and inspect sanitized
ChatAuth diagnostics before release. No recovery-comparison scripts are prepared.
Emulator2857012/startTicks161728441 stopped04:54:08, data/keys retained. All candidate5
build/verify/install/capture/analyze drivers drained. No emulator is active. Phone40.
[Evidence](measurements/collaboration-chat-first-opening-hint-2026-11-06.json).

Starter f92b272 is committed/pushed. Independent check-1 PASS271.266s:81 fresh,
396 total desktop/six targeted browser cases, no failures/errors/skips, Android/
lint/production Web pass (44 existing lint warnings). Driver54568 is drained.
Apple-only42164549935 PASSED on exact f92b272. Artifact/source audit confirms
129 cases/34 suites, including six core-presentation cases, and full Xcode app.
No duplicate dispatch. All local heavy jobs are drained; no emulator is active.

[Current behavior and evidence](collaboration-chat-first-frame.md): the account/
company graph keeps three idle, established latest-window room ViewModels with
independent active entry owners. Reentry shows existing bubbles during transition.
No SDK/Activity is retained. RouteReleased clears transient actions and cancels
pending departure while the independent draft writer can finish. The late-departure
regression failed before correction (one case/52.213s), then final focused gate
retained-room4 passed197/98.875s. No expectation was relaxed.

Candidate4 Android recordings used nine messages throughout, with no sends or
hierarchy inspection during videos. All eight adjacent frames were inspected.
Three repeated opens show bubbles during transition offline/online; first room
after app process restart still shows233ms blank after the full header. Candidate4
APK41 SHA569653c31d21cb2d5054b6f7d6ba91a43a799f496eabb8b9c45a2189519f0645
built/linted270.160s and passed artifact checks. It predates only the final departure
cancellation/test; no final release or phone delivery claimed. Phone remains40.
Emulator2584551/startTicks161491526 stopped04:13:12 with data/keys retained.

First-opening change: AppStage.SignedIn's owner and selected company provide an
optional local access hint through navigation and data-class ChatThreadEvent.Entered.
observeLocal seeds the ownership flow with onStart + distinctUntilChanged, letting
private thread reads overlap initial workspace observation. ObserveCollaborationThread
still verifies captured session and fresh selected workspace/admission before every
publication. The hint is not authority. Default Entered() retains the ordinary
no-hint path. Focused tests and Android recordings completed; first-entry target remains unmet.

Next: cold-online recovery comparison and
remaining first-entry work before release. Commit/push only accurately scoped
validated increments; this does not close the first-entry target. The late-departure correction is
already in74152c0b; never reapply its draft. Starter port is already pushed; only
its hosted Apple acceptance remains pending.

Native CI42162194348 FAILED on dcfea4f3; its evidence is downloaded for exact
diagnosis.74152c0b native42163759500 runs now. Control42163759602 portable passed
and Apple is in progress. All were automatic.
Native CI uses already-live
Platform10485916. Last step: isolated Android SDK fixture build. Do not dispatch a
duplicate or replay Platform deployment/accepted Forward sends. Earlier native runs
failed against304be with only a generic RuntimeError; no exact diagnosis claimed.
ControlCI3a42150575846 and feaf42145472209 passed portable+Apple.

SDK raw-event nine Rust cases compile. Test3/test4 each passed8 and failed1:
crypto clients generate distinct TOKEN_0/TOKEN_1 but mock_room_event defaults to1234,
so the exact event GET returned404. Test-only mocks now require the exact synthetic
client tokens; no auth expectation is removed. Test5 PASS9/32.474s on unchanged
inputs in isolated8de215a0, including crypto/missing-key/cold-cache recovery. All
SDK drivers drained. NDK absent; no native
artifact/version changed. Finish this and
actual encrypted edit/ciphertext size before phone release, then continue all54
communications PRD groups. ChatThreadHistoryPager business policy in presentation
remains flagged in the layer review. Always announce phone installs and use install-
only; never drive the phone UI. Do not rerun diagnostic instrumentation/restoration.

All checkpoints below are historical context, not instructions to repeat work.

## Room preparation accepted; first-frame correction next — 6 November 2026

The local room workflow and failed-edit copy are now validated. Read the updated
[layer audit](collaboration-layer-review.md) and [gate evidence](measurements/collaboration-room-preparation-2026-11-06.json).
Full room-full1 PASS5,428 fresh/674.163s, no failures/errors/skips, lint0;4,193 source
inputs unchanged. Driver70818 drained. Focused copy1 PASS131; room2 PASS81. The
room-preparation drafts were APPLIED/refined; never reapply them. No phone install.

Latest steering: chat room shows a blank body before bubbles, including offline.
[Recorded release40 baseline](collaboration-chat-first-frame.md):367–533ms from
header to first bubble at30fps; not tap latency or a physical benchmark. Four
videos inspected, no new sends, settings restored and retained Bima emulator
stopped without wiping keys/data. No performance fix accepted yet.

NEXT: review/apply the NEW first-frame drafts in.work/chat-editing-review-2026-11-05:
CollaborationThreadSnapshot, CollaborationThreadPolicy, ObserveCollaborationThread,
ThreadObservationFixture, ObserveCollaborationThreadTest and the five files ending
-first-frame.kt.draft. They combine scoped local context/history/commands with one
fresh publication gate, prioritize initial history before draft/reply reads, and
short-circuit content-free empty edit queries. UNCOMPILED/UNAPPLIED. Update DI and
fixture factories, share history-window validation, then test cancellation, late
owner/denial, bounded snapshots, recovery, paging and editor regressions. Measure
actual Android before accepting performance. No unscoped cache or key-check bypass.

SDK raw-event test2 passed7/34.692s; crypto/reopen draft is UNAPPLIED/UNCOMPILED. No
SDK artifact/version changed. Rust1.96/GCC/make installed, NDK absent. Next after
urgent render work: native edit exchange/ciphertext budget, remaining layer audit
and all54 PRD groups. Phone40; LIVE Platform10485916/schema116.1 (docs a5f1defc);
Starter productbce2012/docs0c1d00c. Never replay rollout/accepted Forward operations.
Announce EACH phone install and use install-only; never operate its UI. Protected
stashes and both retained emulator images remain intact. No heavy task active.

## Local editor accepted; continue native edit proof — 6 November 2026

Thirty-three Kotlin files/two resources add the separate edit composer, local
outcomes and same-original pending/failure display. [Contract](collaboration-edit-editor.md),
[evidence](measurements/collaboration-edit-editor-2026-11-06.json), three inspected
screenshots under docs/images/chat-editor. Long press -> More -> Edit; normal draft
and quote retained, immutable retry on lost local response, original bubble key/
time/read identity unchanged. Rejected edits remain durable, bound acknowledgement
cannot discard a concurrently arriving failure. No network spinner/refresh added.

Focused editor4 PASS68/271.796s plus unchanged domain/storage cases; regression1
PASS25/96.744s. Full editor-full2 PASS2,347 fresh/378.927s, no failures/errors/skips,
Android lint0,4,188 inputs unchanged. Other checks/reports were up-to-date; do not
count retained reports as freshly executed. First full1 completed5,406 cases with
three failures: two page equality checks omitted the new revision; one real-time
background deadline test timed out. Corrected tests retain content/order/reopen and
exact2s worker expiry/cleanup assertions under controlled clocks. All drivers,
including18708, are drained. Static and preceding compile/selector failures are
preserved in measurement. Commit/push this editor increment, then continue.

NEXT: rejected edit text needs recovery/copy in the normal long-press actions before
release. Complete raw cold revisions and actual encrypted edit exchange/ciphertext
size. SDK checkout .worktrees/matrix-sdk-raw-event is detached8de215a0... with two
UNCOMPILED Rust files (one raw point method plus7 structure/network/cache/cancellation
tests). Mobile.work/sdk-raw-event-2026-11-06 contains exact source/patch/Cargo.lock
hashes. Do NOT apply the older .patch.draft on top. Before consumption, return SDK
decryption kind with raw bytes: encrypted room membership alone cannot establish
that a raw text event was decrypted. Add UTD/decryption/reopen cases and bounded
reference/original-first integration under current owner/admission/cancellation.
No artifact/version change, SDK publication or real edit send yet.

Rustup installer SHA b1c8cb14... was verified from the official distribution. Its
isolated1.96.0/rustfmt install PASS7.227s (driver64259 drained), root
~/.local/share/hris-sdk-rust, shell profile/PATH unchanged. rustc/cargo1.96.0 verified.
Host C compiler/build tools and NDK are still missing; prepare them, then compile/
test the isolated patch and controlled Android/Apple artifacts. Protected Mobile stashes remain; no applied draft may be replayed.
No editor Apple gate/release APK/phone acceptance yet. Phone40 and stopped retained
Rani/Bima data/keys stay intact; announce each future install and use install-only.

Platform63eddd5b documentation is pushed; LIVE product10485916/schema116.1 after
all14 CI42139174304 jobs passed. Packages/images/rollout all verified;19.227s rollout
at01:28UTC, API/provider healthy, migration history checked after startup, existing
keys/flags/provider/storage retained. Deployment scripts HAVE EXECUTED: never rerun
or replay accepted Forward publications. Starterbce2012 unchanged. Continue all54
PRD groups after this commit; this local editor acceptance is not a completion.

## Editor integration in progress — 6 November 2026

HEAD f993e2a9 is pushed. The eleven editor drafts below have now been APPLIED;
do not reapply them. Current product WIP includes local outcomes, authenticated
history revision invalidation, exact reviewed-ID/expected-access acknowledgement,
DI/ViewModel/controller, long-press Edit, separate composer buffer and localization.
Draft work is supplemented with shared SQLite/IndexedDB invalidation, outcome/
ack cancellation, five editor lifecycle tests and three desktop visual cases.
[Current contract](collaboration-edit-editor.md). No edit APK/phone acceptance yet.

Static3 PASS49.991s after factoring nested outcome logic and fixture factories;
static1/2 failed complexity checks and are retained. Focused editor1 passed61 tests
but failed a missing return in the row helper; editor2 failed a UI-test chaining
signature. Editor3 passed65/68 cases: three new visual cases incorrectly asserted
text on the disabled outer bubble node; they now target its actual text descendant.
Input focus and all five ViewModel editor tests passed. All those drivers drained.
Focused editor4 is RUNNING; freeze source during it and audit screenshots before
full root/browser/Android/lint checks. Do not claim screenshots or full acceptance.

New SDK raw-event patch is DRAFT ONLY in .work/sdk-raw-event-2026-11-06. It adds one
bounded exact raw event point read after the existing SDK decryption attempt,
preserving EventCache/crypto ownership and UTD status. Base8de215a0..., Cargo.lock
and source/build metadata captured; not applied/compiled/published/consumed. Host
has no Rust/NDK. Need raw metadata/UTD/cancellation/byte-boundary tests, controlled
Android/Apple artifacts and real encrypted edit exchange/ciphertext budget. Existing
Maven26.10.23 and Apple26.10.27 remain. All54 PRD groups still need continuation.

Platform10485916 is pushed. Full Verify42139174304 is running; all4 dashboard and
7 backend shards passed at last check, backend5/downstream integrations pending.
Previousa678 failed recovery; its dashboard integration was cancelled by newer CI.
Isolated backup recovery2 PASS68.003s,46 unit tests passed; owned resources removed.
Prepared edit-release scripts NOW target104/42139174304 and accepted Mobile outbox-
full2, but remain UNEXECUTED. Require all14 passing jobs and exact artifact digests
before staging/rollout. No live change:54b/schema116.1/keys/flags retained. Phone40,
stopped Rani/Bima data/keys, Starterbce2012 and both protected stashes unchanged.
Never repeat accepted Forward publications. Continue after the editor commit.

## Edit outbox accepted locally; continue projection and editor — 6 November 2026

Thirty-six Kotlin changes add immutable local edit commands, exact typed SDK
replacement content, encrypted codec/restart support and separate edit receipt
recovery/retirement. [Contract](collaboration-edit-outbox.md),
[evidence](measurements/collaboration-edit-outbox-2026-11-06.json).
Final outbox-full2 PASS5,065 fresh/719.112s, no failures/errors/skips, Android lint0;
4,177 source inputs unchanged. Static1 PASS49.504s; driver82756 drained.
Earlier attempts: outbox1 stopped for fixture signatures; outbox2 stopped for a
missing test import and fake close/destroy expectations; outbox3's151 fresh tests
passed but static/format checks failed. Full1 was stopped after those static
failures. Queue creation ordering is now a separate helper; all28 already-applied
.work drafts were renamed .kt.draft with hashes preserved so they cannot enter
source-tree static analysis. Do not reapply them. Both protected stashes remain.
Commit/push this increment, then continue the all54 PRD groups; no group closure.

NEXT: integrate the11 UNAPPLIED *.kt.draft files in .work/chat-editing-review-
2026-11-05 after reviewing edit-projection-integration.md. They cover local edit
outcomes, same-original pending/failure projection, editor controller/state/events,
composer and seven projection tests. Product UI is NOT wired and those drafts
are NOT compiled. Add authenticated history revision invalidation, exact expected-
access/reviewed-ID acknowledgement, use-case DI/VM/selection/localization wiring,
real SQLite/IndexedDB outcome invalidation, controller/VM and layout tests. Retain
ordinary drafts, original keys/timestamps/read identity and all unseen failures.
Never let an older unresolved edit override a newer observed/retired local edit.
Require the authenticated history room before any pending text overlay.

Complete raw cold revisions/native ciphertext budget and encrypted edit exchange
remain open. The pinned SDK still drops custom metadata on cold point reads.
No edit APK, native publication, Apple acceptance for this increment or phone
installation yet. Phone40 remains installed; retained Rani/Bima emulators stopped
with data/keys intact. Starter bce2012 is complete. Never repeat Forward sends.

PlatformCI42134116564 exacta678 passed all8 backend and4 dashboard shards but FAILED
recovery-integration: backup writer retains combined postgres:tag@digest while
load_backup allowed only tag OR digest. Two Python WIP files now accept the exact
combined18.x-alpine digest spelling without normalizing it;46 recovery unit tests
passed, malformed/unrelated images remain rejected before Docker. A detached
.worktrees/recovery-image-manifest fixture contains that two-file patch and GitHub-
digest-verified exacta678 API/worker JARs. Its isolated recovery1 is RUNNING and
owns the only heavy local slot; no Mobile build until it drains. Artifacts are
for this fixture only, not release acceptance. API98,700,805B SHA45dfbc10…;
worker78,770,874B SHA7b5968be…. Main build/libs and all live data are untouched.
Prepared rollout scripts now point to a678/42134116564 but remain UNEXECUTED and
must NOT run against failed CI. Live54b/schema116.1 and flags stay unchanged.
For this gh version only gh api supports --allow-escape-sequences; gh run view
rejects that option. Captured recovery job log confirms the manifest validation
failure. Remaining dashboard integration and local recovery still need auditing.

## Owned edit target accepted; outbox integration next — 6 November 2026

Eleven Kotlin files add immutable room/original/context-bound edit candidates and
owned local preparation, including legacy earliest-alias reads, hidden/redacted
exclusion and cancellation/checkpoint/account guards.
[Contract](collaboration-edit-target.md),
[evidence](measurements/collaboration-edit-target-2026-11-06.json).
Focused target1 PASS185/357.682s; final target-full1 PASS5,015/624.663s, no failures/
errors/skips, Android lint0;4,168 source inputs unchanged. Drivers75044/37834 drained.
Commit/push next. Preceding evidence product169ee9dc is already pushed, full4,995
accepted. No editor UI or edit APK/native publication is accepted yet.

NEXT: integrate22 UNAPPLIED Kotlin drafts under .work/chat-editing-review-2026-11-05/
outbox-drafts, guarded by manifest source/draft hashes. They extend the existing
immutable encrypted command, SDK content/staging, original-only guards, local
QueueEdit and edit receipt recovery/retirement. NOT compiled; repository bindings,
proof/test fixture constructors, new tests and pending/error projection still
required before applying/acceptance. Legacy fallback draft reduced to256 UTF16
units; full text retained once in m.new_content. Verify actual native ciphertext
budget. Ineligible Sent edits retain a durable failure on the original until
explicit acknowledgement; hidden/redacted originals retire without unreachable
commands. Ordinary drafts must remain separate. Do not reapply the older3 drafts.

Raw complete cold revisions remain open. Upstream main FFI event/room still drops
raw custom metadata; Maven latest26.11.05 was inspected only, not upgraded. A narrow
pinned FFI raw point-read extension is outlined in .work/raw-revision-acquisition-
design.md; no SDK fork, binary or workflow modification/publication yet.

PlatformCI42129516710 exact49ec FAILED backend8's document-reference/retirement
ordering test;11 other jobs passed,2 downstream skipped. Verified artifact13463318718
and exact failing XML retained. Test paused at company client admission, before
document locks. Two test-only WIP files now probe the actual exclusive document
lock and wait for the competing attempt, retaining all outcome assertions.
Focused retirement1 is RUNNING; Platform owns the only heavy local build slot.
Do not run prepared edit-release scripts (they still target failed49ec) or rerun
old CI. No deployment; live54b/schema116.1/flags unchanged.

Phone40 stays installed; both emulators stopped/data and keys preserved. Starter
bce2012 complete. Keep both stashes and accepted Forward publications untouched.
Continue all54 PRD groups after this commit; no group closure is claimed.

## Edit transport evidence accepted; continue authoring — 5 November 2026

Twelve Kotlin files now retain encrypted body-free replacement evidence, including
late/ineligible edits, without granting original send/read identity. Separate
operation indexes are bounded at128 with an authenticated lookahead rejection.
Canonical/input fingerprints, redaction and checkpoint/account checks preserve
recovery evidence independently of display admission.
[Contract](collaboration-edit-evidence.md),
[measurement](measurements/collaboration-edit-evidence-2026-11-05.json).
Focused evidence2 PASS78 fresh/155.626s. Full evidence-full1 PASS4,995 fresh/543.649s,
zero failures/errors/skips, Android lint0;4,159 source inputs unchanged. All wrappers
including72042 drained. Initial evidence1 nullable test compile failure was fixed
after stopping/draining its owned processes; seven shared storage cases now pass
on actual SQLite and IndexedDB. Sources may be edited after committing/pushing.

NEXT: immutable edit target read from owned history (including legacy earliest
alias time and local visibility), existing encrypted command queue extension,
paused SDK staging/recovery/retirement and durable non-applied outcomes, then
long-press composer with ordinary drafts preserved and pending edits on the same
bubble. Retained identity repository supplies the sender without SDK/network.
The3 authoring .kt.draft files are still UNAPPLIED; review them (add room binding,
legacy normalization) before use. Raw complete cold revisions and native proof
remain open; all54 PRD groups remain open. Continue after this commit.

Platform49ec23e0 normalized Testcontainers digest passed24 real local cases,
including both repeated-midnight schedules. Docs8f89a25f pushed; fullCI42129516710
on exact49ec is still running (four dashboard jobs passed). Audit all14 jobs and
exact API/worker artifact digests before rollout. Do not rerun old failed42126384926
or live54b deployment scripts. Live54b/schema116.1 and flags remain unchanged.
Apple d47542123588891 already accepted1,310 cases and full app; excludes later
reference/evidence work. Starter bce2012 complete. Phone40 stays installed, retained
emulators stopped/data intact, both stashes preserved; never repeat Forward sends.

## Edit storage Apple acceptance; transport evidence in progress — 5 November 2026

[Apple evidence](measurements/collaboration-message-edits-apple-2026-11-05.json):
42123588891 exactd47554f8 PASS1,310 cases/154 suites, zero failures/errors/skips,
full Xcode app1188s, simulator compilation720s/tests79s, push envelope and
associations. Artifact13460739856 SHA2565ba983a7… matches GitHub;39 product source
hashes verified. Data/presentation tests are not part of this Apple job; full app
compilation includes them. Physical iPhone waived. Audit driver12479 drained.
Do not rerun. This acceptance excludes later reference/evidence changes.

Replacement-reference product fb52611d is pushed and locally accepted: full4,981
fresh cases/588.776s, focused122/135.577s, lint0. Twelve Kotlin files now add separate
body-free encrypted edit transport evidence; focused evidence1 is RUNNING under
frozen sources. Draft entity/repository/DTO/codec/reader pieces have been applied,
with transactional proof/replay/collision guards and6 shared storage cases on new
SQLite/IndexedDB adapters. Do not reapply .work/apply-evidence.py. Earlier3 authoring
drafts remain unapplied. Finish focused/full gates and fixes before committing this
increment, then durable edit outbox/recovery/retirement and long-press editor with
normal drafts preserved. Raw cold-revision acquisition and native proof still open.

Platform fullCI42126384926 on472abcc9 FAILED all8 backend jobs before DB cases:
Testcontainers2.0.5 misparses combined tag@digest as a different repository. Four
dashboard jobs PASS;2 dependent integrations skipped. Source inspection confirms
this parser limitation. Convention WIP now validates the catalog and uses identical
digest-only spelling for JVM tests, retaining standard compatibility checks. Run
prepared check-pin-smoke.py after the active Mobile gate drains: real isolation,
onboarding/offboarding midnight and worker context, then new full CI after push.
Do not rerun old472 or infer DB acceptance from the prior SQL probe. No live change.

Phone40 remains; both retained emulators stopped/data preserved. Starter bce2012
complete. Preserve both stashes and accepted Forward publications. Continue all54
PRD groups; no feature group closure, edit APK or new device acceptance yet.

## Replacement references accepted locally; continue edit transport evidence — 5 November 2026

[Contract](collaboration-message-edits.md),
[evidence](measurements/collaboration-edit-references-2026-11-05.json). Eleven Kotlin
files add owned replacement facts and explicit provider-reference reconciliation,
including exact sender/event/time, redacted edits, final checkpoint and owner gates.
Original ACK/read/quote facts stay separate. Focused references2 PASS122 fresh in
135.577s. Full references-full2 PASS4,981 fresh in588.776s on4,152 unchanged inputs;
zero failures/errors/skips and Android lint0. Root/browser/Android host/proof compile,
format/architecture/static analysis passed. All wrappers drained. Initial test-scope
compile failure and intentionally stopped full attempt are retained in evidence.
No edit APK or native edit exchange yet; phone40 remains installed.

NEXT: commit/push this increment, then retain separate replacement transport evidence
before durable edit outbox/recovery/retirement and long-press authoring. A provider-
accepted edit arriving after15minutes must settle without displaying an invalid edit
or remaining pending indefinitely. .work/chat-editing-review-2026-11-05 contains
edit-evidence-design.md and UNAPPLIED .kt.draft entity/repository/DTO/codec/reader
pieces. Review/integrate transactional proof recording, original-event collision
and read guards, immutable input fingerprints, redaction, bounded operation lookup,
then actual SQLite/IndexedDB tests. Earlier3 authoring drafts remain unapplied.
The pinned SDK raw cold-revision limitation remains; do not use typed reconstructed
content or notification filtering as complete history evidence. All54 PRD groups
remain open. Keep the authorized long run going after this commit.

Apple42123588891 exactd47554f8 is still building the full app. Prepared local
.audit-apple-storage.py has NOT run;39-file source diff corrected to include proof
bridge. Do not duplicate dispatch. Its result will not cover this new11-file change.
Platform472abcc9 image pin is pushed, docs83d9b26c pushed; full CI42126384926 exact472
runs after old029c failed two Havana repeated-midnight cases. Separate actual PG SQL
proved old10a5→05UTC, new80df→04UTC; pin matches live/Java. Image/architecture and61
Python tests passed; full CI owns new JVM/database acceptance. No local Platform
heavy gate. Audit CI/artifacts before edit-envelope rollout. Live54b10ce8/schema116.1
push/callback/delivery/worker FCM ON, SMTP OFF; no deployment or database changes.
Starter bce2012 is complete/clean; no generic port from this HRIS-specific increment.
Both emulators stopped with data/keys intact; both Mobile stashes preserved. Never
repeat accepted Forward publications or reinstall40.

## Edit-history Apple gate running — 5 November 2026

Product d47554f8778f435a6f8b953b0c8eea7530735871 is committed/pushed. Apple-only CI
[42123588891](https://github.com/fajarxfce/hris-mobile/actions/runs/42123588891)
runs that exact source; do not duplicate dispatch. Local accepted tests and next
provider-reference/authoring work are below. No edit APK/native proof yet.

## Persisted message edits accepted locally; authoring continues — 5 November 2026

[Contract](collaboration-message-edits.md),
[evidence](measurements/collaboration-message-edits-2026-11-05.json).39 Kotlin files
retain encrypted replacement events separately from original send/receipt fields,
including earliest-alias timing, transactional revisions/redaction fallback,
bounded Android raw acquisition and persisted room/list projection. Focused149
cases PASS358.931s. Updated full root/browser/Android gate PASS on unchanged4,150
inputs: first attempt4,859 fresh cases had no assertion failures but presentation
Chrome disconnected; retry88.562s passes175 complete presentation browser cases.
Accepted full-report union4,967 cases, no failures/errors/skips; Android lint0.
All local wrappers drained. No native edit publication or phone release yet.

NEXT priority before authoring release: ReconcileCollaborationHistoryReferences
must read explicit replacement facts because original-only getEventFact excludes
edit aliases; otherwise retained edits stay unresolved/Older available. Do not
expose replacements as original ACK/read/notification facts. Broader raw cold
revision acquisition is still needed when a later invalid edit masks an earlier
valid one. Then implement durable edit outbox/recovery/retirement, long-press UI
with ordinary draft preserved, native exchange/cold offline proof and release.
.work/chat-editing-review-2026-11-05 has edit-outbox-design.md, edit-ui-design.md,
and3 UNVALIDATED authoring .kt.draft files (not applied). Pinned SDK typed fields
win over sendWithExtraContent; typed body must equal bounded fallback to retain
canonical hashes and60,000-character gateway ciphertext budget. Late provider-
accepted edits need retained transport proof even when they cannot display.
Dispatch one Apple-only CI on this product commit; old927 Apple is already done.

Platform029c5828 replacement envelope is pushed, with166 accepted local cases;
docs31928a36 records CI42120370137. That full run has backend7 failure in unrelated
OnboardingScheduleHttpTest's repeated-midnight fixture (HTTP409/SQLState23514).
Investigation found mutable postgres:18.6-alpine digest changed between prior
passing CI20:26UTC (8a334ab9…) and this run22:20UTC (80df0884…); local live image
is older10a5c69a… and resolves Havana midnight to05:00UTC. This is a lead, not
proven cause. Exact failing XML/logs and artifact digest are in Platform .work.
Do not blindly rerun/deploy. Other CI jobs still running. Live remains54b10ce8/
schema116.1, push/callback/delivery/worker FCM ON, SMTP OFF. No live changes.

Phone40 remains installed; both Rani/Bima emulators stopped with data/keys intact.
Starter bce2012 is clean/pushed and fully accepted including Apple42113508947;
no remaining Starter action. Current edit work is HRIS-specific, no generic port.
Preserve both stashes and never repeat accepted Forward publications. All54 PRD
acceptance groups remain open; keep the authorized long run going after this commit.


## Release40 Apple acceptance; edit work started — 5 November 2026

[Apple evidence](measurements/collaboration-summary-inbox-apple-2026-11-05.json):
CI42106835639 on exact927407ad PASS1,298 cases/152 suites, zero failures/errors/
skips, full Xcode app, native push envelope and associations. Artifact13454797609
digest18d75c2a… and29 changed source hashes verified. Domain/raw database tests
execute; collaboration data/presentation compile in the app but their tests are not
in this Apple workflow. Physical iPhone waived. This excludes current edit WIP.
All Apple audit wrappers are drained; do not dispatch another927407ad run.

Phone release40 stays installed and accepted; no reinstall. cf3f53e6 records actual
179.105s offline/HTTP native proof and Bima110.606s retained8-row history proof.
Rani and Bima are now both stopped cleanly with userdata/keys retained. Bima was
stopped after acceptance before Starter compilation to reduce host memory pressure.
Do not reuse the earlier handoff statement that its receiver is still running.

Starter nine-file raw-store port is copied and check-full1/session36694 is running
under frozen Starter inputs. Finish/audit, commit/push and dispatch one Apple-only
run on that port. Platform remains live54b10ce8/schema116.1; push/FCM ON, SMTP OFF.

Mobile now has seven UNVALIDATED edit files: the retained a34a7b2 draft was applied
without dropping either stash, plus five policy and six decoder tests. Policy uses
the earlier retained primary/target timestamp so a later retry cannot extend the
15-minute edit window; original fields/receipt evidence remain separate. Decoder
rejects malformed known nested-relation types and retains complete-content hashes.
Only formatted, not compiled yet. .work/chat-editing-review-2026-11-05/check-decoder.py
is prepared for focused Desktop/Android/format/architecture checks after Starter
finishes. No edit UI, durable edit storage or native edit publication is accepted.
Continue the full edit path and remaining54 chat/call/meeting PRD groups.


## Release40 installed; continue the communications PRD — 5 November 2026

[Delivery evidence](measurements/android-release-phone-2026-11-05-build40.json),
[contract](collaboration-summary-sync.md). Product927407ad185400f16cd5b91ab47208141183bbba
passes consumer-full3 (4,895 fresh cases, zero failures/errors/skips), Android
release build626.219s/lint0 and retained Rani native179.105s. APK88,469,236 bytes,
SHA2566d7821859987a8cc235d69dc36013b595680b229c76541f7651ef16f4cf2dc01,
nondebuggable ARM64 devRelease40/1.0.0, same development signer.

Phone10.77.77.3:5555 install-only39→40 confirmed21:17:59UTC/45.276s including
verification. Device APK hash matches, appId10962 and original first-install time
retained. No launch/clear/uninstall. Do not reinstall40. Rani proof observes actual
changes HTTP200, stable draft previews, offline4 room rows and cold offline restart,
contacts retained, filters/swipes/FAB; network/viewport restored. First observer
protocol-filter failure and compiled-marker assumption are corrected/documented.
Passive observer is stopped/drained. Rani is stopped with unchanged userdata/keys.
Bima updated39→40, retained history proof passes110.606s; owned
receiver remains running on5562 (verify emulator-start.json, not old PID records).
No new messages or Forward publications. Four screenshots reviewed; six in Git.

Platform7883d117 docs record live54b10ce8/schema116.1. CI42102966162 all14jobs pass;
200 business-table contents and original migration history preserved.7 pairs→14
summary records/published changes. Old11a98115 runtime compatibility is tested on
an isolated migrated copy; final backup and old images retained. Live push/callback/
delivery plus worker FCM ON; SMTP OFF. Do not repeat rollout or restore old data.

NEXT: finish Starter nine-file raw-store port under .work/replica-staging-port-
2026-11-05 (files copied, checks pending), commit/push then one Apple-only CI.
Audit Mobile Apple42106835639 exact927407ad, currently running full Xcode app; no
duplicate dispatch. Previous raw-store Apple5f0eacdd is already accepted. All local
Mobile build/native/install/observer wrappers drained before this record.

Then continue edit/delete/groups/media/backups/calls/meetings; all54 PRD groups
remain open. Do not stop at metadata. Preserve stashes a34a7b2 (one tracked + four
untracked edit drafts, use git stash show -u) and bfdef2ce (old Forward work).
Next edit review at .work/chat-editing-review-2026-11-05/continuation.md confirms
five pinned SDK files by Git blob hash: debugInfo separates originalJson/latestEditJson;
replacement events aggregate into originals. RoomMessageLikeEvent exposes live raw
contentJson; loadOrFetchEventWithRelations exposes typed events without raw JSON.
A latest invalid/out-of-window edit can hide an earlier admissible one during cold
recovery; resolve that deliberately. Keep original send/receipt evidence distinct.


## Incremental inbox consumer passes full local gates — 5 November 2026

[Contract](collaboration-summary-sync.md),
[evidence](measurements/collaboration-summary-inbox-2026-11-05.json).
consumer-full3 PASS4895 fresh cases686.04s, zero failures/errors/skips; root,
browser, Android devDebug assembly/lint on4,133 unchanged inputs. Focused49 domain
and28 UI/VM cases plus4 app/DI cases accepted. Earlier failures and owned full2
cancellation are recorded; every wrapper/child is drained before this record.

ChatInbox uses bounded durable Sync and local-only pagination; healthy preview
streams persist through name/checkpoint changes. Point metadata gets one local
CAS retry if sync publishes first. All source is current; do not recopy .work
drafts or rerun fix-lengths.py (already applied then further refined).

NEXT: commit/push this product increment and dispatch one Apple-only CI on its
exact source (previous raw5f0eacdd is fully accepted, not this activation). Build40
scripts are prepared under .work/release-phone-2026-11-05-build40 and require this
full gate plus clean committed source. No release40 build/install yet. Complete
Platform CI42102966162/workflow54b10ce8 and live116.1 migration before real retained
Android online/offline/cold-reopen acceptance. Stage scripts reference that new run.
Old42091460931 reached20min budget twice; all test steps remain under the35min job.
No live API/worker/provider changes yet; current11a98115/schema116; push/callback/delivery flagsON and worker FCMON, SMTP OFF.
Read-only running-container inspection corrects earlier stale pushOFF summaries.

Phone39 remains accepted/installed. Announce each later install-only; never drive
phone UI. Preserve all emulator data/keys and both protected stashes. Bima receiver
5562 remains owned/running (verify start record); Rani stopped with retained data.
Starter fbe5ea8 UI port accepted. Raw-store preflight finds five exact previous
files and four absent new files, no divergence; port follows native acceptance.
Continue all54 PRD groups after metadata, including edit/delete/groups/media/backup/
voice/video/meetings. Forward native publications are already accepted; don't repeat.

Next edit review: stash a34a7b2 contains one tracked mapper change and four untracked
drafts (git stash show -u); preserve both stashes. Android history deliberately
keeps debugInfo.originalJson and discards latestEditJson. Confirm the native edit
envelope/identity before wiring the draft decoder. Preserve original send/receipt
facts and bounded native ownership. Supplement: .work/chat-editing-review-2026-11-05/continuation.md.


## Raw staging Apple accepted; activated consumer under full local gates — 5 November 2026

[Apple evidence](measurements/replica-staging-apple-2026-11-05.json):42094694741 on
exact5f0eacdd PASSED1,279 cases/149 suites, zero failures/errors/skips, full Xcode app,
push-envelope and associations. Artifact13448518222 archive digest verified. Nine
raw-store source hashes verified; all five new AppleReplicaStagingTest cases run.
Compile860s, simulator141s, full app1,483s. Transport compiles in the app; its data
tests are not included by the Apple workflow. This excludes newer format2/Sync/UI.
No duplicate or rerun needed. All Apple audit wrappers drained. Phone stays39.

CURRENT UNCOMMITTED: ChatInbox uses bounded Sync with local-only paging and owned
continuation; unchanged metadata keeps preview streams. Point metadata merge retries
one local CAS when a background publication wins. inbox2 passes28 focused UI/VM
cases134.292s. app1 passes41 domain cases, but two new real-Room integration tests
had virtual-time deadline interference. app2 uses Dispatchers.Default for that
integration body and passes all4 app cases59.807s, retaining the41 exact passing
domain XML reports. app2 wrapper had an incorrect six-fresh-case minimum; explicit
app2/acceptance.json audits four fresh plus41 retained. Assertions/deadline unchanged.

consumer-full1 is RUNNING (session37627), source inputs frozen. Full root, domain/
data/presentation browser tests, Android assembly/lint. It already found two detekt
LongMethod violations (Sync.synchronize128 and NativeCollaborationUi.response124).
Allow tests/children to drain before edits. .work/chat-summary-sync-2026-11-05/
fix-lengths.py is PREPARED, NOT applied: extracts admission completion and fixture
summary endpoint handling. Apply/review/format after drain, then complete full gates
before committing product/UI. Do not recopy earlier .work drafts. No installation.

Platform6fbfe4eb records retained-database migration rehearsal:116→116.1,200 existing
tables and prior migration history unchanged, seven pairs queued, restricted roles,
all temporary resources removed. No live changes. Full CI42091460931 attempt2 reruns
only the cancelled dashboard-integration job after slow Ubuntu font downloads;
13 other jobs passed. Wait/audit before staging/deploying. Next: runtime compatibility,
final rollout, native/offline release40 proof and announced phone install-only, then
raw Starter port and remaining54 communications PRD groups. Preserve all emulator
userdata/keys and both protected stashes. Bima receiver5562 remains owned/running.


## Bounded metadata Sync accepted; activate ChatInbox next — 5 November 2026

Six source/test/DI files add pure bootstrap/change folding and SyncCollaborationList.
[Contract](collaboration-summary-sync.md),
[evidence](measurements/collaboration-summary-sync-usecase-2026-11-05.json).
sync2 PASS38 desktop cases91.246s, zero failures/errors/skips; data Desktop/Android
compilation, format and architecture pass on4,130 unchanged inputs. sync1 had one
untyped listOf(null) test compile error, corrected; product compiled. Both local
wrappers drained. Existing encrypted storage0e0fc53f remains accepted.

NEXT: replace ChatInbox Refresh acquisition with bounded Sync and local-only paging;
retain preview/detail subscriptions for unchanged content, cancel owned continuation
on hide/scope changes, retry transient CAS conflicts without technical UI copy. Add
ViewModel and AppFlow fixtures/gates, full/browser/native proof, migration rollout
and release40. Current ChatInbox still uses the old acquisition; phone39 unchanged.
Do not recopy stale .work drafts. Physical phone install-only after announcement.

Remote Apple42094694741 exact5f0eacdd has passed simulator tests and is building the
full app; audit final job/artifact. It excludes newer format2/Sync. Platform full
CI42091460931 exactd03fb472 has13jobs passed; dashboard-integration still running.
No duplicate dispatch/deploy. Live11a98115/schema116/chat pushOFF; V116.1 requires
backup/clone/migration rollout after CI. Starter generic UI port accepted; raw store
port waits Mobile/native consumer acceptance. Continue all54 PRD groups afterward.
Preserve both stashes and all emulator sidecars/keys; owned Bima receiver5562 running.


## Encrypted metadata cache accepted; Sync use case/UI next — 5 November 2026

Nine list domain/data/test files add encrypted format2, sync window/begin/commit,
V1 compatibility, tracked revisions/tombstones and immediate interactive staging
preemption. [Contract](collaboration-summary-sync.md),
[evidence](measurements/collaboration-summary-cache-2026-11-05.json).
consumer-storage1 passes640 cases239.255s; added pending-cancellation/owner/race
scenarios produce consumer-storage2 PASS644 cases168.482s (21 desktop+623 browser),
zero failures/errors/skips. Android compilation, format and architecture pass on
4,125 unchanged inputs. All local check wrappers/children have drained. These are
scoped cache gates; the earlier4,070-case root gate belongs to raw storage5f0eacdd.
No remote Sync use case or new ChatInbox acquisition is active yet; phone stays39.

NEXT: .work/chat-summary-sync-2026-11-05/sync-policy-draft/CollaborationListSyncPolicy.kt.draft
contains an uncompiled phase/folding policy draft. Review/apply, add domain cases,
then implement bounded SyncCollaborationList with owner/admission capture around
all remote and local operations. Wire ChatInbox to local pagination and owned delta
continuation, retaining existing local previews and detail streams. Do NOT recopy
older consumer-draft or rerun prepare-consumer-*.py over current source: their test
copies predate the two added cancellation/race cases. Actual source is authoritative.
consumer-design.md remains supplementary. Check contradictory equal revisions,
old tombstones/restore, count bounds, finite/pending rounds, cursor expiry/new-parent
reset and late/denied acquisition. Whole-app/native acceptance remains required.

Apple-only CI42094694741 runs exact raw-storage5f0eacdd (started19:31:28UTC), recorded
in .work/chat-summary-sync-2026-11-05/apple-dispatch.json. Do not duplicate/cancel;
five new AppleReplicaStagingTest cases plus transport are included, not this newer
format2 increment. Starter UI port fbe5ea8/e501d4da is fully accepted. New raw storage
port waits for Mobile consumer/native proof. Phone39 already accepted/installed;
announce each later install-only and do not drive its UI. Preserve both stashes
and all emulator data/keys; Bima receiver5562 remains owned (verify start record).

Platform a5dbe2ff records control42090785206 accepted on productd03fb472:706 cases,
seven existing opt-in skips. Full CI42091460931 has all8backend+4dashboard jobs passed;
dashboard-integration and recovery-integration are running. No deploy. Live11a98115/
schema116/chat pushOFF. Prepared artifact/image scripts require final all14-job
acceptance;116.1 migration still needs a retained backup/restore rollout. Continue
all54 open communications PRD groups after metadata; preserve group117–137 ordering.


## Raw staging operations accepted; encrypted consumer starts next — 5 November 2026

Nine generic database/contract files are accepted on a931a09d. Exact unpublished
staging discard preserves active data/cursor/tags, and bounded working reads include
tombstones with their checkpoint. SQLite/IndexedDB exercise late writes, competing
continuation/publication, coherent reads, cancellation, scopes and cold reopen.
[Contract](replica-storage.md), [evidence](measurements/replica-staging-2026-11-05.json).
Focused storage1 found one real SQLite pre-cancellation bug; explicit ensureActive
before I/O fixes it. storage2 passes66 desktop cases68.197s;64 browser cases passed
in storage1 unchanged. Full storage-full1 PASSES4,070 fresh cases755.207s, zero
failures/errors/skips, root/UI checks, Android assembly and zero lint issues;
4,120 unchanged inputs. All local build wrappers/children are drained.

NEXT: push this increment and dispatch one Apple-only CI on its exact source;
five new AppleReplicaStagingTest cases are prepared, not yet executed. Then apply
and test the encrypted metadata consumer drafts under .work/chat-summary-sync-2026-11-05/
consumer-draft (*.kt.draft). They are NOT compiled or accepted. Current drafts add
format2/checkpoint state, encrypted codec, list repository sync methods and staging
preemption, and four common storage scenarios with Desktop/Browser wrappers.
consumer-design.md describes next use-case/presentation work and remaining race/
denial/cancellation tests. Preserve original V1 keys/facts, enforce tracked revision
and tombstone ordering, catch up pending publication before replacement, and never
put remote opaque cursors into UI UUID pagination. No SyncCollaborationList use case
or ChatInbox wiring exists yet. Prepare/run focused storage gates before activation.

Phone39 remains installed; do not reinstall. Starter UI port is fully accepted:
fbe5ea8 audits Apple42088401280,118 cases/full Xcode app/associations. New raw storage
port waits for Mobile consumer/native acceptance. Both protected stashes and all
retained emulator userdata/sidecars/SDK keys remain untouched; Bima receiver5562
is still owned by PID2720994/startTicks158001625 (verify before use).

Platform d03fb472 native feed/control CI42090785206 PASSES706 cases/113 suites,
zero failures/errors and seven existing opt-in skips (six native interops plus
elapsed-retention). Artifact identity audited; full CI42091460931 remains running.
Prepared artifact/image staging scripts are not run. No deployment; live11a98115/
schema116/chat pushOFF. Continue metadata delivery then54 open PRD groups including
edit/delete/groups/media/backup/calls/meetings. Announce each future install-only.


## Native metadata transport accepted; storage consumer next — 5 November 2026

Eight transport/domain/DTO/test files are accepted against Platform d03fb472.
[Contract and next work](collaboration-summary-sync.md),
[evidence](measurements/collaboration-summary-transport-2026-11-05.json).
Ten desktop cases pass; corrected transport2 passes617 browser cases75.003s,
zero failures/errors/skips. Android compilation, format and architecture pass on
4,116 unchanged inputs. transport1 format read temporary .work drafts and failed;
its class-only browser filter selected no cases. Drafts now end .kt.draft and the
entire collaboration browser suite runs. Both wrappers and children are drained.
No consumer/routing/phone behavior change yet; release39 remains installed.

NEXT: implement/test atomic staging discard and bounded working-record reads in
core/database. Nine prepared files under .work/chat-summary-sync-2026-11-05/storage-draft
are not yet applied or accepted: five product files plus common/Desktop/Browser/
Apple contracts. They retain active data, include tombstones, enforce exact CAS,
exercise late writes/competing continuation/publication, cancellation, scope and
reopen. Apply after this transport commit, format, run real SQLite/IndexedDB gates,
then implement the encrypted version2 consumer described in the contract. No
application source is currently frozen. Do not expose a consumer before deployment.

Platform d03fb472 is pushed and native feed passes full worker/API139 cases432.482s
with one existing opt-in skip. Full CI42091460931 now runs exactd03fb472 alongside
Collaboration control42090785206; do not dispatch duplicates. Live remains11a98115/
schema116/chat pushOFF. Starter Apple42088401280 exacte501d4da has simulator tests
passed and full app building; audit final artifact when complete. Mobile39 Apple
fully accepted. Preserve all sample data/keys and both Mobile protected stashes.
Continue long run;54 PRD groups remain open, including group117–137 integration,
edit/delete/media/backup/calls/meetings. Announce every later install-only delivery.


## Release 39 Apple accepted; metadata delta work continues — 5 November 2026

Apple42081732094 PASSES exact source2539c6585f77c4f6898ff28426604c3583927fa4:
1,274 simulator cases/148 suites, zero failures/errors/skips. Complete Xcode simulator
app, native push-envelope extraction and generated associations pass. Downloaded
JUnit/artifact identity audited in [Apple evidence](measurements/chat-hub-controls-apple-2026-11-05.json).
No physical iPhone test. Release39 stays installed on the phone; no new APK needed
for this documentation. Six native captures and delivery4a60e9e6 remain accepted.

Starter generic UI porte501d4d is committed/pushed after independent root/Android/
lint/Web acceptance327.311s:390 desktop and six build-logic cases, zero failures/
errors/skips. Its Apple42088401280 is in progress on exacte501d4d; no duplicate run.
HRIS routes, policy and feature lifecycle remain in Mobile. Platform projection
worker31fa5067 is committed/pushed after full worker graph123 cases378.467s with
one existing opt-in skip. No backend deploy: live11a98115/schema116/chat pushOFF.

Platform native summary bootstrap/delta endpoint is now uncommitted implementation
with codec/policy/HTTP/RLS tests. First feed gate compiled but four fixture cases
failed on native-session cap, refresh interval and Jackson array iteration. Those
fixtures are corrected without loosening production limits; feed2 is running on
frozen sources. Pending account/pair/journal reconciliation and independent metadata
revisions are included. Full worker/API gate and atomic Mobile consumer still remain.
Continue long run through deltas, edit/delete/groups/media/calls/meetings;54 PRD
groups remain open. Preserve both Mobile stashes and all fixture SDK keys/images.


## Release 39 installed; compact swipe hub accepted — 5 November 2026

Product source `2539c6585f77c4f6898ff28426604c3583927fa4` is pushed and installed as
non-debuggable ARM64 devRelease39/1.0.0 on the authorized phone.
[Delivery](measurements/android-release-phone-2026-11-05-build39.json).
88,420,084 bytes; SHA256 `75ff35899b84bcc972bb9cd7e775707d30bd4473d8185aa2ffe45695f6973a1d`.
Clean build/lint494.972s, zero lint. Announced install-only38→39 took46.422s with
device-byte verification. App identity, signer, data and first-install time retained;
no phone UI launched or driven. Do not reinstall this artifact.

Actual Rani Android acceptance passes156.226s: filter icons/dialogs in Contacts and
search, swipes both directions, fixed tabs, scroll-responsive FAB, return from New
chat, both drafts and cold offline reopen. Independent HTTP probes confirm outage
and recovery. Six final captures opened/reviewed. The first attempt expected scroll
on a non-scrollable five-contact fixture; the final driver temporarily uses a
1080x1400 viewport for that gesture and restores it in finally, density unchanged.
No APK/product change, new messages, permission grants, reset or key replacement.
Bima passes four retained-history checks109.519s: eight visible rows with no Older,
decryption or manual-history error after update, reopens and cold process restart.
All native/build/install wrappers and children are drained. Rani is stopped;
Bima receiver is emulator5562/PID2720994/startTicks158001625; verify its current
start record before operating it. Samples1/2 now39, samples3/4 remain37. Preserve
all userdata images, sidecars and original SDK keys; no Forward publication repeat.

Apple42081732094 runs exact2539c658: simulator tests passed18:23UTC, full app still
building at18:32. Audit complete job/downloaded reports before acceptance; do not
dispatch another same-branch run. No physical iPhone claim. Previous38 Apple accepted.

NEXT: continue the long run. Six exact generic component/color/UI-test files are
copied into Starter as uncommitted work after native acceptance; independent Starter
root/Android/lint/Web and Apple gates remain. No HRIS routes or lifecycle copied.
Platform bounded projection worker is WIP on66d18b2d. Its first focused gate compiled
but failed three account-queue reads; the datasource now excludes the unused
PostgreSQL infinite due timestamp. projection2 is running with frozen product inputs.
Full worker/API context gate remains required. Live stays11a98115/schema116/chat
pushOFF. Dedicated metadata delta HTTP and atomic Mobile cursor consumer, then
editing/delete/groups/media/calls/meetings remain; all54 PRD groups open. Preserve
both protected stashes and push every commit. Current UI corrections do not cancel
broader work.

## Compact swipe Chat hub locally accepted; release 39 next — 5 November 2026

Chat now has no title app bar/actions. Fixed Conversations/Contacts support taps
and horizontal gestures. The 56dp New chat FAB hides on downward consumed scroll,
returns upward, honors reduced motion and cannot be clicked while exiting. Both
Contacts and EmployeeSearch have a 48dp filter icon to the right of search, without
a separate filter row. Only the settled page acquires/consumes effects; drafts,
directory position and admitted local previews remain retained. Offscreen settled
pages are excluded from accessibility. [Contract](collaboration-chat-hub.md).

Full root/Android/browser/proof gate PASSES3,088 fresh cases453.069s, zero failures/
errors/skips,4,108 unchanged inputs. All90 AppFlow cases and seven generic UI cases
pass. Seven synthetic Compose captures were opened/reviewed. Earlier focused1
failures were six ambiguous vertical-list selectors and the architecture rule
against page-owned mutable state; selectors now specify a vertical axis and the
scroll rendering state belongs in the slotted generic AppFloatingActionLayout.
Focused2 passed38 fresh cases222.596s. [Evidence](measurements/chat-hub-controls-2026-11-05.json).
All check/formatter wrappers and children are drained.

NEXT: clean ARM64 devRelease39 build/lint/artifact verification, own Apple run on
the new committed source, retained Rani native filter/swipe/FAB/offline proof and
Bima history checks. Only then announce and install-only39 on10.77.77.3:5555.
Phone still38; no39 build/install acceptance yet. Bima was cleanly stopped after
identity verification to free build resources. All four sample images, sidecars
and device keys remain retained. Start sample1 only after build load ends;
restore Bima as receiver afterward. No new messages or fixture grants are needed.

Port four generic components, onAccent color and seven reusable UI cases to Starter
after Mobile native acceptance; HRIS navigation/filters/lifecycle stay in Mobile.
Platform now has uncommitted bounded projection worker/repository/policy/DI/tests
on top of66d18b2d; they are NOT compiled or tested yet. Prepared focused and full
worker/API gates live under .work/chat-summary-delta-2026-11-05. Run both, including
the complete apps:worker:test graph, before committing that capability. Live stays
11a98115/schema116/chat pushOFF. Dedicated native delta HTTP and atomic Mobile
cursor consumer remain next, then edit/delete/groups/calls/meet; all54 PRD groups
remain open. Preserve both protected stashes. Continue the long run after UI delivery.

## Release 38 Apple accepted; compact swipe hub in progress — 5 November 2026

Apple run42069899173 PASSES exact source34e68b128bd9fa80ccc817d8e008caf850920d96:
1,274 cases/148 suites, zero failures/errors/skips. Full simulator app, push-envelope
extraction and generated associations pass. Downloaded JUnit reports and artifact
identity are audited in [Apple evidence](measurements/chat-hub-apple-2026-11-05.json).
No physical iPhone test. Release38 remains the installed phone build.

Current uncommitted Mobile work removes the Chat app bar, adds a scroll-responsive
New chat FAB, makes Conversations/Contacts swipeable and places department filters
as icon disclosures beside the directory and search fields. Generic pager/filter/FAB
components are candidates for Starter only after Mobile validation. The focused
gate is running under .work/chat-hub-controls-2026-11-05/focused1; do not edit frozen
application sources until its wrapper and children finish. No release39 validation,
build or installation is claimed yet.

Platform66d18b2d is pushed: bounded raw direct-summary change capture, Flyway116.1,
version-ordering/recovery support and 64 passing database/sync/recovery cases.
It is not deployed; live remains11a98115/schema116. Next backend work is the bounded
projection worker and owner-scoped native delta feed, followed by atomic client
consumption. Current UI changes are not delta acceptance. Group117–137 and the
protected edit stashes remain unshipped. The user explicitly reiterated that after
these UI fixes the broader chat/call/meeting long run must continue.

## Release 38 installed; Chat/Contacts native offline proof accepted — 5 November 2026

Mobile source `34e68b128bd9fa80ccc817d8e008caf850920d96` is committed/pushed and
installed as non-debuggable ARM64 devRelease38/1.0.0 on the authorized phone.
[Delivery and native evidence](measurements/android-release-phone-2026-11-05-build38.json).
APK SHA256 `cbb6c3d1f6fcffde562a02584212354f561e6b1947cd48766c51a52e8581524e`,
88,403,668 bytes. Build/lint528.929s, zero lint. Announced install-only37→38 took
60.322s including device-byte verification; app ID, signer, first-install time,
data and sessions preserved. No phone UI operated. Do not reinstall this artifact.

[Chat hub](collaboration-chat-hub.md) groups Conversations/Contacts under one bottom
Chat destination. Contacts retains the company directory; New chat opens the
cross-company participant picker. Rows are not yet account-linked chat actions.
Same-owner previews survive names-only local emissions and section reentry;
duplicate lists do not restart detail subscriptions, unchanged metadata does not
rewrite ciphertext/checkpoint, and empty Next no longer leaves a latent navigation
intent. HRIS metadata HTTP remains paged acquisition, NOT a delta feed. Matrix
messages already use incremental transport. Actual metadata deltas/tombstones and
account-linked contact actions remain required work, followed by editing/deletion/
groups/calls/meet. Do not describe this UI/storage fix as delta acceptance.

Final source full2 PASSES root check/Android/browser/proof build-tests:1,045 fresh
cases/399.451s, zero failures/errors/skips,4,103 unchanged inputs. Six synthetic
desktop captures reviewed. [Gate evidence](measurements/chat-hub-2026-11-05.json)
retains the prior login timeout and its passing90-case rerun without changed login
code/timeouts. All local build/check/install/proof wrappers are drained.

Owned Rani/sample1 passes exact38 hub/Newchat return, both draft previews, room
history, offline reopen and cold offline restart98.877s. Independent HTTP probe
confirms the server unavailable; contacts remain after cold restart. Four actual
native captures reviewed. Rani alone received people.directory.read through the
normal administrative API and refreshed workspace by Change company/reselect;
no sign-out, reset, key replacement or new messages. The old provision.py chat-only
permission assertion is now stale for Rani; do not rerun it blindly.

Retained Bima passes four history checks109.672s:8rows, no Older/decryption/history
error after in-place update, two reopens and cold process restart. Bima is the owned
receiver on emulator5562/PID2232286/startTicks157580400; verify current start record
before touching it. Rani/Nadia/Dimas are stopped with userdata AND sidecars/keys
retained. Samples1/2 are38; samples3/4 remain37. Never repeat the already accepted
Forward publication. Phone installs require notification each time, explicit serial
10.77.77.3:5555 and --install-only; never drive phone UI.

Mobile Apple42069899173 runs exact34e68b12:simulator tests passed17:12UTC, full app
still building at17:20. Do not dispatch another same-branch Apple run. Download/audit
all reports, whole app and associations before accepting it. No physical iPhone
claim. Starter batch Apple42061944252 is fully accepted118cases/31suites at0fa8e09;
docsf99650d pushed, no pending batch port or HRIS hub port to Starter.

Backend11a98115/schema116 unchanged; chat pushOFF. GroupV117–137 remain unshipped;
coordinate numbering before a new metadata migration. Protected editing stasha34a7b
(five unvalidated decoder/policy files) and originalbfdef2ce remain; do not overwrite
them. Edit window15minutes from server acceptance, immutable original send evidence
separate from replacement content. All54 PRD groups remain open; continue the long
run and push every commit. Current user corrections do not cancel broader work.

## Forward Apple accepted; generic batch port validated — 5 November 2026

Full [Apple CI 42054097304](measurements/chat-forward-apple-2026-11-05.json) passes
exact source 5a8d4524: 1,273 cases/148 suites, zero failures/errors/skips, including
11 Forward and six raw batch cases. Complete simulator app and associations pass.
Downloaded reports are audited; no physical iPhone claim or additional Mobile CI
dispatch is required for the unchanged application source.

Phone remains installed release 37, accepted native delivery and four retained
history checks are recorded below and in cf054b46. Do not reinstall that artifact.
Bima remains owned receiver 5562; no local Mobile job is running.

Starter port 0fa8e09 is committed/pushed: ten exact raw batch files, independent
root/database-browser/Android/lint/Web pass 803.314 s on 1,010 unchanged inputs,
383 desktop/59 browser/six build-logic cases with 243 freshly executed. Lint zero
errors and 44 existing warnings. All wrappers drained. Independent Starter Apple
dispatch is being reconciled after a GitHub HTTP 502; query recent runs before
retrying so an accepted uncertain dispatch is not cancelled.

Next product work is delivered-message editing and its durable replacement
projection, then delete for everyone, groups, calls and meetings. Original history
must retain send evidence; m.replace must never establish an original message.
The PRD edit window is 15 minutes from server acceptance. No edit implementation
or UI acceptance is claimed yet; preserve that boundary while adding revisions.
All 54 PRD groups remain open. Backend and phone stay as recorded below.

## Release 37 installed; native Forward and retained history accepted — 5 November 2026

Source `5a8d45240e1e21f0b2c7a3fca6629be1ecf65159` is installed on the authorized
phone as non-debuggable ARM64 devRelease 37/1.0.0. APK SHA256
`724f4be58de4ff27e3fbedac367e8edb0239180271442db5a09b0d3f848af738`, 88,403,624 bytes.
Announced install-only update 36→37 took 46.563 s including verification; original
app ID, signer and first-install identity preserved. No phone UI driven or data cleared.
[Delivery and native evidence](measurements/android-release-phone-2026-11-05-build37.json).

All five native Forward phases pass on the same APK: offline picker, one atomic
four-message publication, cold offline sender restart with both drafts preserved,
reconnect without duplicates, and decrypted receipt/cold offline restart on Nadia
and Dimas. Five captures were opened and reviewed. Receiver `sent:false` means no
sender-side delivery icon; it does not mean receipt failed. Do not rerun setup or
Forward: the original synthetic batch has already been sent once.

Retained Bima passes all four history checks in 110.465 s: eight visible rows,
no Older control, decryption placeholder or history error across update, two room
reopens and a cold restart. The fresh Rani test lacked a known exhausted Browse
precondition; its failed first harness and zero-click follow-up remain documented,
not counted as saved-end acceptance. No application source changed during these tests.

All install/proof wrappers are drained. Bima remains the owned receiver on emulator
5562, PID 1754180, startTicks 157069109; verify its current start record before any
mutation. Samples 1/3/4 are stopped with data, sidecars and keys retained; all four
are now release 37. Backend 11a98115/schema116 unchanged; chat push rollout remains off.

Apple CI 42054097304 runs exact 5a8d; simulator tests passed, complete app still
building. Do not cancel with another same-branch Apple dispatch. Download/count
reports and verify the whole job before accepting Apple. No physical iPhone claim.

Starter now contains the exact ten generic core/database batch files from e3c1e326
after Mobile native consumer acceptance and repeat baseline comparison. Its independent
root/database-browser/Android/lint/Web gate is running under
.work/command-batch-port-2026-11-05; no HRIS Forward policy or configuration copied.
Next: finish that gate, document/commit/push and dispatch its own Apple CI. Continue
delivered-message editing, delete for everyone, groups, calls and meetings. All 54
PRD groups remain open. Protected stash bfdef2ce remains retained; do not reapply it.

## Release37 built; native sender accepted, receivers next — 5 November 2026

Source5a8d45240e1e21f0b2c7a3fca6629be1ecf65159 is committed/pushed. Clean ARM64
release37 build/lint passes505.406s/zero lint; all artifact/signing/backup/endpoint
checks pass. APK88,403,624 bytes, SHA256`724f4be58de4ff27e3fbedac367e8edb0239180271442db5a09b0d3f848af738`.
Artifact and scripts: .work/release-phone-2026-11-05-build37. Phone remains36;
WireGuard/ADB10.77.77.3:5555 is unreachable, one user question is pending.
Do not drive phone UI; announce each eventual --install-only installation.

Rani/sample1 updated36→37 in place. Actual Android picker works offline with two
selected targets. Four forwarded intents were committed ONCE. After cold app
restart offline, all four remain queued and source/destination drafts survive.
After reconnect all four are Sent with no duplicate copies. Captures were opened
and reviewed. .work/chat-forward-2026-11-05/native-picker-1.json,
native-publish-1.json and native-reconnect-1.json contain exact evidence.
DO NOT repeat Forward or setup messages: existing publication was resumed, not
replayed. The first publish harness wrongly required an already exhausted Browse
position in a fresh chat that had never browsed; its failed record remains as
native-publish-1-first-attempt.json. The corrected proof records initial history
controls separately. A further Rani Browse test waited for a control that was
already absent after reopen (zero clicks); native-rani-history.json is NOT a
passed saved-end acceptance. Retained Bima's four checks remain required.

Rani is cleanly stopped, data/keys/sidecars retained. Sample3/Nadia is starting on
owned emulator5562. NEXT: drain its start, install-owned-sample.py 3, then
prove-native.py receive 3. Repeat for retained sample4/Dimas. Verify actual received
Forwarded metadata and cold offline history on both; sender Sent alone is not full
native acceptance. Then update retained Bima36→37 and check-bima-history.py; restore
Bima as receiver. Only then run phone preflight and announced install-only delivery.

AppleCI42054097304 runs exact5a8d; simulator tests passed, full app build is still
running. Do not cancel with another same-branch Apple dispatch. Backend11a98115/
schema116 unchanged. No product source changed during native checks. Starter port
is still pending Mobile receiver acceptance; all54 PRD groups remain open.

## Offline Forward locally validated; native delivery next — 5 November 2026

Forward now publishes complete encrypted text batches from long-press selection,
with multiple recipients, original IDs across uncertain retries, retained composer
drafts and forwarded metadata. The picker releases notification focus and retained
viewport observation. [Contract](collaboration-chat-forwarding.md) describes the
bounds: acquired, unedited text to existing direct chats; at most 50 messages,
five targets and 128 total intents. All 54 PRD groups remain open.

Final full6 passes 1,034 fresh test cases / 374.333 s,
zero failures/errors/skips, 4,099 unchanged source inputs. Includes root
check, Android host/compile, browser data/presentation and native proof build/tests.
All three actual Compose focus/viewport lifecycle cases pass. Focused recovery626
passed174.569s; prior full5 passed1,779/447.069s. [Evidence](measurements/chat-forward-2026-11-05.json)
records corrected failures, report hashes and source fingerprints. In particular,
Wasm Promise failures now use safeStorageCall classification before authenticating
the complete retained batch; cancellation/fatal errors still propagate.
All local gate wrappers and children are drained. Three generated picker captures
were opened/reviewed (compact, dark 200% text, landscape), not pixel-baseline tests.

Phone remains installed release36. No release37 build/install or native Forward
acceptance exists yet. Backend11a98115/schema116 is unchanged. Apple source e3c1e326
passes CI42042685222:1,262 tests including six command batch cases and full simulator
app/associations. That run predates Forward; dispatch new Apple CI after this commit.
[Batch Apple evidence](measurements/command-batch-apple-2026-11-05.json).

All owned emulator images are stopped; preserve userdata.img AND sidecars/keys.
Rani/sample1 on release36 has two synthetic sent messages to Nadia/sample3, a
Dimas/sample4 chat and retained source/destination drafts. Setup105.135s is recorded
in .work/chat-forward-2026-11-05/native-setup.json; do not resend fixture texts.
Prepared release37 scripts live under .work/release-phone-2026-11-05-build37.
NEXT: commit/push, build/lint CLEAN ARM64 devRelease37, verify the artifact; start
sample1 and install-owned-sample.py 1. Run phased prove-native.py under
.work/chat-forward-2026-11-05: picker/publish/reconnect 1, receive 3 and receive 4
(after each retained receiver update). The harness switches ONLY the owned emulator
offline and uses synthetic messages. Inspect screenshots and phase records; never
repeat an uncertain publication blindly. Also run retained Bima's four history/
restart checks on the same APK, then restore Bima as persistent receiver.
Announce EACH phone install, explicit10.77.77.3:5555, --install-only; no phone UI.

Protected stashbfdef2ce is retained; do not reapply it. Starter baseline9959c1d was
read-only audited: all four existing batch files match Mobile's pre-e3c baseline;
six new files are absent. Exact audit .work/chat-forward-2026-11-05/starter-batch-baseline.json.
After Mobile consumer/native acceptance, port only generic core/database source
e3c1e326 with independent checks. Continue edit/delete/groups/calls/meet. Main,
group worktree and Starter have no new code changes in this increment.

## Release36 Apple accepted; Forward metadata under test — 5 November 2026

Full AppleCI42036083914 passes sourcea26a1248: downloaded1,256 cases/146 suites,
zero failures/errors/skips, including seven SynchronizeCollaborationDevice tests;
complete simulator app/associations pass. [Evidence](measurements/chat-device-sync-apple-2026-11-05.json).
Phone remains installed36; Bima owned emulator5562 remains active, backend unchanged.

Generic batch sourcee3c1e326 is committed/pushed and separately running Apple-only
CI42042685222. Do not cancel it with another same-branch dispatch. Local raw API
120-focused/2,707-full acceptance remains valid. Forward feature metadata patch was
restored without losing synchronizeDevice fake methods; protected stashbfdef2ce
is retained. Current uncommitted work adds encrypted atomic message insertion,
authenticated exact replay and forwarded metadata across queue/original history/live
projection. It has no recipient UI/use case or native acceptance yet. Focused gate
metadata1 selected an absent jvmTest task (zero tests); corrected metadata2 selects
desktopTest and is running. Do not edit source until its wrapper/child drains.

Next: inspect focused result, finish offline source/target preparation and long-press
Forward picker preserving drafts and original IDs, validate actual encrypted native
roundtrip and restart, then deliver reviewed release and port generic batch to Starter.
All54 PRD groups remain open; continue broader edit/delete/groups/calls/meet.

## Atomic command batches validated; Forward consumer next — 5 November 2026

Ten core/database files now provide transactional insertBatch with immutable
active/archive replay, full rollback and existing 128/account, 512/global limits.
No migration. Six cases per native/browser/Apple wrapper include real close/reopen
and session replacement. Focused SQLite/Chrome120 pass78.843s; full root check,
Android host/compile, collaboration browser data/presentation and proof build/tests
pass589.63s:2,707 fresh cases/zero failures/errors/skips,4,083 unchanged inputs.
No test task restored from Gradle cache. [Evidence](measurements/command-batch-2026-11-05.json).
All local wrappers/children drained. New Apple wrapper is not covered by the still
running older a26a CI42036083914; wait before starting another same-branch Apple run.

Phone remains installed release36/a26a. All four fictional receivers have one
ACTIVE device and one confirmed key publication. Retained Bima runs on owned
emulator5562/PID958042; verify its start record before mutation. Backend
11a98115/schema116 unchanged. No Forward feature, UI or native acceptance yet.

NEXT: restore only the feature metadata patch from protected stashbfdef2ce (keep
stash; preserve synchronizeDevice fake methods), implement encrypted batch intent
insertion and offline Forward preparation/selection/recipient UI. Retain operation
IDs across uncertain replies; authenticate existing logical intents and reuse their
ciphertext; preserve destination composer drafts. Revalidate source visibility and
current local ownership before confirmation, without requiring online credentials.
Native encrypted metadata/history proof remains required. Port generic core batch
to Starter after Mobile consumer validation. Continue edit/delete/groups/calls/meet;
all54 PRD groups open. Push each commit; announce EACH phone install, install-only.

## All four fictional receivers ready; forwarding resumes — 5 November 2026

Phone release36 is installed from Mobilea26a1248 (delivery docsa5bc4190),
SHA256`6a3729ab16af62090b08cc1e210f7643344a3e22ad65fc9bd2baf8c06a7313c6`.
Sample1 publishes its first key without a provider room/message and survives cold
restart; retained Bima passes all four history/restart checks with eight rows and
no Older/decryption/history error. Samples3/4 now complete normal product login,
registration/bootstrap and Open chat on the same release36. Read-only DB confirms
all four fictional accounts each have one ACTIVE device and one confirmed key
publication. No message was sent to force setup. This is recipient setup evidence,
not a new message-exchange test between every pair.

Sample1/3/4 images are cleanly stopped. Bima runs on owned emulator5562/PID958042
(verify the current start record), release36; retain each userdata.img AND sidecars,
private SDK keys and native parents. Do not reset or copy stores between accounts.
Apple CI42036083914 passed simulator tests and is building the app for sourcea26a;
full job acceptance is still pending. Backend11a98115/schema116 unchanged.

Next forwarding work: Mobile restored ONLY the ten core/database batch files from
protected stashbfdef2ce into its worktree after exact baseline comparison. Forward
metadata stays stashed, and the stash itself is retained. Earlier batch118 focused
passed before stashing; add disk close/reopen plus session-replacement coverage,
then validate this current raw API and the actual Forward consumer. Do not claim
new source tests or copy HRIS metadata to Starter. Generic Starter port follows
Mobile validation. Continue edit/delete/groups/calls/meet; all54 PRD groups open.
Push each commit; announce EACH phone install and use --install-only.

## Product release36 installed; first-device publication proved — 5 November 2026

Source [a26a1248](https://github.com/fajarxfce/hris-mobile/commit/a26a124837637831655d19cc9dc89c1929e65e02)
is installed in place on10.77.77.3:5555 as non-debuggable ARM64 devRelease36/1.0.0.
APK88,354,104 bytes, SHA256`6a3729ab16af62090b08cc1e210f7643344a3e22ad65fc9bd2baf8c06a7313c6`.
Original signer/app ID/first install retained; device hash matched. Announced each
installation, install-only, no phone UI driving. Install58.564s including verification.
[Delivery and native evidence](measurements/android-release-phone-2026-11-05-build36.json).

Build/lint541.941s/zero lint; full source gate3,664 fresh/zero failed cases521.721s,
4,077 unchanged inputs. Actual retained sample1 update34→36 publishes its first
server-verified key on Home without a provider room/message. Same enrolled device
and server-published identity survive cold restart;24.456s proof. Retained Bima
update35→36 passes four room/restart checks102.333s, eight rows/no Older or decryption
error. Closed startup diagnostics contain three unavailable worker retries and76
HTTP200, no crash; do not claim all startup operations succeeded on their first try.

Sample1 and Bima are now cleanly stopped, private data/keys/messages retained.
Sample3 runs on owned emulator5562/PID901378 (verify start record), release36 fresh
install complete; normal product login/device setup is running. Sample4 has only
its Firebase/HRIS link. NEXT: finish samples3/4 through normal UI; confirm one ACTIVE
device/one publication per fictional account; preserve all userdata AND sidecars.
Do not send a message to force activation or weaken recipient checks. Restore Bima
as the persistent receiver afterward. Apple-only CI42036083914 is still running on
a26a; previous42026950708 passed157a/release35. No new Apple acceptance yet.

Backend11a98115/schema116 unchanged. All build/gate/install/proof wrappers drained;
current local work is sample3 setup, not a pending phone update. Protected stash
bfdef2ce remains (batch118 focused, forward metadata incomplete); local forward
continuation notes .work/chat-forward-next-2026-11-05.md. Resume forwarding, then
edit/delete/groups/calls/meet and port validated generic storage to Starter. All54
PRD groups remain open. Keep pushing commits and announcing EACH phone install.

## Initial device sync implemented and source-validated — 5 November 2026

An enrolled device now starts owned SDK sync without a provider room or outgoing
message. Device sync and incoming acquisition are joined concurrent children;
existing 50-second company/15-second turn budgets and current admission stay intact.
Sync completion is not proof of key upload: the server recipient guard still
requires confirmed publication. [Contract](collaboration-device-sync.md),
[validation](measurements/chat-device-sync-2026-11-05.json).

Full :check, Android host/compile, browser data/presentation and proof build/tests
passed 521.721s: 3,664 fresh cases, no failures/errors/skips, 4,077 unchanged inputs.
Earlier combined focused81 passed. Subsequent worker32/one failure was a new test
expecting Complete although the fixture has a second room; final gate covers the
corrected healthy-continuation assertion. All gate wrappers/children are drained.
No candidate APK/native key-publication acceptance or phone36 delivery yet.

NEXT: build/lint clean ARM64 devRelease36 using staged .work/release-phone-2026-11-05-build36
scripts; verify artifact; install-owned-sample.py 1 then prove-device-sync.py.
These scripts require the actual retained sample1 image and never drive the phone.
Sample1 currently runs on owned emulator5562 (PID680459; verify current start record),
release34/Home, one ACTIVE device and zero confirmed publications, no provider-room
request/binding. Preserve its userdata.img AND sidecars. Bima is cleanly stopped,
keys/messages retained; release35 history and Apple acceptance remain valid.
Samples3/4 have Firebase/HRIS links but no product devices. Do not send a message to
force setup, reset private stores, weaken recipient guards or clone native keys.

After native proof, finish other fictional receivers, announce EACH phone install
and use --install-only for10.77.77.3:5555. User phone remains validated35. Run Apple
CI on this source. Backend11a98115/schema116 unchanged. Forwarding stashbfdef2ce
remains protected (batch118 focused passed, forward metadata incomplete); restore
and resume after this fix, then port generic database work to Starter. All54 PRD
groups remain open; continue forward/edit/delete/groups/calls/meet. Push each commit.

## Product release 35 installed — 5 November 2026

Product source [157a257d](https://github.com/fajarxfce/hris-mobile/commit/157a257d568c10f32e7159722d8fdd7f579b09e4)
is installed in place as ARM64 devRelease35 on10.77.77.3:5555. Package
`dev.fajar.hris.mobile.dev`, version1.0.0, non-debuggable. APK88,337,720 bytes,
SHA256`c227ba97083c3bccff4a42f113eb8ce8517411b3d97f2779ba646f266f575175`. Device-computed hash matched; existing signer/app ID/first
install time preserved. Installation announced, install-only, no phone UI driving.
[Delivery evidence](measurements/android-release-phone-2026-11-05-build35.json).

This fixes Older messages returning after an exhausted room is closed/reopened.
Exact release APK on the retained Bima account passes allfour checks: in-place
update, two room recreations and cold process restart; eight original messages
remain visible, no older control/decryption placeholder/history failure. Actual
proof108.748s. Redacted screenshot reviewed; message bodies are
not committed. Build/lint596.23s/zero lint issues; source gate
1,064 fresh cases/zero failures, focused6. No redundant fresh-install smoke run.

Bima persistent receiver remains running on owned emulator5562; retain its userdata
and sidecars/native parent. Sample1 has completed product registration/bootstrap,
but zero key publications: its empty direct has no provider-room request/binding.
Trace initial background runtime/key publication before any first message; do not
weaken recipient guard or send a message to force setup. Sample1 persistent data
retained, emulator stopped. Samples3/4 have Firebase/HRIS links but no SDK devices.

Apple-only CI42026950708 passed157a: iOS simulator tests and app build. Pending
device-activation changes are not included in that acceptance. Backend
11a98115/schema116 unchanged. Forwarding WIP remains in stashbfdef2ce (core batch
focused118 passed, metadata incomplete). Next: initial receiver activation gap,
finish demo readiness, resume forward/edit/delete/groups/calls/meet; return generic
storage to Starter after validation. All54 groups open. Push each commit and
announce EACH phone installation. Local record.py has been applied; do not rerun.

## Older-history end regression fixed and validated — 5 November 2026

Closing/reopening an exhausted room used to revive Older messages because retained
room-state references have no message originals to resolve. The screen pager now
walks the finite owned local snapshot, ignoring metadata for message availability
while retaining it. Actual pending messages, canonical local paging, concurrent
growth/replacement and cancellation remain covered. No migration/provider read/
decryption replay on restoration. [Contract](collaboration-chat-history-end.md),
[evidence](measurements/chat-history-end-2026-11-05.json).

Actual release34 Bima receiver reproduces the hidden-then-returning control after
room recreation. Six focused cases pass48.561s. Full :check/Android/browser/proof
pass506.364s,1,064 fresh cases/zero failures/errors/skips,4,071 unchanged inputs.
Baseline/first-fix failures are retained; one multi-page fixture precondition was
corrected before the final pass. All wrappers/children drained. No new-source
fixed APK acceptance yet. NEXT: clean release35 build/lint, actual retained Bima
reopen proof, exact startup smoke, announce EACH phone install, install-only.

User confirmed Bima old pending messages sent at12:02:36. Receiver has eight message
rows and no decryption placeholder; content was not logged. Bima persistent
userdata.img AND sidecars remain under .work/demo-chat-device-2026-11-05/bima;
its emulator was stopped cleanly, native parent/keys retained. Other three samples
now have Firebase/HRIS identity links. Sample1 owned emulator5562/PID324683 is
signed in and opening New chat; its SDK device setup is still pending. Samples3/4
still need product sign-in/device setup. Preserve all published receiver data.

Apple-only CI42017858911 completed successfully on745e (simulator tests/app build).
Phone34/live11a98115/schema116 unchanged. Forwarding WIP remains protected in
stash bfdef2ce9641173e0ff68528f8982c5bab15bf72, basef6a26a1c. Core batch focused118
passed; metadata incomplete. Resume after history delivery, finish forward and
root/native checks, return generic storage changes to Starter. All54 groups open;
continue long run and push every commit. Staged release35 scripts are not yet run.

## Product release 34 installed — 5 November 2026

Product source [745e5a02](https://github.com/fajarxfce/hris-mobile/commit/745e5a0256904453992e5e864bdd59e373de9513)
is installed in place as ARM64 devRelease34 on10.77.77.3:5555. Package
`dev.fajar.hris.mobile.dev`, version1.0.0, non-debuggable. APK88,337,720 bytes,
SHA256`eded7799bb9b643c1bcd5184581e2875444a4f74bda4bdf8b90336d876cdb699`. Existing signer/app ID/first-install time preserved; exact
device-computed APK SHA256 matched the validated local artifact. No pull-back was needed. Installation announced; install-only.
[Evidence](measurements/android-release-phone-2026-11-05-build34.json).

Build/lint pass505.825s, zero lint issues. ARM64/16KiB alignment,
Firebase/WireGuard config, compiled backup exclusions and new diagnostic markers
verified. Exact APK starts twice on owned emulator,12 stable seconds each, both
images reviewed; emulator stopped. No physical-phone UI driving or data reset.

This delivers original queue recovery after credential replacement: old retained
messages and newly queued messages use the original encrypted SDK store and
transactions. Exact paused-queue recovery handles previously affected legacy
records. Only bounded API/auth failures are retried; trust checks remain intact.
The empty-room manual Load messages control is removed; the composer stays usable.
Actual native original/new recipient decryption and process/offline recovery pass
separately. Physical Bima acceptance still requires ordinary phone use. Preserve
keys, drafts and original pending transactions while observing closed diagnostics.
Apple-only CI42017858911 on this product source is running; prior Apple pass is for
36e78206 only. Live API/worker11a98115/schema116 remain unchanged.
The install attempt before this one stopped at package-identity timeout before
upload. Reconnecting the host ADB transport recovered it. Successful update took
465.301s including on-device hash verification. No physical UI was driven. Optional
old/new Bima result question is pending; the first closed log read found no result.

WIP AFTER artifact build (not in release34): atomic CommandStore.insertBatch across
SQLite/IndexedDB plus five shared cases passes118 fresh/86.668s/4,076 unchanged
inputs. Forwarded-message metadata plumbing has just started and is UNVALIDATED.
Finish forwarding as one durable action, local recipient picker, indicator and
native/offline proof. Root gate and Starter port for generic batch remain required;
do not claim a delivered Forward feature from the storage-only check. Supplementary
work .work/command-batch-2026-11-05; no local heavy gate is running.

Continue full communications long run, edit/forward/delete for all, group adoption/UI,
voice/video/meet. All54 groups open. Push every commit and announce EACH next install.

## Original queue recovery verified; release34 next — 5 November 2026

The candidate preserves the original SDK queue operation across credential
replacement and recovers legacy Staged/Sent aliases only by exact retained
transaction within the same native family/device/room. Bounded retry releases
original API/auth failures; trust and identity failures remain excluded. Empty chat
keeps the composer without manual network controls. [Contract](collaboration-chat-queue-recovery.md),
[evidence](measurements/chat-queue-recovery-2026-11-05.json).

Actual native165.743s/two suites/zero skips/12,281 unchanged inputs/unchanged
APK02269c9531c519579d334e25af2ea1caa58fb2cfa81f3e6a1ac284fb3961bb6e: old rejected and
new messages both decrypt at the peer with original transaction/receipt bindings,
keys/device/gateway preserved. Legacy record recovery and stable new queue binding
both checked. Existing quotes/process death/cold offline history remain passing.
Driver31 pass; emulator stopped1.604s. Root :check/Android/browser pass1,241 fresh;
only packaging failed, then identical4,070 inputs packaged/proof-tested55.536s/18
fresh. Earlier failures retained; three current renders reviewed. No code change
was needed for the package retry. Focused54 fresh pass380.779s/domain43 earlier.

NEXT: commit/push, clean signed release34 build/lint/exact emulator validation,
announce EACH install before install-only to10.77.77.3:5555. Release34 scripts are
STAGED ONLY; review before running. Phone remains33, Bima physical acceptance still
pending. Live API/worker11a98115/schema116 unchanged; no backend runtime deployment.
Four .work proof drafts were APPLIED then refined: never reapply. Apple CI42009694285
passed prior36e78206/release33 only. All54 PRD groups open; continue long run.

## Product release 33 installed — 5 November 2026

Product source [36e78206](https://github.com/fajarxfce/hris-mobile/commit/36e78206d792ac68a38585b80e17234d0e691b22)
is installed in place as ARM64 devRelease33 on10.77.77.3:5555. Package
`dev.fajar.hris.mobile.dev`, version1.0.0, non-debuggable. APK88,337,740 bytes,
SHA256`e97aceff5053638741209951c511af2a129637d6a24d4f08fbdad8fbfc494338`. Existing signer/app ID/first-install time preserved; exact
installed bytes pulled back and matched. Installation announced; install-only.
[Evidence](measurements/android-release-phone-2026-11-05-build33.json).

Build/lint pass522.126s, zero lint issues. ARM64/16KiB alignment,
Firebase/WireGuard config, compiled backup exclusions and new diagnostic markers
verified. Exact APK starts twice on owned emulator,12 stable seconds each, both
images reviewed; emulator stopped. No physical-phone UI driving or data reset.

This delivers long-press Reply, composer/bubble quotes, offline quoted history and
Send without spinner, plus closed SDK/queue/delivery diagnostics. Actual quoted
SDK exchange and cold offline recovery pass separately. [Current-source product FCM](https://github.com/fajarxfce/hris-platform/blob/fe38da505292c30c9c1f16f2c0d7b5cc7daa2f7f/docs/measurements/product-chat-fcm-current-2026-11-05.json)
also passes241.839s/one actual case: Firebase restart/SystemUI encrypted reply, five
reviewed images, cleanup verified. Live API/worker now11a98115/schema116 after all14
CI jobs passed; Matrix/TLS/data preserved. Apple-only CI42009694285 is running.
Optional request to open Bima once is pending; post-install phone is connected but
no application process was running yet. Empty-room Load messages remains a UX issue. Pending OLD AND NEW Bima
sends are NOT yet accepted as fixed. Read new closed stage/failure markers from
ordinary phone use, preserve keys/transactions and fix the demonstrated cause.
Continue full communications long run, edit/forward/delete for all, group adoption/UI,
voice/video/meet. All54 groups open. Push every commit and announce EACH next install.

## Actual encrypted quoted replies verified — 5 November 2026

The actual recipient decrypts the third message with its exact first-event quote.
Kill-before-journal recovery retains the original transaction/reply intent; a later
cold process reads all three messages and the quote with the gateway unreachable.
Exactly three encrypted sends and all prior ownership/auth/history guards remain.
[Contract](collaboration-chat-quoted-native.md), [evidence](measurements/chat-quoted-native-2026-11-05.json).

Both native suites pass /159.069s/two cases/zero skips/12,277 unchanged inputs and
unchanged APK5c33de85. Driver31 pass. Mobile root/Android/browser/proof gate passes
2,155 fresh/417.551s/4,066 unchanged inputs. Owned emulator stopped. Applied native
stage drafts are NOT reusable. Mobile closed diagnostic markers are in6753b7a1.

NEXT: full CI42004588608 on Main11a98115 (production matches current test-only WIP),
validated API/worker-only envelope rollout retaining Matrix/TLS/data, clean release33
build/lint/emulator review and EACH install announced before install-only. Phone32
and live8b40/schema116 unchanged. Old AND new Bima sends still pending: read new closed
markers after delivery and fix the demonstrated cause; preserve keys/intents. No
question pending. All54 groups open; continue long run and push every commit.

## Pending-send diagnostics validated — 5 November 2026

New closed SDK acquisition/retained queue and app delivery-stage markers preserve
all admission, retry, cancellation and encryption behavior. No private payload or
identifier is logged. [Contract](collaboration-chat-send-diagnostics.md),
[evidence](measurements/chat-send-diagnostics-2026-11-05.json).

Focused30 and final root/Android/browser/proof/format/architecture pass: 2,155 fresh,
zero failures/errors/skips, 417.551s, 4,066 unchanged inputs. Earlier proof long-method
failure retained and fixed. Actual native quoted-reply proof is running separately;
do not claim its result from this gate. Phone32/live8b40/schema116 unchanged.

ACTIVE: old AND new Bima sends remain pending/failed. Complete native proof, validated
Main envelope rollout and release33/build/lint/emulator; announce before install-only
to10.77.77.3:5555. Read the new closed markers from ordinary phone use to fix the real
cause while preserving keys/transactions. No question pending. All54 PRD groups open;
continue the long run and push every commit. Source-bound scripts in .work are
supplementary; no Starter port for these HRIS communications diagnostics.

## Quoted reply UI and current send investigation — 5 November 2026

Quoted history, checkpoint-driven local previews, long-press Reply, composer strip
and bubble quotes are implemented. Closing the quote retains draft text. Send has
no spinner; a held connection does not block accepting a message or the next draft.
[Contract](collaboration-chat-quoted-replies.md),
[evidence](measurements/chat-quoted-replies-2026-11-05.json).

Final root/Android/browser data + presentation/format/architecture: 3,872 fresh
passed, zero failures/errors/skips, 537.919s, 4,062 unchanged source inputs.
Focused42 passed. The original ui3 wrapper exited143 while its child stayed alive;
that same child completed successfully and was drained, then the normal final
root gate passed. Earlier compile/test failures and reviewed image hashes remain
recorded. History-stage drafts have now been APPLIED; do not reapply them.

ACTIVE PRIORITY: user confirms OLD AND NEW messages to Bima remain pending/failed
on release32. WireGuard/ADB are reachable. Read-only server metadata confirms the
original device/key publication and parent active; gateway52 was bootstrapped08:58
and normal five-minute refreshes reach55 at09:13. A phone buffer contained typed
ChatAuthSoftLogout, but later successful refreshes mean it is not sufficient to
attribute all current failures. Passive response classification confirms history,
sync and joins200; optional m.secret_storage.default_key reads403. This is NOT yet
a demonstrated root cause. No private payloads, tokens or paths were persisted.
Investigate retained SDK queue errors/wedged messages and the application delivery
worker; SDK room enablement alone does not release an unrecoverably wedged request.
Do not reset keys, drafts, commands, account or device. Diagnostic notes are in
.work/chat-send-diagnosis-2026-11-05. The user answer is received; no question pending.

NEXT: resolve delivery, complete actual SDK quoted reply/decryption and cold process
proof, deploy validated Main envelope, then build/lint/owned emulator checks and
announce EACH install before install-only to10.77.77.3:5555. Phone32/live8b40/schema116
are unchanged. Continue delivered edit/forward/delete-for-all, bootstrap resolution,
group adoption/UI, calls and meetings. All54 PRD groups remain open. Push every commit
and continue the authorized long run. No Starter port for this HRIS feature.

## Quoted draft/outbox pipeline — 5 November 2026

Reply references are persisted with encrypted drafts/intents and carried through
background recovery, exact retry/confirmation and the existing paused native queue.
The relation participates in the original content hash. Outgoing bounds now match
Main gatewayd4f34140 without changing historical ID validation. Quoted body snapshots
are not added. [Contract](collaboration-chat-quoted-draft.md),
[evidence](measurements/chat-quoted-draft-2026-11-05.json).

Final gate501.591s/3,401 fresh/zero failures/errors/skips/4,052 unchanged inputs:
root, Android product compile, browser data, format and architecture. Earlier nullable
compile failure and successful pre-alignment gate remain recorded. Worker proof here
uses its simulated SDK; actual quoted-message encryption/decryption remains pending.

NEXT: integrate the source-bound drafts in `.work/chat-quoted-reply-2026-11-05/history-stage`
after reviewing their manifest. They are NOT yet applied or validated: history reply
metadata, checkpoint-driven indexed quote observation (including offscreen deletion),
long-press Reply/composer/bubble UI and initial tests. Complete admission/storage/UI
interaction and screenshot coverage, then actual native quoted reply plus offline
process recovery before a product release. Current phone32/header correction and
live8b40/schema116 remain unchanged. Main envelope is committed but not deployed.
Bima Generic transport error is still unattributed; optional user send-result question
remains pending. All54groups open. Push each commit and continue long run; no Starter
port for this HRIS feature.

## Cold offline history verified — 5 November 2026

Actual encrypted canonical history remains readable after the third owned process
is killed, its gateway mapping removed, and a fourth process opens the original
store. Refused TCP connects before/after the read prove the server is unavailable.
Both earlier messages/event aliases match privately. No fourth encrypted send occurs.
This is native/repository evidence; full product UI and physical-phone internet-off
behavior are not newly claimed. [Contract](collaboration-chat-offline-restart.md),
[evidence](measurements/chat-offline-restart-2026-11-05.json).

Both actual SDK suites pass /155.541s/2 cases/12,261 unchanged inputs,
including original-key renewal/auth recovery, encrypted exchange, history, receipts
and process death. Mobile root/proof build passes /168.453s/18 fresh/4,051
unchanged inputs. Driver30 pass; initial mock-mapping failure retained. Owned emulator
is stopped. Only proof/test/driver source changes; phone32 remains installed from
Mobile64c9432f (deliverye347b0cb). Last sampled release32 process yielded no closed SDK
diagnostics; do not infer that the earlier Generic Bima transport error is solved.

NEXT: continue delivered edit/reply/forward/delete-for-all, original-bootstrap
resolution/adoption and group UI, calls and meetings. User's optional send-result
question is pending; preserve private keys and inspect closed diagnostics after
ordinary use. All54groups open, live8b40/schema116 unchanged. Push each commit and
continue long run. Port Main native fixture changes to Group only with its own gate.

## Product release 32 installed — 5 November 2026

Latest validated product source [64c9432f](https://github.com/fajarxfce/hris-mobile/commit/64c9432f6538070f6adf750dd3920481efa7c032) is built as ARM64 devRelease 32
and installed in place on `10.77.77.3:5555`. Package `dev.fajar.hris.mobile.dev`,
version 1.0.0/code 32, non-debuggable. APK 88,304,880 bytes;
SHA-256 `104793ff434b71f7bd8b5c4c7ff313a8415d4be916474e72d8ca55d74139dcf5`. Existing development signer, app ID and first-install
time are preserved; pulled-back installed bytes match the verified artifact.
Installation was explicitly requested and announced; no phone UI launch, uninstall
or data clearing. [Evidence](measurements/android-release-phone-2026-11-05-build32.json).

Release assembly and lint pass in 500.117 s, with zero lint issues.
Signature, package/version, ARM64-only libraries, 16 KiB alignment, Firebase dev app,
WireGuard API and compiled backup exclusions are checked. Exact APK opens twice on
the owned emulator through its native bridge, with 12 stable seconds per start and
reviewed screenshots. The emulator is stopped. These are startup/install checks;
no new notification/SystemUI reply/FCM or full authenticated workflow proof is claimed.
Source header correction passed focused 18 fresh cases
and root/Android 812 fresh cases on identical
4,050 inputs. Earlier unchanged cases remain bound by report hashes.
No source changes occurred during release build.

The release removes connection status below the conversation name and the permanent
checkmark selection button. Long-press selects messages and reveals contextual
copy/delete actions. Original SDK authentication recovery retains local history and
replaces confirmed credentials in the original protected store without resetting keys.
[Header contract](collaboration-chat-header.md), [recovery contract](collaboration-chat-reconnect.md).

NEXT: observe closed operation diagnostics after ordinary phone use; earlier generic
Bima transport failures remain unattributed. User confirmed immediate history on
release31 reopening, but a physical internet-off test is not claimed. Preserve
original private SDK keys/account data. Continue delivered edit/reply/forward/delete
for everyone, original-bootstrap resolution/adoption, group runtime/UI, calls and
meetings. All54 groups open; live8b40/schema116 unchanged. Continue long run,
atomic pushes and validated delivery.

## Chat header correction — 5 November 2026

The thread header now shows the conversation name and Back only. Removed every
connection subtitle and the permanent checkmark selection button. Long-press selects
the first message; normal taps do not start selection. Contextual count/copy/delete
and accessible bubble selection remain. The empty-selection event/handler is removed.
[Contract](collaboration-chat-header.md), [evidence](measurements/chat-header-2026-11-05.json).

Focused18 / 89.943s and root/Android812 / 286.239s pass on 4,050 unchanged inputs. Reviewed screenshots include compact, landscape and 200% text.
User confirms immediate release31 Bima history on reopening. Physical internet-off
behavior is not yet tested on that phone; closed release31 Generic timeline/sync
errors still lack attribution. Native original-store recovery proof remains bound
to unchanged data source in8fdbe89b/Main0c62f0d7.

NEXT: clean-source release32 build/lint/owned-emulator startup, announce phone install
and use --install-only. Then inspect closed operation diagnostics, continue delivered
edit/reply/forward/delete-for-all, original-bootstrap resolution/adoption, group UI,
calls and meetings. All54 groups open; live8b40/schema116 unchanged. Push each commit
and continue the authorized long run. This HRIS feature does not need a Starter port.

## Original chat connection recovery — 5 November 2026

Rejected SDK authentication is now typed and terminal for live transport. Owned local history stays readable, and the thread offers one compact reconnect action. Confirmed newer bootstrap credentials replace only the original provider identity/gateway after exact native/store drain; no key or account reset. Late callbacks, borrowers, owner changes, cancellation and failing cleanup remain owned. Initial setup checks progress quietly at most six times while visible; routine refresh controls and technical recovery copy are removed.

Combined root/Android/browser/proof gate 2479 fresh passed / 531.299s / 4,050 unchanged inputs; final data334/146.405s and UI59/293.259s also pass. Both actual native suites pass / 145.221s, including same-store recovery after actual rejected refresh and unchanged normal renewal/encrypted/history/process-death assertions. Reviewed 320px200% and compact-thread screenshots. [Contract](collaboration-chat-reconnect.md), [measurements](measurements/chat-reconnect-2026-11-05.json).

NEXT: release32 build/startup/install-only with notice; phone31 remains until separately recorded. User confirms release31 Bima messages appear immediately on reopening. Earlier generic connection cause remains unproven; inspect operation-only diagnostics while preserving private keys. Continue delivered edit/reply/forward/delete-for-all, original-bootstrap resolution/adoption, group runtime/UI, calls and meetings. All54 groups remain open; live8b40/schema116 unchanged. Continue long run, push each atomic commit, maintain Git handoffs. HRIS-specific; no Starter port.

## Original Android SDK access renewal verified — 5 November 2026

Main Platform [de987b13](https://github.com/fajarxfce/hris-platform/commit/de987b13eec8d9d0a7f7a082ba432e2222a89509) is pushed.

The real pinned SDK renews both original gateway sessions after injected server-side
access expiry, retaining original keys, store, queued transaction and canonical history.
Subsequent process-death/receipt recovery and exactly three encrypted sends still pass.
Both refreshes cross the real HRIS API/PG/Synapse fixture; the server verifies ciphertext
with bounded refresh replay, without another rotation. Fixed server time keeps the
30-second verifier replay window open; this is not late-replay or clock-skew acceptance.
[Contract](https://github.com/fajarxfce/hris-platform/blob/de987b13eec8d9d0a7f7a082ba432e2222a89509/docs/collaboration/native-sdk-access-renewal.md).

Native case51.293s/fullgate123.990s, one passed and none skipped; format/architecture
pass on12,248 unchanged backend/Mobile inputs and exact proofAPK3cedee24. Mobile proof
build18host cases/168.282s/4,040 unchanged inputs. Only two backend test files change;
runtime source equals live8b40. Original-driver cleanup and emulator shutdown succeed.
[Evidence](https://github.com/fajarxfce/hris-platform/blob/de987b13eec8d9d0a7f7a082ba432e2222a89509/docs/measurements/collaboration-sdk-access-renewal-2026-11-05.json).

NEXT: read release31 operation diagnostics and user reopen result for the real Bima
error. Normal SDK renewal now passes; do not label that actual error fixed or reset
private keys/account data. Inspect typed rejected-auth signal and original gateway
recovery if indicated, then real delivered edit/reply/forward/delete-for-all, organization
adoption, group runtime/UI, calls and meetings. All54groups stay open, phone31 and
live8b40/schema116 unchanged. Continue long run, atomic pushes and validated delivery.

## Product release 31 installed — 5 November 2026

Latest validated product source [9d5ca633](https://github.com/fajarxfce/hris-mobile/commit/9d5ca633709f23588fd3fc9b29a88002bd4d3b4d) is built as ARM64 devRelease 31
and installed in place on `10.77.77.3:5555`. Package `dev.fajar.hris.mobile.dev`,
version 1.0.0/code 31, non-debuggable. APK 88,304,868 bytes;
SHA-256 `fec690c1031313b3353b73980e390477125c773d51fc103e3900e6c4b5f4f691`. Existing development signer, app ID and first-install
time are preserved; pulled-back installed bytes match the verified artifact.
Installation was explicitly requested and announced; no phone UI launch, uninstall
or data clearing. [Evidence](measurements/android-release-phone-2026-11-05-build31.json).

Release assembly and lint pass in 494.758 s, with zero lint issues.
Signature, package/version, ARM64-only libraries, 16 KiB alignment, Firebase dev app,
WireGuard API and compiled backup exclusions are checked. Exact APK opens twice on
the owned emulator through its native bridge, with 12 stable seconds per start and
reviewed screenshots. The emulator is stopped. These are startup/install checks;
no new notification/SystemUI reply/FCM or full authenticated workflow proof is claimed.
Source local-entry flow passed focused 193 fresh cases
and root/Android 805 fresh cases on identical
4,040 inputs. Earlier unchanged cases remain bound by report hashes.
No source changes occurred during release build.

The release includes local history before network setup, automatic foreground
reconnect, contextual unsent notification replies and shorter EN/ID copy. Routine
refresh and global Saved replies controls are removed. Long-press selection,
multiselect/copy and durable delete-for-me are also included since release30.
[Local-entry contract](collaboration-chat-local-entry.md) and
[selection contract](collaboration-message-selection.md).

NEXT: inspect operation-only Android diagnostics to resolve the actual Bima failure;
this delivery does not prove that provider/session problem fixed. Preserve original
private SDK keys and account data. Prove canonical-history recovery and SDK token
refresh on the isolated native fixture, then original-gateway recovery if evidence
requires it. Continue delivered edit/quoted reply/forward/delete-for-all, organization
bootstrap resolution, private-store inventory, hard-parent recovery and adoption,
group runtime/UI, typed push, calls and meetings. All54PRD groups remain open; live
backend8b40/schema116 unchanged. Continue long run, atomic pushes and validated delivery.

## Local chat entry, reconnect and unsent messages — 5 November 2026

Chat now projects owned local history/outgoing messages before network preparation.
Reopening does not display a network loading ring. Foreground transient failures retry
with a timeout per attempt, increasing delay and lifecycle/account cancellation. Inbox and
thread routine refresh controls are removed; transient connection status stays in the
header, while local/known denial/recovery errors remain actionable. Owned SDK rows
already shown remain through transient failures; this does not newly persist a failed
canonical history import. [Contract](collaboration-chat-local-entry.md).

Held notification replies appear in the original conversation with preserved text and
a direct review action. Inbox rows identify unsent messages; only orphaned inputs use
a fallback entry. The global Saved replies utility no longer appears for ordinary
queues. Recovery still uses the original durable intent and draft-aware departure.
Short EN/ID copy and 375px light/dark200% plus 320px detail screenshots are reviewed.
Android diagnostics identify a closed SDK operation/type without content or secrets.

Final focused 193 fresh cases / 103.676 s (unchanged tests may reuse earlier passing outputs; report hashes distinguish fresh and reused results); root :check, Android-host and dev compilation 805 fresh cases / 265.682 s. Format, architecture and detekt pass. Both final gates preserve identical 4,040 inputs. [Evidence](measurements/chat-local-entry-2026-11-05.json).

NEXT: build and validate release31, announce then install-only on authorized phone,
and read the new operation diagnostics to resolve the actual Bima failure. Never reset
private SDK/account data. Upstream pinnedSDK26.10.23 Rust8de215a0 confirms automatic
refresh is enabled, /join always performs HTTP and refresh errors may become Generic;
ClientDelegate.didReceiveAuthError provides a typed rejected-token signal, currently
not installed by the product. Inspect callback ownership plus exact original gateway
recovery, and prove offline reopening after actual canonical acquisition. Do not claim
the reported session problem solved merely because generic error UI is removed.

Then continue delivered edit/reply/forward/delete-for-all, organization bootstrap
resolution/private-store inventory/hard-parent recovery/freeze/adoption and group
runtime/UI, typed push, calls and meetings. All54PRD groups remain open. No new APK,
phone interaction, live deployment, actual JNI/provider/FCM/SystemUI, Apple runtime or
performance acceptance in this commit. Phone30/source3e1cee4c and live8b40/schema116
unchanged. Continue long run and atomic pushes; no routine confirmation is needed.

## Chat selection and local deletion — 5 November 2026

Long-press selects a message; tapping adds more, with a contextual count/copy/delete
bar and Back-to-clear. Up to 50 selections survive local paging. Selection dismisses
the keyboard while preserving the durable composer draft. Single queued sends keep
their explicit cancellation; delete for me is a separate local choice. Clipboard
publication rechecks the original local owner and route generation.
[Contract](collaboration-message-selection.md).

Confirmed deletion atomically persists encrypted sender/event/logical visibility
rules alongside retained originals. Local queries omit hidden messages; later SDK
replay, history imports and aliases respect the choice. Original send evidence remains
available for reconciliation. Lost storage responses retry the same captured batch;
ordinary credential age does not hide owned facts, and account/access changes cancel
obsolete work. Ten SQLite plus ten IndexedDB, ten domain, eight ViewModel and four
Compose cases exercise these behaviors, plus the actual owning DI graph.

Focused 112 cases / 99.232 s; root :check, Android-host and dev compilation 3,058 fresh cases / 384.05 s; browser data/domain 1,654 fresh cases / 168.16 s. All pass on identical 4,033 build/application inputs; format, architecture and detekt pass. Four generated screenshots were opened and reviewed, including 200% text and a 320px confirmation. [Evidence](measurements/chat-message-selection-2026-11-05.json).

NEXT — newest user complaint takes priority before the next product APK: repeated
loading on reopening chat, generic failure in the Bima Santoso conversation, manual
refresh controls and technical Saved replies UX. Local canonical history observers
are network-independent, but acquisition/runtime failures still surface generic
retry UI and the foreground connection currently has no autonomous reconnect loop.
Notification Saved replies retains inline-reply recovery, not canned snippets;
move unresolved replies into their conversation context without losing original
text or uncertain-send evidence. Diagnose the actual error, not just hide it.

Read-only evidence on 5 November: installed app emitted Generic network/sync failure;
its WireGuard request to Matrix versions returned HTTP 200. Server services are
healthy. Linked Karyawan Demo gateway/device/native parent remain unrevoked and
active, while the short-lived gateway access is expired. That alone does not prove
a refresh defect. No credentials, SDK/private keys or account data were reset.

Then continue real delivered edit/quoted reply/forward/delete for everyone and the
existing organization bootstrap resolution, private-store inventory, hard-parent
recovery, freeze/adoption, group runtime/UI, typed push and calls/meetings work.
All 54 PRD groups remain open. Phone remains release30/source3e1cee4c and live backend
8b40/schema116; this slice is not installed yet. No JNI/provider/FCM/Apple runtime or
performance acceptance is claimed. Continue long run, atomic pushes and validated
install-only delivery with advance notice.

## Product release 30 installed — 5 November 2026

Latest validated product source [3e1cee4c](https://github.com/fajarxfce/hris-mobile/commit/3e1cee4c1c78d99d19c11dcd515cc7cfb69bf044) is built as ARM64 devRelease 30
and installed in place on `10.77.77.3:5555`. Package `dev.fajar.hris.mobile.dev`,
version 1.0.0/code 30, non-debuggable. APK 88,255,208 bytes;
SHA-256 `1fe1d23793d1e6c3ac2d28f5280ea5811a5e1ac23960109242574b8da00f759b`. Existing development signer, app ID and first-install
time are preserved; pulled-back installed bytes match the verified artifact.
Installation was explicitly requested and announced; no phone UI launch, uninstall
or data clearing. [Evidence](measurements/android-release-phone-2026-11-05-build30.json).

Release assembly and lint pass in 534.843 s, with zero lint issues.
Signature, package/version, ARM64-only libraries, 16 KiB alignment, Firebase dev app,
WireGuard API and compiled backup exclusions are checked. Exact APK opens twice on
the owned emulator through its native bridge, with 12 stable seconds per start and
reviewed screenshots. The emulator is stopped. These are startup/install checks;
no new notification/SystemUI reply/FCM or full authenticated workflow proof is claimed.
Source owning setup flow already passed focused 76, root/Android 2,726 and browser
1,634 cases on identical 4,018 inputs. No source changes occurred during release build.

The release includes the accepted local observation and native organization setup
foundations since release 29; organization runtime/UI activation remains unfinished.
NEXT (latest user priority): WhatsApp/Telegram-style chat-room selection/actions,
including long-press, contextual toolbar, multi-select/delete and real edit/reply/
forward flows with offline retention and clear per-item outcomes. These UI changes
are not included in release30. Audit: `.work/chat-message-actions-2026-11-05/CURRENT.md`.
Then continue bootstrap operation resolution, private-store inventory, hard-parent
recovery, freeze/drain/handover and original SDK adoption, group UI/runtime and typed
push/background replies. Read-only protocol audit remains in group
`.work/space-bootstrap-resolution-2026-11-05/AUDIT.md`; no backend implementation or
live migration has started. All 54 PRD groups remain open; live
backend 8b40/schema 116 is unchanged. Continue long run and atomic pushes.

## Native organization setup owning flow — 5 November 2026

The actual feature DI graph now owns finite explicit provisioning retry/bootstrap
preparation and replay, metadata-based retry reconciliation and credential-free local
queue observation. Original Registered LEGACY provenance/current selected identity
and native device anchor setup; no selected HR company or SDK reopening is required.
A missing SDK never authorizes SPACE fallback. [Contract](collaboration-space-native-setup-flow.md).

Pending validates exact current resources and durably stores Unconfirmed before HTTP.
Uncertain retry retains the original operation/body/native parent without re-preparing
or reopening a frozen SDK. Bootstrap requires its original actual build; retry permits
same-family build upgrades. Original hard/wall/monotonic and mutex budgets are finite;
confirmed/concurrent denials survive failed storage and cancellation. Received gateway
credentials are encrypted before publication, including an access-expiry recheck after
storage. Completed credentials/receipts remain owned local facts across age/login/
parent replacement, never a live SDK/directory grant. The bounded local summary omits
tokens and drops queued old-account emissions on replacement/disposal.

Only a confirmed retry whose exact native device metadata has reached its recorded
provisioning revision can be archived. Lost archive responses recover the immutable
original receipt; another operation can then use the active slot. Pending/Unconfirmed/
Bootstrapped cannot be retired by this use case. No automatic retry is introduced.

Twenty-eight new common domain cases cover preparation (8), replay (13), retry
reconciliation (4) and local observation (3), including cancellation/late results,
original command recovery, concurrent denial, unchanged native/SDK provenance and
old-account emission disposal. Existing registration flows and setup transport/storage
are included as regressions. Actual AppContainer resolves the owning use cases and
single replay instance. Focused: 76 cases / 131.109 s.
Root :check, Android-host and dev compilation: 2,726 fresh cases /
340.396 s. Browser data/domain: 1,634 fresh cases /
141.395 s. Final gates preserve identical 4,018 build/application
inputs; format, architecture and detekt pass. All gate sessions closed.
[Evidence](measurements/collaboration-space-native-setup-flow-2026-11-05.json).

NEXT: explicit bootstrap operation recovery beyond 30 s, actual-build changes or an
advanced gateway head. Null gateway metadata cannot exclude a late original request;
preserve original/replacement intent through crashes, never forge headers or discard
uncertainty. Then retained private-store inventory, explicit hard-parent recovery,
old-runtime freeze/drain/handover and original-store adoption, group runtime/UI and
typed push/background replies. No product route was enabled by this DI-bound slice.

All 54 PRD groups remain open. No new APK/phone, live deployment, provider/FCM,
JNI/private-key proof, Apple runtime or performance acceptance. Live backend
8b40/schema 116 and phone release 29/source 084662c5 unchanged. Continue long run,
atomic pushes and validated install-only product delivery with advance notice.

## Durable native organization setup storage — 5 November 2026

Native retry/bootstrap intent now retains the original registration reference,
operation/native parent, platform/build and exact request in encrypted account/space
storage. Local login/company replacement and ordinary age do not hide owned work.
Unconfirmed precedes HTTP; terminal Retried or Bootstrapped preserves the exact device,
provisioning/gateway version and original provider identity. Received group gateway
tokens are encrypted and redacted. [Contract](collaboration-space-native-setup-storage.md).

One active resource per original native parent has bounded account observation and
exact CAS. Shared-key loss cannot regenerate over active/inactive/archive records.
After the owning caller reconciles a confirmed retry against current metadata,
archiveRetried preserves its exact request/outcome as immutable evidence and releases
the active slot. Lost archive responses and same-operation insertion recover the
original completion without publishing pending work. Exact-ID archive/evidence checks
reject substitutions. Bootstrap retirement/adoption remains a separate owning flow.

Fifteen common storage cases pass on actual SQLite and IndexedDB, covering encrypted
gateway restart/new login, original intent, lost commit responses, contention/CAS,
terminal outcomes, native/account isolation, wrong provider/token/bounds, inactive
archived key loss, immutable archives, retained denial and bounded observation.
Actual AppContainer resolves the owning repository. Focused: 41 cases /
119.196 s. Root :check, Android-host and dev compilation: 2,698
fresh cases / 339.227 s. Browser data/domain: 1,606 fresh cases /
138.797 s. Final gates preserve identical 4,006 build/application
inputs; format, architecture and detekt pass. All gate sessions closed.
[Evidence](measurements/collaboration-space-native-setup-storage-2026-11-05.json).

NEXT: finite explicit preparation/replay and retry receipt reconciliation, using the
original Registered anchor/current identity/status, durable uncertainty before HTTP
and gateway storage before publication. Enforce current admission and original hard,
wall and monotonic deadlines. Preserve confirmed denial through failed storage and
cancellation. Parent/build replacement cannot rewrite uncertain bootstrap. Null gateway
metadata cannot disprove a late old operation; explicit server operation recovery or
supersession must preserve old and replacement intent beyond the 30 s replay window. Then
retained private-store inventory, explicit hard-parent recovery, old-runtime freeze/
drain/handover and original-store adoption, group runtime/UI and typed push/replies.

No owning lifecycle flow or product route is activated by storage alone. All 54 PRD
groups remain open. No new APK/phone, live deployment, provider/FCM, JNI/private-key
proof, Apple runtime or performance acceptance. Backend 8b40/schema 116 and phone
release 29/source 084662c5 unchanged. Continue long run and atomic pushes.

## Native organization retry/bootstrap transport — 5 November 2026

Native setup now has typed retry/bootstrap requests, a redacted group gateway result,
raw HTTP/source adapters and a mapping repository in the actual owning DI graph.
Both endpoints use exact native session/operation and no company/cookie transport.
Retry validates the exact device and provisioning revision + 1 receipt. Bootstrap
requires original account/space/device/native parent, expected provider USER/DEVICE,
gateway version, group-only token namespace and supported dates. Current default
headers remain intact: retry permits build changes while bootstrap requires its
original actual build/platform. [Contract](collaboration-space-native-setup.md).

User and device origins stay independent; typed SPACE transport is not enrollment
eligibility. No endpoint is derived from provider metadata. Expired gateway metadata
remains typed; current usability/native hard expiry and durable credential storage
belong to the owning use case. Pending and late cancelled responses cannot publish.

Twelve new common transport cases cover original native/body/operation identity,
actual Android/iOS headers, bootstrap build binding, independently selected user and
device origins, bounded/malformed responses, group token namespace, classified denial,
pending cancellation and noncooperative late response disposal. Actual AppContainer
resolves the new native setup repository. Focused: 26 cases /
96.609 s. Root :check, Android-host and dev compilation: 2,683
fresh cases / 343.862 s. Browser data/domain: 1,591 fresh cases /
136.713 s. Final gates preserve identical 3,998 build/application
inputs; format, architecture and detekt pass. All gate sessions closed.
[Evidence](measurements/collaboration-space-native-setup-2026-11-05.json).

NEXT: durable encrypted account/space retry/bootstrap commands and finite explicit
preparation/replay using the original registered anchor, durable uncertainty before
HTTP and received credentials before publication. Preserve confirmed denials through
failed persistence/cancellation. Old-build uncertain bootstrap needs explicit current
metadata reconciliation, never forged headers or an edited operation. Then retained
private-store inventory, hard-parent recovery, freeze/drain/handover/adoption of the
original SDK store, current group runtime/UI and typed push/background replies.

No durable lifecycle command or product route is activated in this transport slice.
All 54 groups remain open. No new APK/phone, live deployment, provider/FCM, JNI/private
key proof, Apple runtime or performance acceptance. Backend 8b40/schema 116 and phone
release 29/source 084662c5 unchanged. Continue long run and atomic pushes.

## Native original-device registration flow — 5 November 2026

The owning feature now binds account-owned registration observation, finite explicit
LEGACY preparation and serialized replay. Preparation reads selected identity and
native-device metadata and borrows the already open original SDK; it retains exact
provider/company/device/fingerprint/physical parent before any registration HTTP.
No selected HR workspace is required. Missing metadata/SDK never permits SPACE
fallback or a rewritten physical namespace. [Contract](collaboration-space-registration-flow.md).

Pending replay checks actual original SDK identity, stores Unconfirmed before HTTP,
and reuses the immutable native operation/body after uncertain completion without
reopening a possibly frozen SDK. Actual current platform/build headers are sent;
register permits a build upgrade in the same native family. A fresh native parent
cannot replay the old device operation. The fixed 15 s budget includes mutex/local
work; original hard expiry, wall rollback, monotonic expiry and ownership are checked
after suspension. Confirmed and concurrent directory denials survive failed retries
and cancellation. A retained Registered receipt is an account-owned fact, never SDK
or directory authority. Old-account observer emissions are cancelled on replacement.

Twenty-eight new cases cover preparation (8), replay (10), local observation (3)
and native transport (7), including cancellation, late results, loss after commit,
account/parent replacement, concurrent denial, original SDK provenance and exact
request replay. Actual AppContainer resolves the observer/preparer and replay
singleton. Focused: 62 cases / 132.36 s. Root :check,
Android-host and dev compilation: 2,659 fresh cases / 331.944 s.
Browser data/domain: 1,579 cases / 136.27 s. All final gates
preserve identical 3,989 build/application inputs; format, architecture
and detekt pass. All gate sessions closed.
[Evidence](measurements/collaboration-space-registration-flow-2026-11-05.json).

NEXT: native provisioning retry/bootstrap using original registration anchors and
current metadata, then retained private-store inventory, explicit hard-parent recovery,
original-store handover/drain/adoption, group runtime/UI and typed push/background
replies. SPACE live replay remains unavailable until inventory/recovery establishes
eligibility. No new caller/product route has been activated by this DI-bound slice.

All 54 PRD groups remain open. No new APK/phone, live deployment, provider/FCM,
JNI/private-key proof, Apple runtime or performance acceptance. Live backend
8b40/schema 116 and phone release 29/source 084662c5 unchanged. Continue long run,
atomic pushes and validated install-only product delivery with advance notice.

## Durable organization device registration — 5 November 2026

Encrypted registration storage now retains original operation/native binding,
platform/build, selected provider user, label/device origin and exact original SDK
company/user/device/fingerprint/physical native family. User origin and device origin
are independent: a new SPACE device can use an existing LEGACY user, but storage
never authorizes that enrollment. The original physical family remains separate
from the request parent; no cross-parent recovery authority is inferred.
[Contract](collaboration-space-registration-storage.md).

One registration anchor per original request parent supports bounded all-parent
account observation after reauthentication and exact-parent reads. Empty reads
allocate no key. Separate AES-GCM request/outcome purposes bind account/space/native
resource/op/revision; protected key loss cannot regenerate over retained records.
Unconfirmed is durable, CAS checks the exact original, and Registered is terminal.
Confirmed access failures cannot be downgraded by uncertain retries. No HTTP, SDK,
scheduling, server schema or directory admission change is introduced.

Fourteen common storage cases pass on actual SQLite and IndexedDB. They cover
restart/new login, immutable original operation/provenance, response loss after
commit, contention/CAS, terminal success, corrupt routing/ciphertext/bounds, shared
key loss, independent user/device origins, retained denial and all-parent inventory.
Actual AppContainer resolves the repository. Focused: 14 cases /
118.582 s. Root :check, Android-host and dev compilation: 2,624
fresh cases / 350.224 s. Browser data/domain: 1,551 cases /
135.925 s. All final gates preserve identical 3,980 build/application
inputs; format, architecture and detekt pass. All gate sessions closed.
[Evidence](measurements/collaboration-space-registration-storage-2026-11-05.json).

NEXT: account-owned local observer plus finite explicit registration preparation
and replay/HTTP. Reacquire current selected user/native status and actual original
SDK under one bounded attempt, independently of retained command age. Use the
original operation/native parent and actual current platform/build headers; do not
copy identity-selection's fresh-parent retry rule. Register/retry do not bind old
build; bootstrap does. No SPACE fallback on an absent open SDK or current-parent
device: retained-store inventory or explicit recovery must establish eligibility.
Hard-parent recovery, original physical-store adoption, current group runtime/UI
and typed push/background replies remain outstanding. Completed anchors stay
retained; later archival must preserve the original registration for recovery.

All 54 PRD groups remain open. No new APK/phone, live deployment, provider/FCM,
Apple runtime or performance acceptance. Live backend 8b40/schema 116 and phone
release 29/source 084662c5 unchanged. Continue long run, atomic pushes and validated
install-only product delivery with advance notice.

## Selected organization identity on native clients — 5 November 2026

Bodyless native GET now reads the already selected account/space provider user.
The typed 4 KiB projection validates canonical legacy/new identity, original company
provenance and immutable version, while all HTTP stays on the configured HRIS
endpoint. Raw port 0 is rejected before URL-library normalization. Null remains an
absent selection and never runs POST selection or implies an empty original store.
The actual owning feature DI graph resolves this read path.
[Contract](collaboration-space-identity-observation.md).

Identity and device status now share an internal finite read policy function, not
use-case calls. The original native hard deadline and 15 s wall/monotonic budget
cover local admission and HTTP. Current ownership is checked before/after acquisition
and final admission; cancelled/late/replaced-account results cannot publish.
Confirmed denial survives cancellation and failed restriction persistence; metadata
success never clears a denied directory, and a newer concurrent denial wins.
Seven new common HTTP cases and seven new domain cases pass, alongside 13 device
lifetime and 20 identity-selection regressions. Actual AppContainer resolves the new
reader. Final focused gate: 47 fresh cases / 126.165 s.
Root :check, Android-host and dev compilation: 2,610 fresh cases /
327.498 s. Browser data/domain: 1,537 cases / 133.924 s.
All final gates preserve identical 3,972 build/application inputs;
format, architecture and detekt pass. All gate sessions closed.
[Evidence](measurements/collaboration-space-identity-observation-2026-11-05.json).

NEXT: durable native device registration/retry/bootstrap, using the selected Matrix
USER and actual retained SDK identity from Mobile 5860290e. Preferred legacy DEVICE
is not necessarily this phone. Retain immutable operation, native-parent, platform,
realm, original physical owner/user/device/fingerprint. Registration/retry bind the
original native parent but not old client build; bootstrap binds actual build too.
Identity-selection retry rules therefore cannot be copied unchanged. User/device
origins are independent: a new device may use an existing legacy provider user.
Missing current native-device status is not proof that no older SDK exists. Retained-store inventory,
hard-parent recovery, group runtime ownership/UI and typed push/background replies
remain unfinished. No spaceId/companyId or old-store native-family substitution.

Backend 316cd708 has the matching endpoint. No product UI change or new APK installation,
live deployment, provider/FCM, Apple runtime or performance acceptance. All 54 PRD groups
remain open. Live backend 8b40/schema 116 and installed phone 29/source 084662c5 unchanged.
Continue long run, atomic pushes and validated install-only product delivery.

## Native selected provider-user metadata — 5 November 2026

Group [316cd708](https://github.com/fajarxfce/hris-platform/commit/316cd7083bc154e2e28b9425349ed63d40d6425f) is pushed.

GET `/api/v1/collaboration/spaces/{spaceId}/identity` now returns only the already
retained account/space provider-user intent. Missing selection stays null even
when an eligible legacy preference exists. No selection/device/provisioning,
receipt/audit/outbox or provider/credential operation is triggered. The private,
no-store projection contains eight metadata fields and omits the preferred device,
keys and tokens. [Contract](https://github.com/fajarxfce/hris-platform/blob/316cd7083bc154e2e28b9425349ed63d40d6425f/docs/collaboration/space-identity-read.md).

Shared account guard, exact native parent, current group/client/security admission,
original30s wall budget, existing transaction/query timeouts and interruption
checks fence the read. Admission is rechecked after either snapshot outcome;
committed offboarding or a new MFA requirement cannot publish old metadata.
Five new PostgreSQL cases and one new HTTP case pass, with the existing native
transport guard extended to GET identity. Focused domain/PG: 16 cases /
55.207 s; actual API context: 18 cases / 105.251 s.
Full worker: 137 passed, 1 existing opt-in elapsed-retention skip /
375.238 s. All final gates preserve the same 8,506 Git-enumerated
build/application inputs; format and architecture pass. All sessions closed.
[Evidence](https://github.com/fajarxfce/hris-platform/blob/316cd7083bc154e2e28b9425349ed63d40d6425f/docs/measurements/collaboration-space-identity-read-2026-11-05.json).

NEXT: Mobile finite native transport/use case for this endpoint, then durable
group registration/retry/bootstrap retaining actual old SDK provenance. Match the
selected Matrix USER to the actual local SDK user; a preferred DEVICE may belong
to another phone using that same user. Existing Mobile5860290e provides the local
SDK identity port. Current workspace alone cannot choose the correct original user.
No spaceId/companyId substitution, implicit new SPACE store, frozen-runtime reopen
or native-family substitution in the old physical SDK hash. Closed-store inventory,
hard-parent recovery, current group SDK ownership, global UI and typed push/replies
remain separate unfinished work.

No SQL schema/role change or live deployment. Current product remains company chat;
all54 PRD groups open. Live backend8b40/schema116 and installed phone29/source084662c5
are unchanged. No new provider/FCM, phone, Apple runtime or performance acceptance.
Continue long run, atomic pushes and validated install-only product delivery.

## Local SDK identity observation — 5 November 2026

A narrow local-identity port now aliases the existing application Android runtime.
It borrows child ownership to read public SDK user/device/key identity, and preserves
cancellation, directory lifetime and late credential failures. An absent open client
performs no credential/store/network I/O and cannot imply an empty retained-store
inventory. Unsupported hosts remain explicitly unavailable. No implicit restore,
registration, private-key export, handover or physical namespace change occurs.
[Contract](collaboration-local-sdk-identity.md).

Five new Android-host cases cover absent-runtime I/O, owned public-key observation,
invalid/failed clients, pending cancellation/resource release and concurrent credential
failure. Existing Android handover, background and publication regressions also pass.
These use inert FFI handles, not JNI/provider proof. Real AppContainer resolves the
port to the same runtime instance without starting observation. Final root :check,
Android-host and Android dev compilation pass 350.063 s / 2,589 fresh
cases; browser data/domain pass 134.092 s / 1,523 cases. Both retain
identical 3,964 source inputs; format, architecture and detekt pass. All
gate sessions are closed. [Evidence](measurements/collaboration-local-sdk-identity-2026-11-05.json).

NEXT: expose/read already selected provider-user intent through a bounded native
metadata endpoint, without selecting or enrolling on GET. Current selection receipt
only identifies account/version; selected provider user may come from another HR
company. Match actual local SDK Matrix user, not merely current workspace or the
identity preference's original device ID (multiple devices may share that user).
Then use this port inside the owning durable group registration preparation,
retaining exact original company/device/user/key provenance after finite current
account/native admission checks. The port itself does not grant that admission or
prove private-key possession. Retain original physical owner, whose namespace
includes original native family; keep current authority separate. Closed-store
inspection, hard-parent recovery, new-SPACE retained-store inventory and group SDK
adoption still need explicit owners. Continue native registration/retry/bootstrap,
global UI and typed group push/background/replies. No companyId/spaceId substitution.

Current product remains company chat, all54 PRD groups open. Live backend8b40/schema116
and installed phone release29/source084662c5 are unchanged. No new phone installation,
UI interaction or provider/FCM operation. Continue long run, atomic pushes and
validated install-only product updates with advance notice.

## Native organization device status — 5 November 2026

One explicit group native-device GET now maps bounded public status separately
from device/SDK authority. It validates origin, versions, provisioning/outcome
consistency and gateway timestamps; expired metadata remains metadata. Fresh
login may return null while old keys still exist. No enrollment, polling, SDK
acquisition, credential bootstrap or directory admission clearing is triggered.
[Contract](collaboration-space-device-status.md).

The owning use case checks exact current native/local ownership across pending
boundaries, clips the original hard/wall/monotonic lifetime, retains received
access/build denial through cancellation or failed storage, and rejects a newer
concurrent directory denial. Successful recovery metadata cannot reopen the gate.
Seven native HTTP and thirteen domain lifecycle cases pass. Actual AppContainer
resolves the repository and owning use case. Final root :check, Android-host and
Android dev compilation pass 342.801 s / 2,584 fresh cases; browser
data/domain pass 136.437 s / 1,523 cases. Both preserve identical
3,962 source inputs; format, architecture and detekt pass. All gate sessions
are closed. [Evidence](measurements/collaboration-space-device-status-2026-11-05.json).

NEXT: durable native group registration/retry/bootstrap commands and recovery,
original SDK-store adoption/current authority, explicit native hard-parent recovery,
global UI and typed push/background/replies. Server registration/retry fingerprints
include original native parent but not client build; bootstrap includes native
parent, versions and actual build. Preserve original provenance and actual client
headers; neither copy account-wide identity replay into device operations nor
apply bootstrap build restrictions to every command. Null status is not permission
to create a replacement SPACE device. Retained original Matrix user/device/private
keys still require explicit continuity verification; public fingerprints alone
are not proof. The physical SDK namespace includes original native family; changing
it selects another store. Keep immutable physical owner separate from current
admission, and establish retained-store inventory before enabling new SPACE enrollment.
No backend behavior was changed by this status increment.

Current product remains company chat; all 54 PRD groups stay open. Live backend
8b40/schema116 unchanged. Phone release29 remains installed from source084662c5,
without this increment. No new phone installation/UI interaction. Continue long
run, atomic pushes and validated install-only product delivery with advance notice.

## Durable organization identity selection — 5 November 2026

Native group identity selection now retains an encrypted account/space command
before HTTP, preserves its exact operation across response loss and process/login
recovery, and stores a confirmed account/version receipt. Local command facts stay
readable offline; queued old-account emissions are cancelled. Explicit attempts
persist Unconfirmed before transport, serialize finite work and keep received
access denials gated even at cancellation or failed storage. Selection success
never clears directory admission. [Contract](collaboration-space-identity-command.md),
[evidence](measurements/collaboration-space-identity-command-2026-11-05.json).

Review confirmed the server operation fingerprint is account/space/provider realm,
without the first native family or build. An explicit retry therefore uses its
freshly supplied native owner and actual client headers while retaining immutable
original provenance. A real new login has a new local session ID; stale supplied
owners and changes during an attempt still fail. This selection rule does not
change device registration/bootstrap or authorize retained SDK-store adoption.

Nine shared SQLite/IndexedDB, six HTTP and fourteen domain cases pass; nine root
lifetime regressions remain green. Actual app DI resolves repositories/use cases
and singleton replay. Final root :check, Android-host and Android dev compilation
pass 124.829 s / 1,156 fresh cases; browser data/domain pass
150.292 s / 1,503 cases. Final 3,950 inputs are identical;
format, architecture and detekt pass. All gate sessions closed. Earlier compilation
fixes and the replay-contract review remain recorded separately.

NEXT: native group device status and durable registration/retry/bootstrap commands,
original SDK-store adoption and explicit native hard-parent recovery, group UI and
typed push/background/replies. Selection receipt identifies only account/version;
verify actual original Matrix user/device/key continuity before adopting a store.
Never substitute a space UUID for companyId, silently register a replacement SPACE
device when old keys exist, or reopen the frozen old runtime. Device/bootstrap
idempotency and immutable physical provenance need their own server-contract review;
do not copy selection's broader retry rule into them. No user/SDK/device is created
by observing the saved receipt.

Product still uses company chat and all 54 PRD groups remain open. Live backend
8b40/schema 116 is unchanged. Phone release 29 is installed-only and byte/signer/UID
verified from earlier product source 084662c5; this identity increment is not in
that artifact. No phone UI was opened and no data was cleared. Continue long run,
updates, atomic pushes and validated install-only product delivery.

## Product release 29 installed on the authorized phone — 5 November 2026

WireGuard ADB became reachable. Existing release 28 and the development signer
were verified before an explicit install-only update to release 29. Pulled-back
APK bytes match SHA-256 `e17f1681dcc5cacce43fb126b53da261e5b89423e65c3ab3737d29186c46dca1`;
package `dev.fajar.hris.mobile.dev`, version 1.0.0/29, non-debuggable. UID 10962
and original first-install identity are preserved. No uninstall, data clearing,
phone UI launch or interaction occurred. Installation and verification completed
in 81.256 seconds. [Evidence](measurements/android-release-phone-2026-11-04-build29.json).

This delivers the previously verified product artifact from 084662c5; its emulator
startup/notification evidence remains as recorded. Later organization-root and
identity-command work is separate. No new FCM, group runtime, Apple, call/meeting
or authenticated phone workflow acceptance. All 54 PRD groups remain open.

NEXT: continue durable native organization identity/device commands, original SDK
adoption/hard-parent recovery, group UI and typed push/background/replies. Current
identity-command work is uncommitted: 24 Kotlin files, encrypted account/space
CommandStore state, explicit prepare/replay, original native/build binding and
shared denial retention. Nine SQLite cases and five HTTP cases passed; domain
assertion compilation was corrected and focused3 is running. Full/browser gates
and source review remain required; do not call this identity acceptance. State
and gate ownership are in `.work/space-identity-2026-11-05/CURRENT.md`. Preserve
that source work while recording this independent phone-delivery checkpoint.
Continue long run, updates, atomic pushes and validated install-only updates.

## Account-owned organization cache root — 5 November 2026

The organization cache locator now survives restart and reauthentication for the
same account without depending on an HR company or native credential age. Its
first publication is encrypted and atomic; replay retains the same ciphertext,
and a different organization cannot replace it silently. A refused write is
reconciled against actual storage before claiming migration. Root-only ciphertext
also fences lost-key replacement. [Contract](collaboration-space-root.md),
[evidence](measurements/collaboration-space-root-2026-11-05.json).

Nine shared SQLite/IndexedDB storage cases and nine domain cases pass. Real
application DI resolves the repository and both owning use cases. Final root
:check, Android-host and Android dev compile pass 404.541 s /
2,522 fresh cases; browser data/domain pass 148.906 s /
1,474 cases. Both preserve the same 3,929 source inputs;
format, architecture and detekt pass. All gate sessions closed. The initial import
failure and later refused-write review correction remain in the evidence.

NEXT: durable native group identity/device setup commands, SDK-store adoption and
explicit hard-parent recovery, organization UI and typed group push/background/
replies. Identity selection must precede device registration. Its receipt identifies
the account/version, not the selected Matrix user: verify original provider user,
device and key fingerprint before adopting any retained SDK store. Stable operation
identity, build/platform and uncertainty must survive interrupted delivery. Never
substitute spaceId for companyId or open old runtime after freeze. New sign-ins
create a fresh local session ID; token refresh keeps the exact native binding.

The locator alone activates no group UI, network mutation or provider/device runtime.
Product still uses company chat; all 54 PRD groups remain open. Live backend
8b40/schema 116 is unchanged. Release 29 remains verified but uninstalled; four
15-second phone connection attempts timed out and the availability question is
pending. Release 28 is the last confirmed phone install. Continue long run, updates,
atomic pushes and validated install-only product delivery without driving phone UI.

## Current company-chat list cancels queued old results — 4 November 2026

A slow consumer could receive an old page result after page selection or read
admission changed. The existing product observer now resolves guarded access and
selection before one final cancellable row subscription. Both page and denial
replacement discard the old pending row; disposal leaves no local collectors,
and selection still starts no network request. Company/account/age policies stay
intact. [Contract](collaboration-local-list.md),
[evidence](measurements/collaboration-list-observation-2026-11-04.json).

The unchanged product observer failed the regression with three deliveries instead
of two. The corrected observer passes both replacement variants. Focused tests,
format/architecture/detekt pass. Root :check, Android-host and Android dev compilation
pass 335.456 s / 2,504 fresh cases; browser domain
pass 78.347 s / 969 cases. Final inputs identical
across all gates (3,918 files). All sessions closed. No new native/provider/FCM/
Apple or physical-device acceptance.

NEXT: durable group identity/device setup commands, SDK store adoption and explicit
hard-parent recovery, organization UI and typed group push/background/replies.
A durable account-root discovery record is also needed for startup to open the
organization directory offline; never infer it from arbitrary cached partitions.
Mobile transport trace is in `.work/space-directory-usecases-2026-11-04/next-device-transport.md`
and `.work/space-device-2026-11-04/CURRENT.md`.
New native sign-ins create a fresh local session ID; token refresh requires the same
account and exact native binding. Preserve that transport fence and the original
SDK identity during group adoption. This increment improves current company chat;
all 54 PRD groups remain open. Live backend 8b40/schema 116 is unchanged.
Release 29 remains a validated artifact pending phone connection; no install or
phone UI action occurred, release 28 is the last confirmed install. Continue long
run, updates, atomic pushes and validated install-only product delivery.

## Organization directory local reads and finite refresh — 4 November 2026

Local observation now keeps owned directory facts readable through ordinary credential
expiry while cancelling facts on account/admission changes. One explicit refresh
acquires at most one page of 50; durable terminal cursors stop extra requests,
interrupted refresh resumes, and only admitted publication clears the captured
access revision. Original native lifetime and a 15-second total deadline bound
acquisition, including mutex waits. A separate bounded 2-second path retains a
received denial at cancellation. [Contract](collaboration-space-directory-usecases.md),
[evidence](measurements/collaboration-space-directory-usecases-2026-11-04.json).

22 focused behavioral cases pass. A slow-consumer denial regression initially
failed and is now fixed by switching guarded access before observing facts.
Root/Android-host/Android dev compile pass 419.463 s /
2,503 fresh cases; browser domain pass 81.676 s /
968 cases, identical 3,917 inputs. Real application DI resolves
the observer and singleton refresh. Format, architecture and detekt pass.
All sessions closed; no new product/provider/FCM/Apple runtime acceptance.

NEXT: reproduce/fix the same nested-flow boundary in the current company-chat list,
then durable group identity/device commands, SDK store adoption and hard-parent
recovery, organization UI and typed group push/background/replies. Draft regression
and transport trace are in `.work/company-chat-observation-2026-11-04/` and
`.work/space-directory-usecases-2026-11-04/`. Product still uses company chat;
all 54 PRD groups remain open. Live backend 8b40/schema 116 is unchanged.
Release 29 remains verified but uninstalled: three 15-second phone connection
attempts timed out. Existing availability question remains pending; release 28 is
the last confirmed installation. Continue long run, updates, atomic pushes and
validated install-only product delivery when the phone returns.

## Product release 29 verified; phone connection pending — 4 November 2026

Product source [084662c5](https://github.com/fajarxfce/hris-mobile/commit/084662c5bc9277ae8fd56d103a0acda89c3ee5ee) is clean, committed and pushed. The signed devRelease29 APK is
88,107,752 bytes, SHA-256 `e17f1681dcc5cacce43fb126b53da261e5b89423e65c3ab3737d29186c46dca1`.
Package, existing development signer, Firebase dev app, private API, release flags,
16 KiB alignment and backup exclusions are verified; lint has zero issues.
Build/lint plus 18 proof unit tests pass in 548.560 s. The exact ARM64 release APK
opens twice on the dedicated emulator through its native bridge, with 12 stable
seconds per start. Onboarding screenshot is reviewed. The separate proof APK
passes real Android notification/callback/Room/Keystore, three process deaths and
SystemUI reply checks (zero HTTP/Matrix sends). Editor/removal screenshots reviewed.
Owned proof package and emulator are cleaned up. [Evidence](measurements/android-release-phone-2026-11-04-build29.json).

Phone `10.77.77.3:5555` remains unreachable after two 15-second connection attempts.
No installation or phone UI action was performed; release 28 remains the last
confirmed install. The availability question is pending. Retained APK and guarded
verification/install scripts are in `.work/release-phone-2026-11-04-build29/`.
When the device returns: verify current 28/same signer, notify the user immediately
before `install.py --install-only`, then confirm UID/first install and pull-back SHA.
Never install the proof APK on the phone, clear its data or drive its UI.

NEXT: continue organization-directory local-read/refresh use cases, durable native
identity/device commands, SDK adoption/hard-parent recovery, organization UI and
push/background/replies while phone delivery waits. Product still uses company
chat. All 54 PRD groups remain open; this is neither group rollout nor new provider/
FCM/Apple/voice/meeting acceptance. Live backend 8b40/schema 116 is unchanged.
Continue long run, updates, atomic pushes and validated product installs.

## Atomic company-chat admission cleanup — 4 November 2026

A newer access denial can arrive just before an earlier completion clears its
process buffer. Both completion paths now clear only their captured value with a
single atomic compare-and-set. The newer denial remains observable, including
when its durable write is pending. This fixes the existing company-chat cache;
organization directory storage already uses the same mechanism at e97d5e0a.
[Contract](collaboration-local-list.md),
[evidence](measurements/collaboration-admission-atomic-2026-11-04.json).

Root :check, Android-host and Android dev compilation pass in 344.93 s
(1,371 fresh cases); browser data suite passes in 92.87 s
(487 cases). Both keep 3,910 identical source inputs. The new shared
regression injects a denial at each final-clear boundary on real SQLite/IndexedDB
with AES-GCM. Format, architecture, detekt and application DI tests pass. All
sessions closed. No new provider, FCM, Apple runtime or product APK acceptance.

NEXT: prepare a validated product update for the authorized phone, then owning
organization-directory local-read/refresh use cases, durable native identity/device
commands, SDK adoption/hard-parent recovery, organization UI and typed group push/
background/replies. No selected HR company may stand in for organization ownership.
All 54 PRD groups remain open. Live backend 8b40/schema 116 and phone release 28
are unchanged. Continue long run, updates, atomic pushes and validated installs.

## Encrypted organization directory storage — 4 November 2026

Account/space-owned directory facts now survive reauthentication, interrupted paging
and replacement refreshes. Names and company labels remain encrypted; old published
facts stay visible until a replacement completes. Durable terminal cursors and
bounded local reads avoid extra end-page reads. Access denials survive failed or
rejected persistence; exact durable revision plus atomic pending-buffer clear protect
newer denials. Every real host supplies a separate protected content key.
[Contract](collaboration-space-directory-storage.md),
[evidence](measurements/collaboration-space-directory-storage-2026-11-04.json).

Final root :check, Android-host and Android dev compilation pass 346.21 s /
1,370 fresh cases; browser pass 96.303 s / 486 cases. Both keep
3,910 identical source inputs. Seventeen shared cases run on real SQLite/IndexedDB
with AES-GCM; real application DI resolves the new repository. Earlier attempts,
review correction and their validation limits are recorded. All sessions closed.

NEXT: apply the same atomic pending-buffer clear to the existing company-chat list
(the current-then-clear window remains there), then owning local-read/refresh use
cases, durable native identity/device commands, SDK adoption/hard-parent recovery,
organization UI and typed group push/background/replies. This storage adapter alone
does not apply local read admission or perform native acquisition. Product routes
still use company chat. No new product/provider/Apple/FCM or performance acceptance.
Live backend 8b40/schema 116, phone release 28, product/hosted 0b804cb2 and Starter
9959c1d are unchanged.
All 54 PRD groups remain open. Continue long run, updates, atomic pushes and validated
product installs.

## Native group discovery and directory transport — 4 November 2026

A distinct CollaborationSpaceOwner now drives native discovery and bounded contact
pages independently of selected HR company. Initial/current native ownership,
15-second execution limits, conservative monotonic parent deadlines and strict
response/cursor validation reject stale/foreign results. No auto paging, enrollment,
SDK acquisition or local-read reopening. Company labels grant no HR access.
[Contract](collaboration-space-discovery.md),
[evidence](measurements/collaboration-space-discovery-2026-11-04.json).

Root :check + Android-host pass398.202s/3,899 same inputs/2,463 fresh cases;14 unique
new cases (six data cases also on Android-host). Only a new-test opt-in annotation
changes before the second root pass106.324s/946 fresh domain. Three container-test
assertions then verify the actual new dependency graph:2 focused passes69.803s plus
format/architecture/detekt. Production is identical across all gates. Sessions closed.
MockEngine HTTP is not new backend/provider, Apple or product acceptance.

NEXT: encrypted local group-directory ownership/denial retention, durable native
identity/device commands, SDK store adoption and hard-parent recovery, product UI
and group push/background/reply integration. The current UI still uses company chat;
no group rollout or phone install. Actual original queued-key proof5f9896f7/36c60208
remains accepted separately (strict shields Red, not verified sender trust).
Live8b40/schema116, phone28, product/hosted0b804cb2 and Starter9959c1d unchanged.
All54 PRD groups remain open. Continue long run, updates, atomic pushes and validated
product installs.

## Original queued SDK keys recovered through group completion — 4 November 2026

Backend [5f9896f7](https://github.com/fajarxfce/hris-platform/commit/5f9896f72bb047759ca2378247995eac9b1a533a) is pushed.

Actual Android SDK26.10.23 messages survive native legacy completion, an interrupted
queued-key response and two process deaths. Both unknown/known-peer variants decrypt
the exact original encrypted event with the same original keys and store. The
unmigrated peer and original room need no new-send eligibility expansion. The next
process reads one local event before sync; its subsequent cursor acknowledgment
clears the retained provider queue. Strict SDK shields are Red, not verified trust.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/5f9896f72bb047759ca2378247995eac9b1a533a/docs/collaboration/space-sdk-history.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/5f9896f72bb047759ca2378247995eac9b1a533a/docs/measurements/collaboration-space-sdk-history-2026-11-04.json).

Two actual API/PostgreSQL/Synapse/emulator cases pass in109.162s, with12,388 unchanged
combined inputs and the same APK. Mobile root :check, Android-host, proof unit/build/
lint pass in133.464s (3,883 unchanged inputs;18 fresh proof cases, unaffected tasks
reused). Earlier failed attempts and fixture corrections are recorded. All sessions
and owned emulator resources closed. Mobile proof commit [36c60208](https://github.com/fajarxfce/hris-mobile/commit/36c6020892fe6c7f0a2b2f28772f9a8a31d538a6) is pushed.

NEXT: product group runtime/store adoption, typed group push/background/reply
routing, native hard-parent recovery and actual FCM single-notification handover.
This fixture is not product adoption, worker-role isolation, verified sender trust,
Apple/web or load acceptance. Current Mobile product still ignores group push.
Live8b40/schema116, phone28, product/hosted0b804cb2 and Starter9959c1d unchanged.
All54 PRD groups remain open. Continue long run, updates, atomic pushes and validated
product installs.


## Original queued SDK keys recovered — 4 November 2026

The actual Android SDK preserves original keys and decrypts queued messages after
native organization completion, lost-response replay and two process deaths. Both
known/unknown-peer cases pass (109.162s,12,388 unchanged combined inputs). Local
history is read before sync and contains exactly one logical event. Strict shields
are Red: this is decryption evidence, not verified/cross-signed device trust.
[Contract](collaboration-space-sdk-history.md),
[evidence](measurements/collaboration-space-sdk-history-2026-11-04.json).

Root :check, Android-host and proof unit/assemble/lint pass (133.464s,3,883 unchanged
inputs;18 fresh proof cases, unaffected tasks reused). Earlier failing attempts and
fixture corrections are recorded. All sessions and owned emulator resources closed.
Companion backend test/driver publication follows this Mobile proof commit.

NEXT: product group runtime/store adoption, typed group push/background/reply
routing, native hard-parent recovery and actual FCM single-notification handover.
This is proof-only; current product still ignores group push envelopes. Live8b40/
schema116, phone28, product/hosted0b804cb2 and Starter9959c1d unchanged. All54 groups
remain open. Continue long run, updates, atomic pushes and validated product installs.

## Organization push callback and worker integrated — 4 November 2026

Backend [d0478388](https://github.com/fajarxfce/hris-platform/commit/d0478388ba1a84154cbd42f0f1f4b20b45356178) is pushed.

Mixed private callbacks preserve separate company/group types within one total input
bound and original deadline. The group worker reacquires current ownership before and
after outside-transaction FCM delivery. Exact token disable/readback and captured-revision
ACK commit together; changed tokens defer, and newer callbacks survive old outcomes.
Separate bounded company/group pools share the tested lifecycle implementation.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/d0478388ba1a84154cbd42f0f1f4b20b45356178/docs/collaboration/space-push-delivery.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/d0478388ba1a84154cbd42f0f1f4b20b45356178/docs/measurements/collaboration-space-push-delivery-2026-11-04.json).

Focused:191 pass (272.574 s:147 domain,21 callback,23 data).
Initial API:153/154 pass (233.639 s); the new preservation test
compares PostgreSQL array objects by identity. Only its two SELECT statements change to
compare every column/array value as JSON. Full58 HTTP cases then pass
(139.405 s), alongside the96 configuration/contract cases above.
FULL worker:138 cases,1 existing opt-in report-retention skip
(394.54 s). Format/architecture pass. All four gates retain
8,505 inputs; production sources are unchanged and only that HTTP test changes.
All sessions closed. Actual shared API/worker DI is validated. Cloud transport in new
group delivery tests is injected, not real FCM.

NEXT: typed Mobile group push/background/reply routing and runtime store adoption,
actual SDK original queued-key decryption, native hard-parent recovery and actual FCM
one-visible-notification handover while old/company and group pushers coexist. Current
Mobile product ignores new group envelopes; no group rollout or phone install yet.

Base `f6777bfe` pushed. All 54 PRD groups open. Live8b40/schema116 (push on,
SMTP off), phone28, Mobile product/hosted0b804cb2 and Starter9959c1d unchanged.
Continue long run, regular updates, atomic pushes and validated product installs.


## Organization callback admission accepted — 4 November 2026

Backend [f6777bfe](https://github.com/fajarxfce/hris-platform/commit/f6777bfe67350cc1de7b9761a2f680e6fb02e507) is pushed.

Typed group callbacks reacquire current root account/native/device/gateway/identity,
provider metadata, group eligibility and registration. Short access/MFA/provider expiry
permits only opaque wake. Exact LEGACY completion provenance is separate from the
unchanged interactive admission wrapper. No provider/native token is acquired.
Owned original/published room metadata survives peer offboarding; failed/foreign reads
cannot grant access or retire valid keys. Final current checks roll back late append.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/f6777bfe67350cc1de7b9761a2f680e6fb02e507/docs/collaboration/space-push-admission.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/f6777bfe67350cc1de7b9761a2f680e6fb02e507/docs/measurements/collaboration-space-push-admission-2026-11-04.json).

144 domain cases pass (26.148 s). Initial data compilation rejects
one test-double parameter name under -Werror; only that test name is fixed. All25 data
cases pass (274.722 s):10 new callback cases,1 new completed LEGACY
original-room case,12 native pusher cases and2 retained pusher/gateway regressions.
Format/architecture pass on 8,500 unchanged final inputs. All sessions closed.
No shared DI/HTTP/worker wiring yet. No live or phone installation for this backend step.

NEXT: bounded group worker delivery/token-rotation fencing and mixed company/group
callback adapter, then shared API + FULL worker context gates. Mobile background/reply
owner handover, actual FCM single notification, original SDK queued-key decryption and
native hard-parent recovery remain required. Queue ACK is not a delivered/read receipt.

Base `187a0174` pushed. All54 PRD groups open; rollout disabled. Live8b40/schema116
(push on,SMTP off), phone28, Mobile product/hosted0b804cb2 and Starter9959c1d unchanged.
Continue long run, updates, atomic pushes and validated product installs.


## Organization push wake storage accepted — 4 November 2026

Backend [187a0174](https://github.com/fajarxfce/hris-platform/commit/187a01744775f8e514f8770a66c1a097998bb560) is pushed.

V137 stores separate group wake heads with exact account/device/native provenance,
root/worker isolation, bounded deduplication, finite attempts and global group capacity8.
Captured-revision acknowledgments preserve newer callbacks; expired/replaced leases
cannot consume successors. Strict mapping and transactional readback reject foreign,
malformed or missing rows. TTL uses elapsed seconds, including DST session boundaries.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/187a01744775f8e514f8770a66c1a097998bb560/docs/collaboration/space-push-queue.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/187a01744775f8e514f8770a66c1a097998bb560/docs/measurements/collaboration-space-push-queue-2026-11-04.json).

All 139 domain cases pass initially (58.469 s); data test compilation
then finds ambiguous JUnit imports. Those test imports are corrected. Review also
changes the new SQL TTL constraint to exact elapsed seconds and adds a DST case.
All 28 data cases pass (136.263 s): 15 new PostgreSQL queue cases,
11 original company queue regressions and two native/retained pusher cases. Recovery:
21 pass (0.068 s). Format/architecture checks pass on
8,495 unchanged final inputs. Actual isolated PostgreSQL archive/restore preserves
pending work, lease and encrypted capability. All sessions closed. No shared DI changes.

NEXT: current group callback/background admission, mixed company/group callback dispatch,
worker delivery with token-rotation fencing, and Mobile background/reply ownership.
Queue metadata is not authority or a delivered/read chat receipt. Prove one visible
notification across ownership handover using actual FCM. Actual SDK original queued-key
decryption, group store transition and native hard-parent recovery remain required.

Base `7b81b8e8` pushed. All 54 PRD groups open; rollout disabled. Live8b40/schema116
(push on, SMTP off), normal phone28, Mobile product/hosted0b804cb2 and Starter9959c1d unchanged.
Continue long run, updates, atomic pushes and validated product installs.


## Native organization managed pusher accepted — 4 November 2026

Backend [7b81b8e8](https://github.com/fajarxfce/hris-platform/commit/7b81b8e8562417298872040be60c6f23ab2fc72f) is pushed.

The empty-body managed pusher route now accepts current group gateway ownership.
A separate stable capability is reserved before Matrix I/O; native registration and
current admission are rechecked around both outcomes. Exact publication, audit and
outbox commit together. Rotation, expiry, revocation, failed readback or journal cannot
produce successful acknowledgement. Original Matrix identity survives completed
handover. No native FCM/APNs token is acquired by the pusher operation.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/7b81b8e8562417298872040be60c6f23ab2fc72f/docs/collaboration/space-pusher-access.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/7b81b8e8562417298872040be60c6f23ab2fc72f/docs/measurements/collaboration-space-pusher-access-2026-11-04.json).

Native/domain gate: 167 pass (391.391 s), including 12 new native
PostgreSQL cases, one retained migration case, 18 gateway/key regressions and 136 domain.
Initial API: 152/153 pass (233.24 s); the new preservation test
incorrectly assumed an empty original company namespace for a shared fixture account.
Only that test changes to compare exact original rows before/after registration.
All 57 HTTP class cases then pass (138.375 s), alongside the 96
configuration/contract cases already passing. FULL worker: 138 cases
(382.078 s), 137 passing and one existing opt-in skip.
Format/architecture checks pass on 8,485 unchanged inputs. Production stays
unchanged across all gates; corrected HTTP/FULL worker fingerprints match. All sessions closed.

NEXT: typed group callback admission/queue/worker, mixed original/group callback handling
and Mobile background/reply ownership. Prove one visible notification across ownership
handover using actual FCM; provider registration alone does not prove delivery. Actual
SDK original queued-key decryption, store transition and native hard-parent recovery
remain required. Continue the remaining chat/call/meeting PRD.

Base `172b559c` pushed. All 54 PRD groups open; rollout disabled. Live8b40/schema116
(push on, SMTP off), normal phone28, Mobile product/hosted0b804cb2 and Starter9959c1d unchanged.
Continue long run, updates, atomic pushes and validated product installs.


## Organization Matrix pusher protocol accepted — 4 November 2026

Backend [172b559c](https://github.com/fajarxfce/hris-platform/commit/172b559c1cc3719ef660d8b71e7eac1f2fd9bbfd) is pushed.

The fixed Matrix pusher adapter now accepts separate original/group capabilities with
the same event-only application profile. Both survive retry/restart on the same device
and can be removed independently. Deployable log formatting redacts both namespaces,
including partial keys and exception paths. No native FCM/APNs token enters Matrix.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/172b559c1cc3719ef660d8b71e7eac1f2fd9bbfd/docs/collaboration/space-pusher-protocol.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/172b559c1cc3719ef660d8b71e7eac1f2fd9bbfd/docs/measurements/collaboration-space-pusher-protocol-2026-11-04.json).

Eight transport/actual Synapse cases pass (63.677 s). Fourteen formatter
cases pass (0.101 s), including actual deployment configuration in a
fresh process. Format/architecture checks pass on 8,486 unchanged inputs. All
sessions closed. No schema or DI change; no actual product FCM delivery claim.

NEXT: native group managed pusher admission/reservation/publication, API and FULL worker
DI validation, then callback admission/group queue/worker and Mobile background/reply
ownership. Actual SDK queued-key decryption, group store transition and native hard-parent
recovery remain required. Existing native callers still only generate company capabilities.

Base `892d3497` pushed. All 54 PRD groups open; rollout disabled. Live8b40/schema116
(push on, SMTP off), normal phone28, Mobile product/hosted0b804cb2 and Starter9959c1d unchanged.
Continue long run, updates, atomic pushes and validated product installs.


## Organization push capability storage accepted — 4 November 2026

Backend [892d3497](https://github.com/fajarxfce/hris-platform/commit/892d34973b31a51b15288e891a8e2f1b2d6899dc) is pushed.

V136 retains separate encrypted group push capabilities, bound to exact space/account/
group-device/original-native/Matrix identity. Only immutable reservation to publication
is allowed. Root ownership and same-transaction lookup cleanup are enforced; completed
LEGACY handover is mandatory. Original company push records survive unchanged.
Typed readback and the recovery verifier authenticate the same eight coordinates.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/892d34973b31a51b15288e891a8e2f1b2d6899dc/docs/collaboration/space-push-storage.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/892d34973b31a51b15288e891a8e2f1b2d6899dc/docs/measurements/collaboration-space-push-storage-2026-11-04.json).

150 domain/real PostgreSQL/typed storage cases pass (230.795 s), including
136 domain, six new SQL, two retained migration and six storage cases. Format and
architecture checks pass on 8,484 unchanged inputs. Recovery: 20 pass
(0.011 s), including actual generated SQL/archive/key-rotation
coverage in the storage gate. All sessions closed. No shared DI changed.

NEXT: connect native group pusher reservation/provider publication and both key namespaces
with deployable log redaction, then group callback admission/queue/worker delivery and
Mobile background/reply ownership. Preserve actual SDK original queued-key decryption
proof, store ownership transition and native hard-parent recovery as required work.

Base `7b767bbf` pushed. All 54 PRD groups open; rollout disabled. Live8b40/schema116
(push on, SMTP off), normal phone28, Mobile product/hosted0b804cb2 and Starter9959c1d unchanged.
Continue long run, updates, atomic pushes and validated product installs.


## Native group room metadata and receipts accepted — 4 November 2026

Backend [7b767bbf](https://github.com/fajarxfce/hris-platform/commit/7b767bbfdc6ec59b978d9696ab159084c8727cb3) is pushed.

Group gateway routes now expose managed direct hints, room join/state/membership and
public/private read receipts over exact original/published room ownership. Current
native admission and immutable metadata are rechecked around both provider outcomes.
Receipts require mapped event proof before writing. Historical membership authenticates
group positions; original company positions require fresh group sync. Shared bounded
provider projection preserves original creators and distinct peer aliases.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/7b767bbfdc6ec59b978d9696ab159084c8727cb3/docs/collaboration/space-metadata-access.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/7b767bbfdc6ec59b978d9696ab159084c8727cb3/docs/measurements/collaboration-space-metadata-access-2026-11-04.json).

All 134 domain cases pass initially (27.806 s); data compilation then
failed on one missing test import. That import alone changed. The 72-case data gate passes 70 cases
(558.887 s); two new restart tests retained the old mapped provider
endpoint. Only that fixture changed. Both actual Synapse cases then pass
(50.269 s), including post-restart metadata and receipt projection.
Actual API: 149 pass (237.891 s). FULL worker: 138 cases
(394.062 s), 137 passing plus one existing opt-in skip.
Format/architecture checks pass on 8,473 unchanged final inputs. Production
remained unchanged across all gates; corrected interop/API/worker fingerprints match.
All sessions closed. Actual SDK queued-key decryption remains unproven.

NEXT: group push admission/delivery, actual SDK original queued-key authentication and
decryption, Mobile SDK store ownership transition and native hard-parent recovery.
Preserve original crypto identities and current group authority; do not reuse old
company push ownership for a group device. Continue the remaining communications PRD.

Base `93022a1e` pushed. All 54 PRD groups open; group rollout disabled. Live8b40/schema116
(push on, SMTP off), normal phone28, Mobile product/hosted0b804cb2 and Starter9959c1d unchanged.
Continue long run, updates, atomic pushes and validated product installs.


## Current native group sync accepted — 4 November 2026

Backend [93022a1e](https://github.com/fajarxfce/hris-platform/commit/93022a1e48706e544df4604d60cc873a4dac0576) is pushed.

Group sync now admits the complete original/published room inventory under current
native/device/provider authority. Same-scope positions resume; changed scopes restart;
only exact retained devices may initialize from an authenticated company cursor. Both
provider outcomes and final cursor sealing recheck current authority and the full scope.
Strict Matrix sync HTTP dispatch and shared API/worker DI are connected.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/93022a1e48706e544df4604d60cc873a4dac0576/docs/collaboration/space-sync-access.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/93022a1e48706e544df4604d60cc873a4dac0576/docs/measurements/collaboration-space-sync-access-2026-11-04.json).

Initial native gate: 183 of 184 pass (460.111 s). One combined crypto test
reused a fixed fixture company code in its second scenario; only that test changed to
use separate test databases. Both affected cases pass (56.138 s).
All 133 domain, 34 publication/original migration and eight history cases also pass.
Actual API: 146 pass (228.547 s).
FULL worker: 138 cases (391.017 s), 137 passing plus one existing opt-in
skip. Format/architecture checks pass on 8,465 unchanged final inputs. Only the
native fixture isolation and subsequent API tests changed; all production stayed unchanged.
All sessions closed. Actual SDK queued-key decryption remains unproven.

NEXT: connect room metadata/direct hints/join/read receipts and group push; prove actual
SDK queued original key authentication/decryption, then Mobile SDK ownership transition
and native hard-parent recovery. Ignored room-access protocol drafts are unaccepted.

Base `a0dc8a31` pushed. All 54 PRD groups open; group rollout disabled. Live8b40/schema116
(push on, SMTP off), normal phone28, Mobile product/hosted0b804cb2 and Starter9959c1d unchanged.
Continue long run, updates, atomic pushes and validated product installs.


## Original and group sync protocol accepted — 4 November 2026

Backend [a0dc8a31](https://github.com/fajarxfce/hris-platform/commit/a0dc8a31f8b6173bbbe4964c9b76757ffe1948ed) is pushed.

Group sync now has a bounded provider adapter for the complete original/published room
union and a separate six-coordinate encrypted position. Exact original cursors may
initialize a new scope only for retained devices; fresh devices remain isolated.
Original peer aliases and queued encrypted frames survive room adoption. Both HRIS_
and HRISG_ devices retain finite connection ownership.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/a0dc8a31f8b6173bbbe4964c9b76757ffe1948ed/docs/collaboration/space-sync-protocol.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/a0dc8a31f8b6173bbbe4964c9b76757ffe1948ed/docs/measurements/collaboration-space-sync-protocol-2026-11-04.json).

Initial gate: 153 of 155 pass (94.718 s), including all 133 domain,
actual group/company Synapse sync, cursor and transport cases plus native regressions.
Two new assertions called Jackson's whole-node map instead of mapping array elements;
only that test file changed. All three corrected projection cases pass
(30.791 s), with format/architecture checks on 8,463 unchanged
inputs. Production stayed unchanged. All sessions closed. SDK decryption remains unproven.

NEXT: connect group sync with current native admission, complete inventory checks around
both provider outcomes and after cursor sealing. Validate API and FULL worker contexts.
Then historical peer-key proof, metadata/push, Mobile ownership and hard-parent recovery.
Ignored native sync/HTTP drafts remain unaccepted.

Base `68087090` pushed. All 54 PRD groups open; group rollout disabled. Live8b40/schema116
(push on, SMTP off), normal phone28, Mobile product/hosted0b804cb2 and Starter9959c1d unchanged.
Continue long run, updates, atomic pushes and validated product installs.


## Current native original history access accepted — 4 November 2026

Backend [68087090](https://github.com/fajarxfce/hris-platform/commit/68087090d7f20ced6a552a3a13f133bf9ed76e3e) is pushed.

The existing history routes now read separately proven original rooms when their exact
group binding is absent. Canonical original company/server/account metadata and current
native/device/provider admission remain mandatory. Both provider outcomes recheck the
exact original or published tuple. Peer offboarding does not remove owned history;
new sends still require current recipients and published group room authority.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/68087090d7f20ced6a552a3a13f133bf9ed76e3e/docs/collaboration/space-original-history-access.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/68087090d7f20ced6a552a3a13f133bf9ed76e3e/docs/measurements/collaboration-space-original-history-access-2026-11-04.json).

Initial gate: 173 of 175 pass (353.045 s), including 133 domain, eight
native history and 32 existing publication cases. Two new cases collided on the fixture's
fixed provider room ID; only that fixture was corrected to use unique IDs. All three
affected cases then pass (121.123 s). Actual API: 143 pass
(223.329 s). FULL worker: 138 cases (396.093 s), with
137 passing and the one existing opt-in skip. Quality checks pass on 8,455
unchanged final inputs. Production is unchanged after the initial gate. All sessions closed.

NEXT: complete group sync using the original/published union, distinct sync cursor and
shared bounded provider projection. Prove actual SDK queued-key authentication/decryption
while preserving original store/user/device; embedded sender keys and retained peer data
matter, but source inspection is not acceptance. Then metadata/push, Mobile SDK ownership
transition and native hard-parent recovery. Ignored future sync drafts remain unaccepted.

Base `beccbea1` pushed. All 54 PRD acceptance groups remain open; group rollout disabled.
Live8b40/schema116 (push on, SMTP off), normal phone28, Mobile product/hosted0b804cb2 and
Starter9959c1d unchanged. Continue long run, updates, atomic pushes and product installs.


## Original history protocol accepted — 4 November 2026

Backend [beccbea1](https://github.com/fajarxfce/hris-platform/commit/beccbea1d5f2ecee41ce48d93cafeeb3f962e91f) is pushed.

The group history port now reads explicit original room metadata before a published
group binding exists. Shared bounded projection retains actual original creator and
ciphertext. Original and adopted metadata use the same seven-coordinate group cursor;
only the exact retained device may bridge an old company cursor. Fresh devices remain
isolated. Missing crypto keys, cancellation and interruption retain their classification.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/beccbea1d5f2ecee41ce48d93cafeeb3f962e91f/docs/collaboration/space-original-history-protocol.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/beccbea1d5f2ecee41ce48d93cafeeb3f962e91f/docs/measurements/collaboration-space-original-history-protocol-2026-11-04.json).

Initial gate: 163 of 164 passed in 164.863 s, including all 133 domain,
27 existing data/provider/native and three new protocol cases. One new fixture used
version10/federated defaults instead of the existing version12/nonfederated contract.
The four corrected protocol cases pass in 30.813 s; quality checks
pass on 8,455 unchanged inputs. Only that fixture changed; production and all
other tests remained unchanged. Actual Synapse alternates original/adopted history
after restart and checks fresh original-device reads. All sessions closed. No SDK
Megolm decryption or connected native original fallback is claimed.

NEXT: connect original-room lookup only after successful absent group binding, with
current native admission and exact metadata revalidation around both provider outcomes.
Validate actual API and FULL worker contexts for DI changes. Then complete group sync,
historical peer-key authentication/decryption, metadata/push, Mobile SDK ownership
transition and native hard-parent recovery without replacing original stores/keys.

Base `4c6b9dbd` pushed. Group rollout disabled; all 54 PRD acceptance groups open.
Live8b40/schema116 (push on, SMTP off), normal phone28, Mobile product/hosted0b804cb2
and Starter9959c1d unchanged. Continue long run, updates, atomic pushes and product installs.


## Original and published room read scope accepted — 4 November 2026

Backend [4c6b9dbd](https://github.com/fajarxfce/hris-platform/commit/4c6b9dbd103b41ffdf2b242649aac446c6b2fe2b) is pushed.

A complete pure DIRECT read snapshot now combines separately proven original rooms
and published group bindings. Exact adopted overlaps retain both metadata sources but
appear once; unadopted rooms keep their original identity. Conflicts, duplicate rows,
foreign ownership and incomplete/over-capacity inventories fail without truncation.
Original peers may retain historical aliases while the caller's user stays exact.
Fresh and retained device coordinates are validated even for empty scopes.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/4c6b9dbd103b41ffdf2b242649aac446c6b2fe2b/docs/collaboration/space-read-scope.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/4c6b9dbd103b41ffdf2b242649aac446c6b2fe2b/docs/measurements/collaboration-space-read-scope-2026-11-04.json).

All 160 tests pass in 166.957 s: 133 domain (six new read-scope cases)
and 27 data/provider/native cases. Format and architecture checks pass on 8,454
unchanged inputs. Initial compilation stopped on unnecessary non-null assertions in
the new fixture (15.854 s, zero tests); only that fixture changed.
Production stayed unchanged. Existing actual Synapse and native history regressions
pass, including original/fresh devices and cursor isolation. No new SDK decryption or
queued-key delivery proof is claimed. All sessions closed.

NEXT: original-room history framing/cursors under the same seven group coordinates,
then admitted original history fallback and complete group sync projection/admission.
Retain exact old-device-only cursor bridging and prove historical peer-key lookup plus
SDK decryption before adoption. Follow with metadata/push, Mobile SDK ownership change
and native hard-parent recovery while retaining original keys/store/history.

Base `28a1559c` is pushed. Group rollout stays disabled and all 54 PRD acceptance
groups stay open. Live8b40/schema116 (push on, SMTP off), normal phone28, Mobile
product/hosted0b804cb2 and Starter9959c1d unchanged. Continue long run, updates,
atomic pushes and validated product installs.


## Fresh-device history on original users fixed — 4 November 2026

Backend [28a1559c](https://github.com/fajarxfce/hris-platform/commit/28a1559c3481f40b061b88659001d369feaa1ec3) is pushed.

Fresh HRISG_ devices can now read owned history on an already selected original Matrix
user. The exact canonical room member remains mandatory; device origin no longer
incorrectly forces a new group user. Retained devices still require their original
HRIS_ tuple. Fresh devices cannot use old company/group cursors; their own cursors
remain isolated. No keys, SDK store or provider identity are replaced.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/28a1559c3481f40b061b88659001d369feaa1ec3/docs/collaboration/space-history-device-identity.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/28a1559c3481f40b061b88659001d369feaa1ec3/docs/measurements/collaboration-space-fresh-history-device-2026-11-04.json).

All 127 domain cases passed in the initial gate (23.031 s), which
then stopped on a new test override parameter warning under -Werror. Corrected data/
provider/native selection: all 27 pass (169.466 s), with quality checks
on 8,451 unchanged inputs. Only two test fixtures changed after the initial
run; production is unchanged. Native registration/activation/bootstrap and admitted
history exercise both canonical-user origins. Actual Synapse exercises fresh devices
after restart, original event content and cross-device cursor isolation while old
access remains usable. No fresh-device SDK decryption is claimed. All sessions closed.

NEXT: combine published group and separately proven original owned read metadata.
Allow both fresh devices on original canonical users and completed retained devices;
only the exact retained device may bridge its old cursor. Preserve per-room peer
aliases and queued original key frames, and prove actual SDK peer-key lookup/decryption.
Then group sync cursor/filter/projection, history fallback, metadata/push, full Mobile
adoption and native hard-parent recovery without replacing original stores/keys.

Base `3681619d` is pushed. Group rollout remains disabled and all 54 PRD acceptance
groups remain open. Live8b40/schema116 (push on, SMTP off), normal phone28, Mobile
product/hosted0b804cb2 and Starter9959c1d remain unchanged. Continue the long run,
updates, atomic pushes and validated product installs.


## Original room read inventory accepted — 4 November 2026

Backend [3681619d](https://github.com/fajarxfce/hris-platform/commit/3681619df313d6e27d4d276e1e841bc7e01c0637) is pushed.

Existing V129/V130 metadata now supplies bounded original company/server room inventory
and exact room-ID lookup. Original participants, binding creator and room identity
remain explicit before group adoption. Typed mapping validates every row, finite
limits, deterministic ordering and exact ownership; malformed or partial acquisition
cannot advance a scope. Peer offboarding and selected HR company do not erase original
owned metadata. No schema, DI graph, public provider route or device admission changed.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/3681619df313d6e27d4d276e1e841bc7e01c0637/docs/collaboration/space-original-room-inventory.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/3681619df313d6e27d4d276e1e841bc7e01c0637/docs/measurements/collaboration-space-original-room-inventory-2026-11-04.json).

All 137 tests pass in 139.193 s: 127 domain and ten PostgreSQL/repository
cases. Format/architecture checks pass on 8,451 unchanged inputs. Real V130
fixtures cover multiple rooms/two companies, both original participants, spoofed actors,
wrong namespace, unbound rooms, limits/order, HR selection, peer offboarding and parent
purge. Typed malformed/duplicate/partial readback, invalid requests, acquisition failures,
cancellation and interruption are covered. All sessions closed.

NEXT: merge separately proven original and published group read metadata under current
completed LEGACY owner admission. Exact overlaps must preserve original company, room,
conversation, members and actual creator; never fabricate group lease/publication rows.
A peer may have different original/current aliases in different rooms. Preserve queued
original to-device keys before sync advances and prove actual SDK peer-key lookup and
decryption. Historical read scope does not grant new sends or OTK claims to obsolete
devices. Then group sync cursors/filter/projection, history fallback, metadata/push,
full Mobile SDK adoption and native hard-parent recovery with original stores/keys.

Base `b8e84bba` is pushed. Group rollout remains disabled; all 54 PRD acceptance groups
remain open. Live `8b40` / schema116 (push on, SMTP off), normal phone release28, Mobile
product/hosted `0b804cb2` and Starter `9959c1d` remain unchanged. Continue the long run,
updates, atomic pushes and validated product installs.


## Organization owned-room sync scope accepted — 4 November 2026

Backend [b8e84bba](https://github.com/fajarxfce/hris-platform/commit/b8e84bba57f767e80f45a5b17f8b6dc1a3408126) is pushed.

Existing V131 storage now provides bounded participant-owned room inventory without
joining current peer eligibility. Typed mapping verifies bounds, ordering, ownership
and original metadata. A distinct pure DIRECT scope preserves original Matrix users,
actual creator and LEGACY provenance, rejects duplicate/foreign/future or conflicting
metadata and produces a deterministic digest. A 10,001-row overfetch rejects capacity
above 10,000 without returning a truncated scope. No schema or DI graph changed.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/b8e84bba57f767e80f45a5b17f8b6dc1a3408126/docs/collaboration/space-room-sync-scope.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/b8e84bba57f767e80f45a5b17f8b6dc1a3408126/docs/measurements/collaboration-space-room-sync-scope-2026-11-04.json).

All 130 tests pass in 78.157 s: 127 domain (five new scope cases)
and three expanded PostgreSQL/repository cases. Format/architecture checks pass on
8,451 unchanged inputs. Actual RLS covers root participants, wrong account/
company/space/server, unbound rooms and offboarded peers. Typed acquisition covers
bounds/order/duplicates, cancellation and interruption. Existing exact lookup passes.
All sessions closed. Applied scope drafts are stale; committed source is authoritative.

NEXT: combine separately proven original owned-room inventory for completed LEGACY
owners or establish complete original-room adoption BEFORE provider sync advances.
Otherwise filtering only newly bound group rooms could omit queued original to-device
keys. V129/V130 retain original room/creator metadata; do not fabricate group binding,
lease or publication fields. This scope includes published group bindings only.
Then implement distinct group sync cursor/filter/projection with current admission,
metadata/push, retained-history fallback, full Mobile SDK adoption and native hard-parent
recovery. Preserve original store/passphrase/keys/history and uncertain sends.

Base `96e5053b` is pushed. Group rollout remains disabled and all 54 PRD acceptance
groups remain open. Live `8b40` / schema116 (push on, SMTP off), normal phone release28,
Mobile product/hosted `0b804cb2` and Starter `9959c1d` remain unchanged. Continue the long
run, updates, atomic pushes and validated product installs.


## Current group room-history access accepted — 4 November 2026

Backend [96e5053b](https://github.com/fajarxfce/hris-platform/commit/96e5053b8693410895664c8cf8d1a5b47e8249b5) is pushed.

Messages/context/event routes now admit the current group native/device/gateway and
exact owned room. Completed LEGACY proof precedes original cursor-owner construction.
Reads retain owned history after peer offboarding and require no new own-key query;
new-message sending keeps its separate confirmed receiving-device requirement.
Provider I/O stays outside SQL and current authority/owned metadata are rechecked
around both outcomes. Original finite deadline and exact cleanup remain unchanged.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/96e5053b8693410895664c8cf8d1a5b47e8249b5/docs/collaboration/space-room-history-access.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/96e5053b8693410895664c8cf8d1a5b47e8249b5/docs/measurements/collaboration-space-room-history-access-2026-11-04.json).

Initial domain/runtime gate: 137 passed out of 138 (218.055 s),
including all 122 domain and eight room-message cases with actual Synapse history.
Only a deadline fixture failed. The corrected focused case passes
(42.329 s); both fixture failures and the existing
group error contract are recorded. No production source changed after the initial
gate. Retained-device 32 passes (267.985 s), actual API
143 passes (224.739 s), and full worker
covers 138 cases (393.873 s; 137 passed, one existing optional skip). Quality checks
pass; final retained/worker use 8,448 identical unchanged inputs. API source
differs only in the later retained-device test setup correction: both send and history
assertions now reuse one gateway bootstrap. Its initial 32-test run had one failure
(246.231 s); evidence retains that failure and the correction.
All sessions closed.

Actual Synapse retains original message/context/event reads after restart and peer
offboarding. Completed LEGACY tests carry an old company cursor through group
continuation and a terminal page in the original room, while new SPACE rooms retain
old Matrix identities. HTTP tests cover distinct employees, original encrypted content,
strict bounds/credentials, cursor replay, unrelated rooms and late native revocation.
Opaque fixtures remain transport/storage evidence, not valid SDK Megolm decryption.

NEXT: group sync, metadata and push, then full Mobile SDK adoption and native hard-parent
recovery with original SDK store/passphrase/keys/history/uncertain sends. Group sync
needs its own room-set/filter/cursor semantics; do not blindly reuse a room-history
bridge or substitute a group UUID for a company. Before advancing provider sync,
include separately proven original owned rooms or require complete original-room
adoption: filtering only newly bound group rooms could discard pending original
to-device keys while advancing the cursor. V129/V130 retain original inventory and
actual creator metadata; do not fabricate group bindings for those rows. The current
history routes admit published group bindings only. All applied history-admission drafts
are stale; committed source is authoritative. Shared DI changes require actual API
and full worker gates.

Base `39e85812`, Main `5e1237bf` and Mobile `7f7ef335` are pushed. Group rollout remains
disabled and all 54 PRD acceptance groups remain open. Live `8b40` / schema 116 (push
on, SMTP off), normal phone release 28, Mobile product/hosted `0b804cb2` and Starter
`9959c1d` remain unchanged. Continue the long run, updates, atomic pushes and validated
product feature installs.


## Group room-history protocol accepted — 4 November 2026

Backend [39e85812](https://github.com/fajarxfce/hris-platform/commit/39e8581252b8eecabfd87314bbb7742fe3ec2ca6) is pushed.

Distinct group owner/cursor contracts retain original Matrix user/device/room IDs.
New cursors use a separate group purpose; only an exact LEGACY room/device tuple can
bridge the original company history cursor. Shared company/group projection preserves
bounded pagination, event framing and stable exhaustion. Missing crypto keys and
cancellation retain their classification without fallback or SDK store deletion.
[Protocol](https://github.com/fajarxfce/hris-platform/blob/39e8581252b8eecabfd87314bbb7742fe3ec2ca6/docs/collaboration/space-room-history-protocol.md), [evidence](https://github.com/fajarxfce/hris-platform/blob/39e8581252b8eecabfd87314bbb7742fe3ec2ca6/docs/measurements/collaboration-space-room-history-protocol-2026-11-04.json).

All 139 tests pass in 76.378 s (122 domain, 17 provider/cursor/mapping),
with format/architecture checks on 8,446 unchanged inputs. Actual Synapse
covers SPACE and original LEGACY pagination through restart, no duplicate event IDs,
original content/users/devices/creator, context and event reads. The LEGACY case begins
with an actual old company cursor. Existing company protocol regressions pass.
No DI graph, schema or public group history dispatch changed. All sessions closed.

NEXT: wire fixed group messages/context/event reads under current caller admission
and immutable owned room checks around every provider outcome. The admitted device
must prove completed LEGACY transfer before constructing bridge metadata. Owned
history does not require a currently receiving peer. Keep caller guards and current
provider/gateway/request bounds; shared DI changes require actual API and full worker.
Then finish group sync/metadata/push and full Mobile SDK adoption with original store,
passphrase, keys, history, uncertain sends and native hard-parent recovery. Group sync
cursor/filter migration is separate from this room-history bridge. Applied history
protocol drafts are stale; committed source is authoritative. No valid SDK Megolm
decryption or product rollout is claimed by opaque transport fixtures.

Base `6b9e5d22`, Main `653ae30c` and Mobile `6ebc8677` are pushed. Group rollout remains
disabled and all 54 PRD acceptance groups remain open. Live `8b40` / schema 116 (push
on, SMTP off), normal phone release 28, Mobile product/hosted `0b804cb2` and Starter
`9959c1d` remain unchanged. Continue the long run, updates, atomic pushes and validated
product feature installs.

## Group encrypted room-message send accepted — 4 November 2026

Backend [6b9e5d22](https://github.com/fajarxfce/hris-platform/commit/6b9e5d228b4ad7312d26804aee4fe7239ccb397b) is pushed.
The [fixed group room-message route](https://github.com/fajarxfce/hris-platform/blob/6b9e5d228b4ad7312d26804aee4fe7239ccb397b/docs/collaboration/space-room-messages.md)
uses exact immutable owned rooms, confirmed current recipients and original signed
sender identity. Original Matrix room/device/ciphertext/session/transaction IDs stay
intact. Provider I/O is outside SQL and current admission is checked around either
outcome. Offline short receiving credentials work; offboarding blocks new sends while
owned room metadata remains for separately authorized history.

[Evidence](https://github.com/fajarxfce/hris-platform/blob/6b9e5d228b4ad7312d26804aee4fe7239ccb397b/docs/measurements/collaboration-space-room-messages-2026-11-04.json):
125 policy/repository tests passed (75.939 s), room gateway
8 passed (146.529 s), retained-device 32 passed
(260.625 s), actual API 141 passed
(220.003 s), and full worker 138 passed
(397.325 s; one existing optional skip). All 38 prior
self/key/to-device regressions passed in the initial gateway run. Initial compile,
room and HTTP fixture failures plus their corrections are recorded. Production source
did not change after policy acceptance; only the HTTP fixture differs between final
API/worker and the earlier passed room/retained gates. Quality checks pass.

Actual Synapse returns one room event ID through retry and restart, with one original
ciphertext/session/sender event in peer history. Completed LEGACY devices preserve
original keys and HRIS_ identities in both old and new cross-company rooms. These are
transport/storage proofs, not SDK Megolm decryption or delivered/read receipts. Late
response denial cannot undo remote acceptance.

NEXT: group history/sync/metadata/push and full Mobile SDK adoption with original
store/passphrase/keys/history/uncertain sends and native hard-parent recovery. See the
[backend handoff](https://github.com/fajarxfce/hris-platform/blob/6b9e5d228b4ad7312d26804aee4fe7239ccb397b/docs/agent-handoff.md). History reads must retain owned room scope
independently of peer receiving eligibility. Distinct group owner/cursor types and
an exact admitted LEGACY bridge are required; never clear the SDK store to bypass
recovery. Group rollout remains disabled and all 54 PRD acceptance groups remain open.
Live `8b40` / schema 116 (push on, SMTP off), normal phone release 28, Mobile product/
hosted `0b804cb2` and Starter `9959c1d` remain unchanged. Continue the long run, updates,
atomic pushes and validated product feature installs.

## Group encrypted to-device transport accepted — 4 November 2026

Backend [302a43ac](https://github.com/fajarxfce/hris-platform/commit/302a43ac6392ec652e4fa5f7f9b329ac6c3843eb) is pushed.
The [fixed group route](https://github.com/fajarxfce/hris-platform/blob/302a43ac6392ec652e4fa5f7f9b329ac6c3843eb/docs/collaboration/space-to-device.md) preserves original
transaction IDs, device IDs, ciphertext and message IDs. Every recipient must be
confirmed and current. Detached signed sender/recipient identities must match both
envelope curve keys; native/gateway/request access and pinned observations are checked
around every provider outcome. Short receiving credentials can expire without blocking
an otherwise eligible offline device. Whole-request rejection prevents partial sends.

[Evidence](https://github.com/fajarxfce/hris-platform/blob/302a43ac6392ec652e4fa5f7f9b329ac6c3843eb/docs/measurements/collaboration-space-to-device-2026-11-04.json):
124 policy tests passed (48.001 s), 38 gateway tests passed
(487.809 s), 32 retained-device tests passed
(281.174 s), 139 actual API tests passed
(202.139 s), and the full worker suite passed (138 tests,
387.685 s; one existing optional skip). Quality checks pass.
Final retained/API/worker gates share 8,431 identical unchanged inputs. The initial API
run had 13 HTTP timeouts during observed host memory/I/O pressure; the full retry
passed without changing source or timeouts. Both outcomes are recorded.

Actual Synapse retains an unread original-device frame through LEGACY completion and
restart. In-process retries deduplicate; restart may replay the same to-device frame.
Preserve SDK uncertain sends and duplicate handling. Opaque transport evidence does
not prove valid Olm decryption or delivered/read receipts, and late denial cannot undo
prior remote acceptance.

NEXT: group room content/sync/push and full Mobile SDK adoption with original
store/passphrase/keys/history and native hard-parent recovery. See the
[backend handoff](https://github.com/fajarxfce/hris-platform/blob/302a43ac6392ec652e4fa5f7f9b329ac6c3843eb/docs/agent-handoff.md). Room-content drafts are unapplied; existing
V131 ownership can support bounded exact room lookup without inventing a migration.
Group rollout stays disabled and all 54 PRD acceptance groups remain open. Live
`8b40` / schema 116 (push on, SMTP off), normal phone release 28, Mobile product/hosted
`0b804cb2` and Starter `9959c1d` remain unchanged. Continue the long run, regular updates,
atomic pushes and validated product feature installs.

## Scoped recipient query and claim accepted — 4 November 2026

Backend [dd789d0f](https://github.com/fajarxfce/hris-platform/commit/dd789d0f8b7c0da9b018137edf84f7a32d0a3a15) is pushed.
[Fixed group key query and claim](https://github.com/fajarxfce/hris-platform/blob/dd789d0f8b7c0da9b018137edf84f7a32d0a3a15/docs/collaboration/space-recipient-key-exchange.md)
preserve original Matrix identities and enforce current caller, recipient and request
admission around every provider outcome. Queries include pending publication; claims
verify confirmed signed identities before consuming one-time or fallback keys. Current
native hard authority permits offline receiving despite short access/provider expiry.
Changed observations return HTTP 503 for retry. Company key policy remains intact.

[Evidence](https://github.com/fajarxfce/hris-platform/blob/dd789d0f8b7c0da9b018137edf84f7a32d0a3a15/docs/measurements/collaboration-space-recipient-key-exchange-2026-11-04.json):
121 policy tests passed (44.187 s), 29 gateway tests passed (384.642 s), 32 retained
device tests passed (263.747 s), 137 actual API tests passed (203.967 s), and the full
worker suite passed (138 tests, 386.225 s; one existing optional skip). Quality checks
pass. Final API/worker gates share 8,429 identical unchanged inputs. Two initial HTTP
test setup failures were corrected without production changes and are recorded.
Cross-company PostgreSQL, offline receiving, races, original LEGACY IDs, actual
Synapse key exchange/restart, cancellation and strict HTTP behavior are covered.

NEXT: group to-device transport, room content/sync/push and full Mobile SDK adoption,
including original store/passphrase/keys/history and native hard-parent replacement.
Preserve original transaction IDs; provider restart can replay a to-device envelope,
so SDK uncertain-send and duplicate handling remain required. See the
[backend handoff](https://github.com/fajarxfce/hris-platform/blob/dd789d0f8b7c0da9b018137edf84f7a32d0a3a15/docs/agent-handoff.md). To-device drafts are unapplied.
Group rollout stays disabled and all 54 PRD acceptance groups remain open. Live
`8b40` / schema 116 (push on, SMTP off), normal phone release 28, Mobile product and
hosted checks `0b804cb2`, and Starter `9959c1d` remain unchanged. Continue the long run,
regular updates, atomic pushes and validated product feature installs.

## Scoped offline receiving keys accepted — 4 November 2026

Backend [6fd2819f](https://github.com/fajarxfce/hris-platform/commit/6fd2819ff8a2f9b228e47616a4e2868bd3615f15) is pushed.
[V135 protocol](https://github.com/fajarxfce/hris-platform/blob/6fd2819ff8a2f9b228e47616a4e2868bd3615f15/docs/collaboration/space-key-recipients.md) supplies a minimal
read-only public-key projection for current eligible members and exact verified room
pairs. Short native access/provider expiry permits offline receiving; native hard
expiry, revocation or purge removes eligibility without deleting original provenance.
Completed LEGACY devices retain their exact original Matrix IDs and fingerprint.

[Evidence](https://github.com/fajarxfce/hris-platform/blob/6fd2819ff8a2f9b228e47616a4e2868bd3615f15/docs/measurements/collaboration-space-key-recipients-2026-11-04.json):
full database115 PASS1091.125s; focused13 PASS118.536s and quality checks. Both gates
have 8,423 unchanged inputs; only two recovery tools differ. Typed7 carries on
identical source; recovery19 passes at schema135/unknown136. Ordinary-role scope and
column privileges, exact cross-company room binding, pending keys, actual LEGACY
completion, offline expiry, purge/restore, bounds and cancellation are exercised.
No shared DI change; previous API96/FULLworker138 remain the last graph acceptance.

NEXT fixed group key query/claim, to-device/content/sync/push and full Mobile adoption,
including original SDK store/passphrase/keys/history, uncertain sends and native
hard-parent replacement. Query/claim drafts remain uncompiled/unapplied; see the
[current backend handoff](https://github.com/fajarxfce/hris-platform/blob/6fd2819ff8a2f9b228e47616a4e2868bd3615f15/docs/agent-handoff.md). Actual API/FULLworker validation
is required after DI. This storage checkpoint is not an SDK/product acceptance.
Group disabled; all54 PRD groups open. Live8b40/schema116 pushON/SMTPOFF, normal phone28,
Mobileproduct/hosted0b804cb2 and Starter9959c1d unchanged. Continue long run, updates,
atomic pushes and validated product feature APK installs only.

## Admitted room worker accepted — 4 November 2026

Backend [3284aaf7](https://github.com/fajarxfce/hris-platform/commit/3284aaf7135c3f51df8c8fa72bfcd11bc01e6e35) is pushed.
The [room worker](https://github.com/fajarxfce/hris-platform/blob/3284aaf7135c3f51df8c8fa72bfcd11bc01e6e35/docs/collaboration/space-room-worker.md)
reconciles one immutable SPACE alias or retained LEGACY room with the current
participant's own credentials. Actual creator and publisher remain distinct;
current native/device/provider/peer/request admission surrounds provider outcomes.
Binding, successful head and journal commit atomically. Bounded workers have no
waiting executor queue; deadlines, physical capacity after cancellation and owned
shutdown preserve finite operation. The new room-worker flag defaults to false.

[Evidence](https://github.com/fajarxfce/hris-platform/blob/3284aaf7135c3f51df8c8fa72bfcd11bc01e6e35/docs/measurements/collaboration-space-room-worker-2026-11-04.json):
108 domain plus initial 11 PostgreSQL cases pass in 155.778 seconds; final 43
PostgreSQL/Synapse/retained cases pass in 394.504 seconds. Full worker suite has
137 passed/one existing optional skip in 402.151 seconds; actual API 96 passes in
149.641 seconds. Quality checks pass with 8,413 identical unchanged final API/worker
inputs. The scheduled test's wrong cleanup table name is corrected; its initial
failure and exact source continuity are recorded. All test sessions are closed.
Actual scheduled native/room workers connect two fictional companies to one encrypted
Synapse room; both native views/replays preserve it. Retained-room/current-peer
recovery and original signed device keys survive the exercised provider restart.

NEXT recipient projection/query/claim/to-device, content/sync/push and full Mobile
adoption. Preserve offline receiving, original SDK namespace/passphrase/keys/history,
uncertain sends and native hard-parent recovery. The ignored V135 SQL and five-case
recipient test drafts are uncompiled/unapplied; LEGACY/bounded typed mapping coverage
remains. They are not accepted implementation. Current source and exact continuation:
[backend handoff](https://github.com/fajarxfce/hris-platform/blob/3284aaf7135c3f51df8c8fa72bfcd11bc01e6e35/docs/agent-handoff.md).
No cross-company SDK conversation or product APK is claimed by this worker checkpoint.
Group disabled; all 54 PRD groups open. Live 8b40/schema 116 push ON/SMTP OFF, phone 28,
Mobile product/hosted 0b804cb2 and Starter 9959c1d unchanged. Continue the long run,
updates, atomic pushes and validated product feature installs only.

## Bounded room-control capacity accepted — 4 November 2026

Backend [12020f6b](https://github.com/fajarxfce/hris-platform/commit/12020f6b) is pushed.
V134 ROOM_PREPARATION shares ordinary slots 1–7 with gateway requests, uses current
own native/device coordinates without a gateway, and preserves renewal slot zero.
Expired purpose changes retain the owner and require a new token; exact release and
post-lock deadline checks prevent stale work affecting a successor. [Evidence](https://github.com/fajarxfce/hris-platform/blob/12020f6b/docs/measurements/collaboration-space-room-request-slots-2026-11-04.json):
all 109 database cases pass in 1012.632 seconds; focused six database/eight typed
cases pass in 236.801 seconds, with quality checks and 8,400 identical unchanged
inputs. Recovery 19 passes, maximum schema 134/unknown 135. Upgrade, mixed concurrent
capacity, exact cleanup after purge, malformed readback/cancellation rollback, actual
archive restore and lock-wait expiry pass. Initial unnamed-constraint selection
error is fixed; the original lifetime constraint is preserved. All sessions closed.

NEXT six worker-review drafts (claim/advance/settle/outcome policy, three pure tests,
eleven actual-PG cases) are formatted/parser-checked but UNAPPLIED/UNCOMPILED. Apply
only after this checkpoint. Actual-PG draft uses typed JCE provider rotation and an
explicit worker-capability fixture; do not claim ordinary RLS from it. More real
retained LEGACY/Synapse coverage, scheduler and actual API/FULLworker DI gates remain.
Then recipient keys/content/sync/push, offline keys/native hard-parent replacement
and full Mobile adoption preserving SDK store/passphrase/keys/history. Group disabled;
all 54 PRD groups open. Live8b40/schema116 push ON/SMTP OFF, phone28, Mobileproduct/
hosted0b804cb2 and Starter9959c1d unchanged. Continue long run, updates, atomic pushes
and validated product feature installs only.

## Provider peer recovery accepted — 4 November 2026

Backend [5493a8e1](https://github.com/fajarxfce/hris-platform/commit/5493a8e1) is pushed.
Typed join requires exact room readback; recovered SPACE state retains its actual
canonical creator and requires the current caller to be joined. Only the actual
creator can repair missing initial state. [Evidence](https://github.com/fajarxfce/hris-platform/blob/5493a8e1/docs/measurements/collaboration-space-peer-provider-2026-11-04.json):
105 domain, five repository and four actual Synapse cases pass in 83.55 seconds with
quality checks on 8,398 unchanged inputs. First-creator/second-recoverer lost-response
recovery keeps the same alias, actual creator and both original signed keys after
restart. All sessions closed; no new DI, room worker or SDK-message claim.

NEXT explicit room-preparation request kind sharing V127 slots 1..7. Current GATEWAY
kind requires real gateway coordinates; slot zero is reserved for provider renewal.
Do not fabricate either. Preserve same-owner expired replacement, new token/exact
release, admission/RLS and finite cleanup; then bounded claim/advance/settle worker.
Recipient keys/content/sync/push and full Mobile adoption preserving SDK store and
history remain unfinished. Group disabled, all 54 PRD groups open. Live 8b40/schema
116 push ON/SMTP OFF, phone 28, product/hosted 0b804cb2 and Starter 9959c1d unchanged.
Continue long run, regular updates, atomic pushes and validated product APK installs.

## Peer room recovery and native API accepted — 4 November 2026

Backend [a0653dcf](https://github.com/fajarxfce/hris-platform/commit/a0653dcf) is pushed.
V133 gives each exact participant a private preparation head while allowing only one
active request per conversation. Terminal recovery uses the caller's own proof and
the observed public account plus revision. The immutable binding records the actual
canonical Matrix creator separately from requester and publisher; LEGACY remains
pinned to its original room and creator. Native GET/POST room setup/status is wired.

[Evidence](https://github.com/fajarxfce/hris-platform/blob/a0653dcf/docs/measurements/collaboration-space-room-peer-recovery-2026-11-04.json):
full database 103 and actual API 96 cases pass in 1120.114 seconds; full worker suite
has 127 passed and one existing opt-in skip in 375.998 seconds, with 8,397 identical
unchanged inputs and passing quality checks. Retained publication 31 cases pass in
265.182 seconds. Earlier domain 103 and typed/native 5 each pass with recorded source
continuity. Recovery 19 passes, maximum schema 133 and unknown 134 rejected. Initial
compile/fixture failures are resolved. All test sessions closed.

NEXT provider same-alias recovery using the peer's own credentials, strict actual
creator/state verification and bounded room worker. Provider/pure-policy/join drafts
remain unapplied; HTTP/DI drafts are now applied and stale. Then recipient keys,
content/sync/push, offline keys/native hard-parent replacement and full Mobile adoption
preserving SDK namespace/passphrase, keys/history and uncertain sends. Shared DI
changes require actual API and full worker context checks. Group remains disabled;
all 54 PRD groups remain open. Live 8b40/schema 116 push ON/SMTP OFF, normal phone 28,
Mobile product/hosted 0b804cb2 and Starter 9959c1d unchanged. Continue long run, regular
updates, atomic pushes and validated product feature APK installs only.

## Native room admission checkpoint — 4 November 2026

Backend [95353885](https://github.com/fajarxfce/hris-platform/commit/95353885) is pushed.
Current root native/group/client/device/provider admission now wraps room request and
safe status. Intent, private request, receipt and journal are atomic. Original pair
guards precede account/native/device guards. Actual retained-device transfers and
opposite completed requesters keep original identities and actual room creator.
[Evidence](https://github.com/fajarxfce/hris-platform/blob/95353885/docs/measurements/collaboration-native-space-room-2026-11-04.json):
data41 PASS316.012s with format/architecture on8,393 unchanged inputs; domain103 pass
in an earlier mixed run with all production/domain inputs verified identical. Initial
compile, fixture-clock and replay-assertion failures are resolved and recorded.
All test sessions closed. No HTTP/shared DI/room worker or product caller yet.

NEXT fix peer recovery: V131 first-requester retry strands an uncertain room while
that requester is offline. The other current participant must recover the SAME alias
using its own private proof. Preserve actual canonical SPACE creator or exact LEGACY
creator; never expose/reuse another native session. Per-participant bounded queues
are under review, not accepted. Then actual native HTTP/FULL-worker DI, bounded room
worker, recipient keys/content/sync/push and full Mobile adoption preserving original
SDK namespace/passphrase, keys/history and uncertain sends. Offline keys/hard-parent
replacement remain required. Native source/test drafts are APPLIED/STALE; HTTP/DTO/DI
remain unapplied. Group disabled/all54 groups open. Live8b40/schema116 pushON/SMTPOFF,
normal phone28, product/hosted0b804cb2 and Starter9959c1d unchanged. Continue long run,
updates, atomic pushes and validated PRODUCT feature installs only. StarterPR2 is a
stale Draft targeting the old foundation branch; all commits are already in main.

## Original/group history fence accepted — 4 November 2026

Backend [08827578](https://github.com/fajarxfce/hris-platform/commit/08827578) is pushed.
V132 reserves one immutable unique route for same-original-company canonical pairs,
closing the late-original conversation race. Both inserts arbitrate the same key;
rollback frees it and conflicting preexisting histories abort upgrade. Existing
original bound adoption keeps its LEGACY route. [Evidence](https://github.com/fajarxfce/hris-platform/blob/08827578/docs/measurements/collaboration-original-pair-routing-2026-11-04.json):
focused5 PASS99.972s, full database98/API25 PASS967.86s plus format/architecture on
8,387 unchanged inputs; recovery19 passes (MAX132/unknown133). Both winner orders,
commit/rollback, fixed snapshots, RLS, native purge and actual restore pass. Initial
backfill/DDL ordering failure is resolved. After the gate only one redundant trailing
blank line was removed; both SQL hashes are recorded. No new DI/endpoint/worker claim.

Current backend WIP now applies native room policy, request/status and safe view,
plus shared original pair guard and pure/actual PostgreSQL tests. First gate running;
no native room acceptance yet. Group-room-lifecycle/native-room draft files are now
APPLIED/STALE; use actual source. More retained original-device and opposite initialized
requester coverage, actual HTTP/FULL-worker DI, bounded room worker reconciliation,
recipient keys/content/sync/push and full Mobile adoption remain required. Preserve
SDK namespace/passphrase, keys, history and uncertain sends. Group disabled/all54
PRD groups open. Product/hosted0b804cb2, phone28, live8b40/schema116 pushON/SMTPOFF and
Starter9959c1d unchanged. Continue long run, updates, atomic pushes and validated
PRODUCT feature installs only.

## Organization room storage accepted — 4 November 2026

Backend [4320caca](https://github.com/fajarxfce/hris-platform/commit/4320caca) is pushed.
V131 and strict typed storage preserve immutable canonical/original room intent,
first requester versus actual creator, bounded queue and peer-safe status. Exact
binding and request success commit together; bad readback and cancellation roll back
actual PostgreSQL writes. [Contract](https://github.com/fajarxfce/hris-platform/blob/4320caca/docs/collaboration/space-room-lifecycle.md)
and [evidence](https://github.com/fajarxfce/hris-platform/blob/4320caca/docs/measurements/collaboration-space-room-storage-2026-11-04.json):
all93 database cases PASS797.756s, five typed cases PASS106.634s, format/architecture
and19 recovery cases pass. Shared8,376 inputs match; final typed run adds nine files.
Initial compilation errors are resolved and recorded. All test sessions closed.
No shared DI, room use case/endpoint/worker adapter or Mobile product caller changed.

NEXT shared original/group creation fence. Snapshot-only absence cannot prevent a
later old conversation; immutable old conversations precede every old room producer.
Use an exact unique persistent pair route and test both insert orders, rollback,
repeatable-read concurrency and scoped metadata. Then admitted native room lifecycle,
bounded worker provider reconciliation, recipient keys/content/sync/push and Mobile
preflight/stage/freeze/completion/adoption. Preserve original SDK namespace/passphrase,
keys, history and uncertain sends. Offline receiving and native hard-parent replacement
remain required. Group disabled/all54 groups open. Product/hosted0b804cb2, normal phone28,
live8b40/schema116 pushON/SMTPOFF and Starter9959c1d unchanged. Continue long run, updates,
atomic pushes and validated PRODUCT feature installs only.

## Original room peer verification accepted — 4 November 2026

Backend [6415de9c](https://github.com/fajarxfce/hris-platform/commit/6415de9c) adds exact
original-pair lookup and strict original canonical-user/creator/state verification.
[Evidence](https://github.com/fajarxfce/hris-platform/blob/6415de9c/docs/measurements/collaboration-space-legacy-room-policy-2026-11-04.json):
99 domain/8 PostgreSQL/3 actual Synapse cases PASS 144.409s with quality checks and
8,374 unchanged inputs. The original peer joins with its own retained user/device;
room identity, actual creator and signed keys survive restart. No key replacement,
creator impersonation, new group alias, provider logout or SDK-message claim. Backend
push succeeded. Main 1ee06e79 records this checkpoint and the next storage increment.

Current uncommitted backend V131 room lifecycle storage is under test. Initial four
PostgreSQL race/expiry/atomic publication/restore cases passed 94.602s after fixing
a nullable test helper; additional original-room and bounded retry cases are pending.
No V131 acceptance, native/worker room command, group product caller or new APK yet.
Continue backend request/publication authority and original-producer fencing, then
recipient keys/content/sync/push and Mobile preflight/stage/freeze/completion/adoption.
Preserve original SDK namespace/passphrase, keys, history and uncertain sends. Offline
receiving and native hard-parent replacement remain explicit work. Group disabled;
all 54 groups open. Product/hosted 0b804cb2, normal phone 28, live 8b40/schema 116 push
ON/SMTP OFF and Starter 9959c1d unchanged. Continue long run, updates, immediate atomic
pushes and validated product feature installs only.

## Original room creator metadata accepted — 4 November 2026

Backend [6c22caba](https://github.com/fajarxfce/hris-platform/commit/6c22caba) adds V130
and exposes room.createdBy from the original binding, independently of conversation
creator. [Evidence](https://github.com/fajarxfce/hris-platform/blob/6c22caba/docs/measurements/collaboration-original-room-creator-2026-11-04.json):
database 4/data 12/API 97 PASS 363.765s with quality checks and 8,372 unchanged inputs.
Actual upgrade, archive restore, parent purge, malformed tuples and both participants
through HTTP pass. One stale recovery future-schema sentinel was corrected afterward;
all 19 cases pass in 0.011s. Original encryption projection and source rooms unchanged.
Main peer 2ab63fd8 records this; backend push succeeded after transient GitHub failures.

NEXT group room preparation/adoption with actual original creator, exact canonical
members, current admission and bounded uncertainty recovery. Exact original-pair
lookup and old-room peer join/key-retention/restart follow-up is currently being tested
in the group worktree; do not treat it as accepted yet. Then recipient key/content/
sync/push and full Mobile adoption preserving original SDK namespace/passphrase, keys,
history and uncertain sends. Other legacy producers, unbound old rooms, offline keys
and native hard-parent replacement still require integration. Group disabled/all 54
groups open. No Mobile code or APK changed; product/hosted 0b804cb2, normal phone 28,
live 8b40/schema 116 push ON/SMTP OFF and Starter 9959c1d unchanged. Continue long run,
frequent updates, atomic pushes and validated product feature installs only.

## Group room provider accepted — 4 November 2026

Backend [0a6ad2b2](https://github.com/fajarxfce/hris-platform/commit/0a6ad2b2) adds explicit
canonical users, separate group room aliases/markers and strict provider state checks.
[Evidence](https://github.com/fajarxfce/hris-platform/blob/0a6ad2b2/docs/measurements/collaboration-space-room-provider-2026-11-04.json):
96 domain cases passed; six final data cases passed in 50.407s with quality checks.
Actual Synapse covers retained cross-company identities/keys, first peer login/join,
restart, lost create responses and foreign-member rejection. Four initial failures
were corrected test fixtures only. This is room protocol evidence, not SDK messaging.
The first push failed with GitHub 500; HTTPS retry through existing gh authentication
succeeded. Main peer bc24e5e2 records the implementation and next work.

NEXT group room request/queue/publication and original room adoption. V129 inventory
must first expose the original room binding account_id: conversation.createdBy can be
a different participant and must never stand in for the actual Matrix room creator.
Keep original company/conversation/room/user coordinates explicit; verify canonical
participants and current authority before immutable publication. Generic background
jobs remain company-scoped. Then recipient keys/content/sync/push and full Mobile
preflight/stage/freeze/completion/adoption preserving SDK namespace/passphrase, keys,
history and uncertain sends. Offline receiving and hard-parent replacement remain open.
Group disabled; no Mobile caller or APK. Product/hosted 0b804cb2, normal phone 28,
live 8b40/schema 116 push ON/SMTP OFF and Starter 9959c1d unchanged. All 54 groups remain
open. Continue long run, updates, atomic pushes and validated feature installs only.

## Native group conversation API accepted — 4 November 2026

Backend [b478f776](https://github.com/fajarxfce/hris-platform/commit/b478f776) now exposes
native group open/list/detail and original-company metadata inventory with current
caller/peer authority and atomic receipts/journal. [Evidence](https://github.com/fajarxfce/hris-platform/blob/b478f776/docs/measurements/collaboration-space-conversation-native-2026-11-04.json):
data5/API97 PASS222.896s, full worker128 PASS348.067s (127 active/one existing
retention skip), 8,364 identical inputs and quality checks. Opposite company employees
converge on one intent; replay, revoked peer, native expiry, cancellation/rollback,
finite cursors and native transport pass. Main peer1fb7fe77 records acceptance.

NEXT group Matrix room provisioning/adoption and canonical participant verification
before recipient keys/content/sync/push and Mobile runtime adoption. This metadata API
does not grant room content or imply a deliverable message. Preserve original SDK
namespace/passphrase, keys, local history and uncertain sends through full preflight/
stage/freeze/completion/adoption. Offline keys cannot require short native/provider
tokens; hard-parent replacement remains explicit recovery. Group disabled, no product
caller/APK or rollout. Product/hosted0b804cb2, normal phone28/live8b40/schema116 push ON/
SMTP OFF and Starter9959c1d unchanged; all54 groups remain open. Continue long run,
updates, atomic pushes and validated product feature installs only.

## Group conversation storage accepted — 4 November 2026

Backend [4dd2fa96](https://github.com/fajarxfce/hris-platform/commit/4dd2fa96) retains
separate immutable group pairs, public current members and owned original-company
conversation/room inventory. [Evidence](https://github.com/fajarxfce/hris-platform/blob/4dd2fa96/docs/measurements/collaboration-space-conversation-storage-2026-11-04.json):
86 database plus6 typed PostgreSQL cases PASS838.663s on 8,360 unchanged inputs,
format/architecture and19 recovery cases. Opposite openers converge; failed readback,
cancellation and non-progressing pages fail safely. Original bound rooms and restricted
RLS/views survive parent purge and actual archive restoration. Main d4df8767 records it.

NEXT backend native group conversation endpoints with actual API/FULL worker checks,
then explicit room adoption/provisioning, recipient keys/content/push and Mobile group
preflight/stage/freeze/completion/adoption. Keep original SDK namespace/passphrase,
keys, history and uncertain sends. Metadata is not provider room authority; offline
receiving must not need short native/provider tokens, and hard-parent replacement
still requires explicit recovery. Group disabled, no Mobile caller or new APK.
Product/hosted0b804cb2, normal phone28/live8b40/schema116 push ON/SMTP OFF and
Starter9959c1d unchanged. All54 groups stay open. Continue long run, updates, atomic
pushes and validated product feature installs; never proof APKs or unchanged28.

## Signed group key upload accepted — 4 November 2026

Backend [a65a8170](https://github.com/fajarxfce/hris-platform/commit/a65a8170) now admits
fixed group own-key upload with immutable input/reservation, signed continuity and
current exact request authority before acknowledgement. Counts do not acknowledge
pending keys; lost responses preserve recovery, and retained keys survive restart.
[Evidence](https://github.com/fajarxfce/hris-platform/blob/a65a8170/docs/measurements/collaboration-space-key-exchange-2026-11-04.json):
focused18 PASS268.626s, data20/API132 PASS455.786s and full worker128 PASS343.980s
(one existing retention skip), 8,351 identical final inputs and quality gates. Strict
HTTP, same-identity races, caller mutation, late revocation, generation changes and
actual group/retained Synapse restart pass. Main peer e090e7e3 records acceptance.

NEXT backend group room/member/original-room authority before recipient key query/
claim/to-device and content, then Mobile group preflight/stage/freeze/completion/atomic
adoption. Preserve original SDK namespace/passphrase, history and uncertain sends.
Group recipients need version0 pre-ack confirmation semantics and offline receiving
independent of short native/provider credentials. Hard-parent replacement remains
explicit recovery work. Group availability remains disabled; no Mobile caller or APK.
Product code/hosted evidence0b804cb2, phone28/live8b40/schema116 push ON/SMTP OFF and
Starter9959c1d unchanged. All54 groups stay open. Continue full long run, frequent
updates, immediate pushes and validated product feature installs only.

## Group public-key storage accepted — 4 November 2026

Backend [88f5fc20](https://github.com/fajarxfce/hris-platform/commit/88f5fc20) retains
immutable group public-key identity with exact original native/Matrix provenance,
uncertain reservation recovery and one acknowledgement transition. Current write
scope and completed retained fingerprints are enforced. [Evidence](https://github.com/fajarxfce/hris-platform/blob/88f5fc20/docs/measurements/collaboration-space-key-storage-2026-11-04.json):
82 database plus 6 data/retained-provider cases PASS 792.520s on 8,350 unchanged inputs,
format/architecture and 18 recovery cases. A missing isolated fixture table-write grant
was corrected after the first focused gate; production code stayed unchanged. Actual
concurrency, expiry after a row wait, rollback, archive restore and signed retained
Synapse keys across restart pass. Main peer fe0f3c5f records acceptance.

NEXT backend signed key upload under exact gateway/request ownership, recipient
pre-ack/room authority, then Mobile atomic group adoption preserving original SDK
namespace/passphrase, local history and uncertain sends. Offline receiving must not
require the receiver's short native/provider credentials. Native hard-parent replacement
still needs explicit recovery. Storage enables no group endpoint or Mobile caller.
Product code/hosted proof remain0b804cb2; phone28/live8b40/schema116 push ON/SMTP OFF
and Starter9959c1d unchanged. All 54 groups remain open. Continue full long run,
frequent updates, immediate pushes and validated feature installs; no proof APKs.

## Group gateway self access accepted — 4 November 2026

Backend [941fa2f5](https://github.com/fajarxfce/hris-platform/commit/941fa2f5) admits fixed
identity/capabilities with current group/native/device/provider ownership and bounded
request slots. Exact lease release and current admission preserve retained identity
across restart, cancellation, competing requests and revocation during I/O.
[Evidence](https://github.com/fajarxfce/hris-platform/blob/941fa2f5/docs/measurements/collaboration-space-gateway-access-2026-11-04.json):
focused 7 PASS 114.736s, data 9/API 130 PASS 331.029s, full worker 128 PASS 347.237s
(127 active/one existing retention skip), 8,340 unchanged inputs and quality checks.
Native HTTP expiry/renewal/bootstrap/self access and actual retained Synapse keys
remain covered. Main peer 9b4fa5ca records this acceptance.

NEXT backend immutable key reservation/publication, recipient and room/member authority,
then Mobile preflight/stage/freeze/completion/atomic adoption preserving original SDK
namespaces, passphrases, history and uncertain sends across all local projections.
Offline recipient key availability must not require a live short native/provider token;
native hard-parent replacement still needs explicit recovery. Group remains disabled
with no product caller or new APK. Product code/hosted evidence remain 0b804cb2; phone
28/live 8b40/schema 116 push ON/SMTP OFF and Starter 9959c1d unchanged. All 54 groups remain
open. Continue full long run, frequent updates, immediate pushes and validated product
feature installation only; never install proof APKs on the user phone.

## Native provider renewal and group maintenance accepted — 4 November 2026

Backend [b2dee963](https://github.com/fajarxfce/hris-platform/commit/b2dee963) now recovers
expired provider heads through a native versioned/idempotent command, slot0, current
admission and retained signed-key continuity. Provider generation/receipt/journal
writes are atomic. Native HTTP returns only deviceId/providerGeneration. Group replay
and request cleanup share the owned worker timer with bounded atomic batches.
[Evidence](https://github.com/fajarxfce/hris-platform/blob/b2dee963/docs/measurements/collaboration-space-provider-renewal-2026-11-04.json):
focused8 PASS150.027s (actual retained provider/restart35.204s); final coverage domain93,
data41, API94, full worker128 (one existing retention skip). Final data6/API94 passes
226.232s and worker352.928s on identical8,338 inputs with quality gates. Preliminary
test-only corrections and reproduction commands are recorded. Main peer c8d65e82.

NEXT current group gateway exchange and key/room/member authority before Mobile
preflight/stage/freeze/completion/atomic bootstrap adoption. Mobile recovery must
orchestrate native refresh, explicit provider renewal, then status/bootstrap/refresh;
expired replay alone never deletes owned history/keys. Preserve original SDK namespaces,
passphrases and uncertain sends through history/outbox/drafts/read/push/deep links.
No group caller, product APK or live rollout is enabled yet. Product code/hosted proof
remain0b804cb2; phone28/live8b40/schema116 pushON/SMTPOFF and Starter9959c1d unchanged.
All54 groups remain open. Continue full long run, frequent updates, atomic immediate
pushes and validated product feature installs; never install proof APKs on the phone.

## Group request ownership accepted — 4 November 2026

Backend [88d20851](https://github.com/fajarxfce/hris-platform/commit/88d20851) adds V127
and typed storage for seven ordinary group gateway requests plus a reserved provider
renewal slot. Exact release and expired cleanup preserve successors and device/key
provenance. [Evidence](https://github.com/fajarxfce/hris-platform/blob/88d20851/docs/measurements/collaboration-space-request-storage-2026-11-04.json):
focused6 PASS92.446s, then all76 database plus data5 PASS679.944s on8,333 unchanged
inputs; format/architecture and17 recovery cases pass. One test-only nullability
compile correction is recorded. Main peer ef586d83 records the accepted storage.

Next explicit native provider renewal, current gateway exchange and request/replay
maintenance, followed by rooms and Mobile group handover/adoption. Storage grants
no current authority and enables no new caller. Preserve original SDK namespaces,
keys, local history and uncertain sends. Gateway replay expiry alone never deletes
owned local facts. Product code/hosted acceptance remain0b804cb2, phone release28,
live8b40/schema116 pushON/SMTPOFF and Starter9959c1d unchanged. All54 groups remain
open; continue the full long run, updates, atomic pushes and validated feature installs.

## Native group gateway transport accepted — 4 November 2026

Backend [272b66b5](https://github.com/fajarxfce/hris-platform/commit/272b66b5) now returns
original Matrix identity atomically with native group bootstrap credentials, exposes
safe gateway revision/deadline status and dispatches the distinct group refresh prefix.
[Evidence](https://github.com/fajarxfce/hris-platform/blob/272b66b5/docs/measurements/collaboration-space-gateway-http-2026-11-04.json):
93 domain/33 actual data pass;92 API/OpenAPI PASS158.550s; full worker127 PASS356.150s
(126 active/one existing retention skip),8,324 final inputs unchanged and quality checks.
Real elapsed replay/cooldown recovery and both refresh families pass. Preliminary
failures and test-only fixes are retained. Main peer277aa0fe records this acceptance.

Next backend bounded group requests, native provider renewal after expiry, replay
maintenance and room authority; then Mobile preflight/handover/group ownership across
history/outbox/drafts/read/push/deep links. Gateway-replay-specific401 does not revoke
the native parent or authorize deleting local history/keys; reacquire status before
fresh bootstrap. Keep original SDK namespaces and uncertain sends. No group Mobile
caller, deployment or new product APK is enabled. Product code/hosted acceptance stays
0b804cb2, phone28/live8b40/schema116 and Starter9959c1d unchanged. All54 groups remain
open; continue long run, updates, atomic immediate pushes and validated feature installs.

## Group gateway issuance accepted — 4 November 2026

Group backend [6b92246a](https://github.com/fajarxfce/hris-platform/commit/6b92246a)
adds current native/group/device/provider admission and atomic bootstrap/refresh,
including exact completed retained-device provenance, bounded lost-response replay
and rollback after late denial or cancellation. Final broader gate passes 154 cases
in 343.118s on 8,321 unchanged inputs; focused 14 passes in 167.690s. Main peer
`d4e2a771` records this acceptance. See the backend issuance measurement for evidence.
No HTTP/DI or Mobile group caller is enabled by this increment.

Next expose an atomic bootstrap identity envelope, safe gateway revision/deadline
status and native bootstrap/standard Matrix refresh dispatch; verify API and full
worker contexts. Then complete provider/room authority and Mobile group adoption
while preserving original SDK namespaces and keys. All 54 communications groups
remain open. Mobile product code/hosted acceptance remain `0b804cb2`; phone release
28, live 8b40/schema 116 and Starter 9959c1d unchanged. Continue long run, frequent
updates, immediate pushes and validated product feature installation only.

## Hosted handover regression accepted — 4 November 2026

All three hosted workflows on exact Mobile `0b804cb2` passed and their artifacts,
GitHub archive digests and 3,881 source hashes are verified. [Evidence](measurements/collaboration-runtime-handover-hosted-2026-11-04.json):
control41775492328 has 3,118 portable and 1,219 Apple-simulator tests, Android builds/lint,
application bindings and Xcode host compilation. New durable-record cases run on
desktop/browser/Apple. Native-HRIS41775492365 has 5 actual API/PostgreSQL/Synapse/emulator
scenarios, 241 Android-host and 18 proof-host cases, plus notification process-recovery
and SystemUI reply callbacks. All three notification images were visually inspected.
Protocol41775492415 passed both sync modes (59.527s native, 89.279s classic), including
offline invitee E2EE, refresh key continuity and browser restart/media; 2 browser cases
per mode, with owned cleanup. These are regressions; the new local stage/resume proof
remains the separately accepted 67.337s local run.

Actual Apple Matrix/APNs/CallKit, group runtime/room adoption and all 54 communications
acceptance groups remain open. Next finish group gateway admission/room authority and
Mobile provider-key preflight/handover orchestration without changing original SDK
namespaces. Group V126 typed gateway storage is pushed as [338915f5](https://github.com/fajarxfce/hris-platform/commit/338915f5): 19 focused and 181 broader cases pass on identical inputs, with 16 recovery-tool checks. Main peer 2a0b9d74 records both accepted increments. Normal phone release 28/live 8b40/schema 116 pushON/
SMTPOFF and Starter 9959c1d unchanged. Continue long run, updates and atomic pushed
commits; install only a validated new product feature build on the phone.

## Durable Android SDK intent accepted locally — 4 November 2026

Mobile now implements owned handover intent read/stage, immutable schema2 target,
exact observed-client pinning, bounded registry exclusion and complete SDK shutdown
before an exclusive protected write. Legacy restore/install/session conversion and
credential updates reject staged records. Expired credentials and local login
replacement do not hide retained intent. No product caller or server freeze is enabled.
Read [the contract](collaboration-runtime-handover.md).

[Evidence](measurements/collaboration-runtime-handover-2026-11-04.json): final root/
Android/proof gate PASS160.404s on3880 unchanged inputs:4044 desktop,241 Android-host,
18 proof and10 build-logic cases (18 fresh on final run;1958 fresh on the preceding
run). Browser463 PASS98.262s. Format/architecture/Detekt and proof assembly/lint pass.
Initial focused725 PASS151.499s preceded exact-client pinning. One ignored-draft
format failure and one omitted older proof-interface adapter are recorded and fixed;
no failed test assertion. Only the proof adapter changed after the1958-case run.

Actual encrypted Android SDK/Keystore stage and separate-process recovery PASS67.337s.
Wrong fingerprint, foreground/background exclusion, borrower interruption, write
failure, cancellation before/after commit, same-target replay, foreign-target denial,
legacy restore/install rejection and original key preservation pass. Fixture-only raw
SDK reopening verifies retained files; it does not adopt a product group runtime.
Proof app cleared; owned emulator3731489/serial5562 stopped. No phone interaction.
APK is debug-only,293169624 bytes, SHA db8c856d0c69ba21861071cca9a127ba0306a78c903b0bb1da6010e7308f2861;
never install it on the user phone. See measurement for exact bytes and report hashes.

Group backend is pushed as c9bf7762:137 domain/API/OpenAPI PASS188.105s and full
worker/context127 PASS377.895s (126 active/one existing retention skip), same8299
inputs. Main peer805c181f records that acceptance. Group discovery/contact/identity/
device/retry/freeze/drain is native-only and disabled by default. No live rollout.

NEXT group gateway and room authority; provider publication/readback preflight before
local staging/server freeze; full Mobile group ownership for the original SDK store,
inbox/outbox/drafts/receipts/history/push/deep links and cross-company end-to-end proof.
The local key observation is not provider publication or an access grant. Do not
substitute a space ID into an old company namespace. Actual old-APK downgrade has not
been tested. Hosted control/native/protocol checks will run on this code push; collect
their results and exact source hashes. All54 groups and actual Apple runtime stay open.
Normal phone28/live8b40/schema116 pushON/SMTP OFF and Starter9959c1d unchanged.
Continue full authorized long run, frequent updates, atomic commits and immediate pushes.
Ignored .work/runtime-handover-2026-11-04 drafts are superseded by tracked sources.

## Completed native recovery accepted; group HTTP continues — 4 November 2026

Group branch is pushed through `631a2d3d`. Current native registration/retry/freeze/
drain now validate exact immutable completion for an ACTIVE retained target. Original
identity/key/retirement and successful queue provenance remain pinned; current native/
group/client/MFA admission is mandatory. Ordinary native refresh can recover receipts
without a new device, queue request or freeze. Fresh retry of successful work stays
denied and drain observes actual retained server slots.

[Evidence](https://github.com/fajarxfce/hris-platform/blob/631a2d3d/docs/measurements/collaboration-completed-native-replay-2026-11-04.json):
27 actual PostgreSQL cases PASS124.865s, API47 PASS168.496s, full worker127
PASS370.67s (126 active/one existing opt-in retention skip), same8291 unchanged inputs
and quality checks. Initial expired-enqueue fixture failure and its test-only fix
are recorded. No group HTTP/Mobile caller or deployment is enabled by this commit.

Next group HTTP discovery/contacts/selection/device/status/retry/freeze/drain and
actual HTTP/OpenAPI/API/worker validation, then rooms/gateway and durable Mobile
handover. Mobile record/persistence review has started without source changes:
closeOtherOwners retires only present entries, so a durable exact-target gate must
also prevent future foreground/background/process-restart SDK acquisition and drain
pending reservations. Confirm old key publication/readback before permanent freeze;
keep the original encrypted store namespace/passphrase and uncertain sends.

Normal phone28, live8b40/schema116 pushON/SMTP OFF, Mobile3d85d569/hosted gates and
Starter9959c1d remain current. All54 groups and actual Apple runtime remain open.
Continue full long run, frequent updates, atomic Git handoffs and immediate pushes.

## Retained worker activation accepted; completed native replay continues — 4 November 2026

Group branch is pushed through `5b66e661`. Retained activation now observes current
native/group/client/MFA proof, exact source/key/gateway metadata, frozen target and
zero live old slots; it reads signed provider keys outside SQL and atomically retires
the original device/provider/gateway with group publication and journal. Busy work
uses bounded existing retry/deadline budgets. The old SPACE path remains covered.

[Evidence](https://github.com/fajarxfce/hris-platform/blob/5b66e661/docs/measurements/collaboration-retained-worker-activation-2026-11-04.json):
focused23 PASS133.163s, API47 PASS156.651s, full worker127 PASS365.38s (126 active and
one existing opt-in retention skip), same8290 unchanged inputs and quality checks.
Actual PostgreSQL verifies competing workers, every write-boundary rollback and
current/late revocation; actual Synapse readback/restart retains user/device/keys.
Three failed initial runs and their fixture-only fixes are recorded. Owned resources
are closed. No new phone/live deployment or product HTTP/Mobile caller is enabled.

Current group WIP implements explicit completed native registration/retry/freeze/drain
replay with a pure continuity policy and actual regression cases; follow its current
handoff/gates. Then durable Mobile key-publication preflight, future SDK-acquisition
gate and exact original store/inbox/outbox/read/push ownership, plus group HTTP/rooms/
gateway and cross-company end-to-end acceptance. Public fingerprint alone is not
private-key proof or confirmation of provider publication.

Normal phone28, live8b40/schema116 pushON/SMTP OFF, Mobile3d85d569/hosted gates and
Starter9959c1d remain current. All54 groups and actual Apple runtime remain open.
Continue full long run, frequent updates, atomic Git handoffs and immediate pushes.

## Owned gateway metadata accepted; retained activation underway — 4 November 2026

Group branch is pushed through `612d8279`. V125 exposes exact owned original gateway
metadata through a restricted non-login view; no hashes/replay/token columns. The
typed port distinguishes absent, wrong-parent and retired heads. Current admission
still governs use; the metadata increment has no product caller.

[Evidence](https://github.com/fajarxfce/hris-platform/blob/612d8279/docs/measurements/collaboration-legacy-gateway-metadata-2026-11-04.json):
18 focused cases PASS186.803s;151 broad database/domain/storage/native cases
PASS728.592s, same8290 unchanged inputs, format/architecture PASS. Actual role
isolation, retirement/parent purge/archive restore, strict mapping and failure/
cancellation/interruption pass. Recovery125/future126 rejection passes15 tests.
No failed run; all owned resources closed.

Current group WIP applies admitted retained-worker activation, provider key readback,
atomic old/new publication, bounded busy retry, DI and actual database/Synapse tests.
It is unaccepted; follow the group worktree's handoff and gates. Next explicit
completed native replay and durable Mobile SDK/store/inbox/outbox/read/push ownership.
Normal phone28, live8b40/schema116 pushON/SMTP OFF, Mobile3d85d569/hosted gates and
Starter9959c1d remain current. All54 groups and actual Apple runtime remain open.
Continue full long run, frequent user updates, atomic Git evidence and immediate pushes.

## Typed completion port accepted; gateway observation continues — 4 November 2026

Group branch is pushed through `60b58d3d`, on accepted V124 `a22816d0`. The typed
completion repository validates exact owned mapping and successor versions after
the atomic retirement function. Missing/foreign/unreadable readback rolls the caller
transaction back; interruption skips another SQL read and preserves its flag.

[Evidence](https://github.com/fajarxfce/hris-platform/blob/60b58d3d/docs/measurements/collaboration-legacy-runtime-completion-ports-2026-11-04.json):
12 mapping/actual storage cases PASS111.923s with8280 unchanged inputs and passing
format/architecture. Actual wrong-owner/stale-version, nullable gateway, readback
failure rollback and API capability denial pass. Earlier compilation errors and
Java jOOQ/nullable-version fixes are recorded. No DI/worker/client caller yet.

Current group WIP is V125 narrow owned original-gateway metadata plus typed read
port, needed to observe its exact version before completion without exposing hashes
or encrypted replay. Follow that worktree's handoff for active checks. Then continue
admitted retained worker using existing provider key readback, completed replay and
durable Mobile runtime/store/inbox/outbox/read/push ownership. Live8b40/schema116,
normal phone28, Mobile code3d85d569/hosted gates and Starter9959c1d remain current.
All54 groups and actual Apple runtime remain open. Continue full long run, frequent
user updates, Git evidence and immediate pushes.

## V124 atomic retained completion accepted — 4 November 2026

Group branch `feat/communication-space` is pushed at `a22816d0`. V124 retains an
immutable completion mapping and a restricted worker-only function that retires the
original device/provider/optional gateway in the same transaction as group
credential/ACTIVE device/SUCCEEDED queue publication. Current proof, exact versions
and zero live original slots are checked with DB time after blocking guards.
PUBLIC/API execution is denied; the non-login owner cannot read credential secrets.

[Evidence](https://github.com/fajarxfce/hris-platform/blob/a22816d0/docs/measurements/collaboration-legacy-runtime-completion-2026-11-04.json):
six actual migration cases PASS128.582s/8273 unchanged inputs;133 broad database,
domain/storage/native cases PASS603.054s/8275 inputs;15 recovery-tool cases pass.
Format/architecture pass. Tests cover caller rollback/cancellation/interruption,
actual lease and blocked native expiry, immutable owned mapping after parent purge
and archive restore. No failed run. Recovery now supports124.

Completion repository work is now uncommitted in the group worktree; follow its
current handoff and source checks. V124 has no application/worker/HTTP/client caller
yet. Synthetic database key/credential metadata is not provider or SDK acceptance.
Next typed port, admitted retained worker with existing exact key readback and
completed replay, then durable Mobile gate/store/inbox/outbox/read/push handover.
Mobile code3d85d569/hosted gates, normal phone28, live8b40/schema116 pushON/SMTP OFF
and Starter9959c1d remain current. All54 groups and actual Apple runtime remain open.
Continue the long run, user updates, Git evidence and immediate commit pushes.

## Native freeze and drain accepted — 4 November 2026

Group branch `feat/communication-space` is pushed through `9d3c8a99`, following
`57b2bee3` and `53a86f48`. Current native freeze atomically records one exact retained
target with receipt/journal and preserves replay through refresh. Drain reads at
most eight original server slots under current admission, reports active count and
latest expiry, and never mutates runtime/queue state. No DI/HTTP is exposed yet.

[Freeze](https://github.com/fajarxfce/hris-platform/blob/57b2bee3/docs/measurements/collaboration-legacy-runtime-freeze-2026-11-04.json)
passes18 cases/64.356s/8268 unchanged inputs; [drain](https://github.com/fajarxfce/hris-platform/blob/9d3c8a99/docs/measurements/collaboration-legacy-runtime-drain-2026-11-04.json)
passes23/69.9s/8271, both with format/architecture. Actual database cases cover root
initialization, competing calls, every write-stage rollback, current/late denial,
cancellation/interruption, real lease expiry and concurrent cleanup. All owned
resources are closed. No failure in either first run.

Next atomic group publication/old device-provider-gateway retirement and immutable
completed mapping, then durable Mobile SDK/store/inbox/outbox/read/push handover.
Zero server slots is not SDK shutdown or remote rollback. Existing Matrix key
repository already supports exact device readback and pure signature/fingerprint
validation; reuse it. Group handoff/transfer contract contain the current review.
Mobile code3d85d569 and hosted gates, normal phone28, live8b40/schema116 pushON/
SMTP OFF and Starter9959c1d remain current. All54 communications groups and actual
Apple runtime remain open. Continue the full long run, frequent updates, immediate
commit pushes and installation of validated product builds.

## All legacy runtime consumers fenced — 4 November 2026

Group implementation in `/home/fajar/hris/.worktrees/communication-space` is now
accepted and pushed at `53a86f48`. Bootstrap/refresh retain original runtime guards;
provisioning/direct-room workers hold bounded slots around provider I/O and reject
late results after a freeze. Together with `0bf751e5`, all fixed legacy consumers
are fenced. Current admission, cancellation/interruption and original keys remain
protected. Gateway refresh requires no provider slot.

[Evidence](https://github.com/fajarxfce/hris-platform/blob/53a86f48/docs/measurements/collaboration-legacy-runtime-consumers-2026-11-04.json):
19 focused, 166 full native data, 47 API, 126 active worker plus one existing opt-in
skip, and five actual Android SDK cases pass. All five runs use identical 8264-input
snapshots and pass format/architecture. Android took 1035.238s; the three recovery
states each retain 176 originals, with SDK receipt and automatic WorkManager
recovery. All owned fixtures and the exact emulator are stopped. Earlier failures
and corrected fixture clocks remain recorded; production deadlines are unchanged.

Next: admitted native freeze/replay and bounded drain status, then atomic retained
group publication/old credential retirement and completed-mapping replay continuity.
Formatted uncompiled drafts are in the group worktree's ignored runtime-freeze-next;
actual database tests remain. Existing V121 already reserves terminal device revision
1000000 for revocation; no new boundary migration is needed. Mobile needs durable
SDK gating, original namespace/passphrase retention and coherent room/read/push
handover. No public group transfer or new product build is enabled by this increment.

Mobile implementation `3d85d569` and all three hosted gates remain accepted. Normal
phone release 28, live `8b40`/schema 116 with push ON and SMTP OFF, and Starter
`9959c1d` stay current. Actual Apple Matrix/APNs/CallKit and all 54 communications
groups remain open. Continue the authorized long run, frequent user updates, Git
handoffs, immediate push of each commit and installation of validated product builds.

## Local SDK source hosted control and native gates accepted — 4 November 2026

Source 3d85d569 now passes all three hosted gates. Control41741297181: portable
3114 and Apple1217 tests, all zero failures/errors/skips, plus full Xcode iOS host
build. Artifact digests and 3877 source hashes verified. See
[control evidence](measurements/collaboration-local-sdk-control-2026-11-04.json).
Native41741297183: 232 Android-host,18 proof-host and5 actual native cases pass;
three worker states retain176 original messages each, SDK/WorkManager and
notification/reply recovery pass with owned cleanup. Artifact13283198269 SHA
32b77e9048af2929c4a9b832c7efb66e33c6de3d0f091262ed07f4723f40896a,
3877 inputs plus pinned workflow verified. Three SystemUI images were viewed and
retained. See [native evidence](measurements/collaboration-local-sdk-native-2026-11-04.json).
Raw protocol41741297195 is separately accepted below. No hosted job remains pending.

These gates validate the local-identity increment and existing runtime behavior;
retained group ownership transfer, actual Apple Matrix/APNs/CallKit and all54
communications acceptance groups remain open. Phone28 stays installed; never
install a debug fixture APK. Live8b40/schema116 pushON/SMTP OFF and Starter9959c1d
are unchanged. No reusable Starter fix is introduced by the HRIS identity protocol.

Platform group branch feat/communication-space now has accepted/pushed gateway
runtime fencing at0bf751e5. It claims one original-runtime slot, checks current
admission and exact runtime/lease around provider I/O/publication, and preserves
keys/winning credentials through freeze, cancellation and cleanup failures. Full
native data160/API47/full worker126 active plus one existing skip/current SDK5 pass.
The five current-source Android cases passed1047.184s with all three176-message
worker recovery states and owned emulator cleanup. Earlier setup/verifier failures
are recorded; the post-driver ciphertext verifier now obtains fresh same-identity
credentials through ordinary HTTP without extending production token lifetimes.
[Gateway evidence](https://github.com/fajarxfce/hris-platform/blob/0bf751e5/docs/measurements/collaboration-legacy-runtime-gateway-2026-11-04.json).

Next group work: fence bootstrap/refresh and provisioning/direct-room workers,
then admitted atomic legacy retirement/group publication and Mobile durable SDK
drain/alias/room/push/read handover. Six-file drafts and18 focused tests are ready
for review/application in that worktree; they are not accepted yet. No public group
transfer, new product APK or Starter port comes from this server increment. See
the group's handoff for current checks. Continue the full long run, frequent user
updates, Git evidence and immediate push of every atomic commit.

## Local SDK increment: hosted raw protocol regression accepted — 3 November 2026

Mobile source 3d85d569 is pushed. Protocol CI41741297195 passed both native and
classic sync modes against pinned Platform e0dbb567. Downloaded artifact digests
and all 14 provider-driver source hashes match. Each mode passes two browser/native
scenarios, with no failure/flaky/skip; native72.855s, classic64.882s, owned cleanup
true. Sliding-sync requests are17 and0 as expected. Actual encrypted Android/web
messages, offline-invitee first-message decryption, SDK token renewal preserving
keys, encrypted pending IDs and browser restart/media regression pass.
[Evidence](measurements/collaboration-local-sdk-protocol-2026-11-03.json) records
both artifacts and their distinct fixture APK hashes. These are raw provider SDK
regressions, not group HRIS transfer or a product phone build. The new local identity
stage/resume proof is separately accepted below.

Control/Apple41741297181 and actual-HRIS native41741297183 remain running. Their
retargeted audit scripts are prepared under .work/local-sdk-identity-2026-11-03/
hosted (control) and hosted/native (fetch/audit); do not run acceptance until jobs
finish. Source fingerprints use check3/sources.json from the local acceptance.
The native audit expects232 Android-host,18 proof-host and5 native cases; visually
inspect its three SystemUI PNGs after downloading. No audit process is left running.

Platform group work continues at e905b8d6 with UNCOMMITTED V123 runtime fences in
/home/fajar/hris/.worktrees/communication-space. Focused five actual database cases
pass; full database/storage regression is running there. Do not edit its checked
core/modules/tool inputs. Its raw recovery-tool future-schema test needs correction
from123 to124 after that run; the current source is not accepted yet. Untested
runtime-port drafts are isolated in its ignored runtime-ports-next directory.
Live8b40/schema116 pushON, phone28 and Starter9959c1d are unchanged. Continue the full
long run, frequent user/Git updates and push every atomic commit.

## Local SDK identity continuity accepted — 3 November 2026

AndroidCollaborationSdkClientSource now reads public ed25519/curve25519 identity
from an owned opened client and closes its Encryption handle. The common mapper
returns user/device plus the exact HRIS canonical fingerprint, with bounded IDs,
strict 32-byte keys and canonical padded/unpadded base64. There is no product caller,
private-key export, store alias, group gateway credential change or migration yet.
See [contract](collaboration-local-identity.md) and
[evidence](measurements/collaboration-local-sdk-identity-2026-11-03.json).

Root check3 PASS 156.1s with 18 fresh proof cases and 3877 unchanged checked hashes.
Required reused reports: 4042 desktop/572 suites, 232 Android-host/38, 10 build-logic/2;
root format/detekt/architecture/distribution/Python checks pass. Check2 had 2727 fresh
passing cases before a fixture compilation error; only LocalSdkIdentityProbe changed
before the final build. Browser 461/54 PASS 99.15s, including the two new mapper cases.
Proof APK 291101880 bytes SHA 0ccc4c12e654e6a9cc4f113a7f33b995fad4bc547fac3c109e83262c1704af36,
lint 0 errors / 47 warnings. Earlier check1 stopped 44.671s on Detekt method length;
check2 stopped 572.074s on a leftover exception catch. Both are recorded, not accepted.

Actual SDK 26.10.23 emulator proof PASS 74.319s (boot 30.427s). Local stage, explicit
process stop/restart, same-store key continuity, different empty-store identity,
wrong-passphrase rejection, correct reopen after failure and exclusive file-lease
release pass. No reachable provider is used. Owned files are removed and exact
emulator PID 1433824/transport stopped. All sessions/checks are complete. Scripts and
safe JSON remain under .work/local-sdk-identity-2026-11-03. This is clean-close/store
continuity, not an interrupted migration or server/private-key possession proof.
Do not install the debug proof APK on the phone. Normal release 28 remains installed.

Next explicit retained runtime/SDK ownership transfer and group gateway/rooms/
inbox/push/read migration. Platform source is /home/fajar/hris/.worktrees/
communication-space at e905b8d6 (preceded by fc1a6c40). V117–122 and opt-in group
native/worker graph are accepted: 14 API/worker cases, 126 active full worker plus 1
opt-in retention skip, actual scheduled Synapse activation, and 26 native status
cases. Group flags stay default false; public runtime and retained-device activation
remain unaccepted. Read its handoff and ignored runtime-transfer-next.md. Preserve
original company/native store hashes, keys and pending transaction IDs; never logout
a losing Matrix candidate. Actual Apple SDK continuity remains pending.

Live 8b40/schema116 pushON/SMTP OFF, phone 28 and Starter 9959c1d remain current. This
HRIS-specific identity convention requires no Starter port. All 54 communications
acceptance groups remain open. Continue the full long run, frequent user/Git updates
and push every atomic commit. Older V119-running notes below are historical.

## Release 28 source control/Apple gate also accepted — 3 November 2026

Control41701412602 at8273ae8d now passes both jobs. Downloaded digest-verified
portable13264263598 and Apple13267227537 reports contain3110 and1215 cases, all with
zero failures/errors/skips;432 source hashes verified. Full iOS host build passes.
[Evidence](measurements/chat-empty-history-control-2026-11-03.json) retains exact
artifact hashes, suite counts and job/step timing. Native41701412608 is separately
accepted below. This completes matching hosted gates for the already-installed
normal release28; no new build or reinstall is needed. Actual Apple Matrix runtime,
APNs/CallKit and the remaining collaboration requirements remain open.

Continue the Platform group migration; its V119 full database check is running in
/home/fajar/hris/.worktrees/communication-space. Mobile source is unchanged. Main
live8b40/schema116 pushON and all prior phone28 evidence remain current. Keep the
long run, frequent user updates and every commit pushed.


## Release 28 source native gate accepted; group migration continues — 3 November 2026

Native run41701412608 at8273ae8d passed. Downloaded artifact13266761084 has verified
SHA3bb2406976bc4e15e34ffae6a313e1d0629f2afa8d0f6a659890ea2cde0d3b86;
5 actual native cases,230 Android host and18 proof host pass with0 failures/errors/
skips and432 source hashes checked. All three worker scenarios retain176 originals
in11 incoming passes/reopens; SDK and WorkManager acceptance pass. Raw16 notification
groups,4 runtime cases,3 input-recovery process deaths and5 actual application actions
pass with cleanup. All3 final SystemUI PNGs were viewed and retained.
[Evidence](measurements/chat-empty-history-native-2026-11-03.json) distinguishes the
proof APK from actual product FCM and physical-phone acceptance. Normal release28
is already installed; do not reinstall. Control41701412602 portable passed, Apple is
still running. Its retargeted audit is prepared under.work/chat-empty-history-control-2026-11-03.

Platform main1545dba5 records live8b40/schema116 push activation: all3 chat push flags
ON, SMTP OFF, coordinated restore and TLS ingress/data-preservation checks passed.
No real-user message or phone notification was sent during rollout. The first
switch rolled back on an IP collision; static API/worker IPs and provider-first
startup passed. See Platform's private-push-operations runbook before any deployment.

Group migration is separate in /home/fajar/hris/.worktrees/communication-space,
branch feat/communication-space atba6adf0a. V117 directory, bounded native contact
policy, V118 owned legacy metadata and compound inventory paging are tested and
pushed; no group endpoint/device/room grant or Mobile ownership change is wired yet.
V119 immutable provider identity selection is WIP there: focused PostgreSQL3 cases
PASS91.61s,3599 unchanged source hashes; full core database check now runs. Do not
edit its fingerprinted inputs. Preserve existing SDK stores, original provider
identities and history; never substitute spaceId into the old company/native-parent
storage owner. All remaining communications work continues.


## Release28 installed; complete c770 native/Apple evidence — 3 November2026

[Phone28](measurements/chat-empty-history-phone-2026-11-03.json) is installed and
pullback-verified on10.77.77.3:5555. Source8273ae8d, normal minified devRelease1.0.0,
code28;516.789s build, lint0,88,058,600bytes, SHA
806e23a770b52f71218e614d8a87af161af322c92d5852b0311929378d655374.
Same development signer;16KiB alignment/Firebase dev config/private API/no fixture
endpoints/18 compiled backup exclusions verified. Owned emulator boot28.391s,
two cold starts stable12s each/no crash;61.242s smoke, both PNGs viewed, PID3811836
stopped/transport absent. Physical install19:27:03.203491UTC takes46.348s including
pullback; appId10962 and firstInstall2026-10-29 15:48:23 retained. Device lastUpdate
2026-11-04 02:27:21. No uninstall/data clear/UI launch. DO NOT reinstall28.

[c770 native41691878601](measurements/chat-history-local-reuse-hosted-2026-11-03.json)
now PASS:5 actual API/PostgreSQL/Synapse/Android cases,230 Android host/18 proof host,
zero failures/errors/skips,432 verified source hashes. Artifact13263452546 SHA
306f78dcd4fe371deec24365ca7d0f694b069b02c16847b2a7018cfe28cf1d99.
All three worker draft/cancellation/queued scenarios now pass alongside SDK and
WorkManager. Raw SystemUI notification/editor/retirement PNGs inspected and tracked.
This raw proof is separate from actual product FCM run16; native CI pins Platform304.
The failed older537 andbd399 artifacts remain rejected as previously documented.

[c770 control41691878650](measurements/chat-history-local-reuse-control-2026-11-03.json)
also PASS:3109 reported portable cases/316 suites,1215 Apple simulator cases/124,
no failures/errors/skips; pager and locale suites present in both.432 source hashes,
full Xcode997s. This does not implement/accept actual Apple Matrix/APNs/CallKit.
New8273 UI native41701412608/control41701412602 have started/queued; inspect status.
No repeated c770 proof or phone reinstall is needed without a new failure/change.

Platform898d0cf1 records all14 accepted8b40 CI jobs and exact packages. Accepted
images now staged (API08df88ff, worker276c545e), existing build/libs/services preserved.
Main .work/chat-push-rollout-2026-11-03/checkpoint.py is RUNNING session57292 after
maintenance notice. It stops known HRIS writers/provider, creates coordinated HRIS
and nonempty Matrix checkpoints, restores them in isolated containers, and restarts
source services in its cleanup. Check result/health promptly; do not start another
local heavy job. Recovery uses an immutable8b40 tool/config snapshot plus the exact
accepted API JAR under recovery-verifier; the prior local API JAR differed and was
preserved. deploy.py has NOT run; flags remain OFF until confirmed activation.
After checkpoint PASS, run reviewed deploy/denial/health checks and record result.

The communication-space worktree has pushed5debc632 plus seven uncommitted bounded
repository/datasource/entity/test files; formatted/architecture-checked, no Gradle
run yet. run-data.py is prepared for3 focused tests; execute after rollout/cleanup.
No DI/API/group runtime migration yet. Keep schema117 out of the current116 release.
Continue full PRD, push every commit and keep user updates; no stop at this milestone.

## Empty-history presentation checked; release28 preparation — 3 November2026

The thread now distinguishes no locally saved messages from confirmed empty history.
One centered localized Load messages action replaces the misleading first-message
prompt plus Older messages pair. Populated history actions are centered; loading,
retry/incomplete notices and composition remain usable. No runtime paging/cursor or
native ownership behavior changed. [Evidence](measurements/chat-empty-history-layout-2026-11-03.json):
297 fresh tests (164 desktop presentation,128 browser presentation,5 localization),
138.925s,784 unchanged input fingerprints; format/detekt/architecture74 PASS.
Four PNGs actually inspected and retained in docs/images/chat-empty-history.
Two rejected test-fixture attempts are documented; final test scrolls the actual
lazy list for older controls and checks normal/200percent text without hover styling
in the empty-state baseline.

Phone still27; DO NOT reinstall27. Release28 scripts prepared and syntax checked
under .work/release-phone-2026-11-03-build28, but no build/verification/emulator/
preflight/install has run. After this commit/push, build from a clean tree; validate
artifact/lint, owned emulator cold starts and screenshots, then preflight installed27,
notify user and install-only28. Keep one heavy local job and preserve app data/signature.

Platform main checkout remains8b40bd0f: all8 backend and4 dashboard shards plus
recovery integration passed; full Verify41695437208 still runs dashboard integration.
Backend artifact audit:3161 API cases/6 opt-in skipped,117 worker/1 opt-in skipped;
no failures/errors. Actual Matrix/FCM opt-in evidence is separate, as below. All
chat push flags remain OFF pending final CI, staged images and fresh coordinated
checkpoint/restore. Main .work/chat-push-rollout-2026-11-03 contains prepared scripts.

Platform group branch feat/communication-space (isolated worktree of that name)
pushed5debc632: V117 stable directory and restricted role,33 database tests plus12
recovery-tool cases. Bounded directory repository/query/tests are WIP there, not yet
run or wired to DI/public API. Keep schema117 separate from the116 push rollout.
Current c770 native41691878601 and control41691878650 still run; control portable
passed and Apple builds Xcode. New UI commits may queue later proofs; do not cancel
the current acceptance or claim Apple Matrix/APNs/CallKit. Continue full PRD.

## Phone release27 installed; continue server and PRD — 3 November2026

[Installed evidence](measurements/chat-inbox-history-phone-2026-11-03.json). Normal
minified devRelease1.0.0/code27 from clean8ac28f68 (runtimec770) is installed on
10.77.77.3:5555. Build PASS236.486s; lint0; 16KiB zip alignment, Firebase dev config,
18 compiled backup exclusions, private API and absence of fixture endpoints verified.
APK88058548 bytes, SHA4c86f7720b3745dce81f64c4aa1a6218933bfc3df2c94a8c04b4c7830fc32fd5.
Same signer aca5ce6a26b978dee1d63c63103b6799848ab760d47ef4cf5ee68620fc8721f3.
Two owned emulator cold starts PASS61.544s, each stable12s, PID2612/3303; both
onboarding PNGs actually viewed. Owned emulator3503064 stopped/transport absent.
Phone installation at18:42:21.763681UTC takes45.27s including exact APK pull-back.
AppId10962 and firstInstall2026-10-29 15:48:23 preserved; lastUpdate2026-11-04
01:42:41 device time. No uninstall/data clear/UI launch. DO NOT reinstall27.

Native two-case regression and actual product FCM run16 are accepted below. Platform
8b40bd0f is pushed: optional MFA browser readiness fix plus current FCM/TLS staging
evidence. Replacement full Verify41695437208 is running from that exact source;
old41692502923 ended cancelled after its known dashboard4 failure, with backend1/3/4
and dashboard1/2/3 passed, others superseded. New full gate remains required for
server rollout. Private TLS candidate is staged only; live304/schema116/push OFF.
Keep proceeding through push rollout and cross-company/recipient ACK/rich-chat/
voice/video/meeting PRD. Keep user updates frequent and every commit pushed.


## Current chat Android recovery and actual FCM accepted; build27 next — 3 November2026

Mobile c770a83f repairs durable history reuse; commit is pushed. Immutable product6/
peer6 build PASS62.014s from a clean c770 tree. [Native regression evidence](measurements/chat-history-local-reuse-native-2026-11-03.json): both formerly failing
QUEUED/FROZEN_DRAFT scenarios PASS645.323s with fresh2/0/0/0 JUnit, Platformd00ac5f5.
Each proves176 encrypted originals, one worker send,175 peer sends, one process
death,11 incoming passes/reopens and11 foreground pages,186 references/10 state-only
references, exhausted browse and retained offline rows. Mobile3824/Platform4325
source fingerprints unchanged; both fixtures and owned emulator cleaned up.

[Actual latest product FCM](measurements/chat-current-product-fcm-2026-11-03.json)
run16 PASS271.055s/driver209.335s, fresh1/0/0/0. Normal product login/Chat navigation,
one native registration/managed pusher/delivered wake, Firebase-caused process
restart, real SystemUI Reply and encrypted peer receipt. All five PNGs actually
viewed. Independent checks verify fixture credentials, disposable image, QEMU PID
and ADB transport gone. No phone/live changes. These are fixture APKs; NEVER install
them on the phone. Product6 SHA87f6eb26b4fc5c660780d4d4041f357e709c580c9b55c2896571fa401bc27e7c;
peer6 SHAdb15d3d0e8674bf633ddbef84002c6731e7ba8478fcb47bd0af65a16dc3d6c68.

[Inbox Apple](measurements/chat-inbox-previews-apple-2026-11-03.json) run41685480887
at537b89ae PASS1104 cases/124 suites,419 source hashes, full Xcode1318s; archive13256328884
SHA7ab89377183921feb7e0540b71b54810d7624c3919074eadb2ab25e04e1794ce.
It predates the pager fix. Current c770 control41691878650 is running; matching native
41691878601 pending with fetch/audit/sources432 ready in.work/chat-history-local-reuse-hosted-2026-11-03.
Old537 control41685363392 FAILED its browser time-zone test, confirmed in Mobile archive13256385047 (SHA9b6b975a246d88f6da446efd62defd68fda0eabbc6d6b28dfd07554748543e3c);
Apple job passed. The fix is already a59d3cd2;
do not rerun old source. Details in.work/chat-inbox-previews-control-2026-11-03.

Phone remains26. Build normal signed arm64 devRelease27 using prepared.work/release-phone-2026-11-03-build27/build-arm64.py after clean Git, then verify-and-smoke.py,
inspect both startup PNGs, preflight, notify, install-only. Preserve Google/account
state, signer and first-install/UID; never launch or drive the phone UI. Cancelled
candidate27 attempt1 remains retained; no version27 has been installed.

Platform full Verify41692502923 atd00 found dashboard shard4 failing optional MFA
Escape while the dialog is still busy (trace confirms); a test readiness assertion
is being validated locally. It is not accepted for deployment. Private TLS candidate
is staged under ~/.config/hris/chat-push-pilot, active files unchanged. Non-root/read-only
nginx configuration check passes after bounding its default temp directories; no
proxy is running. Full CI, rollout checkpoints and live push activation continue.
The screenshots also show an empty room's Older messages action before the initial
browse cursor is known; refine that empty state in the next UI increment without
hiding unknown history. Recipient ACKs, cross-company migration, rich chat, calls,
meetings, real Apple/web SDK integration and all54 groups remain active.


## Native history regression repaired locally; verify before phone27 — 3 November 2026

[Evidence](measurements/chat-history-local-reuse-2026-11-03.json). bd399 Native
41677393348 attempt2 FAILED (not infrastructure): artifact13255771715 SHA
5fa6964f87b96508ab695ab25083b6c1459ec4714480f287ef9b2727334ba8bf.
421 source hashes verified;230 Android host/18 proof-host pass; five provider cases
execute with two failures. QUEUED and FROZEN_DRAFT both retain176 originals across
11 incoming passes, then hit HistoryFocusCapacity during foreground paging.
Cancelled-draft, native SDK and WorkManager cases pass; all owned cleanup true.
Raw notification proof completes but its PNGs were not accepted/inspected in this
failed gate. Never mark it passed. Attempt1 SDK archive failure stays separately
retained. Fetch script now selects artifacts created after the current attempt's
job start, since GitHub retains both same-name attempt artifacts.

Foreground paging opened anchors from fresh provider references without first
reconciling their already durable originals. It now checks those local facts,
selects only unresolved candidates in the original bounded page, and preserves
that page's cursor when resolution exposes the next local page. Final progress/
generation checks and SDK lifetime limit16 remain. Two new VM regressions prove
175 saved messages/nine provider pages/20 older actions use zero native windows,
and a mixed page opens only its missing middle message. Focused desktop/browser/
app-Wasm/format/architecture/detekt PASS215.692s, unchanged source:291 fresh cases
(163 desktop,128 browser)/32 suites; no failures/errors/skips; architecture74,
detekt0. Actual SDK acceptance still requires the two failed scenarios rerun.

Candidate normal release27 at a59d3cd2 was intentionally stopped before distribution
when the real native failure arrived. Owned process group3214634/Java3214937 only,
399.62s, exit-15; no device operation. Evidence/helper retained in.work/release-phone-2026-11-03-build27/attempt1-cancelled. The root helper is ready for a fresh27 attempt;
no artifact27 has been verified or installed. Phone remains26, do not reinstall26.
After this commit/push, build/verify immutable product6 and peer6 using prepared
Mobile.work/product-chat-fcm-2026-11-03/build6.py and verify6.py. Run the two actual
NativeCollaborationWorkerInteropTest QUEUED/FROZEN methods with peer6 and an owned
emulator, then current-source actual FCM with both6 APKs. One heavy local job only.
Normal27 build/smoke/images/notice/install-only follows these gates; preserve data,
signer and Google identity and never drive the user's phone UI.

Platform d00ac5f5 is pushed and clean; actual product FCM run15 PASS266.651s with
Mobile1136 product/peer5, real Firebase restart/notification/encrypted SystemUI reply,
all five PNGs viewed and cleanup true. New worker JAR430620d7e64614db7524de60f0edbe09914e71917ab640542265457ade960b1c.
Do not reuse old APK5 for the new pager/UI acceptance. Platform ProductFcmDevice can
own a copied image on emulator5562; NativeDevice explicitly supports5562 too.
Native tests accept HRIS_COLLAB_NATIVE_SERIAL=emulator-5562. Keep API source immutable
during either provider run and require fresh JUnit, APK/source hashes and cleanup.

New537 Native41685363689 is running with the unfixed pager; a59 Native41688998875
is pending and may be superseded by this commit. Likewise control53741685363392
predates the timezone harness fix; a59 control41688998638 is pending. Audit exact
replacement runs after this commit, not old pending IDs. Root Apple53741685480887
continues and does not include this new pager. A matching control Apple run includes
presentation simulator tests and full Xcode; evaluate its actual evidence. Prior
bd399 Apple41677479513 is accepted. Runtime304/schema116/push OFF; Starter9959c1d.
Continue all54 groups, keep the user updated and push every commit. Cross-company
ownership/actual delivered-read ACKs, rich chat, backups and voice/video/meet remain.


## Browser dates passed; next clean build27 and current-source FCM — 3 November 2026

[Browser evidence](measurements/chat-browser-timezones-2026-11-03.json). Actual bd399
control41677393371 failed one of123 presentation browser cases: Asia/Jakarta data
was missing only from the standalone test bundle; the application already imports
it. Added the existing pinned @js-joda/timezone2.23.0 to wasmJsTest and its initializer.
The package lock changes exactly one test workspace, no package versions. First
local attempt failed lock enforcement before tests (42.906s), retained. Normal
upgrade task passed19.011s. Fresh browser check2 PASS184.074s, unchanged inputs:
1,523 cases/151 suites, zero failures/errors/skips, application Wasm compile,
format/architecture74/detekt0 pass. Named-zone and midnight assertions are unchanged.
Production Kotlin source remains537b89ae. Commit/push then normal release27 build.

Platform d00ac5f5 is pushed and its actual product FCM run15 PASSED:266.651s, fresh
one-case JUnit, driver207.402s. Legitimate native authentication occurs before session
creation; the incorrect push admission ordering is removed with future/revocation/
credential/ownership checks intact. Broad backend325 pass/one optional skip. Real
FCM starts the dead product, real SystemUI Reply reaches the peer encrypted; all five
PNGs viewed, cleanup/secrets removal/owned emulator absence confirmed. Immutable
artifacts5 are Mobile1136 and must be replaced by current source artifact6 before
latest UI/push deployment acceptance. Builders6/verification prepared in.work/product-chat-fcm-2026-11-03; not run. Fresh worker JAR SHA430620d7e64614db7524de60f0edbe09914e71917ab640542265457ade960b1c.
Live runtime304/schema116/push OFF and Starter9959c1d stay unchanged.

No heavy local job remains after the browser check. Release27 helpers in.work/release-phone-2026-11-03-build27 are prepared, not run. Build from clean source, verify lint/
signer/source/Firebase/endpoints, smoke twice on owned emulator and view images,
then notify immediately before install-only. Current phone26/UID10962 remains;
ADB was connected17:40UTC. Preserve data and Google identity, never drive phone UI.
After this phone update, build product/peer6 and repeat actual FCM with the latest UI.

bd399 Native41677393348 attempt2 still runs; do not duplicate retry. Attempt1 SDK
archive failure is retained. New537 full Apple41685480887 and Native41685363689 are
running/pending; source audits prepared in.work/chat-inbox-previews-apple-2026-11-03 and its matching hosted folder (419/430 paths). Native pending may be superseded automatically by this
new commit; audit the exact replacement source/outputs. bd399 Apple41677479513 is
already accepted. The537 control41685363392 started before this test-harness fix;
no cancel was performed because it was already running. A fresh control gate follows
this commit. Continue all54 groups with user updates, every commit pushed and Git
handoffs; complaints and phone updates do not stop the PRD.


## Offline inbox preview accepted locally; keep updating the user — 3 November 2026

The user explicitly requests continued progress updates, every commit pushed,
reviewable Git handoffs and each validated new mobile build installed on the phone.
Complaints steer the communications PRD; do not stop at a partial milestone.

[Contract](chat-inbox-previews.md) / [evidence](measurements/chat-inbox-previews-2026-11-03.json).
Owned local names publish before optional message/draft previews. Visible-page
observers are bounded to 50 conversations (UI 30), cancel on page/visibility change,
and clear on confirmed admission/owner loss. Timestamp labels and draft prefix are
localized. Explicit retry reopens failed local observation without requiring the
network to recover. Large text moves timestamps under the full-width name.

Full root/Wasm/product+proof build/lint PASS 672.455s, unchanged inputs: 4,037
desktop/571 suites, 230 Android host/37, 18 proof-host/five, ten logic/two (including
reused); 1,805 cases/259 suites fresh. Product lint zero; proof 47 existing warnings,
zero errors. Seven new domain, three VM and three screenshot cases pass. All three
final PNGs actually inspected; original large-text collision fixed before this run.
No generic Starter port needed. Recipient delivery/read ACKs, pending-outbox preview,
unread counts/recent ordering and cross-company migration remain required.

Previous bd399317 [Apple gate](measurements/chat-experience-apple-2026-11-03.json)
ACCEPTED: run41677479513, artifact13254086231, SHA
4c9b320004cce82fed6cd2132aa0ecb8d9ef289d5cd37f364a01e1670e103847;
410 source hashes, 1,097 simulator cases/123 suites, full Xcode1258s. It excludes
this newer preview source. Native41677393348 attempt1 failed SDK emulator archive
installation before native execution; retained under.work/chat-ux-hosted-2026-11-03/
attempt1. Exact-source attempt2 is running. Audit rather than requesting another run.
After this preview commit/push, dispatch its own Apple scope and audit automatically
triggered Native gate. Expected simulator count grows by seven to1,104; verify it.

Phone still release26: installed and verified, do not reinstall. Release27 helpers
prepared in.work/release-phone-2026-11-03-build27, not yet run. Build only from clean
source after the one heavy Platform job finishes. Verify source/signing/endpoints,
emulator cold starts and images, then notify immediately before install-only.
Preserve signer/UID/data/Google identity; never drive the user's phone UI.

Platform admission-check-1 is active (session96067): actual product FCM run14 proved
callbacks200/expected encrypted room/native+pusher1 but no retained hints. The
policy incorrectly required authentication to occur after native-session creation;
legitimate issuance retains earlier authentication. WIP fix plus domain/PostgreSQL
regressions is being checked with fresh worker JAR before product run15. Run13 was
a diagnostic nullable-map compile failure, fixed in14. Do not enable live push or
claim product FCM acceptance until real notification and encrypted inline reply pass.
Runtime304/schema116/push OFF; Starter9959c1d unchanged. Continue the full PRD.


## Release26 installed; source UI gates and remaining communications continue — 3 November 2026

[Phone evidence](measurements/chat-experience-phone-2026-11-03.json): clean bd399317
normal devRelease26 build PASS474.453s, lint0,88042148 bytes SHA
21d134bc1e1cbae5e4fdf242da450f1318bdab07c6a5c3568740a7586a540cf4.
Two real emulator cold starts pass53.966s; both onboarding PNGs actually viewed,
owned emulator stopped. Initial smoke helper incorrectly retained its25 assertion:
failed before installation, recorded separately, corrected to26 without rebuilding.
Phone10.77.77.3:5555 updated16:56:36UTC,43.372s including pull-back verification;
UID10962, original first-install time and signer/data preserved. No phone UI driven.
Do not reinstall26. Future phone updates still require notice immediately before
install-only, with the same signer and preserved data.

[1136 Native evidence](measurements/chat-push-activation-hosted-2026-11-03.json)
ACCEPTED:41667296924, artifact13250826555 SHA
fa1cc410d5f660c92cb9a00af73d4bda24b478f1ab7d0cdd18385fc4ab9f698f,
408 source hashes,230 Android host/18 proof host/five actual provider cases all pass.
Raw16/runtime4/input recovery3 deaths/actions5 and all three SystemUI PNGs inspected.
This gate does not invoke product FCM. New bd399 Apple41677479513 and Native41677393348
remain running; prepared audits/sources in.work/chat-ux-apple-2026-11-03 (410 paths)
and.work/chat-ux-hosted-2026-11-03 (421 paths). Fetch/audit and inspect required images.

Active next increment: offline inbox previews from bounded owned local history/drafts.
Initial entities/ObserveCollaborationInbox are WIP and not yet validated; no acceptance
claim. Preserve owner/admission fencing and stop detail observers when hidden. Real
recipient durable delivery/read ACKs and group cross-company ownership remain required.
Platform FCM run12 now executing with exact artifacts5 and independent bounded push
state probes; one heavy local job. Wait before another Gradle/emulator job. Runtime304/
schema116/push OFF and Starter9959c1d unchanged. Continue the whole PRD after these fixes.


## Primary Chat tab and compact thread accepted locally — 3 November 2026

[Contract](chat-experience.md) / [evidence](measurements/chat-experience-2026-11-03.json).
User's four complaints are active priorities, not cancellation of the long-run PRD.
Chat is now a primary bottom tab (removed from services), with notification links
returning to that parent. Bubbles fit content, use day separators/compact real
pending/sent/failed icons, and a multiline pill composer. Known provider exhaustion
is restored on room reopening and checked before another provider acquisition.
A saved empty list remains visible during refresh; no toolbar spinner for each
history synchronization. Delivered/read ACKs, preview/unread list projection and
cross-company ownership are still NEXT, not implemented by this increment.

Focused156 tests PASS239.29s. Full xvfb root/Wasm/product+proof build/lint
PASS530.774s, inputs unchanged:4024 desktop/569 suites,230 Android host/37,
18 proof-host/five,ten logic/two all pass (including reused);933 cases/145 suites
fresh. Product lint0, proof47 existing warnings/0errors. Initial unpublished-empty
progress regression rejected and fixed. Three light/dark2x-text/landscape PNGs
actually viewed and tracked; prior cancellation/window/large-text tests pass.
No generic Starter change is needed. Atomic commit/push then clean normal
release26 via.work/release-phone-2026-11-03-build26/build-arm64.py; scripts prepared,
not built yet. Notify before install-only; never drive phone UI. Phone25 remains.

Platformfd09de67 records four completed fictional SAMPLE accounts: distinct random
Argon2 passwords, chat-only API membership grants, API person links,4 successful
native logins and5 other contacts each; all temporary verification sessions revoked.
No provider device/message or Firebase mapping was changed. Native accounts alone
are not proof of Firebase mobile sign-in/device enrollment. Private passwords only
in Platform.work/chat-demo-2026-11-03/accounts.private.json; never upload. Group
cross-company admission/migration is required by the amended PRD and documented;
removing company filters alone must not bypass its existing ownership constraints.

Apple41667309251 attempt2 at1136fb9e is now ACCEPTED:1097 simulator/123 suites,
397 source fingerprints, full Xcode1202s; artifact13250850483 SHA
 d354a19dc0bf94cdf5a3fe7a07db41202405ac2cf776961182c90674db8312a1.
[Evidence](measurements/chat-push-activation-apple-2026-11-03.json). Attempt1 failed
before checkout due hosted-runner capacity and is retained. New UI source requires
its own full Xcode gate. Native41667296924 at1136 still running; audit separately.

Platform FCM run11 (clock corrected by5bebbdc6) registers native+Matrix pusher and
sends an encrypted peer original; callback200 but delivered0, timeout waiting for
FCM-caused restart. Counts1/1/0, both owned cleanup true, chat/home PNGs viewed.
New UNCOMMITTED Platform push-state fixture diagnostics have not run. Finish after
Mobile build frees local capacity. Keep one heavy local job. Runtime304/schema116/
push OFF and Starter9959c1d unchanged. Continue recipient ACK/UI, group scope,
FCM/inline reply, rich messages, voice/video/meetings and all54 groups; do not stop
at this source/UI milestone.


## Checkpoint Native accepted; product FCM diagnostics and Apple retry ongoing — 3 November 2026

Native41662285409 at0a452afb is ACCEPTED after source/archive/report/visual audit.
[Evidence](measurements/chat-input-checkpoint-hosted-2026-11-03.json):395 source hashes,
artifact13248605004 SHAef0ee301490503efba73a8493bb049e40c5031c2cb2152e68e667d7129bd4342,
230 Android host/18 proof-host/five actual provider cases all pass. Raw16/runtime4,
three ordinary input deaths/four callbacks, one586ms Complete with exactly one
SessionChanged copy, five action cases and all three PNGs actually viewed. Owned
cleanup passes. Old41650906105 remains rejected. This does not cover new1136 activation.

Product source1136fb9e529faa2d5ad2f3dc02bf8c7fb326e298 is pushed and locally accepted
(full495.015s;4,015 desktop/230 host/18 proof/ten logic). Native41667296924 is its
separate pending gate. Apple41667309251 attempt1 failed before any checkout/tests:
GitHub could not acquire a macOS runner for15 minutes. Exact-source failed-job retry
attempt2 requested; retain.work/chat-push-activation-apple-2026-11-03/
runner-unavailable-attempt1.json. No Apple source failure or acceptance is claimed.
397 Apple/408 Native source fingerprints and audit scripts are prepared in matching
.work/chat-push-activation-apple and -hosted folders dated2026-11-03.

Isolated build5 from clean1136 PASS45.425s. product5 SHA
e05999a372674776628e89a7dc5e8e9450dc1c20e7c31c8beb40736de02d7662;
peer5 SHA9ccd3b6b50d98543b77308e3648af8dcd4ebd1f3ca7aad98b295b83dbb97ad9b.
Loopback18084, x86 devDebug/proof only: NEVER install on a phone. Platform73cd2ed9
now records bounded gateway/status/error codes and private OS/SDK/job failure logs;
25 Python guards and compile/format/architecture PASS35.569s. Run9 currently executing
with unchanged artifacts5 under Platform.work/product-chat-fcm-2026-11-03/run9.py.

Run7 stopped at emulator boot (all cleanup passed). Run8 boots31.569s, logs in,
registers FCM and opens the peer thread, but pusher wait fails; thread says service
unavailable. Counts1/0/0; no encrypted send/FCM delivery. Both PNGs viewed, owned
cleanup and process/image/credential absence pass. Cause remains UNKNOWN. New diagnostics
must identify the actual response/worker failure; do not bypass application activation
from the fixture. Exact previous failures are in Platform's product-chat-fcm-boot evidence.

NEXT finish run9 diagnostics, correct proven issue, obtain actual product FCM/new-source
gates and build validated normal release26+. Preparation exists, not built/installed.
Phone25 remains installed; notify before each install-only, preserve signer/UID/data,
never launch/drive phone UI. Platform runtime304/schema116/push OFF and Starter9959c1d
unchanged. Keep one heavy local job. Continue all54 groups; voice/video/meeting UI absent.

## Product push activation implemented; full local checks accepted — 3 November 2026

[Contract](collaboration-push-registration.md) / [evidence](measurements/chat-push-activation-2026-11-03.json).
ActivateCollaborationPusher now has production application callers through a bounded,
scoped third company recovery turn. Chat setup and confirmed native push registration
request existing durable recovery work. Registration borrows an installed admitted
runtime, rechecks native status and releases it on every path. No implicit enrollment
or notification permission request. Original incoming/outgoing turns retain their budgets.

Full xvfb root/Wasm/Android product+proof/lint PASS495.015s with unchanged source.
4,015 desktop/566 suites,230 Android host/37,18 proof-host/five,ten logic/two all pass,
including reused reports;2,421 cases/298 suites execute fresh. Product lint0, proof47
existing warnings/0 errors. Earlier fixture/expectation/detekt failures are retained.
The separate hris-product-peer scenario is compiled, not yet executed. HRIS-specific
admission and proof code require no generic Starter port.

NEXT: push this atomic source increment; dispatch matching CI scope=apple (full Xcode)
and retain automatic Native provider gate. Native41662285409 still runs for the earlier
0a452 checkpoint; audit its artifact and actually view all three PNGs independently.
Old41650906105 remains rejected. The new application common source needs new Apple
coverage; old a3f Apple41651053921 is not acceptance for this change.

Build fresh isolated product5.apk/peer5.apk using .work/product-chat-fcm-2026-11-03/
build5.py, then verify5.py. Product uses loopback18084; NEVER install it on a phone.
Platform .work/product-chat-fcm-2026-11-03/run7.py now fingerprints committed fixture
source and uses artifacts5.json; run only after fresh artifacts exist. Actual FCM is
still UNACCEPTED after six historical attempts. Run7 must prove normal UI setup,
managed pusher, actual Firebase-caused process start, SystemUI reply and encrypted
peer receipt with owned cleanup. Inspect captured PNGs and preserve failure frames.

Normal release26 preparation is in .work/release-phone-2026-11-03-build26, not built
or installed. Require clean committed source, matching validation, normal private API,
minified emulator smoke and notice before install-only to10.77.77.3:5555. Phone25 is
already installed; do not reinstall25 or drive the phone UI. Preserve signer/UID/data.
Platform be345fe7/runtime304/schema116/chat push OFF and Starter9959c1d are unchanged.
No employee account/message or live service changed. Continue all54 groups; voice,
video and meeting UI remain absent. Push every atomic commit and continue long run.

## Native checkpoint correction accepted locally — 3 November 2026

The checkpoint fixture/host driver now record and validate intermediate review states.
Incomplete Retry may retain an exact unreviewed account copy; Complete still requires
original removal and exactly one SessionChanged copy, with no draft/resolution.
[Evidence](measurements/chat-input-checkpoint-native-2026-11-03.json): local native
100.163s PASS, raw16/runtime4/three process deaths/five actions, all three PNGs actually
viewed. This run completes in one758ms turn; Pending interruptions are host regression
coverage, not observed native behavior. Owned emulator2180707 stopped/transport absent.

Full xvfb root/proof/build/lint PASS127.791s, inputs unchanged. Reports including reused
results:3997 desktop/230 Android host/18 proof-host/10 build-logic, zero failures/errors/
skips;18 proof-host cases freshly executed. Product lint0, proof47 existing warnings.
Twenty-seven native-driver regressions pass. Production/common source is unchanged;
Apple41651053921 remains applicable, and installed phone25 must not be reinstalled.
The local APK also contains an uninvoked product-peer scenario outside this checkpoint
commit. Push this atomic checkpoint, audit the automatically triggered exact-source
Native replacement; old41650906105 stays REJECTED. Prepared audit in
.work/chat-input-checkpoint-hosted-2026-11-03 expects18 proof-host and reviewStates.

Actual-product FCM is still UNACCEPTED. Platform run6 passes fictional password login,
one native FCM registration and the Chat is ready UI, then setup navigation times out;
owned peer/emulator cleanup passes. Run5 emulator timeout/failed combined cleanup and
all earlier attempts are retained. Platform now has20 passing driver guards and uses
snapshot-based activation plus failure frames. Mobile peer4 APK is prepared from the
full quality run; its peer source/MatrixProofInstrumentation remain UNCOMMITTED.

NEW CONFIRMED PRODUCT GAP: ActivateCollaborationPusher is bound and tested but has no
production caller. Native FCM registration therefore does not establish the Matrix
chat push binding. Wire a finite, scoped application activation path with cancellation,
current registration/session/runtime ownership and explicit retry behavior. Do not call
it from the fixture to bypass the product gap. Then repeat actual product FCM acceptance,
full new-source checks/Apple, and deliver next validated phone26+ with notice/install-only.
No phone UI, real employee message, Firebase account or live configuration was touched.
Platform runtime304/schema116/chat push OFF and Starter9959c1d unchanged. Continue all54
groups; voice/video/meeting UI still absent. No generic Starter port for HRIS policy.

## Reviewed-send Apple accepted; owned FCM preparation continues — 3 November 2026

Apple41651053921 at a3f39be7 is ACCEPTED:1,094 simulator/123 suites,384 source hashes,
compile646s/tests107s/full Xcode911s. Artifact13239546708 SHA256
e6efe85c5a99aa4a3a19b5ff5801202e2093469335ccffffe1213d4c7398be50.
[Evidence](measurements/chat-reviewed-send-apple-2026-11-03.json). Includes14 reviewed
and7 saved-observation cases. No physical iPhone/APNs/CallKit claim. Native a3f remains
REJECTED as recorded below; no retroactive success. Phone25 installed, no new install.

Mobile FCM fixture build3 PASS56.243s, source digests unchanged: devDebug product
and separate peer x86 APKs plus18 proof-host tests. Artifact hashes and prior failures
are in .work/product-chat-fcm-2026-11-03. Product explicitly targets loopback18084 for
the isolated fixture; **never install that APK on the phone**. Next phone remains26+.
The three checkpoint regressions pass; local native checkpoint execution/full root
quality are still pending. Source remains UNCOMMITTED fixture-only WIP.

Platform owned-product FCM driver/worker/test source is UNCOMMITTED. Eight Python
guard/cleanup cases pass; compile1 now running under .work/product-chat-fcm-2026-11-03.
No emulator is running and no FCM message has been sent. Finish compile, then improve
UI label selection to prefer clickable matches (Sign in/Home also occur in headers),
verify exact artifacts, run bounded fixture after server readiness, inspect captures.
Keep one heavy local job at a time. Actual FCM must independently prove notification
and encrypted reply; ordinary process restart alone is not causal FCM evidence.

Live Platform304/schema116/push OFF and Starter9959c1d unchanged. All54 groups open,
voice/video/meeting UI absent. Continue implementation after this gate, push commits;
no completion claim or generic Starter port here.

## Native a3f gate rejected; product FCM fixture in progress — 3 November 2026

Phone25 remains installed and verified; do not reinstall or drive its UI.
Native41650906105 at a3f39be7 is **REJECTED**. [Evidence](measurements/chat-reviewed-send-hosted-2026-11-03.json)
verifies393 sources and archive13237819903. Android230/37 and proof15/4 pass;
raw16 and runtime4 pass. Protected-input proof stops at recoverInputs:300 after
one1979ms Retry with original1/account-copy1. The old assertion did not record
review enum or individual failed predicate; do not invent a proven cause. Five
provider scenarios and five action/SystemUI cases did not execute. Apple41651053921
still building full Xcode at last query.

UNCOMMITTED/UNACCEPTED fixture changes: NotificationInputCheckpoint permits an
unreviewed durable copy only on an incomplete turn, records each review enum, and
requires exactly one SessionChanged copy plus original removal on Complete. Exact
text/token equality and no draft/resolution remain asserted. Three pure checkpoint
regressions added; native execution still pending. Product app code is unchanged.

Actual-product FCM test is being implemented across Mobile proof and Platform:
HrisProductNotificationPeer is a separate encrypted sender package, real devDebug
product app logs into isolated API with fictional native password, real worker
and private Synapse/TLS proxy own the FCM chain. ProductFcmDevice owns a disposable
copy of the AVD image on5562, refuses phone serials, never changes source image;
eight driver safety/cleanup tests pass. No real employee messages/Firebase accounts
created. Existing private service-account path is used only by owned worker; no
secrets in Git/GitHub. No actual FCM test has executed yet.

.work/product-chat-fcm-2026-11-03: Mobile build1 FAIL7.529s (init script affected
included build-logic), fixed to beforeProject application plugin. Build2 FAIL118.156s
because peer referenced private cleanup methods; switched to shared cleanup helper.
Build3 currently running, source fingerprints fixed through completion. Next collect,
compile Platform test fixture/worker, run short owned FCM scenario and inspect captures;
then full quality and native checkpoint proof. Keep one heavy local job at a time.
Platform runtime304/schema116 and live push flags unchanged/off; all54 groups open.
Continue long run. No generic Starter port for these HRIS test fixtures.

## Current: release 25 installed; actual chat FCM acceptance next — 3 November 2026

Source **a3f39be7390874cf04b807b9abad365393530852** is committed, pushed and locally
accepted. Earlier entries saying reviewed-send is uncommitted are historical.
[Release evidence](measurements/android-release-phone-2026-11-03-build25.json): clean
release build/lint538.577s; APK88,009,188 bytes, SHA256
c377886820f8a8ddf7d17de8a6971ee17cae93ed67ba7f2ed00066186f491075.
Same signer, arm64/16KiB/private API/Firebase/backup exclusions verified, lint0.
Two minified12-second cold starts PASS62.449s; both PNGs actually viewed, owned
emulator1937890 stopped/transport absent. In-place phone25 install verified by
pull-back hash, appId10962 and original first-install preserved. User notified;
no phone UI launch/data clear/sign-in reset. **Do not reinstall25. Next build26+.**

Full Apple41651053921 and Native41650906105 run at exact a3f source; still running
at installation. Prepared audits in .work/chat-reviewed-send-apple-2026-11-03 and
...-hosted-... pin384/393 committed source hashes. Require full Xcode/simulator
(14 reviewed,7 observation cases) and Native five providers/raw16/runtime4/three
input deaths/five actions; inspect all three hosted PNGs before accepting. Do not
reuse418 evidence as proof of a3f. No heavy local build/emulator remains active.

NEXT: actual product-app chat FCM acceptance with fictional owned API/PostgreSQL/
Synapse accounts and an owned emulator. Existing native FCM inbox fixture and
MatrixPushProviderFixture can be reused; plan is not implemented yet. Native password
login is selected when isolated API disables Firebase Email, so no new Firebase user
or real employee test message is needed. Use existing private service-account path
without logging/copying secrets into Git or GitHub. Prepare backend/APK before booting
emulator; keep the scenario short, as known long local provider runs hang. Hosted
five-provider suite remains the long-run gate. Do not repeat emulator-flag experiments.

Platform72447fb8/runtime304/schema116 and Starter9959c1d unchanged. Chat push OFF;
all54 PRD groups OPEN, voice/video/meeting UI absent. Continue full communications
work after FCM; no completion claim here. Push each atomic commit. HRIS-specific
reviewed-send requires no generic Starter port.

## Reviewed-send accepted locally; next source commit and release25 — 3 November 2026

[Contract](collaboration-reviewed-replies.md) / [evidence](measurements/chat-reviewed-send-2026-11-03.json).
All37 source inputs are LOCALLY ACCEPTED. Full8 PASS571.836s:3,997 desktop/564 suites,
230 Android host/37,15 proof/four,ten logic/two; all failures/errors/skips0. 2,700 tests/
338 suites executed fresh. Root quality, shared Wasm compile, Android dev/proof
assembly and lint pass. Product lint0; proof47 unchanged warnings/0 errors.
Browser9 PASS66.197s:31 real IndexedDB cases/two suites, including19 reply-storage
and12 recovery cases. All37 source digests verified unchanged during both checks.

Focused7 PASS267.501s with57 fresh/five suites, including9 MVI,7 Compose and41 app
cases (21 reply cases, two new actual-Koin/encrypted-SQLite scenarios). Five screenshots-7
ACTUALLY VIEWED; single primary send, secondary Open chat, readable immutable/held
states, Indonesian320x640/font2 with full final button visible. Only two extra domain
race tests changed after focused7; no production/UI changes. Full domain suite now
includes14 reviewed-reply and7 saved-reply observation cases. Failed attempts1–6 are
preserved with reasons; no failures retroactively relabelled.

NEXT commit/push accepted source + current docs. Source is still uncommitted at this
checkpoint; verify manifest paths/digests and git diff --check. Then generate exact
committed384-source Apple/393-source Native manifests, fill prepared script placeholders,
dispatch full Apple and find automatic Native run at that source. Templates under
.work/chat-reviewed-send-apple-2026-11-03 and ...-hosted-...; require exact artifact
hashes, full Xcode/simulator (14 new reviewed,7 observe), Native five providers plus
runtime4/raw16/three input deaths/five actions and all three PNGs actually viewed.
Do not reuse accepted418 results as proof of new reviewed-send source.

Release25 scripts .work/release-phone-2026-11-03-build25 PREPARED, NOT RUN. No heavy local
job or owned emulator now. Build clean committed source, verify artifact/lint/signer/
16KiB/backup exclusions, run two minified emulator starts and inspect PNGs, stop owned
emulator. Preflight expects currently installed24 and same signer; notify immediately
before explicit10.77.77.3:5555 install.py --install-only. Never launch phone UI or clear
data. Phone24 remains installed; next version25+. Preserve Google account mapping.

Current implementation: encrypted mutable draft separate from immutable confirmed
ID/body/millisecond timestamp/full parent; explicit reapproval changes parent only;
resume handles edited resolution before original completion, fences old operation,
reproduces new command before input removal; discard fences both or explicitly only
an unsent edit while original already started. Current owners guard every I/O, autosave
successors join all predecessors, stopped/replaced UI clears private state. Same-account
existing-command recovery is distinct from permission for a new queue entry. No HTTP or
Matrix send in these domain operations; actual integrations use fictional provider ports.

Host418 full Apple/Native accepted, protocol reports success, control41639264755 still
running at last query. Platform72447fb8/runtime304/schema116 and Starter9959c1d unchanged;
chat push OFF. All54 PRD groups OPEN; voice/video/meeting UI absent. After release25,
continue native notification rollout acceptance and remaining communications; no final
at this milestone. No generic Starter port is required for this HRIS-specific policy.


## Reviewed-send focused checks pass; full8 running — 3 November 2026

Source is STILL UNCOMMITTED/NOT RELEASE-ACCEPTED. [Progress/evidence](measurements/chat-reviewed-send-2026-11-03.json).
Focused7 PASS267.501s:57 fresh/five suites, zero failures/errors/skips; includes
9 MVI/7 Compose UI and41 application tests across three suites (21 reply tests,
including two new actual-Koin/encrypted-SQLite restart/duplicate-draft cases).
Domain926/86 and encrypted reply-storage19 cases passed earlier and were reused.
Root format/architecture/detekt, devDebug assemble/lint pass; product lint0 issues.
All37 inputs unchanged during focused7. Five screenshots-7 ACTUALLY VIEWED:
focused editor, held reply, immutable confirmed reply, OriginalProcessing refusal,
Indonesian320x640/font2 at bottom. Open chat is secondary to Send; entire final
button fits after real scroll. These are desktop Compose keyboard/captures, NOT
Android IME or phone UI. Earlier screenshots-6 showed two competing primary actions;
polish and reinspection are complete. Earlier failed checks1–6 remain recorded.

Two final unit races were then added: discard winning after final input read
prevents late edited insertion; new command already starting refuses discard.
NO production source changed after focused7. Full8 is NOW RUNNING in
.work/chat-reviewed-send-2026-11-03/check-8, exec session37322. ONE heavy job only;
no owned emulator. Next collect full8. Root :check does NOT run browser tests, so
run python .work/chat-reviewed-send-2026-11-03/check-browser.py 9 browser afterward
(two actual IndexedDB reply/recovery suites), then audit exact reports/source hashes.
The reviewed domain suite now has14 tests; observed-native-parent suite has7.

Release25 scripts PREPARED ONLY under .work/release-phone-2026-11-03-build25.
They expect currently installed24 and the same signer; no25 build or installation.
Use clean accepted source, verify APK/lint/backup exclusions/16KiB alignment and
minified emulator cold starts, inspect PNGs, stop owned emulator, notify before
explicit10.77.77.3:5555 --install-only. Never launch phone UI, clear data or alter
Google mapping. Phone24 source418c7ef6 stays installed.

Hosted templates PREPARED ONLY under .work/chat-reviewed-send-apple-2026-11-03
(384 paths) and ...-hosted-... (393 paths). Fill exact committed source hash/run IDs
and generate sources.json from committed bytes; placeholders currently prevent use.
Apple template requires14 reviewed-reply and7 saved-reply observation simulator cases
plus existing full Xcode gates. Native additionally requires runtime4/raw16/three
input deaths/five actions and actual viewing of all three SystemUI PNGs. Do not
infer acceptance from dispatch or reuse418 evidence for reviewed-send.

Host418 Apple41639434001 and Native41639264778 ACCEPTED. Protocol41639264850 reports
success (not separately artifact-audited); control41639264755 still running its
portable/Apple adapter jobs at last query. Starter9959c1d and Platform72447fb8/runtime304/
schema116 unchanged; live chat push OFF. All54 groups OPEN, voice/video/meeting UI absent.
Continue long run after verified source/release25; no final at this milestone.


## Host native gate accepted; reviewed-send validation underway — 3 November 2026

Native418c7ef6 / run41639264778 ACCEPTED. Artifact13234703425 /249017 bytes,
SHA256 f4f3846f6995b1777b4c475fbc428b3a348f26cb8bfb371d8977d4bbc95b6825;
385 source digests verified; five provider/three suites,230 Android host/37,
15 proof/four, all zero failures/errors/skips. Raw16, four runtime composition
cases2.883s, three ordinary kills/four callbacks, one368ms Complete turn,1 protected
input->1 review, HTTP/messages0. Five actions/SystemUI31.605s, ALL THREE hosted PNGs
ACTUALLY VIEWED: native card/actions, real Gboard reply editor, acknowledged card/editor/
private text gone with unrelated System notification retained. Fixture uninstalled.
Build797s/278s/native1110s. APK9df962051e0dfeb02abacca587f44474df83bd4ef8c6e1fb7e31a9302d89142c.
[Measurement](measurements/chat-native-host-hosted-2026-11-03.json). Apple418 already
accepted; phone24 installed. These gates exclude the current reviewed-send WIP.

Reviewed-send source is UNCOMMITTED/NOT ACCEPTED; [contract](collaboration-reviewed-replies.md).
All domain/storage/page/DI/app recovery code exists, including mutable encrypted draft,
immutable explicit confirmation, two operation fences, actual observed parent, explicit
saved-edit-only discard, full autosave predecessor join, and protection against duplicate
callbacks erasing even an empty draft. Validation .work/chat-reviewed-send-2026-11-03:
- focused1 failed39.252s before tests: missing policy import, fixed.
- focused2 failed98.437s:924 domain/86 suites PASS, data smart-cast compile error fixed.
- focused3 failed180.209s:956 fresh/88 suites PASS, including12 new reviewed-reply
  cases and19 encrypted reply-storage cases (four new SQLite compatibility/CAS/bounds).
  Presentation BackHandler dependency missing, fixed.
- focused4 failed76.436s:926 domain/86 PASS (new actual native-parent observation case).
  BackHandler additionally needs ExperimentalComposeUiApi opt-in; NEXT fix and rerun.
  Additional keyboard/read-only/edit-only screenshots and MVI native-parent/typing tests
  exist but have NOT executed. Actual-Koin edited recovery tests added, NOT executed.
37 source inputs currently manifested. No local heavy job active at this checkpoint.
No reviewed-send screenshots inspected, full root/Android/Wasm/browser/Apple pending.
No release25 build/install. Keep next release25+, preserve phone24 data/signer/session,
notify before install-only, never drive phone UI. Continue long run; all54 groups open,
voice/video/meeting UI absent. Starter9959c1d and Platform72447fb8/runtime304/schema116
unchanged; chat push OFF. No generic Starter port is needed for these HRIS policies.


## Reviewed-send implementation in progress; host Apple accepted — 3 November 2026

PHONE24 remains installed, source418c7ef6; next meaningful build25+. Do not reinstall24
or launch/drive phone UI. Platform runtime304/schema116 unchanged; chat push OFF.

Apple host41639434001 ACCEPTED: artifact13233721273, SHA256
69012858c98e697c8ac0e28f40e41569b4458f75ca28e70da892552f9333af1d;
1,079 simulator cases/122 suites,376 source digests verified; compile437s/tests60s/
full Xcode720s. This covers418c7ef6, NOT the following working-tree changes.
Native41639264778 still running; fetch/audit scripts are ready under
.work/chat-native-host-hosted-2026-11-03. Inspect all three hosted PNGs after audit.

UNVALIDATED WORKING TREE: edited notification reply resolution and encrypted working
drafts. Domain now separates draft from immutable confirmed operation ID/body/time;
confirmed current-parent consent precedes original permanent cancellation and edited
queue insertion. Resume handles resolution before old completion; confirmation clears
held review, recovery never silently reapproves another family. Discard supports explicit
saved-edit-only mode when original already started. Actual MVI/page/EN+ID bindings added;
autosave predecessor joins and full parent observation are being checked. No compilation,
format, tests, screenshots or acceptance yet. Scripts .work/chat-reviewed-send-2026-11-03
are supplementary; finish implementation and meaningful tests, do not ship this WIP.

NEXT fix/update fixtures for new required use-case dependencies, add domain crash/fence/
reapproval tests, real encrypted SQLite/IndexedDB schema1 compatibility/max bounds/CAS,
actual-Koin app recovery and MVI keyboard/disposal tests. Review draft status and late
observation handling. Then focused/root xvfb check, Wasm/Android/lint, visually inspect
screenshots, commit/push accepted source, hosted Apple/Native and validated release25
install-only after notifying user. One heavy local job at a time. No heavy job active now.
All54 communications PRD groups still OPEN; voice/video/meet UI absent. Continue long run.


## Phone24 installed; routing hosted gates accepted — 3 November 2026

**PHONE24 IS INSTALLED. Do not reinstall it; next meaningful product25+.**
[Release record](measurements/android-release-phone-2026-11-03-build24.json).
Clean source418c7ef6009c7bcc28bd28175f1e16c827228107, devRelease1.0.0/code24,
arm64/nondebuggable,87,959,188 bytes, SHA256
296ac1d255d2dede0de4ad3f865782de7263e2d9320ee315eea55182fe89f0e1.
Build/lint486.413s; lint0. Existing signer, dev API/Firebase app, Git textproto,
16KiB alignment and18 backup exclusions verified. Two12s minified cold starts pass
59.3s; both onboarding PNGs ACTUALLY INSPECTED. Owned5562 PID1438468 stopped/absent.
Install-only13:03:15Z confirmed49.973s, pulled bytes match. AppId10962 and first install
2026-10-29 15:48:23 preserved, no data clear/uninstall/phone UI. User notified before
and after installation. Work .work/release-phone-2026-11-03-build24. No local heavy
job or owned emulator remains. Preserve signer/data/Google mapping; never drive phone UI.

Routing ff3b3246 hosted gates are ACCEPTED:
- [Apple41634596520](measurements/chat-native-routing-apple-2026-11-03.json):
  1,079 simulator/122 suites,372 verified sources; compile663s/tests123s/full Xcode1050s.
  Artifact13231372692 SHA256 c7c248b6fe8c3954168d085f5507b9ccd99aacb3587ef866e96db576812af367.
- [Native41634550829](measurements/chat-native-routing-hosted-2026-11-03.json):
  five provider/three suites,230 host/37,15 proof/four;378 verified sources; all pass.
  Artifact13233140670/248228 bytes SHA256
  0024d1c0674f423ae86a6bcf01f3fee39d73b010d27b78a13526f0ae98313f63.
  Raw16/103.09s,3 process deaths,1->1 exact input,one1162ms Complete turn,HTTP/messages0;
  five actions/SystemUI35.954s and ALL THREE hosted PNGs ACTUALLY INSPECTED. Fixture
  cleanup verified. Build940s/332s/native1142s. This one-turn pass does not exercise
  Retry or establish why the two older unrecorded-result first-turn assertions failed;
  those failures remain rejected. APK eaf482880080c172c093b87eab3d0000247ebb446b1c2f2d97fe57d49daa762d.

New host418c7ef6 [Apple41639434001](measurements/chat-native-host-apple-2026-11-03.json)
and [Native41639264778](measurements/chat-native-host-hosted-2026-11-03.json) are RUNNING.
Audits/source manifests ready in .work/chat-native-host-apple-2026-11-03 (376files)
and ...-hosted-... (385files). Native now additionally requires four runtime composition/
resource/cleanup cases. Collect artifacts, check exact hashes, inspect native PNGs.
Do not dispatch another same-branch Apple before current finishes. Automatic protocol
41639264850/control41639264755 also belong to418c7ef6. No success inferred from dispatch.

NEXT continue edited reviewed-send. A grounded plan and identified race/codec bounds
are in .work/chat-reviewed-send-2026-11-03/design.txt (NOT implemented). Durable edited
resolution must precede original operation fence, keep its fresh stable ID/body across
retries/reapproval, and be handled before original Complete. Preserve started/conflicting
original/new commands. Settle explicit discard-of-unsent-edit behavior when original
has already started; never claim original cancellation. Use actual Koin/Room/IndexedDB,
MVI/lifecycle and compact EN/ID UI; no standalone storage-only detour. Source is free
for new edits now. Keep all accepted work/pending checks in Git and push each commit.

Starter9959c1d remains clean/accepted; this host is HRIS policy, no Starter port.
Platform72447fb8/runtime304/schema116 unchanged, chat push OFF. All54 PRD groups OPEN;
voice/video/meeting UI absent. Continue the long run; do not finalize at this milestone.


## Product Android notification host accepted locally — 3 November 2026

[Contract](collaboration-android-notification-runtime.md) /
[evidence](measurements/chat-native-host-2026-11-03.json). All24 source/resource/build/
fixture/driver inputs are locally accepted. Full3 passes324.458s:3,968 desktop/563,
230 Android host/37,15 proof/four,ten logic/two; zero failures/errors/skips. Root
quality/Wasm/Android dev and proof assembly/lint pass,613 fresh/98. Product lint0;
proof47 warnings/0 errors. Focused2 passes127 fresh (112 desktop+15 proof). Cold sealed
MarkRead waits for the real restored session and retires exact journal/private read.

Native fixture IDs were corrected by review after full3, before native execution;
ONLY NotificationRuntimeProbe and its driver success assertion differ from full3.
Proof4 passes107.52s/15 tests/quality/lint. All production digests remain unchanged.
Native1 passes105.401s: four Android runtime/resource/lifetime cases with explicitly
fake admission/settings, raw16, three ordinary process kills/four callbacks,
protected1->review1 in one201ms turn, wrong-account/old-family guards, HTTP/messages0.
Five real actions and SystemUI reply editor/retirement pass47.078s; ALL THREE PNGs
ACTUALLY INSPECTED. APK cee98ba169db8d6aeca131e9c3c505e6afbda0a37f1198782b49a50f930d051d.
Fixture uninstalled/data removed; owned5562 PID1367732 stopped/transport absent.
No local heavy job or owned emulator remains. Work/audits in .work/chat-native-host-2026-11-03.

Android Application now starts one actual Koin host and implements the private native
action host. Application lifetime owns the parent independently of selected company.
Ready gating shares one8s budget with each operation; close releases callers and
stale Blocked parent phases become Pending, without relaxing current-parent cleanup.
Copy/labels follow observed EN/ID/System settings. Readiness/cancellation/held cleanup,
start-once/rejected second host/late state and real Koin/SQLite integration pass.
Live server chat push remains OFF; edited reviewed-send is not implemented.

NEXT commit/push accepted host source/docs, then clean release24 build, artifact/lint
verification and minified emulator cold starts. Scripts are PREPARED, NOT RUN in
.work/release-phone-2026-11-03-build24; preflight expects installed23 and unchanged
signer. Notify immediately before explicit10.77.77.3:5555 --install-only. Never drive
phone UI. Phone23 is installed, no24 built/installed yet. Continue long run afterward.

Hosted routing Apple41634596520 and Native41634550829 still run at ff3b3246; they
exclude this host. Audit templates for next host Apple/Native are prepared under
.work/chat-native-host-apple-2026-11-03 and ...-hosted-... with placeholders; fill
source manifests/commit/run IDs after push. Do not dispatch another Apple until old
run finishes. New automatic Native will queue behind the existing run. Both earlier
Native failures remain rejected. Starter9959c1d unchanged/accepted; this is HRIS
application policy, no Starter port. Platform72447fb8 runtime304/schema116 unchanged.
All54 PRD groups remain open; chat UI exists, voice/video/meeting UI absent. Proceed
with edited reviewed-send and remaining communications after release24/hosted audits.


## Product notification runtime in progress — 3 November 2026

24 host, Android composition/resource, readiness, fixture and driver inputs are WIP
and are NOT yet accepted for product release. Current source manifest:
.work/chat-native-host-2026-11-03/inputs.json. Full check3 is running; keep those
inputs frozen until completion. No owned emulator is active. Native runner/device
scripts are prepared in that directory and must follow a successful source audit.

ApplicationNotificationHost now binds actual ApplicationCollaborationLifetime state
through recoveryParent to one SurfaceOwner and Process/Delivery. Android Application
implements the private action host and starts a single Android runtime. Current
localized immutable copy is sampled per publication/reconciliation; callback labels
resolve through the same application language. No Activity is retained.

Cold-start review found that initial Pending could produce Blocked(null), rejecting
MarkRead before restored identity arrived. Delivery now waits for this host's observed
session readiness within ONE8s deadline shared with execution. Closing releases its
waiting callers; cancellation remains caller-owned. Surface advisory state treats
Blocked for an old parent as Pending; a current-parent cleanup failure stays Blocked.
Actual Koin/encrypted SQLite test starts sealed MarkRead while application state is
Pending, then starts the real collaboration lifetime and verifies exact private read,
journal retirement and no outgoing message. It passes.

Focused1 rejected138.823s:39 cases pass, Android compile exposed missing explicit
core.settings.domain dependency in apps/android. Fixed. Focused2 passes203.129s:
112 desktop/eight suites plus15 proof/four, zero failures/errors/skips,127 fresh.
Root format/architecture/detekt and Android dev/proof assembly/lint pass. Unit tests
include start-once, replacement/held cleanup, late state, close-before-start, rejected
second host, cancellation, close during readiness, shared8s deadline and stale
Blocked advisory. All19 focused source/report digests audited.

The subsequent native runtime fixture and driver changes are NOT yet compiled/run.
They test real Android runtime resources/NotificationManager and joined cleanup with
explicitly fake admission/settings ports, separately from actual Koin desktop tests.
25 driver tests pass. Full3 includes all24 current inputs and root checks/Wasm/Android.
No native success, full-host acceptance or release24 has been claimed.

Hosted routing checks41634596520 Apple and41634550829 Native remain in progress at
ff3b3246. Do not duplicate/cancel Apple. They exclude this host increment. Starter
9959c1d and Platform72447fb8 remain unchanged; Platform runtime304/schema116, chat
push OFF. Phone23 remains installed. Next: complete full/native host checks, commit
and push accepted source/docs, then build/validate release24 and notify before
10.77.77.3:5555 install-only. Do not launch or drive phone UI. Continue reviewed-send
and remaining PRD afterward; all54 groups remain open, voice/video/meeting UI absent.


## Updated hosted checks dispatched — 3 November 2026

Routing/driver increment ff3b3246f726e86be91d84efc1da1b5033809c38 is pushed and locally
accepted below. ONE full [Apple41634596520](measurements/chat-native-routing-apple-2026-11-03.json)
and automatic [Native41634550829](measurements/chat-native-routing-hosted-2026-11-03.json)
are running at that source. Source manifests and audit/fetch scripts are ready in
matching .work directories. Apple must include34 workspace-domain simulator cases
(14 selection) plus full Xcode; do not cancel it by dispatching another same-branch
Apple. Native must retain reply-recovery.json, validate at most four completed worker
turns, pass all five actions and providers, and have all three SystemUI PNGs inspected.
The two earlier rejected Native runs remain rejected. No local heavy job or owned
emulator remains. Begin application runtime integration described below/host-next.txt.
Phone23 is installed; next meaningful validated product24+. No phone action yet.


## Native chat routing accepted locally — 3 November 2026

[Contract](collaboration-native-chat-navigation.md) /
[evidence](measurements/chat-native-routing-2026-11-03.json). All36 current source,
resource, manifest, fixture/driver and workflow inputs are accepted locally.
Focused5:47 fresh tests/ten suites in80.024s, zero failures/errors/skips.
Full6:558.836s,3,956 desktop/561 suites,230 Android host/37,15 proof/four,ten logic/two;
zero failures/errors/skips,1,039 fresh cases/157 suites. Root quality, Wasm compilation,
Android dev/proof assembly and lint pass; product lint zero issues, proof47 warnings/
zero errors. All source and report digests audited. No local heavy job remains.

Native1 passes110.2s on owned API35 emulator5562. Raw16 groups; three ordinary process
kills/four callbacks retain exactly one protected input then one reviewed copy;
wrong account cannot consume it, old family requires review, HTTP/messages zero.
The recovery worker completes locally in one178ms turn. Five actions Open/Read/
Dismiss/Reply/Mute pass; real SystemUI reply editor/card retirement passes. All three
native PNGs and both narrow/large-text EN/ID status PNGs ACTUALLY INSPECTED. APK SHA256
cf4a8d72fcca67ed48bc66a840b36aac0ade622492aaeb6cbb6ed68950173eb3. Fixture uninstalled;
owned emulator PID1153410 stopped, transport absent. No phone action. Work and
runner/audits retained in .work/chat-native-routing-2026-11-03.

Typed Activity cold/new intents authenticate Open independently of display readiness,
then select with captured actual identity/workspace origin. The ViewModel joins
superseded native/public requests; explicit company change also joins native cleanup.
The final route rechecks identity, workspace, policy and RESUMED lifecycle. Actual
Koin/SQLite plus full StarterApp Compose flow proves waiting before RESUMED and back
to Chat. MFA has the existing verification/company-selection path and explicit retry.
Five focused runs preserve failures: initial missing token; incomplete UI fixture plus
bad fixture disposal (exact test process terminated after snapshot); missing platform
notification bindings; incorrect single-row assertion for a retained three-row list;
then all47 pass. No production admission/cancellation guard weakened.

Both hosted Native f38441624916023 and ee269941628358557 are REJECTED at input proof
line206: the first bounded worker turn did not report Complete, but its actual result
was not recorded. Artifacts/source hashes verified;245 host/proof and raw16 pass,
five actions and five providers unexecuted. The updated proof records redacted results
and retained row counts, drives at most four independent worker invocations when
Retry requests a later scheduling turn, and still requires final exact single copy,
no loss/HTTP/commands. Production1s/4s deadlines unchanged.25 Python driver cases pass.
Fresh hosted Native on this source MUST pass; do not accept either rejected run or
claim that local one-turn success establishes the hosted failure cause.

Apple f38441624960880 is ACCEPTED:1,045 simulator/119 suites,340 verified sources,
compile609s/tests79s/full Xcode1247s,artifact13228012431 SHA256
ede6633618f385af15a46ad63c4fefbdd56a559158813ca4608358ff1307eaa8. It excludes new routing
and six workspace files. CI now explicitly runs core:workspace:domain simulator tests.
NEXT push this increment and dispatch ONE full updated Apple, prepare source/report
manifests; collect the automatic Native run. No Apple is currently running.
Starter9959c1d remains clean and fully accepted (112 Apple cases/full Xcode); this
routing/HRIS policy is not a Starter port. Platform304/schema116 remains unchanged.

NEXT implementation: bind the product Android application notification runtime.
ApplicationCollaborationLifetime.state -> recoveryParent must own its SurfaceOwner;
never derive parent from Activity/selected workspace. Use one explicit application
scope, real native display/private receiver, actual Koin Process/Delivery, and actual
Reply/ActionCallbacks. Application implements AndroidCollaborationActionHost and
starts once after collaboration lifetime; disposal closes and joins owned work.
Resolve labels using app/device language. Prefer a typed immutable-copy supplier
sampled once per publish/reconcile so language can change without rebuilding process;
constant suppliers preserve existing fixture behavior. Test parent replacement,
held native cleanup, start-once/close-before-start and actual graph/native wiring.
Details/alternatives reviewed in .work/chat-native-routing-2026-11-03/host-next.txt.
Production display is STILL UNBOUND and chat push OFF. After real host acceptance,
prepare meaningful release24, validate minified cold startup, notify before installing
with explicit10.77.77.3:5555 --install-only. Never launch/drive phone UI;23 is installed.
Then continue edited reviewed-send and remaining communications. All54 groups remain
OPEN; chat UI exists, voice/video/meeting UI does not. Continue long run, not a final.


## Native app routing in progress; hosted callback failure traced — 3 November 2026

31 application/localization source and test files are now APPLIED but UNVALIDATED.
Read .work/chat-native-routing-2026-11-03/inputs.json and resolver-decision.txt;
the older eight draft files are historical and must not overwrite current source.
Typed Activity ingress, exact sealed Open authentication independent of display
readiness, captured-owner company selection, collectLatest cancellation/join,
RESUMED final navigation fence and compact EN/ID retry/verification UI are present.
Unit/MVI, actual Koin+encrypted SQLite and narrow/large-text layout tests are written.
Focused check1 is running; no other local heavy job or owned emulator. Keep inputs
frozen until it finishes. manual-selection-followup.txt records an additional review
fix: explicit company change must cancel/join active native navigation before leave.
Screenshots are not yet generated or inspected; application routing is not accepted.
The production display host remains unbound, and chat push remains OFF.

[Native41624916023](measurements/chat-native-callbacks-hosted-2026-11-03.json) is
REJECTED at f3843601. Downloaded archive/source/report digests verified. 230 host/37,
15 proof/four and raw16 API35 cases pass; input recovery then fails at
NotificationInputProof.kt:206 because the FIRST bounded worker turn does not report
Complete. Its result category was not recorded, so timeout is not yet proven.
Five native actions/SystemUI and all five provider scenarios did not run. Fixture
uninstall/data cleanup is verified. Do not mark this run accepted or merely weaken
the assertion. New automatic Native41628358557 is in progress at ee2699c3, with
unchanged input proof code; no duplicate rerun. Callback local native5 remains valid.

Mobile Apple41624960880 still runs at f384; wait before dispatching another Apple.
Starter Apple41626260198 is now ACCEPTED:112 simulator/30, compile571s/tests154s/
full Xcode664s,32 verified sources,artifact13228195169; Starter docs9959c1d pushed.
Platform304/schema116 unchanged. Phone23 remains installed; next meaningful validated
product24+, notify before install-only and never drive phone UI. All54 groups open.
Continue routing and actual host integration, then reviewed-send and remaining PRD.


## Guarded company selection accepted locally — 3 November 2026

[Contract](collaboration-native-link-ownership.md) /
[evidence](measurements/chat-native-link-ownership-2026-11-03.json).
Six core/workspace source inputs pass full3 in838.365s: 3,934 desktop/556 suites,
230 Android host/37 and ten logic/two; zero failures/errors/skips. 3,720 fresh cases/
476 suites. Root quality, Wasm, Android dev assembly and lint pass; lint zero issues.
Focused2 passes34 fresh domain/three suites in56.321s. All source/report audits are
complete in .work/chat-native-navigation-2026-11-03. No local heavy job remains.

WorkspaceSelectionOwner captures actual session/audience/account/native family.
WorkspaceSelectionOrigin additionally captures exact selected workspace ID, including
explicit null. SelectWorkspace rejects mismatches before company/account lookup,
guards successful continuations, uses existing CAS, and withholds success after
late commit/cancellation. WorkspaceContext exposes actual owner even with no admitted
company. Six new tests cover replacement at each boundary and manual selection.
Focused1 rejected32.594s/one of34: test incorrectly expected previous-family selection
admitted. Corrected to null current workspace with stored fact preserved; no guard
relaxed. No application navigation or production host activation in these six files.

NEXT typed application routing. Eight source drafts are prepared but NOT APPLIED in
.work/chat-native-routing-2026-11-03/draft-map.json. Read resolver-decision.txt there:
Open must authenticate through existing domain Open and extracted common input checks,
independent of Delivery/Process display readiness. Draft navigation helper has been
updated to authenticated Invocation. Other app changes still required: typed channel
and flow, AppStage actual owner, pending-link owner check, MVI collectLatest lifetime/
retry/dismiss, compact localized status, actual Koin/navigation tests. Full details in
.work/chat-native-navigation-2026-11-03/implementation-next.txt and ui-decisions.txt;
resolver-decision supersedes its older Delivery.act suggestion. MFA failure needs an
existing verification/company-selection path, not a useless Retry loop. No draft .kt
files live outside production/test source sets; scratch uses .txt.

Callback implementation f3843601 is committed/pushed. ONE full
[Apple41624960880](measurements/chat-native-callbacks-apple-2026-11-03.json) and automatic
[Native41624916023](measurements/chat-native-callbacks-hosted-2026-11-03.json) remain
running at f384. Manifests/audit scripts are ready in corresponding .work directories.
Native acceptance requires five actual actions and inspected SystemUI PNGs. Do not
dispatch another same-branch Apple before this one finishes (CI cancels in progress).
Current six workspace files need later full Apple coverage. Native automatically queues
new source; do not duplicate/cancel. Earlier Native/Apple4389 audits already accepted.

Generic Starter raw port cb36373/docs25af5af is pushed/clean: eight identical files,
260.027s,377 desktop/18 browser/six logic; zero. All ten new common contracts fresh on
both platforms, lint0errors/44priorwarnings. Full Starter Apple41626260198 still pending,
32-source manifest/audit prepared. Starter has no workspace module; selection policy
stays in Mobile. Platform304/schema116 unchanged. Phone23 already delivered; next
meaningful validated APK24+, notify before install-only, never drive phone UI.
Production notification hosts remain unbound; chat push OFF. Reviewed edited-send,
voice/video/meeting UI and all54 PRD groups remain open. Continue long run.

## Native callbacks and actual SystemUI reply accepted locally — 3 November 2026

[Contract](collaboration-native-notification-callbacks.md) /
[evidence](measurements/chat-native-callbacks-2026-11-03.json).
Current implementation has 21 tracked inputs, including the CI SystemUI opt-in.
Full9 passed in 115.487s: 3,928 desktop/556 suites, 230 host/37, 15 proof/four,
ten build-logic/two; zero failures/errors/skips. Fifteen proof cases are fresh;
other reports reused. Full5 passed in 365.122s with 579 fresh cases/91 suites.
Final Python driver tests: 21, direct entry and discovery both pass.

Native5 PASSED in 116.032s on the exclusively owned API35 emulator5562:
16 raw groups, three ordinary process kills/four input callbacks, and five actual
application actions (Open, MarkRead, Dismiss, Reply, Mute). All use real OS components
and encrypted Room/Keystore; fictional session/history, HTTP zero, no message command.
Input recovery 16.004s; action proof 49.488s. All three SystemUI screenshots ACTUALLY
VIEWED. Reply card, private text and editor disappear. Final stricter Python validator
also accepts this real XML and rejects native2/3/4 stale-card XML. APK SHA256
2eddbccf0061d0cb62ab26ddaf63eba2902b4606efe8a94e3e7acf4a295e7c8d.
Fixture uninstalled; emulator PID694671 stopped and transport absent. No local heavy
job remains. All evidence/audit complete in .work/chat-native-callbacks-2026-11-03.

Rejected attempts remain recorded: native1 fixture DM version was wrong; native2
plain cancel, native3 immediate neutral/cancel and native4 publication-barrier/cancel
left original SystemUI history. Native5 uses silent empty updates with public 600ms
OS expiry, publication confirmation and absence confirmation, batched for namespace
cleanup. No hidden flags. Generic eight-file notification port to Starter is NEXT;
raw primitives are not there yet. Do not copy HRIS callback policy or proof app.

[Native41615080704](measurements/chat-notification-actions-native-2026-11-03.json)
at4389f537 is independently accepted/audited: five provider/three suites, 230 host/37,
15 proof/four; zero. 341 source hashes and GitHub artifact13223539624 verified.
Archive SHA256 c25aef94c3251cf3226733cecd0c3951292e55542c47d2f4d4a29db4ae7632fa.
Build998s/Platform348s/native1152s. Raw16 and three input-recovery kills pass.
[Apple41616077153](measurements/chat-notification-actions-apple-2026-11-03.json)
at4389 is also accepted: 1,045 simulator/119 suites, 335 source hashes, full Xcode.
Neither hosted run covers the current callback changes. Do not repeat old audits.

NEXT commit/push this implementation; automatic native CI now includes --reply-system-ui.
Dispatch one full Apple at the new source, prepare both evidence audits, then port
raw primitives to Starter with independent root/Android/browser/Web/Apple checks.
Continue typed native Activity Open/cross-company navigation and production host binding;
draft in .work/chat-native-navigation-2026-11-03. No navigation edits yet. Reviewed
edited-send still pending. Production notification hosts remain unbound, chat push OFF.
Phone23 already delivered; next meaningful validated product build24+, notify before
install-only and never drive phone UI. Starterd312769 and Platform72447fb8 unchanged.
All54 communications PRD groups OPEN; continue long run.

## Saved-reply native accepted; callback native proof being built

[Native41609991459](measurements/chat-saved-replies-native-2026-11-03.json) at
8b5f8750 is accepted/audited:five provider/three suites,230 Android host/37 and15
proof/four, zero failures/errors/skips;337 source hashes verified. GitHub artifact
13221852660 ZIP SHA256c716e1dfb01b62db127f0bb96ba446896c482c8ab67176a0982bcfbfb74f3341.
Allfive APK digests7fe9569397794fba22672b1464aecdaa20f793c8eed157fe668cce754ea451c5.
Raw16/two, verified fixture uninstall, three ordinary process kills/four callbacks
PIDs3669,3882,4058,4243; one protected input to one review input, wrong-account
preservation, HTTP0 and no command in capture proof. Input recovery18.229s.
Build977s/Platform333s/native1113s. Do not repeat completed fetch/audit. This covers
phone23 source; later actions4389 and current native callbacks remain separate.

CURRENT19 uncommitted implementation/fixture/driver inputs are frozen in
.work/chat-native-callbacks-2026-11-03/check-4 (PTY68732), ONLY local heavy job.
Focused1 PASS116.816s/57fresh4; focused2 PASS115.299s/72fresh6, zero. Actual Koin/SQLite
Delivery/Process/global callback forwarding exercised, including owner contention.
Nonreply application callbacks and private Android action facade/receiver added;
Open cannot become a broadcast action. Process waits boundedly for native ownership
and retries only pre-mutation BUSY. Reply capture acknowledgment never queues or reads.
Native fixture with five actual PendingIntent actions and optional SystemUI reply
driver now added but NOT accepted. Full3 rejected41.515s solely fixture LongMethod;
seedOriginal extracted, cleanup added, full4 now running. No emulator started.
After full checks, run one short owned5562 proof with --reply-system-ui, inspect
actual images and callback/journal records. Add failure audit if needed; no local
long provider suite. Then commit/push and hosted full Apple/native exact source.

Native438941615080704 and Apple438941616077153 remain outstanding; no duplicates.
Starter d312769 clean/pushed: independent Apple41612522666 now ACCEPTED102sim/28,
24sources/full Xcode686s, see Starter handoff. Phone23 already delivered; next product
version must be24+. Native Activity product navigation, binding and reviewed edited-
send remain next. Production host unbound/push OFF; all54 PRD groups OPEN. Continue.

## Saved-reply Apple accepted; native callback acknowledgment in progress

Apple41610059249 at8b5f8750 is ACCEPTED and audited:
[evidence](measurements/chat-saved-replies-apple-2026-11-03.json).
1,037 simulator/117 suites, zero;331 source hashes and artifact13219964122 verified.
GitHub ZIP SHA256924e0e9790073255bf07763efa507536466d946f782ac8cff86ab0718ce39146.
Compile693s/tests127s/full Xcode1296s. Requires/passes two Apple command prevention,
seven discard,six observe and14 complete cases. Full audit complete; do not repeat.
This covers delivered phone23 source, excludes later action/dismissal4389.

Common actions4389f537911d3a6a09b7e81d10978af0bca3922f is committed/pushed after
full local acceptance below. ONE full Apple41616077153 now dispatched there;
[checkpoint](measurements/chat-notification-actions-apple-2026-11-03.json),
335 frozen source hashes. Require five point-read/three dismissal domain cases
plus previous cases/full Xcode. Native8b41609991459 is running and438941615080704 is
pending behind it. Both native source manifests/fetch/audit scripts are prepared in
.work/chat-saved-replies-native-2026-11-03 and
.work/chat-notification-actions-native-2026-11-03. No duplicate/cancellation.

CURRENT seven source inputs are APPLIED, UNCOMMITTED in
.work/chat-native-callbacks-2026-11-03, frozen in focused1 (PTY60197), ONLY local heavy
job. Protected ReplyCallbacks now acknowledge only after confirmed capture, with
Saved retained if acknowledgment fails/timeouts and all cleanup joined within owner
lifetime. Delivery/Process forward actions and reply acknowledgment through their
bound owner, then request journal discovery. Actions acknowledgment authenticates
Reply target, persists Dismissing/removes exact card/finalizes; no queue or read.
Actual graph/owner tests added, acceptance pending. Need a bound Delivery/Process
integration test to execute forwarding, then nonreply Android callback facade and
actual owned native IPC/SystemUI proof. New constructor receiver hooks are not wired
in production. Android Activity Chat navigation remains next; company/session guards
must be preserved. No newer phone build or install;23 is already delivered.

Starter c5ca7c3 clean/pushed, Apple41612522666 pending. Platform304/schema116 and
Google mapping unchanged. Chat push OFF; all54 groups OPEN. Continue long run.


## Native actions and durable dismissal accepted locally

[Contract](collaboration-notification-action-disposition.md) /
[evidence](measurements/chat-notification-actions-2026-11-03.json).
Sixteen unchanged implementation inputs are ACCEPTED locally. Focused2 PASS103.277s:
61 desktop/seven suites,45 fresh/six. Full3 PASS459.096s:3,916 desktop/555,230 Android
host/37,16 fresh browser/one,ten logic/two;2,326 fresh/283, zero failures/errors/skips.
Root quality/Android dev assembly/lint/shared Wasm pass, lint zero issues. Full audit
and record complete in .work/chat-notification-actions-2026-11-03. No local heavy
job or owned emulator remains. Initial rejected focused1 and exact test correction
are preserved: owner close returns Unauthorized after join; caller cancellation
separately propagates and retry does not duplicate a private read intent.

Open returns captured navigation only after sealed kind/full reference checks.
Read/mute use durable commands and exact native/journal cleanup. Dismissing persists
before OS removal, strips preview/actions and remains indexed without being eligible
for display. Metadata-only recovery finishes Dismissed after restart or content denial;
old renders and capacity remain protected. Actual app tests use Koin/SQLite and a
SIMULATED OS display. This is not native callback or product notification activation.

NEXT commit/push, then connect bounded Android action callbacks, reply acknowledgment
and Activity Open navigation, with actual isolated Android proof. Concrete next design
in .work/chat-notification-actions-2026-11-03/native-followup.txt. Full Apple must include
five point-read/three dismissal-intent domain cases and current application, after
saved-reply41610059249 completes; no same-scope cancellation or duplicate dispatch.
Production hosts remain unbound/chat push OFF. Reviewed edited-send still pending.
Phone23 already installed; next validated product release must use code>23 and notify
before explicit install-only, no phone UI. Platform304/schema116 unchanged. All54 PRD
groups remain OPEN, voice/video/meeting UI absent. Continue long run.

Starter command prevention port is locally accepted and pushed c5ca7c3 (sourceaa58a21).
Full Starter Apple41612522666 is running on24 frozen sources; audit script ready.
Previous callback native6976 is fully accepted below; no repeat audit. Mobile saved-
reply Apple41610059249 at8b5f8750 is still building full Xcode and excludes these actions.

## Callback native proof accepted — 3 November 2026

[41601851260](measurements/chat-notification-callback-native-2026-11-03.json) at
6976d49ca0befc9c4bf391ee2755da69fbc7c11f is ACCEPTED and audited. Five provider/three
suites,230 Android host/37,15 proof/four, zero failures/errors/skips. All306 exact
sources and GitHub artifact13218014959 verified; ZIP digest
`e60be6dde0b3a9cbd75b00499cdf15b0732953aaa4d7767574e4483ed5e6ac1e`.
Raw16/two with verified fixture uninstall; actual RemoteInput/PendingIntent recovery
across three ordinary process deaths, four callback PIDs3520/3712/3965/4237,
one protected input -> one review input, wrong-account preservation, no HTTP/message
command in that isolated capture proof. Input recovery24.077s. SDK encrypted sends,
seven receipt POST200 and WorkManager death/network recovery also passed separately.
No repeated fetch/audit; complete in .work/chat-notification-callback-native-2026-11-03.
Excludes saved-reply UI8b5f8750 and current uncommitted actions. Production host remains
unbound and chat push OFF. Phone23 already installed; no new phone action.

## Current native action implementation under validation

Sixteen source inputs are APPLIED, UNCOMMITTED in
.work/chat-notification-actions-2026-11-03. Focused1 (PTY59769) is the ONLY local
heavy job; freeze these inputs until completion. The implementation authenticates
native kind plus full reference before dispatch. Open returns captured navigation
only; read/mute/dismiss use the native owner and exact journal/native cleanup.
No Android nonreply receiver or Activity navigation wiring yet; production unbound.

Dismiss now persists Dismissing before native removal, strips preview/actions and
keeps the encrypted journal index discoverable. It cannot republish that original.
Reconciler handles this state as metadata-only cleanup and finishes Dismissed,
including after company content denial or native-family replacement. Pending cleanup
retains bounded capacity. Read/mute retain their existing durable command semantics.
Domain/actual SQLite/application regression fixtures are included; not accepted yet.
Focused runner includes point read, dismissal intent, publication lifecycle/storage,
application actions/commands/bindings and quality. Full browser/root/Android/Wasm
and native callback consumer still required. No phone install of this WIP.

Starter aa58a21 port PASSED253.982s:367 desktop/121,53 browser/nine,six logic/one,
221 fresh/65, zero;lint0 errors/44 existing warnings. Port and docs c5ca7c3 pushed.
Independent Starter Apple41612522666 onaa58a21 is running,24 frozen sources/audit ready.
Mobile saved-reply Apple41610059249 at8b5f8750 is building the full Xcode application.
Callback native41601851260 at6976 completed successfully and its artifact audit is
now running; do not duplicate it. Phone23 already installed, do not reinstall.

## Release 23 installed; native actions in progress

Release23 from8b5f87501d618ac40fdcaccc94d4d97b85270e00 is INSTALLED:
[evidence](measurements/android-release-phone-2026-11-03-build23.json).
DevRelease1.0.0/code23, arm64, nondebuggable, same signer/API/Firebase and18 backup
exclusions. Build/lint PASS504.577s, zero lint issues; clean source before/after.
APK87,923,116 bytes, SHA256
`f835766c7be05ca9056b9da7075e626a59a238e73aa3a5d6ab2ec26e946ea679`.
Owned5562/PID168155 boot30.552s; startup smoke PASS51.440s/two12-second cold starts,
no crash, screenshot actually viewed. Owner stopped/transport absent1.605s.
After notice, install-only to10.77.77.3:5555 started09:30:52Z and verified90.085s;
installed bytes match, UID10962/first-install preserved. No phone UI launch/data clear/
uninstall or real-account messages. DO NOT reinstall23; next validated update code>23.

Saved-reply consumer8b5f8750 and local evidence are committed/pushed. Full hosted
Apple41610059249 is RUNNING on that source,331 frozen hashes:
[checkpoint](measurements/chat-saved-replies-apple-2026-11-03.json).
Audit script ready in .work/chat-saved-replies-apple-2026-11-03. Require exact two
AppleCommandPrevention,seven Discard,six Observe and14 Complete cases plus full Xcode.
Previous callback native41601851260 at6976 remains running; do not duplicate/cancel.

CURRENT UNCOMMITTED native-action work: five source files APPLIED in
.work/chat-notification-actions-2026-11-03, NOT TESTED yet. Point journal reference
read and application Open/read/mute/dismiss coordinator with actual graph and
ownership regression fixtures. Android action receiver/Open navigation not wired.
Before validation, complete durable dismissal: a process death between OS dismissal
and journal cancellation must not repost the dismissed original. Read/mute already
retain durable state; simple remove-then-cancel is insufficient for dismiss.
A recoverable journal dismissal intent before native removal is under review.
Do not bind production hosts or claim completed action acceptance from these drafts.

The ONLY local heavy job is Starter command-prevention port full1 (PTY12066) in
../compose-fluent-starter/.work/command-prevention-port-2026-11-03. Eight source
hashes frozen there; no Starter source edits until its check completes. Independent
root/browser/Android/lint/Web and full hosted Apple required. Mobile release jobs
and emulator are complete. Platform304/schema116 unchanged, chat push OFF,
voice/video/meeting UI absent; all54 groups OPEN. Continue long run.


## Saved replies accepted locally; next deliver release 23 and complete native actions

[Contract](collaboration-saved-replies.md) /
[local evidence](measurements/chat-saved-replies-2026-11-03.json).
The 41 implementation inputs are ACCEPTED locally: compact Chat -> Saved replies ->
detail, company-admitted reactive private input, explicit safe discard and durable
cancellation without a temporary Queued row. Actual Koin/SQLite integration proves
input -> discard -> reopen -> late duplicate -> Cancelled, with no SDK enqueue/delivery.
Only exact Cancelled revision0 can retire a duplicate without disposable conversation
metadata; current account/company denial checks remain. No source test failures remain.

Focused5 PASS88.461s/76 desktop cases/11 suites (15 fresh/five). Full6 PASS749.590s:
3,898 desktop/552,230 Android host/37,34 actual browser/three,ten build-logic/two,
zero failures/errors/skips. Of these,3,188 cases/433 suites executed freshly; other
root suites were reused as recorded. Root quality/shared Wasm/Android dev assembly
and lint passed; lint zero issues. The 41 source hashes remained unchanged.
Three final Compose screenshots were actually viewed: compact list, detail and
320x640 Indonesian detail at twice the font scale. This is controlled screen evidence,
not all-screen/full-device acceptance. First four rejected focused attempts and
fixture/test-only corrections are preserved in the measurement; no policy was relaxed.
Local work/evidence: .work/chat-notification-review-2026-11-03. No local heavy job remains.

NEXT commit/push this consumer and dispatch ONE full hosted Apple on that source.
Require AppleCommandPreventionTest two Room cases plus Discard seven, Observe six
and Complete fourteen domain cases. Presentation/VM and application integration tests
execute on desktop, not in the current Apple simulator task; full Xcode compiles the
application. Phone22 is already installed. Build validated release23 with the same
signer/API/Firebase/data, inspect two owned-emulator starts, notify before explicit
10.77.77.3:5555 install-only; never launch or drive the phone UI.
Then implement native reply status/Open/read/mute/dismiss and durable reviewed-send
resolution, followed by production hosts and owned-device acceptance. No editable
review send yet. Open chat navigates only; different-body conflict stays held.
Chat push OFF; production hosts unbound. Voice/video/meeting UI absent; all54 PRD
groups OPEN. Continue long run after atomic pushed commits. No real-account messages.

Port the eight generic archiveAbsent files/tests to Starter after Mobile proof, with
independent root/browser/Android/lint/Web/full Apple. No schema migration. Existing
raw device store port is complete (Starter935d07d). Platform304/schema116 unchanged.

Native input worker41599045402 atadc0 is ACCEPTED and audited:
[evidence](measurements/chat-notification-input-recovery-native-2026-11-03.json).
Five native/three,230 host/37,15 proof/four; raw16/two31.807s and verified uninstall.
All297 sources/artifact13217406201/GitHub ZIP digest verified. Build989s/Platform329s/
native1089s. Includes input worker, excludes callbacks6976/current saved replies.
Callback native41601851260 at6976 is RUNNING; do not duplicate/cancel it. Its audit
must require the new inputRecovery proof across three ordinary process deaths.
Apple41603224301 at6976 is ACCEPTED and audited:
[evidence](measurements/chat-notification-callback-apple-2026-11-03.json).
1,021 simulator/114, zero;300 sources/artifact13217292129/digest verified;
compile708s/tests94s/full Xcode1137s. Excludes these saved replies. Do not repeat audit.
Physical iPhone waived; hosted Xcode remains mandatory, no native APNs/CallKit claim.

## Earlier callback checkpoint (current validation state above takes precedence)

## Android reply callbacks accepted locally with real process-death proof

[Callback contract](collaboration-notification-callbacks.md) /
[acceptance](measurements/chat-notification-callback-2026-11-03.json).
Fifteen inputs are ACCEPTED: focused1 PASS165.884s/22 fresh/six; full2 PASS279.701s/
543 fresh/84; final full3 after explicit HTTP cleanup PASS103.175s/15 fresh proof cases.
Full totals3,866 desktop/546,230 Android host/37,15 proof/four,ten logic/two, zero.
Fifteen final source hashes unchanged; lint0 errors/same47 existing warnings;
13 Python driver cases pass. Work/audits/record complete in
.work/chat-notification-callback-2026-11-03. No local heavy job or owned emulator remains.

Native2 PASS28.597s: raw16 groups/two plus actual input recovery14.624s. Three ordinary
exact-PID receiver deaths, separate sender PendingIntent/RemoteInput, two duplicates
leave one protected row, missing/nonreply callbacks unconfirmed, wrong account cannot
remove input, restored account/new local family retains one SessionChanged review.
Four receiver process IDs3220/3454/3562/3675. HTTP0/no command send. Actual Koin/Room/
Android Keystore and app worker; fictional identity/history key and no Matrix transport.
APK SHA256 `98a1ffcb2c1e8c371c816c4fda116628851739006b3c2f7c6f013a8e6abd71a5`.
Fixture uninstalled/absence verified; owned5562/PID3977137 stopped/absent1.607s.
Native1 was rejected before execution by invalid :reply-sender process name; corrected
:reply_sender installs. Original APK/log/audit retained. Driver now preserves rejected
installer diagnostics and verifies absence instead of masking rejection with uninstall.

Application-owned callback scope admits8/eight seconds; job completion releases native
callback even before coroutine entry. Private Android Reply-only receiver finishes
goAsync once and gives localized saved/unconfirmed feedback. Actual production
Application does NOT bind host/receiver. Current proof does not automate SystemUI
reply keyboard or verify its spinner; it does not send an encrypted notification reply.
NEXT continue native reply status acknowledgement, Open/read/mute/dismiss and retained
input review UI, then bind production notification hosts and perform full owned device
acceptance. Keep chat push OFF until those flows are usable. Do not call the full PRD
done; all54 groups remain open, voice/video/meeting UI absent.

Native41595695537 at5bc is ACCEPTED and audited:
[evidence](measurements/chat-notification-input-flow-native-2026-11-03.json).
Five provider/three suites,230 host/37,15 proof/four, raw16 and verified uninstall;
SDK3 encrypted sends/seven receipt POST200. Three worker states176 originals/11 passes/
11 reopens/priority1. WorkManager ordinary death/restart/network restoration.
All282 sources and artifact13214446060 verified, ZIP SHA256
`cef2497479e927b2c47d925f654f5e7691e04121279e4f2991694f980a38abe5`.
APK `9b8f6a31695bb73bc219f9bba9c1acda4678d4371d79663b6dcaecf640f945fd`;
raw31.799s/build858s/Platform307s/native1108s. Includes corrected receipt barrier,
excludesadc0 input worker/current callbacks. Audit complete; do not repeat.
Automatic native41599045402 atadc0 is RUNNING, excludes these callbacks. No duplicate
or cancellation. The callback commit must receive its own later hosted native gate,
whose raw driver now additionally requires inputRecovery and three ordinary kills.

Full Mobile Apple41599066906 atadc0a486 is ACCEPTED and audited:
[evidence](measurements/chat-notification-input-recovery-apple-2026-11-03.json).
1,021 simulator cases/114 suites, zero;290 exact sources, Protect4/Transfer11/Recovery9
and Apple raw storage8 included. Compile720s/tests96s/full Xcode1122s;
artifact13215930181 and GitHub digest verified. No repeat audit. Physical iPhone waived.
ONE callback Apple41603224301 at6976d49c is RUNNING, dispatched08:25:56Z;
[checkpoint](measurements/chat-notification-callback-apple-2026-11-03.json),
300 frozen sources/audit.py ready in .work/chat-notification-callback-apple-2026-11-03.
Native callback41601851260 at6976 is pending behind input worker41599045402 atadc0.
Both native fetch/audit scripts are ready in .work/chat-notification-input-recovery-native-
2026-11-03 and .work/chat-notification-callback-native-2026-11-03, respectively.
Require callback inputRecovery/three ordinary deaths for6976; do not duplicate/cancel.

Starter935d07d records independent Apple41596469348 acceptance:100 simulator/27,
all eight storage cases/full Xcode758s/16 sources/artifact13213137388 verified. Generic
raw storage port complete; generic notification port waits for integrated product proof.
Phone release22 from6976d49c is now INSTALLED:
[evidence](measurements/android-release-phone-2026-11-03-build22.json).
DevRelease1.0.0/code22, arm64, existing signer, nondebuggable. Build/lint PASS478.822s,
lint0 issues; clean source before/after build. APK87,873,208B, SHA256
`d838e46dc106db00fac3d2de57daea7dd632f5a7d30af29ce2990d58d0e2c883`.
Owned emulator smoke PASS41.207s/two12-second cold starts, no crashes; second startup
screenshot actually inspected. Emulator stopped/transport absent. User reenabled
WireGuard/ADB after timeout. After notice, install-only completed85.838s from08:28:02Z
at10.77.77.3:5555; pulled installed APK matches, UID10962/first-install preserved.
No app-data clear, uninstall, phone UI launch or real-account messages. Next validated
update must use code>22 and notify before install-only. Platform304/schema116 and
Google mapping unchanged.

NEXT retained-input review list/detail and safe discard/edited send, native reply status
and Open/read/mute/dismiss, then production hosts. Review must fence duplicate callbacks
before deleting input; never insert a temporary Queued command then cancel. Concrete
source-grounded design notes in .work/chat-notification-review-2026-11-03/design.txt;
no review UI implementation yet. Deliver a real consumer with any needed raw storage
addition, not another standalone foundation. Continue long run.


## Finite input recovery accepted locally; native callback integration next

The 23 Kotlin inputs in [input recovery](collaboration-notification-input-recovery.md)
are ACCEPTED locally; [evidence](measurements/chat-notification-input-recovery-2026-11-03.json).
Focused1 passed 229.149s with 83 fresh cases/10 suites. Full2 passed 361.759s:
3,861 desktop/545 suites, 230 Android host/37, eight fresh actual browser/one,
15 proof host/four and ten build-logic/two; 2,265 fresh cases/274 suites, zero
failures/errors/skips, all 23 source hashes unchanged. Root quality, proof assembly
and shared Wasm pass. Audit/record complete in
.work/chat-notification-input-recovery-2026-11-03. No local heavy job remains.

The account-encrypted cursor snapshots at most 64 opaque references in four pages,
advances exact CAS before attempts and retains interrupted/failed retry across later
healthy entries and process reopen. Finish detects arrivals and replacement generations.
Actual application worker wiring adds a four-second phase and at most two one-second
attempts before existing reply/priority/company phases; overall 90s remains unchanged.
Koin/SQLite tests exercise 64 inputs, failed prefix, 32 container reopens, cancellation,
arrivals and old-family review. The provider proof container now supplies its own
persistent Android input credential store. These are application/storage proofs;
native callbacks and notification-input ordinary OS-death replay remain open.

NEXT implement native capture callback ownership AND actual OS-death proof together.
Capture and request a wake inside the bounded receiver; never restore Matrix there.
Handle cancellation before coroutine entry and finish goAsync exactly once; give
explicit generic feedback on failed capture. Keep Open navigation-only. Add retained
input review before production host activation. Do not split more standalone storage
foundations. Source-grounded notes are in
.work/chat-notification-input-recovery-2026-11-03/next-native-callback.txt.
Production native notification hosts stay unbound and chat push OFF.

Native 41595695537 at 5bc5a7a31d17aff8fab4e7bb3e4cac32d37bf198 is RUNNING;
no duplicate/cancellation. Audit scripts and 282 frozen sources are READY in
.work/chat-notification-input-flow-native-2026-11-03. It includes capture/transfer
and the corrected own-receipt barrier, excludes the current input worker. Earlier
89dd and bf69 queued runs were superseded automatically. Latest ACCEPTED native
41592216948 at 81c7 has five provider cases, raw16 groups/two with verified uninstall,
230 Android host and11 proof host, zero; artifact13212856645/source273. Its
[evidence](measurements/chat-notification-input-native-2026-11-03.json) is already audited.
Do not redo. That success with the old receipt barrier does not negate the separately
reproduced de69 fixture race.

Latest ACCEPTED Mobile Apple 41592237356 at 81c7 has 997 simulator cases/111 suites
and full Xcode, excludes 89dd transfer, 5bc flow and this worker. Dispatch ONE full
Mobile Apple after this accepted worker is committed/pushed, requiring the new
Protect/Transfer/Recovery domain cases plus eight Apple raw storage cases. No physical
iPhone is required. Record exact sources, all report/artifact digests and Xcode result.

Starter raw DeviceRecordStore is COMMITTED/PUSHED at16f4b237 (docs c37bff9), with
independent root/browser/Android/lint/Web PASS325.503s:365 desktop,51 fresh browser,
six logic,16 identical Mobile inputs/schema. Lint0 errors/44 existing warnings.
Full Starter Apple41596469348 remains RUNNING, dispatched once07:27:45Z; audit script
ready in Starter .work/device-records-apple-2026-11-03. Do not repeat local tests.
Generic notification primitive port waits for integrated Mobile proof.

Phone release21 from de69 remains installed; no new product build/install this
increment. Notify before the next validated build's install-only delivery to
10.77.77.3:5555; preserve signer/data/session and never drive the phone UI.
Platform304/schema116 and Google mapping remain unchanged. All54 PRD groups remain
open; voice/video/meeting UI absent. Continue long run.

## Earlier checkpoints (historical; current state above takes precedence)


## Application capture and protected-input recovery accepted locally

[Capture/recovery flow](collaboration-notification-input.md) is locally ACCEPTED;
[evidence](measurements/chat-notification-input-flow-2026-11-03.json).
CollaborationNotificationReplies.capture protects input before durable wake/scheduling,
without account restoration or Matrix. Eight-second turn/eight shared admissions;
scheduling at most1s. Failed enqueue leaves the protected reference and pending wake.
resumeProtected shares the existing submit/resume mutex through authenticated transfer,
exact cleanup, account reread and processing. Independent capture stays available.
Uncertain transfer cannot start a command in that call; replay reproduces the original.

Focused1 PASSED94.632s:41 fresh desktop cases/four suites. Full2 PASSED265.033s:
3,837 desktop/542,230 Android-host/37,15 proof-host/four,ten build-logic/two;
546 fresh/86, zero failures/errors/skips and five unchanged Kotlin hashes. Root quality,
proof assembly and shared Wasm pass. Eight additional actual Koin/encrypted SQLite
cases cover no-session capture/wake/reopen, shared-mutex exclusion, independent capture,
cancellation, scheduler failure/deadline, bounded admission and copy/cleanup response
loss. Actual HTTP/Matrix are fictional. Work/audit/record complete in
.work/chat-notification-input-flow-2026-11-03; drafts are APPLIED, do not copy again.

Underlying capture/transfer use cases are committed/pushed89ddf252. Their independent
[proof](measurements/chat-notification-input-transfer-2026-11-03.json) has54 desktop+
11 proof focused cases and full3,829 desktop/230 host/11 proof/ten logic, zero; exact
account copy read before device removal, old-family review and no-session preservation.

NEXT implement durable device-input discovery/worker and native callback ownership.
The refined design in .work/chat-notification-input-recovery-2026-11-03/design.txt
uses an account-encrypted finite snapshot of at most64 opaque references, nextIndex,
inFlight/sticky retry/continuation/finished, exact CAS and guards after pending work.
Snapshot capture is bounded to four metadata pages; persist each attempt before running
resumeProtected. Finish rereads current references to detect arrivals/replacement before
old cursor. A finite snapshot prevents a slow first page or constant arrival from
starving older work across process restarts. These are design notes, NOT implemented.
Preserve the90s worker's reserved company/priority/reply budgets when adding this phase.

Native callbacks must only capture/request wake, never restore Matrix inside goAsync.
HrisWorkerProofContainer currently omits explicit notification-input credentials and
uses the fixture memory default; add AndroidCredentialStore before claiming input
process-death proof. Production Android already has the key. Explicit discard must
coordinate with account transfer/commands. Native input callbacks, automatic protected
row discovery, review UI and input OS-death proof remain open. No production binding.

The receipt fixture correction is locally ACCEPTED:
[evidence](measurements/chat-read-receipt-barrier-2026-11-03.json). Wait up to20s for
both own receipt IDs after the independent sender observation; privacy and restored-store
assertions remain. Four new regression cases cover either field lagging, wrong event,
deadline/cancellation joins and storage failures. Root/proof assembly/lint passed116.304s;
15 fresh proof-host/four suites, two source hashes unchanged, product root tests reused.
Lint0 errors/same47 existing warnings. No native acceptance for this correction yet.

Hosted native41587502684 atde69 FAILED one SDK assertion at exchange:62;
[audited evidence](measurements/chat-notification-focus-native-2026-11-03.json).
Raw16 groups/two scenarios and the other four provider scenarios passed, with cleanup;
239 sources and artifact13210193167 verified. ZIP SHA256
`a64bcc7ca10b08c626d4451ff331bdc369f8404171905ddcaf4d3791f9427771`.
Five receipt POSTs returned200 before the failed own-store assertion. The artifact does
not show which receipt field lagged. Do not claim whole native acceptance. Automatic
81c7 run41592216948 is already running with the old barrier; no manual duplicate/cancel.
Later automatic queue may supersede pending89dd source with this correction.

Full Apple41592237356 at81c7 is ACCEPTED:
[evidence](measurements/chat-notification-input-apple-2026-11-03.json).
997 simulator cases/111 suites, zero failures/errors/skips,268 frozen sources;
compile466s/tests47s/full Xcode752s. All eight AppleDeviceRecordStoreTest cases passed.
Artifact13211114027 GitHub SHA256
`29edcd0c7b6c328965bc33064f92c434d47177486ee8dc9578d32735f7747d98`.
Audit complete in .work/chat-notification-input-apple-2026-11-03; do not repeat.
This includes raw schema13/protected input/Swift keys but EXCLUDES89dd capture/transfer
use cases. Consolidate those with the next application flow for one later full Apple.
Mobile raw storage is now proven for the generic Starter port:15 Kotlin files/schema13,
no HRIS keys, encryption or policy. Starter must run its own root/browser/Android/Web
and full Apple gates. Its five base database/schema files were byte-identical before
Mobile's raw-store increment; verify current status before porting.

Starter generic DeviceRecordStore port is APPLIED/UNCOMMITTED,16 byte-identical inputs
(15 Kotlin/schema13) from Mobile76bd/accepted Apple81c7. Four preexisting Kotlin bases
and schema12 matched. Independent Starter root/database browser/Android dev/lint/Web
check1 is RUNNING in .work/device-records-port-2026-11-03; this owns the ONLY local
heavy slot. Do not start Mobile Gradle/emulator until it completes. No Starter source
mutation during that check. Inspect reports/schema/source hashes, fix real failures,
commit/push, then dispatch one Starter full Apple requiring eight raw storage cases.

Phone21 remains installed. No product APK was built in this increment; next validated
phone update must notify first, select10.77.77.3:5555 and --install-only. Platform304/
schema116 remains unchanged. Chat push OFF/native hosts unbound/voice-video-meeting UI
absent/all54 PRD groups still open. Continue long run after these commits.

## Protected pre-session notification input accepted locally

The [encrypted input repository](collaboration-notification-input.md) is locally
ACCEPTED; [evidence](measurements/chat-notification-input-2026-11-03.json). Unverified
callback token/text is protected before login with a separate device content/index
key, stable HMAC identity, random-generation AES-GCM envelope, strict syntax before
key creation, existing-record key authentication, capacity64 and exact deletion.
Actual Android/Apple/Desktop/browser hosts supply and own the protected key; desktop
key persistence is independent of session persistence. This grants no account access,
UI display or send. No native callback or authenticated transfer is activated yet.

Focused1 PASSED232.721s with17 desktop cases/three suites. Its browser selector lacked
a trailing wildcard, selected ZERO cases and is NOT browser acceptance. Full2
PASSED363.451s:3,809 desktop/539 suites,230 Android-host/37,11 proof-host/three,ten
build-logic/two;2,216 fresh/270,root quality/proof assembly/shared Wasm pass. It inherited
the same empty browser filter. Corrected browser3 PASSED33.015s with14 FRESH actual
IndexedDB cases/one suite. All18 implementation inputs (17 Kotlin/one Swift) unchanged
across checks; zero failures/errors/skips. Commands, coverage gap and correction are
preserved. Fourteen real SQLite cases mirror browser behavior; one additional Koin
case verifies no-session retention, no implicit request, singleton key ownership and
close/reopen. Work/audits/record complete in .work/chat-notification-input-2026-11-03.
No repeat local checks absent new changes or failure.

Full Apple [41588371503](measurements/chat-notification-focus-apple-2026-11-03.json)
at de69f282 (phone21/focus) is now ACCEPTED:989 simulator cases/110 suites, zero failures/
errors/skips,234 frozen sources; compile662s/tests92s/full Xcode1194s. Artifact13210841463
GitHub SHA256 `3bc0e9347b239da15165c160db5003384bb82b925f641560f115344cdc4e6375`.
Audit complete in .work/chat-notification-focus-apple-2026-11-03; do not repeat. This
excludes raw device records and protected input. AFTER this accepted source is committed/
pushed, dispatch ONE consolidated full Apple with prior234 paths plus raw15/schema13
and input18. Verify all eight AppleDeviceRecordStoreTest cases and complete Xcode;
physical iPhone remains waived. Do not dispatch another while that run is active.

NEXT authenticated capture/transfer and exact cleanup before application worker/native
callback/review UI. Read .work/chat-notification-input-2026-11-03/next-transfer.txt and
the existing CollaborationNotificationReplies mutex. Coordinate repos directly in
use cases, preserve guards after pending work, prove actual-account durable copy before
removing device input, and keep old-family input for review. Do not expose unverified
text to current-account UI or use a supplied receipt without rereading stored facts.
Worker paging must not starve mixed-account inputs. Protected repository is not itself
native callback/OS-death acceptance or Drive/iCloud backup.

No local heavy job or owned emulator remains. Phone21 is already installed; no APK was
built in this repository increment. Native41587502684 at de69 continues; automatic
41590342856 at76bd was queued before this next push. Let automatic queue supersede
pending older source; no manual duplicate/cancel. Latest accepted native41585074316 at
a90 is documented below. Live304/schema116 and Starter0d4432e unchanged. Generic raw
storage port awaits Mobile Apple acceptance. All54 PRD groups remain open; chat push
OFF/native notification hosts unbound/voice-video-meeting UI absent.

Historical in-progress notes below are superseded by this acceptance.

## Encrypted notification input in progress

Raw DeviceRecordStore is COMMITTED/PUSHED at
**76bd7673e898b2c5ca4b6af98827a03848fc6071**; accepted local evidence below.
The next encrypted input implementation is UNCOMMITTED and focused1 is RUNNING:
.work/chat-notification-input-2026-11-03,18 frozen inputs (17 Kotlin/one Swift).
No source mutation or second local heavy job until this check completes. SQLite14
new storage cases already passed; browser/Koin/quality results are not yet accepted.

The six .txt drafts from the raw-store work directory have now been APPLIED and
improved; do not recopy them over current source. Canonical token decoding now occurs
before key creation. Retention authenticates an existing record before allowing more
writes, so a well-formed wrong key cannot mix encryption keys in one queue. Explicit
protected input key is wired in Android/Apple/browser/Desktop and closed by its app
container. Desktop uses OS credential storage independently of session-persistence
settings. New actual SQLite/browser tests exercise fourteen cases each; one Koin
integration checks no-session preservation/reopen and key ownership. Contract:
[protected pre-session input](collaboration-notification-input.md). No use case/native
callback/review UI or authenticated transfer cleanup is implemented by this increment.

After focused1: inspect fresh XML and exact source hashes; fix any real failure with
its failed-run evidence preserved. Run full root/proof assembly/Wasm and required
browser cases, record actual counts, commit and push the accepted implementation.
Then implement authenticated transfer and exact cleanup before OS callback/review UI.
Do not expose unverified text under a different account or treat local encryption
as account authorization. Physical iPhone waived; full Xcode still required.

Hosted Apple41588371503 at de69 is still building Xcode; audit once after completion.
Native41587502684 at de69 is RUNNING; automatic41590342856 at76bd is PENDING behind it.
No manual duplicates/cancels. Auditors ready for de69 Apple/native. After the Apple
result is audited, freeze its234 paths plus raw15/schema and any accepted input sources
for one consolidated full Apple. Existing CI excludes this uncommitted repository.
Phone21 remains installed; no other APK or real-account messages. Live304/schema116
and Starter0d4432e unchanged; notification hosts unbound/chat push OFF/all54 groups open.

## Device-owned raw records accepted locally; continue encrypted input repository

[Raw storage contract](device-records.md) and
[evidence](measurements/device-records-2026-11-03.json) are locally ACCEPTED.
DeviceRecordStore retains opaque device-owned records before account activation, with
atomic existing-record-first insertion, global bounded capacity, exact cleanup and
bounded ordered pages. No encryption, account authorization or notification policy
lives in this raw layer. SQLite12->13/IndexedDB11->12 are additive; every prior Room
entity definition is unchanged. Public record diagnostics are redacted. Nothing evicts
pending input or clears these rows on logout/account replacement.

Focused1 PASSED99.993s: nine SQLite cases/two suites and51 actual browser/eight. Full2
PASSED593.026s:3,794 desktop/537 suites,230 Android-host/37,51 browser/eight,11 proof-host/
three,ten build-logic/two;1,764 fresh cases/281 suites,15 unchanged Kotlin hashes and
schema digest verified, zero failures/errors/skips. Root quality, isolated proof
assembly and shared Wasm passed. Eight Apple wrappers are prepared; Apple acceptance
is pending a subsequent full hosted run. No more local tests absent a new reason.
.work/chat-device-records-2026-11-03 audit.py/record.py completed.

NEXT implement encrypted pre-retention notification input repository and explicit
protected device-key ownership in application hosts. Six UNAPPLIED .txt Kotlin drafts
and next-input-repository.txt/drafts-note.txt are in the raw-store work directory.
They have not been compiled or accepted. Use existing shared actual SQLite/browser
contract fixtures; validate key loss/tamper/cancellation/lost-response/reopen/capacity
before application integration. Signed notification origin must be authenticated for
the actual current account before transfer to owned input or review UI. No automatic
send for a previous family; quarantine deletion must follow proven durable transfer.
Native callback/review UI and input-specific OS-death proof remain open.

No local heavy job/emulator remains. Phone21 is already installed; no new APK needed
for this raw-only increment. Platform304/schema116 and Starter0d4432e unchanged. The
five prior raw database/schema inputs match Starter exactly, but port only after
Mobile's remaining Apple acceptance. Generic notification adapter port still waits
for integrated Mobile acceptance. All54 PRD groups remain open, production chat
notification hosts unbound and push OFF; voice/video/meeting UI absent.

Current hosted gates: Apple41588371503 and native41587502684 at de69 include focus/
build21 but exclude these raw records. Do not cancel/duplicate; audit each once after
completion. Then freeze their source union plus raw records and any accepted encrypted
input increment for ONE subsequent full Apple. Native auto queue handles later source.

Historical checkpoint below is superseded by this current status.

## Corrected native provider proof accepted; device records in progress

Hosted native [41585074316](measurements/chat-native-proof-observation-hosted-2026-11-03.json)
at **a90dc3f6801f4dc3a49cab4eca8b6d629b024196** is ACCEPTED. Actual isolated API/PG/
Synapse304 and owned API35 emulator: all five SDK/worker scenarios passed, plus
230 Android-host/37 suites and11 proof-host/three, zero failures/errors/skips.
Three process-death worker cases each recover176 originals in11 passes/11 container
reopens; priority completes in one pass. SDK has three encrypted sends/seven receipt
POST200 responses. WorkManager has automatic restart after ordinary process death and
network restoration. Raw notification IPC16 groups/two scenarios passed39.452s with
verified fixture uninstall before the independent provider cases. The same proof APK
SHA256 is reported by all drivers.232 frozen source hashes verified.
Artifact13209277435,129955 bytes, GitHub SHA256
`7567bceb58756ef13780c0cf6fe69a2da24007d9fa7421f43fbc94806f8865f6`.
Build976s/Platform316s/native1101s. Audit complete in
.work/chat-proof-observation-native-2026-11-03; do not re-extract or rerun. This confirms
the corrected continuous observation fixture removes the demonstrated priority failure.
No native notification-input process-death/review acceptance: priority uses an explicit
fake display, raw IPC runs separately, production notification hosts remain unbound.

Automatic native41587502684 at de69f282 is now IN_PROGRESS, includes focus/build21.
Auditors prepared in .work/chat-notification-focus-native-2026-11-03 with239 frozen
source paths. Wait/audit once; no manual dispatch or cancel. Full Apple41588371503 at
de69 is still running; latest accepted Apple41585101233 covers v2/a90, excludes focus.

DeviceRecordStore source is IN PROGRESS,15 Kotlin files plus additive schema13.
Focused1 passed99.993s: nine SQLite cases/two suites and51 actual browser cases/eight,
zero failures/errors/skips. This includes two concurrent DB owners, exact deletion,
replay/capacity, account replacement, cancellation, reopen and actual predecessor
migration. Generated schema13 only adds device_records; every prior entity definition
is unchanged. Full2 is RUNNING; do not change source inputs or start another local
heavy job. Work: .work/chat-device-records-2026-11-03; contract docs/device-records.md.
Apple/Starter acceptance of this raw store is still pending; encrypted input repository
and native callback integration are not implemented yet. Phone21 remains installed.

## Release21 installed; v2 full Apple accepted

Product build21 at **de69f28229aaeb7367e8bd37ee2439d012ad1a57** PASSED475.842s,
with unchanged clean source before/after. Artifact signature, same prior signer, arm64,
16KiB alignment, private dev API/Firebase and18 backup exclusions verified; product lint0.
Dedicated owned5562 emulator boot28.618s; two stable12-second cold starts, no crash,
readable seven nodes each. Startup screenshot inspected; no all-page claim. Owned
emulator stopped and transport absent2.005s. Phone install-only is CONFIRMED, started06:14:40Z/51.017s including verification.
UID10962 and first-install2026-10-29 15:48:23 preserved; no uninstall/data clear/phone UI
launch or account reset. APK87,822,980 bytes; pulled installed SHA256 matches
`03c2c7c7df52af241c6786f8288c38acc73bce74f9a81ee7ba40b0fa4f8da440`.
[Release evidence](measurements/android-release-phone-2026-11-03-build21.json).
.work/release-phone-2026-11-03-build21 holds complete scripts/evidence; do not reinstall.
Next actual validated product build must use >21; notify before install-only.

Full Apple [41585101233](measurements/chat-notification-action-recovery-apple-2026-11-03.json)
at a90dc3f6 is ACCEPTED:986 simulator cases/110 suites, zero failures/errors/skips,
227 relevant source hashes. Full Xcode1019s, compile566s, tests93s. Artifact13208248217
SHA256 `6de03ebfb67ebe1896de9175d24f5cc833be462a5102b6ff07e1e1d257c8933e`
verified against GitHub. Audit complete; do not repeat extraction or dispatch.

ONE subsequent full Apple [41588371503](measurements/chat-notification-focus-apple-2026-11-03.json)
was dispatched06:11:52Z at de69f282 with234 frozen source paths, includes focus/build21.
It is PENDING; audit.py ready in .work/chat-notification-focus-apple-2026-11-03. Wait for
completion, audit once; do not cancel or duplicate. Native corrected41585074316 at a90
still runs, automatic41587502684 at de69 queued behind it. No manual duplicate runs.

DeviceRecordStore implementation is now IN PROGRESS (uncommitted): generic bounded
opaque device-owned records independent of account activation, additive SQLite13 and
IndexedDB12. Only raw storage source exists so far; tests/migration/graph validation
must pass before commit or use. No new app release for this unfinished increment.
Continue the device-encrypted pre-retention input gap. Read the
existing design in .work/chat-device-records-2026-11-03/design.txt and the focus
work-directory after-release-input-quarantine.txt. Raw device-owned bounded opaque
storage needs actual SQLite/IndexedDB/Apple contracts and migration evidence before
an encrypted repository uses it. Existing generic Inbox is plaintext; account-owned
Replica/Blob writes must not be bypassed with a synthetic account. Do not activate
production notification hosts/push yet. All54 PRD groups remain open.

Historical checkpoint below is superseded by this current status.

## Focused-route notification disposition accepted; deliver build21

Focus integration is locally ACCEPTED. [Contract](collaboration-notification-focus.md),
[evidence](measurements/chat-notification-focus-2026-11-03.json). Actual admitted thread
owner feeds resumed/window-focused route registration; stale cleanup cannot clear a
new route. The process observes focus revisions and joins obsolete work. Issuance
carries the exact previous native reference; focused publication removes it before
Cancelled settlement, then validates the original source and exact inactive write.
No native show on the matching-focus path; blur/reopen cannot revive that original.

Focused1 failed app compilation after69 passing domain/UI cases due to omitted remember
import. Focused2 passed155.262s:107 desktop/12 suites plus11 proof/three;49 fresh/nine
with69 explicitly reused. Full3 passed336.536s:3,785 desktop/535,230 Android-host/37,
11 proof-host/three,ten build-logic/two;2,190 fresh/265 and18 unchanged Kotlin hashes,
zero failures/errors/skips. Root quality, proof assembly and shared Wasm passed.
Work/audits complete in .work/chat-notification-focus-2026-11-03. No further tests needed
without new changes/failures. Focus changes conservatively wake other watched sources;
measured64-source behavior remains separate. Production native hosts remain unbound,
chat push OFF; all54 PRD groups open and voice/video/meeting UI absent.

NEXT deliver build21. Scripts prepared, not yet executed, in
.work/release-phone-2026-11-03-build21. Require clean accepted HEAD for build-arm64.py;
it checks unchanged HEAD/clean status at the end. DO NOT commit or edit tracked files
while this release build runs. One local heavy job at a time. Build+lint with the
previously required2560MiB local heap, verify-arm64.py/verify-lint.py, then owned5562
short two-cold-start smoke and guaranteed stop; inspect the screenshot. Phone was
connected at preparation, explicit serial10.77.77.3:5555. Preflight signer/identity,
notify user IMMEDIATELY BEFORE install.py --install-only; no phone UI launch, uninstall
or data clear. Record actual build21 evidence rather than copying build20 failed-run
or Apple fields. Release20 remains installed until successful update is verified.

Hosted Apple41585101233 and corrected native41585074316 at a90dc3f6 are still running;
their source excludes focus. Auditors ready in .work/chat-action-recovery-apple-2026-11-03
and .work/chat-proof-observation-native-2026-11-03 (232 frozen relevant source files).
Run artifact auditors only after completion; native success auditor requires actual
five scenarios/16 raw groups/11 host proof cases. If failed, inspect controlled category
and preserve failure before another fix. No duplicate native/manual run. Apple must
complete and be audited before dispatching one new full Apple for focus/build21. A
read-only artifact audit and workflow dispatch can run during APK build, but defer all
tracked documentation writes/commits until the release build finishes.

Release20 devRelease1.0.0 from **543c61b9f821c6f63f404970c8b015a7c4ea9013** is INSTALLED
on10.77.77.3:5555 with explicit install-only; do not reinstall it. APK87,806,596 bytes,
SHA256 `12d378a322b806d2711e7d718862671fcaea6dff964497d802875896a5261d0b`.
Same signer, package/dev API/Firebase app/arm64/16KiB alignment and18 backup exclusions
verified. Lint zero issues. Initial build FAILED496.399s at ApkFlinger heap exhaustion
after compilation; logged before retry. Only local Gradle heap changed1536MiB->2560MiB;
unchanged clean source then PASSED build+lint71.115s. No failed APK installed.
[Evidence](measurements/android-release-phone-2026-11-03-build20.json).

Owned emulator3003329/5562 boot28.560s; two stable12s cold starts/no crash, readable7
nodes each, total55.289s through x86_64 libndk_translation. Screenshot inspected as
readable onboarding with no observed clipping; this is not all-page/authenticated-chat
acceptance. Emulator stopped/transport absent2.005s. Phone update+pulled-APK verification
67.228s starting04:52:32.103844Z; UID10962 and first-install2026-10-29 15:48:23 preserved.
Phone-reported last update2026-11-03 11:52:52. No uninstall/data clear/phone UI launch,
real-account messages or sign-in resets. Local scripts/evidence in
.work/release-phone-2026-11-03-build20. No local heavy job or owned emulator remains.

Full Apple [41579492354](measurements/chat-muted-notification-apple-2026-11-03.json)
at release20 source543c61b9 is ACCEPTED:984 cases/110 suites, zero failures/errors/skips;
227 source hashes, full Xcode900s, test compilation520s and execution75s. Artifact
13205878217 SHA256 `6e61f4319ea5a6bbec6831216dde6a4f38c73942ab0e1a4c0b6104801d77f883`
verified against GitHub. Audit complete; do not redispatch or extract again. This
excludes the in-progress authenticated-action v2 changes.

Native [41575838871](measurements/chat-priority-notification-native-failure-2026-11-03.json)
at76d004e5 FAILED: raw OS notification16 groups/two scenarios passed with fixture
uninstall verified; SDK and scheduled scenarios passed, but all three worker scenarios
failed in priority processing after175 seeded messages. Reported category was
HrisProof_Unavailable_Unclassified. Host230/37 and proof6/1 passed; full native
acceptance is withheld. Artifact13206336258 SHA256
`aab64315f53494a2cb518e1e02a470e8d562f8bf92d9528fb750ddfce335fa4` verified.
Current automatic native41579417140 at543c61b9 is still running; do not duplicate.

The proof observation defect is now FIXED and locally accepted: seeded session and
permitted workspace flows remain active until caller cancellation. Background-only
workspace reads remain prohibited, production completion guards remain unchanged,
and error evidence uses fixed category labels. [Contract](collaboration-native-proof-observation.md),
[evidence](measurements/chat-native-proof-observation-2026-11-03.json).
Red1 reproduces four failures/five cases38.263s; Green2 passes five cases51.190s.
Full3 passes121.630s:11 fresh proof-host/three suites, reused3,766 desktop/532,
230 Android-host/37,ten build-logic/two; four unchanged hashes, proof assembly/root
quality. Lint zero errors,47 warnings with exact diagnostic counts matching prior
tracked baseline. Initial evidence recorder mistakenly required zero total issues;
corrected to preserve verified warnings, no source/test rerun. Audit complete in
.work/chat-proof-observation-2026-11-03. No local heavy job or owned emulator remains.
Corrected hosted native execution is still required; no provider acceptance claim.

Authenticated action v2 and old-family private retention are ACCEPTED. Strict open
requires exact issuing parent; separate recovery authenticates actual account/audience
and preserves original parent. Storage receives CURRENT captured recovery access,
separate from the issuing descriptor: old-session writes correctly fail the database
ownership check. Actual Koin/SQLite cases verify SessionChanged review without sending,
reopen/replay deduplication and exact existing-command completion without another send.
[Contract](collaboration-notification-action-recovery.md),
[evidence](measurements/chat-notification-action-recovery-2026-11-03.json).

Focused7 PASSED229.472s:126 fresh cases/12 suites. Full8 PASSED345.108s:3,766 desktop/
532 suites,230 Android-host/37,six proof-host/one,ten build-logic/two,41 browser/three;
2,212 fresh cases/265 suites,19 unchanged Kotlin hashes, zero failures/errors/skips.
Root quality/proof assembly/shared Wasm passed. Six earlier rejected attempts are
preserved: stale unsupported-version examples, interrupted obsolete retention barrier,
real inactive-session write refusal, and an incorrect current-owner fixture assertion.
No checks need repeating absent new changes. Work/audits complete in
.work/chat-notification-action-recovery-2026-11-03. Full Apple at543c61b9 excludes v2;
freeze the union of its227 source paths and these19 for one subsequent Apple run.

Corrected native41585074316 at **a90dc3f6801f4dc3a49cab4eca8b6d629b024196** is
IN_PROGRESS. Superseded41584514002 atc64cc036 was cancelled only after the corrected
run existed: it still had the confirmed one-shot fixture. Cancellation and source
checks saved in .work/chat-proof-observation-2026-11-03/superseded-native.json.
Older native41579417140 at543c61b9 also FAILED; its artifact has not yet been audited.
No full provider acceptance is claimed; do not duplicate these runs.

ONE consolidated full Apple [41585101233](measurements/chat-notification-action-recovery-apple-2026-11-03.json)
was dispatched05:38:38Z at a90dc3f6,227 frozen source paths (all19 v2 paths were already
in the previous227-file union). It is PENDING. .work/chat-action-recovery-apple-2026-11-03/
audit.py is ready; run after successful completion only, exclusive extraction. This
build includes v2 and current storage ownership; Android proof-only code does not
execute on Apple. Do not dispatch/cancel another Apple run until this result is handled.

NEXT implement actual focused-route notification ownership/disposition while hosted
checks run. No local heavy job or owned emulator remains. The phone still has verified
release20; these test-fixture builds are not new product APKs. On the next actual
validated product release use >20, notify before explicit install-only, preserve data.

After build21 delivery continue device-encrypted pre-retention notification input,
authenticated callback/review UI and actual platform host integration. Focus ownership
and durable disposition are now implemented; generic Starter port remains scoped to
proven reusable foundations, not HRIS policy. Keep real notification OS-death/permission/
historical baselines and64-source measurement as explicit remaining acceptance.

Device-encrypted pre-retention input quarantine,
review UI, real native callbacks, permission wakeups, baselines and notification-input
OS-death proof remain open. Production native notification hosts are unbound; chat push
OFF. Voice/video/meeting UI absent; all54 PRD groups open. Continue the long run after
installation. Live304/schema116 and Starter unchanged; generic port waits for integrated
Mobile proof. Next actual product release must be >20, same signer/data, phone notice
then install-only. Physical iPhone waived; full Xcode remains required.

## Durable mute disposition accepted

Historical checkpoint below: Apple/native pending instructions are superseded by the
accepted Apple and failed-native evidence at the top of this file.

Muted originals now receive a guarded inactive Cancelled watermark before priority
hint acknowledgment. Prepare carries metadata and the exact prior journal reference;
inside the surface lease, exact native removal precedes conditional commit. No preview,
action token or native show is created. Source/parent/denial/settings changes, cancellation
and uncertain writes never report confirmed suppression. A committed inactive record
survives response loss and reopen; unmute cannot revive that original. Missing originals
remain pending unless an owned redaction fact already supplies terminal evidence.
[Contract](collaboration-muted-notifications.md),
[evidence](measurements/chat-muted-notification-2026-11-03.json).

Full3 PASSED294.686s:3,757 desktop/532 suites,230 Android-host/37,six proof-host/one,
ten build-logic/two; proof assembly/shared Wasm/root quality.2,162 fresh cases/262 suites,
17 final source hashes, zero failures/errors/skips. Focused1 PASSED245.152s/125fresh;
focused2 PASSED123.563s/123fresh plus six explicitly reused proof cases. Fourteen new
domain cases/six Koin SQLite cases and one priority/process test ending between durable
suppression and hint ACK, then reopen/unmute. Fake HTTP/SDK/OS remain explicit. No
failed attempt. No local heavy job or owned emulator remains at this checkpoint.
.work/chat-muted-notification-2026-11-03/audit.py and record.py completed; do not rerun
full checks without a new reason. No storage schema/browser migration was introduced.

Release20 delivery is complete; see the current checkpoint above. Keep its source and
installed artifact distinct from subsequent implementation commits. Do not repeat the
accepted mute checks unless new changes or failures require them.

Continue authenticated action v2 and old-family typed-input retention for explicit
review, then actual-parent/account/company/conversation focused-route suppression.
Read .work/chat-muted-notification-2026-11-03/next-actions.txt and the existing source;
those notes are design only. Current action v1 authenticates parent only as AAD, so a
callback after replacement cannot independently recover its original parent. HRIS v1
native hosts never activated; do not invent a mandatory legacy migration. Normal Send
must still require exact current parent. Unknown/locked-key input needs bounded device
encrypted quarantine and explicit account-authenticated review; no premature "saved".
Focus must have its own revision/ownership and durable disposition; do not model it as
a failed-source surrogate that revives old originals on blur. ChatThreadViewModel gets
actual owner from readContext; arbitrary route IDs do not grant authority. Permission/
channel/capacity wakeups, historical/backfill/dropped-hint baselines, native callbacks,
notification-specific OS-death evidence and measured64-source behavior remain open.

Full Apple [41575891048](measurements/chat-priority-notification-delivery-apple-2026-11-03.json)
at76d004e5 is ACCEPTED970/109,221 hashes, full Xcode1,142s/test compile609s/test64s.
Artifact13204977615 ZIP SHA256
`9abea12c79fc9ed86669b8c08b014a146c8d27a320aa06f669b5cb78b0902a6f` verified against
GitHub. Audit .work/chat-priority-notification-delivery-apple-2026-11-03 is complete;
do not repeat extraction/dispatch. It EXCLUDES current mute work. Dispatch ONE full
Apple at the accepted mute/release20 source using union of old221 and current17 paths;
freeze hashes, audit after completion. Physical iPhone waived; full Xcode required.
Shared app integration executes on desktop; Apple compiles production app counterparts.

Native41574010908 at56d3d6a6 FAILED before emulator/application execution: system-image
download reported `Error on ZipFile unknown archive`; TCP5560 unavailable. Build/Platform
fixture compile succeeded. Existing run41575838871 at76d004e5 is running; do not manually
duplicate. This source includes priority fake-display proof, which must be distinguished
from actual raw OS notification tests. Production notification hosts remain unbound,
chat push OFF, all54 PRD groups open; voice/video/meeting UI absent. Live304/schema116
and Starter unchanged. Generic native port waits for integrated Mobile proof.

## Priority-to-notification handoff accepted; native focus/callbacks and durable disposition next

Incoming captures only retained candidate IDs and returns them only with Complete AFTER
joining timeline/runtime. Priority then submits through the one explicit NotificationDelivery
binding BEFORE hint CAS, within its existing20s budget rather than room12s. The publisher
rechecks parent/company/source authority. Unbound/closed host, missing history, uncertainty,
permission/channel/capacity and cancellation retain the hint; Submitted/Suppressed/Unchanged
can resolve it. New push revisions during acquisition or native show survive old ACK.
Hinted NOT_REQUESTED cannot acknowledge missing content; clock rollback cannot expire a
not-yet-due hint. [Contract](collaboration-priority-notification-delivery.md),
[evidence](measurements/chat-priority-notification-delivery-2026-11-03.json).

Delivery atomically takes ownership of ONE provided Process and starts it once; acceptance
is not readiness. Rejected process remains caller-owned. Closed cannot reopen. closeAndJoin
joins registered work, sources and exact compensation before storage closure. Koin onClose
only starts cancellation. Production hosts still DO NOT bind it; chat push OFF.

Focused1 PASSED133.858s:79 desktop/six suites+six proof/one,85fresh. Focused2 PASSED83.134s:
82 fresh desktop/six suites+six explicitly reused proof cases. Full3 PASSED236.238s:
3,736 desktop/530 suites,230 Android-host/37,six proof-host/one,ten build-logic/two, proof
assembly/shared Wasm/root quality.494fresh/77 suites,14 hashes,zero failures/errors/skips;
no failed attempt. Three new virtual binding/close cases bring process suite to38; ten new
actual Koin/SQLite priority cases bring its suite to17. Actual process/publisher/journal with
fake HTTP/SDK/OS. First candidate fake show asserts acquisition released; later independent
journal reconciliation may overlap another incoming transport, so no global no-network rule.

Native HrisPriorityHistoryProbe now explicitly starts the real process/publisher/journal
against HrisPriorityNotificationHost's FAKE display (opaque refs only). It asserts real provider
isOpen=false before FIRST fake submission, Published before ACK and journal retention after
reopen. It emits priorityNotificationSurface=fake. The pinned Platform driver keeps older
summary fields and discards these extra flags from its reduced output. Do not claim native
OS publication from this proof. Existing16rawOS groups are separate. Root proof build passed;
this NEW native execution has not been accepted. Worker fixture/proof container join delivery
before Koin/database/runtime closure. HrisProofRuntimeAccess remains unchanged for unrelated
finite operations; native worker uses the real Android repository.

NEXT bind actual-parent/account/company/conversation focused-route ownership; cancel/join
obsolete work and retire current card while focused, independently of delayed viewport read.
Read CollaborationNavigation.kt, ChatThreadViewportObserver.kt and ChatThreadState.kt before
editing. Continue using the existing UI/UX and Compose-testing skills for route integration.
Durable initial/backfill/dropped-hint AND suppression disposition needs explicit implementation:
current Projection.Suppressed creates no journal (initial-mute test deliberately checks none).
If hint ACK is uncertain and mute/focus changes before retry, that original may be reconsidered.
Do not claim no historical revival until a durable terminal disposition/baseline exists. Reuse
journal watermark semantics carefully; no fake success under unavailable storage or access.

Finish authenticated action v2 (old parent encrypted under actual-account/audience AAD),
old-family/pre-retention encrypted input review and actual native callbacks/receiver lifetime,
then notification-input OS-death proof. Current HRIS v1 callbacks were never activated; do not
invent mandatory migration. Native permission/channel/capacity wakeups and measured64-source
load remain integration work. All54 PRD groups open; voice/video/meet UI absent. Generic Starter
port waits for integrated Mobile proof. Live304/schema116 unchanged; no product APK this step.

No local heavy job or owned emulator after Full3. Automatic native41571732304 at52876f16 was
still running;56d3d6a6 run41574010908 pending. Next push may supersede that pending run; match
actual source head before recording acceptance, do not manually duplicate. Full Apple41568117750
at93f8a185 is already accepted962/108,207 hashes/full Xcode1,126s and excludes candidate/identity/
priority work. Dispatch ONE consolidated full Apple after committing this handoff, freeze the
union of old207 hashes and candidate5/identity7/current14 paths, then audit after completion.
Physical iPhone waived; full Xcode required. Phone19/sourcec0 still installed. Next meaningful
release>19: notify then explicit10.77.77.3:5555 install-only, preserve signer/data, no phone UI
automation. Continue long run and push each atomic commit.

## Background identity accepted; wire the priority publication handoff

Incoming recovery now acquires its own SDK identity under the existing finite runtime,
without selected-workspace reads or a second runtime. A2-second cooperative metadata budget
leaves time for history; missing/transient identity preserves retry while originals still
progress. Exact admitted device, current parent, wall/monotonic expiry, known denial and
cancellation guard SDK/storage. Only a raw SDK denial can retain a new gate; derived expiry/
family failures cannot become permanent company denial. Existing selected local-read semantics
remain. [Contract](collaboration-incoming-identity.md), [evidence](measurements/chat-incoming-identity-2026-11-03.json).

Focused1 FAILED47.392s at nullable generic expected-type compilation; fixed. Focused2
FAILED46.491s:17 domain cases/four failures exposed both the fixture's unsupported restrict
method and outer continuation wrongly retaining derived failures as SDK denial. Restricted
only at the raw SDK response boundary and added no-denial-on-expiry assertion. Focused3
FAILED46.642s at the fixture's Unit/Boolean return-type mismatch; corrected. Focused4 PASSED
162.934s/83 cases/13 suites/root quality. Added one actual2-second deadline case, then Full5
PASSED329.868s:3,723 desktop/530 suites,230 Android-host/37,six proof-host/one,ten build-logic/two,
proof assembly/shared Wasm/root quality.2,134 fresh cases/261 suites, seven final hashes,
zero failures/errors/skips. Eight new domain and four actual Koin/SQLite incoming cases.
NotificationPublisherFixture now gets identity through incoming recovery, without repository
seeding. SDK/HTTP/OS remain fictional in these integration tests; no device acceptance claimed.

NEXT connect priority delivery through the one explicitly owned process. Design notes in
.work/chat-incoming-identity-2026-11-03/next-delivery.txt are NOT applied. Capture only opaque
candidate IDs after exact original/tombstone acquisition and return them AFTER timeline/runtime
close. Publish before CompletePushPriority CAS under outer20s, never inside room12s. Hinted
NOT_REQUESTED cannot Complete without an original. An unbound/closed host must return pending;
keep hint through publication uncertainty, permission/channel/capacity or cancellation.
Submitted/Suppressed/Unchanged can resolve it; expiry remains a separate scheduling decision.

Suggested app NotificationDelivery binding takes ownership of one passed Process, starts once,
never reopens after close, rejects second binding, joins active work/compensation on disposal.
Native entry points still wait for focus/permission wakeups and authenticated callbacks/input
review. Koin singleton plus onClose is not a substitute for explicit native-host closeAndJoin.
Tests must use actual Process/Publisher/Koin/SQLite with an explicit fake OS, not success no-ops.
Existing PriorityRecoveryTest and its reopen cases need an owned host. Join it BEFORE fixture
container/database closure. Current native HrisPriorityHistoryProbe expects successful hint ACK:
explicitly bind actual process/journal to a labelled proof-only fake display for this case,
verify native provider runtime closed before fake show, retain Published before ACK and record
fake-surface scope. Existing16rawOS groups remain separate; no native-input acceptance implied.
HrisWorkerProofContainer uses real Android sender.repository; HrisProofRuntimeAccess deliberately
rejects identity only in unrelated finite proof operations. Production Android identity reads
its protected initialized SDK credential record and closes the credential source.

Then initial/backfill/dropped-hint baseline, focused-route ownership, permission/channel recovery,
authenticated action v2/old-parent and pre-retention encrypted input review, and actual
notification-input OS-death tests. Chat push OFF, all54 PRD groups open; voice/video/meet UI absent.
Generic Starter port waits for integrated Mobile proof. Live304/schema116 unchanged.

Apple41568117750/93f8a185 ACCEPTED962/108,207 hashes/full Xcode1,126s, excludes candidate52876f16
and this increment; do not repeat it. Native automatic41571732304 at52876f16 was in progress at
03:40Z; do not dispatch a duplicate. Full local5 finished; no owned local heavy job/emulator.
Phone19/sourcec0 remains installed. Next meaningful release>19: notify and explicit
10.77.77.3:5555 install-only, preserve signer/data, no phone UI automation. Continue long run;
push every atomic commit.

## Full Apple process validation accepted; background identity acquisition in progress

[Run41568117750 at93f8a185](measurements/chat-notification-process-apple-2026-11-03.json)
is ACCEPTED:962 simulator cases/108 suites, zero failures/errors/skips,207 verified
source hashes. Full Xcode1,126s, test compilation595s/tests88s. Artifact13201895179
SHA25603d28b4fac2535cc12618fa06ea9e0c43bc0a8a44e3f796812445948b00d90d4 matches GitHub.
Scope covers the process gate/coordinator. Shared app tests do not run in this simulator
task; app composition compiles in Xcode. Candidate commit52876f16 and subsequent work
are outside this snapshot. No native callback/input/APNs/CallKit acceptance. Do not repeat.

Candidate commit52876f16 is pushed and its previous full validation remains accepted.
Tracing incoming delivery found a prerequisite: ReadCollaborationIdentity.acquire only
accepted selected workspace, and incoming recovery did not acquire self identity. That
could leave notification preparation pending when chat had never been opened. Current
UNCOMMITTED work in .work/chat-incoming-identity-2026-11-03 generalizes that explicit
acquisition to finite background admission/device without selection, and acquires metadata
inside the existing runtime with a2-second sub-budget. Missing/transient identity retains
retry while original acquisition still progresses. Confirmed denial/parent cancellation
remain gated. Actual notification fixture now obtains identity through incoming recovery,
without direct repository seeding. Seven Kotlin inputs; focused1 currently running.
Do not commit before observing results and required root/full checks.

Next continue opaque candidate handoff AFTER transport/timeline close and BEFORE hint ACK,
inside priority20s rather than incoming12s. Worker must use the one actual process owner;
no absent-host success path. Handle initial/backfill/dropped hints and native focus/permission
wakeups, authenticate callbacks and retain/review encrypted input, then actual notification-input
OS-death proof. The existing native priority proof expects hint completion: explicitly adapt
its host/delivery assertions if changing that contract; never silently fake publication.
Chat push OFF, all54 PRD groups open. Phone19 remains installed; next meaningful release>19
requires notice and10.77.77.3:5555 install-only. No phone UI automation. Live304/schema116
unchanged. One local heavy job at a time; continue long run and push each atomic commit.

## Notification candidate API accepted; connect incoming delivery and native ownership next

Process.publish now queues max 20 opaque candidates/caller-owned completions. The caller
has an eight-second cooperative budget and joins matching cancelled work/compensation.
Company/conversation source slots support provisional candidates and stay within 64 total.
Candidates wait for the first observation; an actual failure bypasses publication.
Promotion retains the same watcher/handled signal. Complete discovery rejects duplicate
source keys, cleans changed/forgotten native IDs exactly, and drops excess provisional
slots before accepting an incoming full journal. [Contract](collaboration-notification-candidates.md),
[evidence](measurements/chat-notification-candidates-2026-11-03.json).

Work captures epoch/signal/scan revision/request liveness after lazy registration. Parent
replacement rejects old requests before scanning the new journal. Cancellation after
committed preparation keeps only the unchanged source handled, leaving quiet recoverable
journal until another source signal or explicit retry; it does not immediately replay
behind the cancelled caller. Other requests sharing its source remain live. Eligible
candidates alternate with dirty references; existing-card failed-source cleanup precedes
candidate failure. Busy work waits for idle/completion without dropping a request.

Focused1 PASSED98.549s/65 cases, focused2 PASSED104.273s/75, focused3 PASSED91.834s/81;
four suites and root quality in each. Full4 PASSED259.766s: 3,711 desktop/528 suites,
230 Android-host/37, six proof-host/one, ten build-logic/two, proof assembly/shared Wasm/
root quality. 483 fresh cases/77 suites, five frozen source hashes, no failures/errors/
skips or failed attempt. The81 focused cases are35 virtual process,30 mechanical owner,
ten finite-reconciler integration and six actual Koin/SQLite process cases. New real graph
cases cover first-original alert/replay silence, initial mute without publication, and
uncertain native result retaining Prepared then replaying the same reference silently.
Acquisition/native OS remain fakes; this is not notification-input/device acceptance.

NEXT connect priority recovery WITHOUT nesting an eight-second publisher wait inside the
incoming room's12-second budget. Outer priority is20 seconds; incoming is15 seconds.
History.priority already acquires and reads an exact original/tombstone before hint ACK.
An optional APPLICATION callback through history.priority/incoming.priority can capture
only retained candidate IDs in a local variable. Outer priority can publish after incoming
returns/closes the transport lease, before completing the hint, under its existing budget.
This avoids a second network by-room lookup. Inspect every signature/call site and actual
retained event shape first; callback is IDs only and grants no lasting publication authority.
Bind actual process ownership explicitly; do not silently ACK publication uncertainty or
add a no-op path once native chat notifications are enabled. Worker handoff remains DESIGN
ONLY in .work/chat-notification-candidates-2026-11-03/plan.txt. apply-initial.py is APPLIED/
STALE; use current .kt. No priority/worker/native entry point is changed yet.

Then app/navigation-owned focus with actual parent/account and joined disposal; first-sync/
backfill/dropped-hint baseline; native permission/channel/capacity recovery wakeups. Measure
realistic card/source load before claiming device performance (full rescan per outcome,
up to64x5 raw streams). Finish authenticated native callbacks, account-owned action v2/
old-family/pre-retention encrypted review, then notification-input OS-death proof. Current
v1 HRIS callbacks never activated; do not invent mandatory legacy migration. Chat push OFF;
all54 PRD groups open, voice/video/meeting UI absent. Generic Starter port waits for integrated
Mobile proof. Physical iPhone waived; full hosted Xcode required.

Apple41568117750 at93f8a185 is still building Xcode,207 earlier hashes, EXCLUDES candidate
changes. Do not redispatch; audit .work/chat-notification-process-apple-2026-11-03/audit.py
after completion. Previous41564138207 atc69bd898 is accepted962/108, full Xcode1245s,
202 hashes/artifact13199928107 digest verified. No shared app integration tests run in that
simulator task. No local heavy job or owned emulator remains after Full4. Phone19/sourcec0
remains installed. Next meaningful product release above19: notify then explicit
10.77.77.3:5555 install-only, preserve signer/data, never drive phone UI. Live304/schema116
unchanged. Continue long run and push every atomic commit.

## Bounded notification process accepted; connect candidate delivery next

The process now owns surface startup/close, cold readiness collection, one conflated wake,
max 64 opaque publication/source slots and joined finite work. Full scans and snapshot
application each have an eight-second cooperative budget. Old references receive exact
native removal before their source is forgotten; newer renders survive. Failed discovery/
uncertain cleanup pauses the namespace and requires explicit recovery. Source failure never
grants publication; completion/exception does not auto-restart. Parent/source/request
callbacks cancel only obsolete registered work and never join the actor from a callback.
[Contract](collaboration-notification-process.md),
[evidence](measurements/chat-notification-process-2026-11-03.json).

Own ACK/version changes retain the observer and handled source, avoiding feedback loops.
Contended references retry only after a different exact reference is discovered. A distinct
surface-busy code propagates through the reconciler without semantic cleanup. Dirty work
waits for idle plus a completion revision different from the captured attempt, including
already-observed completion. Generic storage failures cannot retry on their own completion.
Notification bindings now live in applicationNotificationModule, included by worker DI.

Focused1 FAILED 73.928s: 14 of 55 cases failed because the new fake references used non-UUID
IDs; domain validation correctly rejected them. Corrected the fixture. Focused2 ran 60
passing cases but FAILED detekt at 95.951s: worker-module length 108 exceeded 100. Extracted
notification bindings, refined registration/cancellation races, added real Koin process
coverage. Focused3 PASSED 97.502s: 63 cases/four suites and root quality. A final post-copy
capacity guard is covered by Full4 PASSED 264.707s: 3,693 desktop/528 suites, 230 Android-
host/37, six proof-host/one, ten build-logic/two, proof assembly/shared Wasm/root quality;
465 fresh cases/77 suites, ten final source hashes, zero failures/errors/skips. The 63
focused cases comprise 20 process, 30 owner, ten reconciliation and three actual Koin/
SQLite process cases (privacy, mute/private-read, company denial; fake HTTP/SDK/OS).
Audit helper now inventories archived as well as live paths so later narrower filters
cannot erase old run counts. Failed attempts remain rejected in the measurement.

NEXT new-candidate requests through THIS actor, not a competing worker Publisher. Current
process handles discovered active journal only; no native entry point starts it. Bound
opaque candidate queue and caller wait, retain durable priority hints through uncertainty,
register source readiness before publication and join caller/parent/source/focus invalidation.
Review company/conversation source keys and provisional watches because a new candidate has
no journal reference. Preserve observer state when its committed publication is discovered.
.work/chat-notification-process-2026-11-03/candidates-next.txt contains design notes, not code.
Previous process-gate process.txt is APPLIED/STALE; current .kt is source of truth.

Tracing found priority recovery already acquires and reads the exact original/tombstone
before ACK, but incoming recovery has a 15-second/room 12-second budget and priority has
20 seconds. Do not add an unbounded nested publisher wait. Ordinary history acquisition
returns counts, not candidate IDs; dropped-hint delivery and initial/backfill baseline need
explicit design before promising every missed notification. Add app/navigation-owned focus
with actual parent/account and disposal, independent of visible-read timing. Native host
also needs explicit permission/channel/capacity recovery wakeups. Full rescan per outcome
and max 64x5 source streams are not device performance acceptance; measure realistic loads.

Then authenticated native callbacks, old-parent action v2 and encrypted review for old-family/
pre-retention input, actual notification-input OS-death proof. Existing HRIS callbacks were
never activated; do not invent mandatory legacy-v1 migration. Chat push OFF, all 54 PRD
groups open; voice/video/meeting UI absent. Generic Starter port awaits integrated Mobile
proof. Physical iPhone waived; full hosted Xcode mandatory.

[Apple41564138207 at c69bd898](measurements/chat-notification-reconciliation-apple-2026-11-03.json)
is now ACCEPTED: 962 simulator cases/108 suites, zero failures/errors/skips, 202 verified
source hashes, full Xcode 1,245s (test compile623s/tests70s). Artifact13199928107 archive
SHA256 fc76a7884f31197c11ae35769b5a38deea75f9476f787cff5e763f14b2694905 matches GitHub.
It excludes process-gate/actor changes. Shared app integration tests are not in this
simulator task; the app compiles in Xcode. No iOS actionable surface/APNs/CallKit or
notification-input acceptance is claimed.

The consolidated successor [41568117750](measurements/chat-notification-process-apple-2026-11-03.json)
was dispatched ONCE at93f8a185 with207 source hashes. It is PENDING; do not redispatch.
Audit .work/chat-notification-process-apple-2026-11-03/audit.py only after full Xcode
completes. Any subsequent candidate implementation is outside that frozen snapshot.
Do not repeat diagnosed local provider emulator hangs. No local heavy job or owned
emulator remains after Full4. Phone19/sourcec0 remains installed; next meaningful release above19:
notify then explicit10.77.77.3:5555 install-only, preserve signer/data, never drive phone UI.
Live304/schema116 unchanged. Continue long run and push every atomic commit.

## Process gate accepted; implement the bounded notification coordinator

The surface owner now exposes cold Pending/Ready/Blocked/Closed coordination states.
Ready carries parent, opaque epoch, advisory idle flag and completion revision; it is not
admission or an execution lease. `pause(parent)` closes the epoch before attempting
joined work/compensation and namespace cleanup without account keys. Even successful
cleanup stays Blocked until explicit reconciliation. Timeout also stays blocked; it does
not prove an uncooperative native call has stopped. Old-parent/nested pauses are rejected.
[Contract](collaboration-notification-process-gate.md),
[evidence](measurements/chat-notification-process-gate-2026-11-03.json).

Focused1 passed 91.248s, 30 fresh cases/one suite. Full2 passed 251.782s: 3,669 desktop/
526 suites, 230 Android-host/37, six proof-host/one and ten build-logic/two; 441 fresh
cases/75 suites. Full2 exposed the new Ready copy-visibility warning. Only the explicit
ConsistentCopyVisibility annotation was then added. Targeted3 passed 125.708s on final
source: 30 owner plus six proof-host cases, proof assembly/shared Wasm/root quality.
All runs have zero failures/errors/skips, with three source hashes per run. Full2 is
not relabeled as a final-source full rerun. No local heavy job or owned emulator remains.

NEXT implement the bounded opaque process reconciliation coordinator. Detailed design,
Kotlin DRAFT and test plan are in .work/chat-notification-process-gate-2026-11-03/
(actor-next.txt, process.txt, process-tests-plan.txt). None is applied Kotlin yet. The
coordinator owns startup/close, max 64 references, one conflated wake channel, whole-scan
budget and joined source subscriptions. A failed journal scan calls pause; explicit
retry reopens. Source callbacks cancel matching registered work without joining from the
callback; the actor joins it. Register lazy work before checking epoch/source again.

Preserve a source subscription across own ACK/version changes, then rescan without
redirtying the same handled signal. Contended retains its old reference and retries when
discovery returns a different reference; no hot loop on an unchanged one. Busy needs a
distinct failure code and must propagate through Reconciler without semantic cleanup.
Wait for idle plus a completion revision newer than the failed attempt's captured
revision, so a concurrent completion is not missed. Generic storage failures must not
retry themselves on their own completion wake. Pending/failed source observations never
grant publication; explicit recovery can restart subscriptions. Add request-scan revision
checking around lazy registration and review the draft before applying it.

After this coordinator, route candidate delivery through the same actor, add owned
focused-thread registration and initial/backfill/alert classification. Then authenticated
native callbacks, old-family/pre-retention encrypted input review and actual notification-
input OS-death proof. Actual Koin/encrypted SQLite integration must supplement virtual
fake-port tests. Current HRIS native hosts remain inactive; chat push OFF, all 54 PRD
groups open, voice/video/meeting UI absent. Do not port HRIS policy to Starter.

Full Apple 41564138207 at c69bd898 is in progress (202 earlier source hashes); dispatch
was already requested once. Audit .work/chat-notification-reconciliation-apple-2026-11-03/
audit.py after full Xcode completes. It excludes this process-gate change. Do not duplicate
the dispatch, rerun completed hosted proofs or repeat local provider emulator hangs.
Phone19/sourcec0 remains installed; next meaningful product release above19 must notify
then install-only to explicit10.77.77.3:5555, preserving signer/data and never driving its
UI. Live304/schema116 is unchanged. Continue long run and push every atomic commit.

## Finite semantic reconciliation accepted; continue process lifetime and native callbacks

Issuer and publisher now accept an optional exact expected publication reference. Invalid/
foreign conditions fail before journal IO; changed/missing reference returns Unchanged
without sealing or mutation. Reconciler maps Unchanged to Contended WITHOUT native IO,
including ACK-only version changes with unchanged render ID. The parameterized Koin
factory reuses publisher's own owner turn, followed by a separate exact cleanup turn as
needed; no nested leases. [Contract](collaboration-notification-reconciliation.md),
[evidence](measurements/chat-notification-reconciliation-2026-11-03.json).

Current admitted sources rerender silently. A source Failure bypasses publication.
Confirmed suppression/company denial removes native content then cancels exact journal;
transient/permission/history outcomes hide while preserving active journal. Removal is
registered before journal IO, so cancellation/lost committed response still joins exact
compensation. Replaced render/contended journal requires rescan. Reconciler references
must come from owned discovery, never raw callback extras; callback auth remains separate.

Focused1 passed158.850s:29 fresh cases/three suites (17 existing issuer,3 conditional,
9 actual Koin/SQLite app cases). Full2 passed370.835s:3661 desktop/526 suites,230 Android-
host/37, six proof-host/one, ten build-logic/two and20 browser/two;2092 fresh cases/259
suites, zero failures/errors/skips, six frozen Kotlin inputs. Root quality/architecture74/
detekt, proof assembly/shared Wasm compile pass. No failed attempt or local heavy job/
owned emulator remains. Application cases use fictional acquisition and a fake OS.
Reconciler.txt and implementation drafts are APPLIED/STALE; actor-next.txt is DESIGN ONLY.

NEXT process actor, exclusive owner of native publication/reconciliation/removal. Bound64
opaque refs/dirty IDs; source/processed-parent/focus collectors have explicit joined
lifetimes. Register/revalidate initial subscriptions and rescan after every journal-changing
outcome because ACKs do not wake source observations. Contended must discover CURRENT
reference, not drop work or repeatedly reuse a stale one. Source failure bypasses publishing
until observation recovers. Preserve Busy work without hot retry. Consider a cold surface
status Flow from existing Phase so actor can await Ready/Blocked/Closed after asynchronous
start; avoid stateIn readiness retaining stale Ready when owner scope closes. Normal
native operations should all use the actor, not competing direct publisher owners.

Focused-thread registration belongs app/navigation/lifecycle. It must cancel/join pending
publication and retire its current notification, independent of viewport read timing;
include owned parent/account and dispose old routes. Journal discovery failure needs finite
namespace cleanup/gated recovery so unavailable keys cannot leave private cards indefinitely.
MaintainCollaborationRuntimeOwnership already buffers per-company restriction before
runtime retention; recoveryParent alone is not a company grant. Supplemental
.work/chat-notification-reconciliation-2026-11-03/actor-next.txt contains these design notes.

Then authenticated native callbacks, old-parent action v2/retained-input review and actual
notification-input process-death proof. Production HRIS callbacks are not yet live, so verify
real compatibility needs before designing legacy-v1 migration. Source primitives and finite
reconciler are not a started notification host. All54 PRD groups still open; chat push OFF.

Full Apple dispatched ONCE: [41564138207](https://github.com/fajarxfce/hris-mobile/actions/runs/41564138207) at c69bd898,
202 source hashes. [Pending record](measurements/chat-notification-reconciliation-apple-2026-11-03.json).
Do not redispatch. Audit using .work/chat-notification-reconciliation-apple-2026-11-03/audit.py
after full Xcode succeeds; pending is not acceptance. Later changes need separate evidence.
Last audited Apple11cba615/41554262782 (936/105, Xcode1399s) and corrected native7a0ab3d3/
41554674578 precede this source. Do not repeat local provider emulator hangs. Physical
iPhone waived; full hosted Xcode required.

Phone19/sourcec0 remains installed. Next meaningful product release>19: notify then
explicit10.77.77.3:5555 install-only, preserve signer/data, never drive UI. Live304/schema116
unchanged; voice/video/meeting UI absent. Generic Starter port waits for integrated Mobile
proof. Continue long run and push every atomic commit. Older entries below are historical.

## Explicit native removal cleanup accepted; continue semantic reconciliation

The surface lease now records remove-only cleanup obligations before native IPC, sharing
its existing four-reference bound with show. Failed/cancelled requests join exact
compensation; another cleanup failure blocks publication until explicit namespace
reconciliation succeeds. Newer native renders survive stale cleanup. [Contract](collaboration-notification-removal.md),
[evidence](measurements/chat-notification-removal-2026-11-03.json).

Focused1 passed89.019s:22 fresh owner cases/one suite. Full2 passed244.637s:3649 desktop/
524 suites,230 Android-host/37, six proof-host/one and ten build-logic/two;424 fresh cases/
74 suites, zero failures/errors/skips, two frozen Kotlin inputs. Root quality/architecture74/
detekt, proof assembly and shared Wasm compile pass. No failed attempt. No raw adapter,
storage schema or native host activation changed, so no new raw/provider/device proof
is claimed. No local heavy job or owned emulator remains. The five removal-tests.txt
cases in the preceding reconciliation directory are APPLIED/STALE; do not reapply.

NEXT implement finite semantic reconciliation of an owned opaque publication reference.
Supplemental .work/chat-notification-reconciliation-2026-11-03/plan.txt and reconciler.txt
are UNAPPLIED drafts. The refined approach keeps publisher's own owner turn, followed by
a separate exact cleanup turn as needed, avoiding nested leases. Add an optional expected
publication reference to publisher -> Issue and compare it with the internally read
previous.cleanupReference before reuse/mutation. That parameter does NOT exist yet.
Reconciler receives only references produced by owned journal discovery, never raw intent
extras; callbacks need separate authentication. Confirmed read/mute/redaction/denial retires
the exact current record; transient missing/history/observation failure hides native content
while keeping the journal active. A source Failure bypasses publication. Remove/register
native obligation before journal cancellation so cancellation during journal I/O still
joins cleanup. Replaced native render returns Contended/rescan. Test actual app graph,
stale version/render, old family, late denial, cancellation and uncertain storage/native IO.
Draft details remain subject to review; do not assume they are accepted implementation.

Then the bounded process actor and native callbacks:64 opaque refs/dirty IDs, initial
subscription revalidation, joined observers, explicit journal rescan and Busy lifecycle
wakeups without hot retries. Existing source changes and per-company buffered denial
are accepted, not yet connected to this host. Old-parent action authentication and
pre-retention encrypted input review remain open; current native callbacks are not live,
so establish actual compatibility needs before adding legacy-v1 migration. See prior
entry and [host plan](collaboration-notification-publication-plan.md).

Latest audited full Apple remains11cba615/41554262782 (936/105, Xcode1399s), native remains
7a0ab3d3/41554674578 (16 raw groups plus five SDK/WorkManager scenarios). Their exact
source/artifact digests are recorded below; both precede this change and source observation.
Do not redispatch completed runs or repeat long local provider emulator hangs. Physical
iPhone waived; full hosted Xcode still required for future integrated app acceptance.

Phone19/sourcec0 remains installed. Next meaningful product release>19: notify then
explicit10.77.77.3:5555 install-only, preserve signer/data, never drive UI. Live304/schema116/
chat push OFF; all54 PRD groups open. Voice/video/meeting UI absent. Generic Starter port
waits for integrated Mobile proof. Continue long run and push every atomic commit.
Older entries below are historical.

## Source invalidation and hosted audits accepted; continue finite native reconciliation

Five exact published source checkpoints now emit advisory changes without reading keys,
document bodies or returning content. Unit signals are not publication permission.
ObserveCollaborationNotificationChanges binds them to the actual native parent and the
specified company's buffered read admission, independent of selected workspace. Parent/
denial changes cancel and join sources; each signal checks admission/parent within8s.
Known denial returns before a pending session read. Failure or normal completion of any
source/session/admission stream closes observation; no implicit restart.
[Contract](collaboration-notification-invalidation.md),
[evidence](measurements/chat-notification-invalidation-2026-11-03.json).

Focused1 failed135.321s:19 cases/three suites, one assertion comparing a normalized Failure
message although kind/code were correct. Fixed classification assertion; review added
known-denial/pending-session coverage and checked source completion. Focused2 passed109.483s:
20 fresh cases/three suites. Full3 passed383.654s:3644 desktop/524 suites,230 Android-host/37,
six proof-host/one and ten build-logic/two;2055 fresh cases/255 suites, zero failures/errors/
skips. Root quality/architecture74/detekt, proof assembly and shared Wasm compile pass.
Its browser filters lacked trailing wildcards and produced NO browser reports: audit
rejected that gap. Corrected browser-only4 passed26.473s:17 fresh cases/two suites on the
identical ten frozen Kotlin inputs. Do not count empty browser3 as acceptance. Local audit
now rejects missing browser groups and prevents later XML from filling an earlier run's
missing output. A same-file copy error while hardening that helper was fixed; no app rerun.
No local heavy job or owned emulator remains. Implementation .txt drafts are APPLIED/STALE.

NEXT finite application reconciliation under the existing surface lease. Review found
lease.remove currently does not register cleanup obligations, so a failed explicit remove
can leave the owner Ready when compensation sees no attempted show. Track removal refs
before IPC under the same four-reference bound; joined compensation must block subsequent
publication if removal still fails. Five unapplied cases plus a broader design are in
.work/chat-notification-reconciliation-2026-11-03. Then reconcile an exact reference:
silently rerender benign source-stamp/privacy changes; retire confirmed read/mute/redaction/
denial; hide on transient uncertainty while preserving active journal. Never remove a
newer render for a stale reference. Avoid nesting publisher.withOwner inside another lease.
An optional exact-reference condition on issuance is proposed, not implemented.

Afterward add the process actor: bounded64 opaque references/dirty IDs, initial subscription
revalidation, joined observers, explicit publication-journal rescan and no private payload
queue. Busy invalidations need a lifecycle wakeup without hot retries. Current owner exposes
no readiness/completion signal. MaintainCollaborationRuntimeOwnership already buffers
lists.restrict BEFORE runtime retention; its recoveryParent alone is not company admission.
See [host plan](collaboration-notification-publication-plan.md) and supplemental
.work/chat-notification-invalidation-2026-11-03/host-next.txt.

Callback/input review remains open: old v1 full-parent-AAD action may lose its trusted
old-parent lookup after journal overwrite. Native HRIS callbacks have not been activated,
so verify actual migration needs before adding hypothetical legacy compatibility. Consider
an account/audience-authenticated v2 envelope carrying original parent inside ciphertext;
normal send still requires current parent, older-family input needs explicit review.
Pre-retention encrypted quarantine/failed-save retry and notification-specific OS-death
proof remain unimplemented. These are design findings, not accepted features.

Independent hosted audits are now complete and recorded:
- Full Apple11cba615/41554262782:936 simulator cases/105 suites, full Xcode1399s
  (test compile785s/run98s),184 source hashes. Artifact13195921070 ZIP
  f1e92fc3875f93f391997f94b3c484356e3031f0777575a5f50608d17950a920 matches GitHub.
  [Evidence](measurements/chat-notification-publisher-apple-2026-11-03.json).
- Corrected native7a0ab3d3/41554674578:16 raw IPC groups32.622s with verified fixture
  uninstall, then all five SDK/WorkManager scenarios,230 Android-host/37 and6 proof-host/1;
  zero failures/errors/skips,185 source hashes. Artifact13195567598 ZIP
  d87e7240d3760239a21326810eecf6b5da2dba63bdb246c35d3b4f7e01fba1d4 matches GitHub.
  All drivers report APK324f0ae0cd5099d66bd6db12f01db07e5d0a5da914447c650daeff2fd4e0ad32.
  Build1018s/Platform332s/native step1094s. [Evidence](measurements/native-notification-corrected-hosted-2026-11-03.json).
Both precede retirement6f54a5dc, settings/read observations522ae177 and this increment.
Neither proves active HRIS notification callbacks or notification-input process death.
Do not redispatch these completed runs or repeat long local provider emulator hangs.

Phone19/sourcec0 remains installed. Next meaningful product release>19: notify then
explicit10.77.77.3:5555 install-only, preserve signer/data, never drive UI. Live304/schema116/
chat push OFF; all54 PRD groups open. Voice/video/meeting UI absent. Generic Starter port
waits for integrated Mobile proof. Continue long run, push every atomic commit.
Older entries below are historical.

## Settings/read observations accepted; continue native source invalidation

Cold encrypted settings and private read-state streams now follow their exact owned
partitions, share finite-read decoding, suppress duplicate mapped snapshots and join
pending mapping on cancellation. Empty defaults create no key. Fixed read-event
selection is copied at subscription; malformed mapping can recover, while failed
upstream completes without restarting. [Contract](collaboration-notification-source-observation.md),
[evidence](measurements/chat-notification-source-observation-2026-11-03.json).

Focused1 passed98.449s:26 fresh cases/two suites. Full2 passed397.352s:3624 desktop/521
suites,230 Android-host/37, six proof-host/one, ten build-logic/two,26 browser/two;
2061 fresh cases/254 suites, zero failures/errors/skips, nine frozen Kotlin inputs.
Root quality/architecture74/detekt, proof assembly and shared Wasm compile passed.
No failed attempt, schema/key change, or local heavy job/owned emulator remains.
The unrelated-write test is a fictional independent checkpoint, not full journal-ACK
or native-host integration. Source-observation .txt test drafts are APPLIED/STALE.

NEXT implement advisory metadata changes for history/company list/self identity/
settings/read partitions, exclude publication ACK/action/reply/traversal writes, then
combine actual native parent and each company's buffered read admission. Raw changes
carry no contents/grant and wake semantic validation. Watcher completion/failure must
close the gate; do not distinct Unit signals or depend on selected workspace. Planned
repository/UC drafts under .work/chat-notification-invalidation-2026-11-03 are UNAPPLIED.
Then bounded64 opaque per-card application ownership, initial subscription revalidation,
joined invalidation and retained dirty work while busy. Same-source stamp/privacy
changes may silently rerender; read/mute/redaction/denial retire only the exact current
source. See [host plan](collaboration-notification-publication-plan.md).

Native callbacks and encrypted old-family/pre-retention reply review remain required.
A newly identified design issue: after native-family replacement and journal overwrite,
the old v1 parent-bound reply envelope may no longer have a trusted old-parent lookup.
Do not assume the latest journal authenticates every old OS input. Resolve bounded
old-parent retention or a compatible authenticated parent-hint envelope/quarantine
before activation; untrusted intent extras never grant send/read authority. No chosen
schema/implementation yet. Notification-specific OS-death proof remains separate.

Corrected native41554674578 at7a0ab3d3 is running actual emulator tests; full Apple
41554262782 at11cba615 is running full Xcode after simulator tests. Neither is accepted
yet. Existing Apple audit helper and new corrected-native fetch/audit helpers under
.work/chat-native-notification-corrected-hosted-2026-11-03 (185 source hashes) are ready;
run only after success. Do not cancel/redispatch or repeat long local provider hangs.
Both hosted runs exclude retirement6f54a5dc and this observation increment.

Phone19/sourcec0 remains installed. Next meaningful product release>19: notify then
explicit10.77.77.3:5555 install-only, preserve signer/data, never drive its UI. Live304/
schema116/chat push OFF, all54 PRD groups open. Publisher/raw surface still unactivated;
voice/video/meeting UI absent. Generic Starter port waits for integrated Mobile proof.
Continue long run, push every atomic commit. Older entries below are historical.

## Opaque discovery and cancellation accepted; continue source observation and native host

ReadCollaborationNotificationPublications returns one-to-four ordered opaque cleanup
references under the actual expected native parent. It decrypts internally but returns
no preview/original/action content. CancelCollaborationNotificationPublication is a
singleton/nonwaiting8s exact-reference CAS that strips preview/actions, preserves the
original watermark and returns opaque settlement metadata. Same-account older family
cleanup works under retained company denial. Neither operation performs OS removal or
authenticates a callback. [Contract](collaboration-notification-retirement.md),
[evidence](measurements/chat-notification-retirement-2026-11-03.json).

Focused1 passed150.536s:15 fresh cases/two suites (12 domain, three actual Koin/SQLite).
Review then added a pre-copy output bound and stronger shape-valid foreign-account/
audience plus oversized lazy-list cases inside the existing test. Full2 passed390.283s:
3618 desktop/521 suites,230 Android-host/37, six proof-host/one, ten build-logic/two and
12 browser/one;2041 fresh cases/253 suites, zero failures/errors/skips, seven frozen
Kotlin inputs. Root quality/architecture74/detekt, proof assembly and shared Wasm compile
pass. No failed attempt, schema/key change, or local heavy job/owned emulator remains.
Application integration uses fictional HTTP/simulated SDK and a fake OS; it proves
denied cleanup across actual encrypted reopen, stale privacy-render exclusion and lost
committed cancellation. The latter cannot reissue the same original after reopen.

NEXT add narrow owned source observations and their application lifetime: company
admission including buffered denials, settings/read/history/list/identity changes and
focused navigation. Exclude publication-ACK writes from source invalidation. Initial
subscription must revalidate so changes between publication and registration are not
missed. Keep bounded opaque work when the nonwaiting owner is busy; no private payload
queue, hot retry loop or selected-workspace-only gate. Source-stamp/privacy changes may
need a silent rerender; read/mute/redaction/denial requires exact current retirement.
A different suppressed candidate must never clear another current card. Use the new
discovery/cancellation boundary within the native owner, then callbacks, old-family/
pre-retention encrypted review and notification-specific OS-death proof. See
[integration plan](collaboration-notification-publication-plan.md). Supplementary
.work/chat-notification-source-observation-2026-11-03/plan.txt is DESIGN ONLY; no source
observation code exists yet. Retirement review-notes.txt is APPLIED/STALE.

Corrected native CI41554674578 at7a0ab3d3 is running, last Android fixture build. Follow
actual raw16-group and five SDK/WorkManager artifacts separately. Earlier e4/41552877271
was manually cancelled after fixed source was queued because its known raw fixture
cleanup would block all subsequent SDK preflights; pending11c/41554252506 was superseded.
The failed7cf/41550122006 evidence remains preserved. No repeat of long local provider
emulator hangs. Full Apple41554262782 at11cba615/184 source paths is running, last
simulator tests. Do not cancel/redispatch; audit existing helper after full Xcode success.
It excludes this retirement increment. Physical iPhone waived, full Xcode required.

Phone19/sourcec0 remains installed; no product release/install here. Next meaningful
release>19: notify immediately before explicit10.77.77.3:5555 install-only, preserve
signer/data and never drive its UI. Live304/schema116/chat push OFF and all54 PRD groups
open. Publisher/raw transport remain unactivated by native hosts; call/video/meeting UI
is still absent. Continue long run and push every atomic commit. Generic Starter port
waits for integrated Mobile proof. Older entries below are historical.

## Native fixture ownership repair accepted locally; continue host invalidation and CI audit

The raw notification driver now verifies dedicated AVD/qemu/boot/API, debug proof APK
identity and absent package before installation. It uses no replace/downgrade flags,
owns uncertain installation, and uninstalls/verifies absence in finally. A force-stop
failure cannot skip uninstall, and any cleanup failure fails the run. The following
Platform driver's refusal to replace an existing package remains unchanged. Eight new
Python command-boundary tests pass and now run in hosted native CI before Gradle.
[Evidence](measurements/native-notification-ci-cleanup-2026-11-03.json).

Actual local API35 raw proof passes16 groups/two scenarios in6.279s and uninstalls.
Immediately afterward the unchanged Platform NativeDevice.prepare installs successfully,
then cleans/uninstalls in2.704s; package absence verified. This is the exact sequential
ownership boundary, not the full SDK/WorkManager/provider suite. APK343275325 bytes SHA256
35f155670f64c872f9a52bed1ea6d1972a7c83f58a15f3654f82974a63c39678,
from fully checked publisher11cba615. No new Gradle run for this Python/workflow-only fix.
Historical local leftover was uninstalled only after matching prior owned c260118c APK.
Owned emulator5562/PID2060758 boot24.166s, stopped1.604s; no local heavy job remains.

The preceding failed hosted7cf/41550122006 is preserved: raw11 groups pass15.788s,
then all five native preflights fail. Artifact13193192595 ZIP
85bc739ca0fc77d2b7c47c2405d5b63ff2b90fa44122dfa35069a411b31b4bd5
matches GitHub digest. No instrumentation/gateway execution occurred in those five cases.
Corrected hosted41554674578 at7a0ab3d3 is now running. Older e4/41552877271 was manually
cancelled after that repair was pushed/queued because its known pm-clear-only driver
would block every following SDK preflight. Pending11c/41554252506 was superseded by the
workflow. Neither is acceptance evidence. Do not repeat long local provider-emulator
hang experiments.

Full Apple41554262782 was dispatched ONCE at2026-11-03T00:38:10Z for
11cba6157151ca6a837f452e94d090e61d730351,184 verified source paths. It is running, not
accepted. [Pending evidence](measurements/chat-notification-publisher-apple-2026-11-03.json).
Follow .work/chat-notification-publisher-apple-2026-11-03/audit.py after actual success;
never cancel/redispatch. Physical iPhone waived; full Xcode required. It includes the
publisher but excludes this Python/workflow fix, which does not change app code.

NEXT continue ongoing all-company/read/settings/history invalidation, focused-thread
scope and bounded opaque denied-card retirement, then old-family/pre-retention encrypted
reply review and Android/iOS callbacks. The admitted publisher is a tested Koin factory;
no native host starts it yet. Follow [publisher contract](collaboration-notification-publisher.md)
and [integration plan](collaboration-notification-publication-plan.md). Source checkpoints
must exclude publication-ACK writes to avoid feedback. A source-stamp/privacy change may
need silent rerender; read/mute/redaction/dismissal needs exact retirement. Unrelated
suppressed candidates must never clear a different card. Publisher and cleanup .txt
drafts are APPLIED/STALE; follow-through.txt remains design only.

Phone19/sourcec0 remains installed. Next meaningful product release>19: notify immediately
before explicit10.77.77.3:5555 install-only, preserve signer/data, never drive its UI.
Live304/schema116/chat push OFF and all54 PRD groups open. No Starter port until
integrated Mobile proof. Continue long run and push each atomic commit. Older entries
below are historical.

## Admitted publisher accepted; fix sequential native fixture cleanup, then host integration

The parameterized Koin publisher factory now coordinates Prepare/Issue/Validate/native
show/Validate/Finish/Validate inside a started surface owner. Inputs are opaque candidate
IDs, processed parent and generic localized copy; output is an enum. Preview/envelopes
come from retained admitted originals. Failures after submission trigger joined exact
owner compensation while preserving journal uncertainty. Issuance marks newly retained
originals; caller opt-in plus that flag are both required for an alert. Replay, default
recovery and same-source privacy/native-family rerenders are silent. Open aliases the
authenticated Reply target for navigation only; host callback routing is still pending.
[Contract](collaboration-notification-publisher.md),
[evidence](measurements/chat-notification-publisher-2026-11-03.json).

Focused1 passed159.874s:27 fresh cases/two suites (17 domain, ten actual Koin/SQLite app
cases). Full2 passed352.401s:3603 desktop/519 suites,230 Android-host/37, six proof-host/
one, ten build-logic/two and17 browser/one;2031 fresh cases/251 suites, zero failures/
errors/skips, eight frozen Kotlin inputs. Root quality/architecture74/detekt, proof
assembly and shared Wasm compile passed. No failed local attempt. App tests deliberately
use a fake OS with fictional HTTP/simulated SDK acquisition. No native host activated.

NEXT first repair native test composition: hosted41550122006 at7cf6bd72 failed all five
SDK/WorkManager cases before instrumentation or gateway traffic. Its raw notification
probe PASSED11 groups/two scenarios in15.788s, then only pm-cleared the proof package.
The following Platform NativeDevice.prepare explicitly refuses an already installed
package, causing a deterministic ownership conflict. Do not weaken that refusal. The
raw driver must verify the dedicated AVD/debug proof APK and absence before installation,
own uncertain installation, and uninstall/verify absence in finally even when force-stop
fails. Add bounded driver ownership/cleanup tests and actual short native rerun, then
audit subsequent hosted raw+five-case run. No need to repeat the known long local
provider-emulator hang experiments. Failure artifact13193192595 was downloaded and its
GitHub digest verified under .work/chat-native-notification-ci-failure-2026-11-03.
Proposed driver/tests under .work/chat-native-notification-ci-cleanup-2026-11-03 are
UNAPPLIED .txt drafts; no Python fix committed yet. Publisher .txt drafts are APPLIED/
STALE; do not reapply. There is currently no local heavy job or owned emulator.

Consolidated Apple manifest184 paths and dispatch/audit helpers are prepared under
.work/chat-notification-publisher-apple-2026-11-03, NOT dispatched yet. Dispatch once
after commit/push; full Xcode required, physical iPhone waived. Last audited full Apple
isdb74/41547382869 (930/104, Xcode1336s), excluding this publisher and later inventory.

Then continue ongoing all-company/read/settings/history invalidation, focused-thread
scope, opaque denied-card retirement, old-family/pre-retention encrypted reply review,
Android/iOS host callbacks and notification-specific OS-death proof. The publisher's
Suppressed/Pending/Unchanged result intentionally does not clear a different active card;
the host must own that invalidation before activation. Supplementary follow-through.txt
and the [integration plan](collaboration-notification-publication-plan.md) record the
boundaries. Native chat notifications and call/video/meeting UI remain unactivated.

Phone19/sourcec0 remains installed; no product build/install here. Next meaningful
release>19: notify immediately before explicit10.77.77.3:5555 install-only, preserve
signer/data, never drive its UI. Live304/schema116/chat push OFF and all54 PRD groups
open. Keep long run going; push every atomic commit. Starter port waits for integrated
Mobile proof. Older entries below are historical.

## Opaque native pending inventory accepted; continue admitted publisher

Android records the latest opaque card/render ID in a bounded canonical no-backup
AtomicFile before notify, verifies the synced commit, and cancels absent IDs. Under the
required serialized sole writer, an old removal cannot erase a newer pending render;
the latest request can cancel its older visible predecessor. Namespace cleanup unions
persisted IDs and active tags without account decryption. Corrupt inventory permits
visible cleanup but remains failed/retained, so the owner gate stays closed. No private
payload/token/account data, new key or eviction. [Contract](native-notification-actions.md),
[evidence](measurements/native-notification-pending-2026-11-03.json).

Focused1 passed116.052s with24 fresh cases/six suites, proof assembly/lint and root quality.
Full2 passed242.339s:3592 desktop/518 suites,230 Android-host/37, six proof-host/one,
ten build-logic/two and18 browser/five;415 fresh cases/75 suites, zero failures/errors/
skips, five unchanged source/driver hashes. Lint retains47 existing warnings/no errors.
Native1 passed8.515s:16 behavior groups/two actual API35 scenarios, fixture data cleared.
APK SHA256 c260118c936f0b56c0c6df44a1363f61114b58131dd0b885376f9d1ec063ac0f.
Owned emulator5562/PID1971617 stopped in1.606s; unrelated emulators untouched. No local
heavy job remains. No failed attempt in this increment. The fixture reopens a real file,
but OS absence/older presentation and full inventory are synthetic setups, not stalled
OS queues, killed notification-input processes or power-cut durability tests.

Full Apple41547382869 atdb74e22d is now AUDITED SUCCESS:930 simulator cases/104 suites,
full Xcode1336s,175 verified source hashes, artifact13193300792 and GitHub ZIP digest
6eca533575245a2d6f0bc94c0649b69306ac0cf493833f9e301e6b4c3edd9a11.
[Apple evidence](measurements/native-notification-actions-apple-2026-11-02.json).
It excludes owner7cf6bd72 and this inventory. Native HRIS41544727565 ateac completed
success but needs artifact audit; raw+interop41550122006 at7cf is running. db74 native
HRIS41547370301 was superseded/cancelled; protocol41547370414 succeeded. Inspect actual
artifacts before acceptance; do not repeat long local provider-emulator hangs.

NEXT integrate Prepare/Issue/Validate/Finish with the surface owner in a finite app
publisher and actual Koin/SQLite tests. Explicit new-original versus silent replay/
same-source rerender policy is needed; default recovery stays silent. Open should be an
authenticated navigation-only alias and must never execute a send/read action from an
outer routing hint. Supplementary .work/chat-notification-publisher-2026-11-03 contains
plan.txt and UNAPPLIED .txt drafts, not implemented source. Follow the expanded
[integration plan](collaboration-notification-publication-plan.md), including ongoing
all-company/source/settings/read invalidation, focused-thread registration, opaque
denied-card retirement, retained old-family/pre-retention reply review and actual native
host/process-death proof. The raw transport and owner are still not bound to delivery.

Phone19/sourcec0 remains installed; no product build/install in this increment. Next
meaningful release>19: notify immediately before explicit10.77.77.3:5555 install-only,
preserve signer/data, never launch/drive the phone UI. Live304/schema116/chat push OFF,
all54 PRD groups open. Continue long run with atomic commit/push and Git handoff; no
Starter port until integrated Mobile proof. Older entries below are historical.

## Mechanical surface owner accepted; continue queued OS cleanup and HRIS integration

CollaborationNotificationSurfaceOwner/Lease now implement closed startup/parent epochs,
nonwaiting finite requests, registered child cancellation and joined cleanup. The lease
checks epoch/latest supplied parent/context/completion and keeps only four opaque attempted
references. Failed compensation blocks new native requests until explicit reconciliation;
phase CAS prevents a pending retry reopening a replaced parent. No Activity/private
preview/payload queue is retained. This class is NOT bound into HRIS delivery yet and
is not company/source admission. [Contract](collaboration-notification-surface-owner.md),
[evidence](measurements/chat-notification-surface-owner-2026-11-02.json).

Focused1 failed compilation54.270s:10 intentionally throwing test callbacks needed
explicit generic<Unit>; production compiled, no tests ran. Test-only annotations fixed
that inference failure. Focused2 passed78.288s,16 fresh cases/one suite with quality/
architecture74/detekt. Added logout/no-parent coverage and guaranteed release of the
non-cancellable test barrier; production hashes stayed unchanged. Full3 passed311.019s:
3587 desktop/517 suites,230 Android-host/37, six proof-host/one, ten build-logic/two,
17 browser/one.420 fresh cases/72 suites, zero failures/errors/skips, three frozen inputs.
Actual browser runtime, virtual-time/fake native surface; no new native/DI acceptance.
No local heavy job or owned emulator remains at this checkpoint.

NEXT resolve queued native-submission cleanup before activating the coordinator. Raw
Android notify acknowledges a queued OS request, but current clear enumerates active
cards and remove returns Absent without cancellation. Add explicit queued/restart proof;
a durable opaque pending-ID registry under native no-backup storage may be required for
namespace cleanup without old account decryption. Do not equate joined app work with
an OS/database transaction. Current raw proof covers active cards, not that uncertainty.

Then bind the owner to actual processed native state and coordinate Prepare/Issue/
Validate/Finish with localized policy-limited payloads. Retained company denial, source/
settings/read changes and focused thread suppression remain separate. Existing
OpenCollaborationNotificationAction gates company access, so denied-card dismissal still
needs opaque authenticated retirement. Issuance.Ready currently carries only the render;
new-source versus reused/source-stamp-only alert policy must be explicit. Open/navigation
must never send/mark read merely from a raw routing hint. Old-family/pre-retention replies
need encrypted explicit review before activation. Continue the expanded
[integration plan](collaboration-notification-publication-plan.md#integrating-the-tested-android-transport).
Supplemental .work/chat-notification-native-next-2026-11-02/application-owner-after-raw.txt
now has extra boundary notes. Its earlier statement that no owner code exists is obsolete;
the owner described above is implemented/tested. Other proposed integration is not.

Full Apple41547382869 atdb74e22d/175 source hashes is still RUNNING (last full Xcode app
build); follow .work/chat-native-notification-apple-2026-11-02/audit.py after success,
never cancel/redispatch. It excludes this new owner. Native HRIS41547370301 atdb74 was
pending behind eac/41544727565; inspect actual status and both raw11-group plus five-case
interop artifacts before acceptance. No repeat of long local provider emulator hangs.

Phone19/sourcec0 remains installed; no product release/install here. Next meaningful
release>19: notify immediately, use explicit10.77.77.3:5555 install-only, preserve signer/
data and do not launch/drive its UI. Live304/schema116/chat push OFF, all54 PRD groups
open. Continue long run; push every atomic commit and keep evidence in Git. Generic
Starter port still waits for integrated Mobile proof. Older sections are historical.

## Raw Android actions proved; continue HRIS publication ownership

The core notification data module now has a separate raw actionable surface. Actual
Android NotificationManager/PendingIntent/RemoteInput proof covers private/public
versions, reply-only mutability, explicit private receiver, full URI action identity,
stale render cancellation, preserved original envelopes, namespace cleanup and denied
permission/channel. No HRIS receiver/delivery binding is active yet. Do not route chat
content through generic plaintext notification history.
[Contract](native-notification-actions.md),
[evidence](measurements/native-notification-actions-2026-11-02.json).

Focused1 passed90.051s:19 fresh cases/five suites (13 core notification/four, six proof
host/one). Full2 passed248.797s:3570 desktop/516 suites,230 Android-host/37, six proof
host/one, ten build-logic/two,13 browser/four;399 fresh cases/74 suites, zero failures/
errors/skips.13 implementation/workflow/driver inputs frozen. Removing UPDATE_CURRENT
was a review improvement after the first native pass; replay retains the original
envelope instead of rewriting extras. Full2 tests that product source.

Lint3 failed68.995s with one NewApi error: the isolated probe checked PendingIntent.
isActivity before its API31 guard. Only that fixture assertion moved inside its guard.
Follow-up4 passed90.169s: proof assembly, six proof-host cases, lint, formatting,
architecture74 and detekt. Product hashes match full2; only the probe API guard differs.
Lint has zero errors and47 existing version-availability/proof-manifest warnings; no
dependency upgrades or unrelated fixture-policy changes were made.

Native1 passed10 behavior groups/two actual API35 scenarios in25.966s before the envelope
review. Native2 passed11 groups/two scenarios in5.065s against final sources, including
same-render envelope preservation; no native scenario failed. Separate emulator-only
proof package, fictional text and no account/provider. Fixture data cleared after each
run; owned5562 emulators stopped; other emulators untouched. Final proof APK SHA256
b57d30c9e85011db99386586f6ddc0f0998ff6e1572b6ed816f5b22c31d3a02a.
This is actual raw IPC, not HRIS admission, notification-input OS death, or phone UI.

Hosted Native HRIS workflow now runs tool/check_native_notifications.py on owned5560
before its existing five SDK/WorkManager cases. New run41547370301 atdb74e22d is PENDING
behind eac2ac98/41544727565 in progress. Track its raw artifact plus the five existing
cases separately; do not claim either pending run as acceptance. Native protocol
41547370414 is also in progress.

Consolidated full [Apple41547382869](https://github.com/fajarxfce/hris-mobile/actions/runs/41547382869)
was dispatched once at2026-11-02T23:41:45Z fordb74e22d9dbb9a4ffcdc7ada0a211337041aba3e,
175 verified source paths. It is IN PROGRESS. Follow .work/chat-native-notification-apple-
2026-11-02/audit.py after actual success; do not redispatch/cancel. Tracked
[pending evidence](measurements/native-notification-actions-apple-2026-11-02.json).
Last audited Apple remainsbb95ff1d/41539628582:895/101/full Xcode964s; it excludes issuer,
lifecycle and this increment. No local heavy job or owned emulator remains now.

NEXT implement the serialized application surface lifetime and admitted coordinator,
then callback retention/review/routing, company/settings/read invalidation and focused
thread suppression. The expanded [integration plan](collaboration-notification-publication-plan.md#integrating-the-tested-android-transport)
is authoritative; .work/chat-notification-native-next-2026-11-02/application-owner-after-
raw.txt is supplemental design ONLY, no owner source drafted/applied. Transport has
five routing hints but HRIS currently authenticates four actions: Open/navigation must
not dispatch send/read from an untrusted hint. Existing Replies.submit does not retain
old-family/pre-retention failures automatically. Resolve explicit encrypted review
before enabling chat notifications. Add notification-specific OS-death replay, then iOS
surface/full Xcode and integrated Mobile proof before generic Starter port.

Phone19/sourcec0 remains installed. No new product release or phone install here.
Next meaningful release>19: notify immediately, install-only with explicit10.77.77.3:5555,
preserve signer/data and never launch/drive its UI. Live304/schema116/chat push OFF,
all54 PRD groups open. Keep long run going and push atomic commits. Earlier sections
below are historical.

## Exact publication lifecycle accepted; continue native integration

ValidateCollaborationNotificationPublication checks an exact active render against
current native parent/company/source and all authenticated actions, then rereads the
journal. FinishCollaborationNotificationPublication acknowledges Published or retires
Dismissed/Cancelled by exact CAS, strips retired preview/actions and returns opaque
metadata. Same account may clean its older native family under company denial, but
cannot publish it. Both operations are finite8s; Finish is singleton/nonwaiting.
Internal authentication is shared with issuance without UC-to-UC calls. These are
point-in-time domain operations, not a lasting OS lease or distributed transaction.
[Contract](collaboration-notification-publication-lifecycle.md),
[evidence](measurements/chat-notification-lifecycle-2026-11-02.json).

Focused1 passed159.318s,62 fresh cases/seven suites:14 lifecycle domain,16 issuance,
14 raw publication, nine envelopes, four actual application lifecycle, three issuance
integration and two DI cases. Full2 passed378.476s:3565 desktop/515 suites,230 Android
host/37, six proof-host/one, ten build-logic/two,53 browser/four.2039 fresh cases/252
suites; zero failures/errors/skips; ten frozen input hashes. Quality/architecture74/
detekt, proof assembly and shared Wasm compilation passed. SQLite/application reopen
uses fictional HTTP/simulated SDK and memory fixture credentials; no OS display or
notification-input process-death proof. No failed check in this increment.

NEXT serialize native publication and exact-render cancellation in an application-owned
lease, cancel/join invalidation and handle namespace cleanup independently of old
account decryption. Connect admitted priority candidates before wake acknowledgment;
ordinary/backfill discovery and focused-thread suppression still need explicit policy.
Then native adapters/actions, retained-input review and notification-specific OS-death
proof. The supplemental .work/chat-notification-native-next-2026-11-02/plan.txt remains
design beyond the now-implemented lifecycle. No native callback/display is active.
Use UI/UX/Compose skills for UI and dedicated emulator, never user-phone automation.

Raw-journal [Apple41539628582](https://github.com/fajarxfce/hris-mobile/actions/runs/41539628582)
atbb95ff1d passes independently audited895 simulator cases/101 suites and full Xcode
964s (test compilation637s/execution102s), zero failures/errors/skips.150 source
hashes and GitHub artifact digest verified; artifact13188010537, ZIP SHA256
8ca4c79714ae4d319c2d762d1cc37fba650bbd2e98039fbd63c89e0877e916d8.
[Apple evidence](measurements/chat-notification-publication-apple-2026-11-02.json).
This excludes issuer017 and this lifecycle; later consolidated Apple must cover them.
Native1eb/41523212486 remains the last independent five-case audit and excludes
notification inputs. No local heavy Gradle job or owned emulator remains here.

Phone19/sourcec0 remains installed. Build the next product release with version>19,
notify immediately before explicit10.77.77.3:5555 install-only, preserve signer/data,
and do not launch its UI. Chat exists; native notification actions/call/video/meeting
UI remain absent. Live304/schema116/chat push OFF, all54 PRD groups open. No Starter
port for HRIS policies. Continue long run with atomic commits/pushes; older sections
below are historical.

## Admitted issuance accepted; continue exact native validation and cleanup

IssueCollaborationMessageNotification now retains an admitted exact Prepared render,
using actual native parent, retained company gate, acquired DM, original/self/read/
settings/peer facts and repeated source checks. It reuses authenticated stored action
envelopes after uncertainty; new renders preserve the stable card ID and use fresh
render/operation UUIDs. Singleton nonwaiting mutex,10s budget, no network/selected
workspace/credential renewal, exact CAS/no hidden retry. Timestamp/event-ID ordering
is an explicit acquired presentation watermark, NOT causal order. Same inactive source
cannot revive; changed source stamps or room binding conflict. Ready is not an OS
lease. [Contract](collaboration-notification-issuance.md),
[evidence](measurements/chat-notification-issuance-2026-11-02.json).

Focused2 passed67.523s:55 cases/six suites with explicit reuse, five fresh app/DI cases.
Sixteen domain issuance,11 projection,14 raw publication, nine action-envelope, three
actual Koin/SQLite issuance-flow and two DI cases. Full3 passed437.218s:3547 desktop/
513 suites,230 Android-host/37, six proof-host/one, ten build-logic/two,50 browser/four;
2018 fresh cases/250 suites, zero failures/errors/skips and12 unchanged input hashes.
Root quality/architecture74/detekt, proof assembly and shared Wasm compile passed.
Browser executes16 issuance+11 projection+14 publication+9 actions. Actual app tests
prove reply-token reopen and one retained command through submission flow, lost actual
journal response recovery, and retained denial across reopen. Fictional HTTP/simulated
SDK; fixture credentials remain in memory, not notification-specific OS death proof.

Focused1 failed one of55 fresh cases in165.353s: the actual denial fixture compared the
whole Failure, including its original message, although admission storage intentionally
retains kind/code and reconstructs a canonical message. Corrected test-only assertions
before/after reopen; production denial policy was unchanged. No other failure in this
increment. All .txt drafts under .work/chat-notification-issuance-2026-11-02 are APPLIED/
STALE; do not reapply. No local heavy job or owned emulator remains at this checkpoint.

Raw-journal full Apple41539628582 atbb95ff1d is still RUNNING (last full Xcode simulator
app build),150 verified source paths. Follow .work/chat-notification-publication-apple-
2026-11-02/audit.py only after actual success; no duplicate dispatch or cancellation.
It EXCLUDES this issuer. Latest audited full Apple remainsc0/41530048115 (895/101,
full Xcode910s), and native1eb/41523212486 remains the last independent five-case audit.
Once current Apple finishes, validate a later consolidated source containing issuance
and any following lifecycle changes; do not claim pending CI as acceptance.

NEXT implement the [native lifecycle boundary](collaboration-notification-publication-plan.md#exact-lifecycle-boundary).
Supplementary .work/chat-notification-native-next-2026-11-02/plan.txt is design ONLY,
no native lifecycle code drafted/applied yet. Add finite exact publication revalidation;
submission acknowledgment and retirement return only opaque target/version details.
A current account may clean its own older-native-family records without a company
content grant, but may not publish them. Retirement must strip preview/actions, preserve
watermarks and reject stale expected versions. OS cancellation matches exact render;
parent replacement uses independent namespace cleanup. Serialize native publication/
cleanup and join invalidation; do not claim distributed atomicity with the OS service.
Then candidate/backfill and focused-thread suppression, Android/iOS callbacks, explicit
retained-input review UI and notification-specific OS process-death proof. Use UI/UX/
Compose skills for UI, dedicated emulator only. No tests on user accounts/messages.

Phone19/sourcec0 remains installed with signer/data preserved; no further product build
or phone install in this increment. Next product version>19: notify immediately before
install-only using explicit10.77.77.3:5555, never launch/drive its UI while in use. Chat
UI exists; native notification actions and voice/video/meeting UI still absent. Live304/
schema116/chat push OFF unchanged. All54 PRD groups remain open; keep long run going,
commit/push every atomic increment, no final here. No Starter port for HRIS policies.
Earlier sections below are historical.

## Raw publication journal accepted; continue admitted issuance and native delivery

The account-owned encrypted publication journal now retains exact rendered previews and
four action envelopes, with one atomic checkpoint/document update,64 active slots and
four-record discovery pages. Inactive conversation watermarks release active capacity
and omit preview/actions. Old acknowledgments/dismissals cannot replace a newer render;
uncertain commits reopen with original envelopes. Existing history keys only, no schema
migration or new credential. Raw reading is NOT current company/native publication
admission. [Contract](collaboration-notification-publications.md),
[evidence](measurements/chat-notification-publication-2026-11-02.json).

Focused3 passed35 cases/four suites in250.861s. Full6 passed in449.328s:
3528 desktop/511 suites, 10 buildLogic/2 suites, 230 androidHost/37 suites, 6 proofHost/1 suites, 23 browser/2 suites, zero failures/errors/skips. 1972 fresh cases/246 suites;
explicit task reuse recorded,11 input hashes unchanged. Root format/architecture74/
detekt, proof host/assembly and shared Wasm passed. Browser executes14 actual IndexedDB
publication cases plus nine existing action cases; SQLite uses the same14-case contract.
Fixture credentials retained in memory across reopen; no reply-input OS process death
or native publication/callback acceptance.

Failed attempts remain recorded. Focused1 failed test compilation (cross-module nullable
smart casts and nonexistent credential clear method). Focused2 rejected real issuer
Base64 padding; all14 publication cases failed and one waited60s at an unreachable
commit barrier. Fixed canonical encoding validation and commit-or-result test select,
with malformed padding/unused-bit cases. Focused3 accepted. Full4 failed903.650s with
one of373 fresh cases: AppFlow Welcome wait at3912, BEFORE lifecycle/navigation action.
Host memory/I/O pressure observed, not proved causality. Unchanged isolated scenario5
passed47.020s; no UI/timeout modification, then accepted full6. The focused artifact
helper also shadowed a folder variable; corrected helper only, removed its generated
copies and used archived fresh XML. No failed run is described as acceptance.

Full Apple [41530048115](https://github.com/fajarxfce/hris-mobile/actions/runs/41530048115)
atc0fafd8592bfaa1eaab40f153235a18f66afa59f is now AUDITED:895 simulator cases/101 suites,
zero failures/errors/skips,141 source hashes and GitHub ZIP digest verified. Artifact
13183286547 SHA2568874cfd4d6da386cd3c681d97816e7aa54bded7f855efe648c847f7f3fa5b07b;
compile496s/tests85s/full Xcode910s. Covers durable reply cycle/application worker,
excludes this publication journal. [Apple evidence](measurements/chat-notification-reply-worker-apple-2026-11-02.json).
Physical iPhone waived; no APNs/CallKit/native notification acceptance. At last check,
c0 control41530010424 and protocol41530010464 succeeded (not separately artifact-audited);
9129 native41528135379 succeeded (not independently audited), c0 native41530010432 runs.
Latest independently audited native five-case source remains1eb/41523212486 below.

Journal bb95ff1d398e1a821ec0132734614f6250bda9c2 is COMMITTED/PUSHED. Full Apple
[41539628582](https://github.com/fajarxfce/hris-mobile/actions/runs/41539628582) was dispatched
at2026-11-02T22:43:18Z on that exact source,150 verified source hashes; last queued.
[Dispatch evidence](measurements/chat-notification-publication-apple-2026-11-02.json)
is not test acceptance. .work/chat-notification-publication-apple-2026-11-02 has run/
sources/audit.py ready. Follow actual success and full Xcode, audit/download once;
do not redispatch/cancel. No local heavy job or owned emulator remains at this checkpoint.

NEXT continue .work/chat-notification-issuance-2026-11-02: twelve
Kotlin inputs drafted as .txt ONLY, not applied/compiled. apply.py requires clean Git
and accepted journal measurement, records base hashes and rejects duplicate apply.
Review source before applying: internal current-family/company/source authority,
issuer with exact envelope replay, deterministic acquired timestamp/eventId watermark
(explicitly not causal order),16 domain and three actual Koin/SQLite integration cases,
shared preview factory/Prepare refactor, candidate validation and owning DI bindings.
Then format/focused1 (expected55) and full/browser (expected50/four suites). Draft
hypotheses are NOT tested acceptance. Native source discovery/backfill behavior,
revalidation/serialized owner lease, exact retirement, Android/iOS callbacks, retained
input review UI and notification-specific OS death proof still remain.

Release19 remains installed on10.77.77.3:5555, sourcec0/signature/data preserved; no new
phone install or UI launch in this increment. Notify before every future install and
use explicit serial/install-only, next version>19. Chat UI exists; voice/video/meeting
UI and native chat actions still absent. Live304/schema116/chat push OFF unchanged.
All54 PRD groups open; continue long run and push every atomic commit, no final here.
No generic Starter port for HRIS storage/policy. Earlier sections are historical.

## Release19 installed; continue native notification publication

Release19 (devRelease1.0.0, arm64, non-debuggable) from
c0fafd8592bfaa1eaab40f153235a18f66afa59f is INSTALLED on the authorized phone
10.77.77.3:5555. Notify-before-install was given; install-only update started
2026-11-02T21:49:41.888604Z and verification finished in46.912s. UID10962 and first
install2026-10-29 15:48:23 preserved; no uninstall/data clear/UI launch or driving.
Installed bytes match SHA25669046c53bf99091a218e2c1470c0291e8916d2ddf2a2ba4ad79cbe0099bcf470.
Same dev signer as18; private API/Firebase app/arm64/16KiB alignment/18 backup
exclusions verified. Build+lint475.141s, zero lint issues, clean source before/after.
[Release evidence](measurements/android-release-phone-2026-11-02-build19.json).

Owned emulator5562/PID1504915 booted26.706s; two cold starts passed12s stable windows,
readable UI/no crash,18.984s and16.566s. Total smoke61.320s; second screenshot visually
inspected (onboarding, no clipping; not a pixel baseline). Emulator is STOPPED and
transport absent. Startup wrapper exited1 solely because its initial cleanup checked
ADB immediately after signalling; later inspection found the PID gone/transport closed.
Added bounded10s polling and confirmed cleanup, no second app run. The verifier's first
attempt also had a preparation mistake: fixed backup count18 was accidentally changed
to19 with versionCode; corrected verifier only, APK unchanged. Both helper failures are
preserved in evidence; do not call the initial wrapper a clean command pass. Unrelated
CodePush emulator5580 was untouched. No own local heavy job/emulator remains.

Native [41523212486](https://github.com/fajarxfce/hris-mobile/actions/runs/41523212486)
at1ebc0f9400a8200bda56b0178e447d814a40feca is newly AUDITED: five actual interop cases/
three suites,230 Android-host/37 and six proof-host/one, zero failures/errors/skips.
Artifact13180757970 ZIP SHA2563b517b430b840a48abc4124bac0c44ea0fb17657804ad6dd26572d1aa274d6c4
matches GitHub;130 source hashes checked. SDK receipts/encrypted sends, three worker
process-death states and separate WorkManager restart/network recovery all passed
and cleaned up. APK hash0d6a2b8ff3bca0ca3f4097e77b1d21ab585014e4ef166ee2d82ed7c8d41e2d25
reported by all five drivers. Android build804s/Platform compile259s/native step969s.
[Native evidence](measurements/chat-reply-seek-native-hosted-2026-11-02.json).
This source covers basic seek only: excludes cycle/new reply worker. Existing native
scenarios do NOT exercise notification reply inputs, receivers or display.

Full Apple41530048115 atc0fafd85 is RUNNING (last simulator compilation),141 source
hashes verified. .work/chat-notification-reply-worker-apple-2026-11-02 has dispatch,
sources and audit.py ready; wait for actual success/full Xcode before auditing. Do
not redispatch/cancel it. Latest accepted full Xcode remains8ca/41520480368 below.
At last check: c0 control41530010424/native41530010432 pending; native protocol
41530010464 running;9129 native41528135379 running;1eb control41523212264 running.
9129 control41528135677 was superseded/cancelled. Follow actual results; none of these
pending/running jobs is accepted yet. Physical iPhone tests remain waived.

NEXT: implement [native publication/receiver plan](collaboration-notification-publication-plan.md).
Supplementary .work/chat-notification-publication-2026-11-02/plan.txt and storage-design.txt
are design notes only, NO journal code drafted/applied yet. Evaluate one account
active-card index plus encrypted per-conversation documents/watermarks, exact-CAS
state and stable envelopes; avoid a two-partition atomicity gap and permanent active
capacity consumption by dismissed cards. Current history has timestamp/event IDs,
not causal sequence: define acquired-candidate ordering, never arbitrary push order.
A suppressed unrelated candidate must not cancel the current different card. Native
publication requires a serialized owner lease in addition to domain post-IO guards.
Then native actions, explicit retained-input review UI and actual reply-input OS-death
proof. Use UI/UX/Compose skills for UI; dedicated emulator only, never user-phone UI.

Release19 adds internal reply recovery, not native notification actions or voice/video/
meeting UI. Chat push OFF and live304/schema116 unchanged. All54 PRD groups remain
open. Continue long run, atomic commits and push each; don't final at this delivery.
Next validated product update must be >19 and use notify-then-install-only with same
signer/data. No generic Starter port for these HRIS policies. Earlier checkpoints
below are historical.

## Application reply recovery accepted; build/install release19, then native publication

The real Koin application now runs a bounded reply phase inside global chat recovery.
It uses the durable Begin/Advance/Settle/Finish cycle, four IDs/eight seconds total,
two seconds per attempt, no waiting processor or in-invocation wrap. Begin checks the
worker's captured native parent before storage. Company denial retains input; other
failures retain retry state across later healthy pages/reopen. Global recovery reserves
reply8s/priority20s/company50s within90s; independent failures beat healthy continuation
and cannot skip the ordinary company turn or acknowledge pending work. The isolated
company-only proof fixture explicitly supplies no reply work, not native reply proof.
[Worker contract](collaboration-notification-reply-worker.md),
[evidence](measurements/chat-notification-reply-worker-2026-11-02.json).

Focused1 passed 73 cases/five suites in 171.264s: 18 cycle domain, seven actual SQLite/
Koin reply-worker, 17 existing submission-flow, 29 global worker and two DI cases.
Full2 passed in 415.399s: 3514 desktop/510 suites, 230 Android-host/37, six proof-host/
one, ten build-logic/two, 18 actual browser cycle cases/one. 1953 fresh cases/244 suites,
zero failures/errors/skips; explicit reuse and all ten input hashes unchanged.
Format/architecture74/detekt, proof APK assembly and shared Wasm compile passed.
No failed attempts. SQLite fixture credentials stay in memory; no OS-death reply-input,
native display or callback acceptance yet. Source drafts under .work/chat-notification-
reply-worker-2026-11-02 are now APPLIED; do not reapply them.

NEXT release: .work/release-phone-2026-11-02-build19 contains PREPARED scripts only.
Read-only pre-build check found authorized phone10.77.77.3:5555 connected on code18;
code19 is newer. No installation or UI driving occurred. Build from this clean pushed
source using build-arm64.py (assembleDevRelease +lint, existing dev signer, private API),
verify-arm64.py, owned emulator5562 cold startup/screenshot inspection, then preflight
and notify immediately before install.py --install-only. Preserve data/signature and
Google session; never launch/drive user phone UI. Use owned cleanup stop-emulator.py
on either emulator outcome; startup failure must also clean up the recorded owned PID.
No local heavy job remains after full2. Existing CodePush emulator5580 is unrelated and
must remain untouched. Do not repeat the failed long local provider-worker attempts.
Keep tracked files clean during the product build; archive other CI results in .work
until build's clean-source verification completes.

Dispatch full Apple on this committed source with the prepared .work/chat-notification-
reply-worker-apple-2026-11-02/dispatch.py after push. It checks 141 source paths and
records the dispatch before sending; on uncertain result recover the existing run,
never blindly dispatch twice. Latest accepted full Xcode remains 8ca/41520480368
(863 simulator cases/98 suites), excluding seek/cycle/worker. Control41523212264 and
native41523212486 at1eb last running; cycle9129 control41528135677/native41528135379
pending and may be superseded by this push. No new native result is accepted yet.
Prepared native1eb audit/sources are .work/chat-reply-seek-native-hosted-2026-11-02;
download its actual final artifact first, then audit. Existing five scenarios do not
exercise notification replies. Latest audited native remains1a92 below.

After release installation, continue [native publication/receiver plan](collaboration-notification-publication-plan.md):
encrypted exact-render journal, admitted issuance, owned OS publication and actions,
explicit retained-input review UI, and actual native process-death proof. Chat push
stays OFF until usable. This release adds internal recovery, not voice/video/meeting
UI. Phone18/source42f5d157 and live304/schema116 remain unchanged at this checkpoint.
All54 PRD groups remain open; continue long run, atomic commits and push each. No
Starter port for this HRIS policy. Earlier checkpoints below are historical.

## Durable reply cycle accepted; connect the bounded application worker next

The encrypted cursor now retains a cycle UUID and initial/in-flight/sticky-retry/
finished outcomes. Begin keeps an active cycle, starts new work at minimum sequence,
and returns no work for an empty account without requiring a history key or creating
a cursor. Advance records the attempt before work; Settle cannot clear earlier
failures; Finish proves an empty tail and includes interrupted attempts. A new tail
after a lost Finish cannot hide an older reply. Legacy integer cursor decode and
existing key/AAD are preserved; stale legacy writes cannot clear a cycle.
[Contract](collaboration-notification-reply-recovery.md),
[measurements](measurements/chat-notification-reply-cycle-2026-11-02.json).

Focused1 passed 59 cases/six suites in 192.770s. Review then found the fresh-account
empty-queue behavior; added a pending probe and three regressions. Focused2 passed
62/six in 211.542s. Full3 passed in 476.782s: 3501 desktop/509 suites, 230 Android-host/
37, six proof-host/one, ten build-logic/two, and 43 actual browser/four (31 domain,
12 real IndexedDB). 1965 fresh cases/246 suites, zero failures/errors/skips; explicit
reuse recorded and all 18 Kotlin hashes unchanged. Format/architecture74/detekt,
proof assembly and shared Wasm compile passed. Real SQLite/IndexedDB reopen and
assembled reply workflow are covered; credentials held in test memory, not OS-death
notification-input acceptance. No application recovery phase activated yet.

NEXT: .work/chat-notification-reply-worker-2026-11-02 has ten UNAPPLIED Kotlin drafts,
draft-map.json, inputs.json, plan.txt, focused/full harness and audit. The earlier
seek-only worker draft is obsolete. New worker uses Begin/Advance/Settle/Finish,
eight seconds total/four IDs/two seconds per attempt/no waiting processor. Global
recovery reserves reply8s, priority20s and ordinary company50s within90s. Any
independent failure must beat healthy continuation before wake acknowledgment.
Expected native parent must be passed into Begin and checked BEFORE storage, so a
stale worker cannot start a replacement account's cycle. This extra guard is in the
worker drafts, not this accepted commit. Update all constructor sites, including the
isolated company-only proof fixture. Drafts include five global budget/lifetime/ACK
regressions, seven real SQLite/Koin worker cases, and one new domain owner guard.
Apply, test and audit them next; no worker test result is claimed yet.

Full Apple [41520480368](https://github.com/fajarxfce/hris-mobile/actions/runs/41520480368)
at 8ca3c7d79dfbbd98c7db5456f5aad31d2621642b passed: 863 simulator cases/98 suites,
zero failures/errors/skips; simulator compile797s/tests102s/full Xcode1395s. Artifact
13179977279 ZIP SHA256 dfd3916c8c4ec5b1d14c64cc0431635e9a6b6e58c3d3bb48bfcf5a0c90faccde
verified against GitHub; 117 source hashes checked. This covers read/review and
application submission flow, excludes seek/cycle/worker. [Apple evidence](measurements/chat-notification-reply-flow-apple-2026-11-02.json).
Control41523212264/native41523212486 at 1ebc0f94 are running; native last in emulator
step. Older queued 8ca control/native were superseded/cancelled, not passed.
Latest audited native remains 1a92 below. Dispatch full Apple on the later committed
worker source; do not claim physical iPhone/APNs/CallKit acceptance.

Continue long run, commit atomically and push each. After worker acceptance, make a
validated release19 from clean pushed source, verify signer/ABI/API/cold startup,
notify before phone installation, then explicit10.77.77.3:5555 --install-only. Keep
same dev signer, data and Google session; never drive phone UI while it is in use.
Phone18/source42f5d157 and live304/schema116/chat push OFF remain unchanged. No own
local heavy job/emulator remains after cycle full3. Other host jobs are unrelated;
do not touch them. Native journal/issuer/callbacks/review UI follow. No generic
Starter port for this HRIS policy; all54 PRD groups remain open. Earlier checkpoints
below are historical.

## Encrypted reply seek accepted; implement durable cycle and actual worker next

Basic account reply recovery now has encrypted exact-CAS seek, opaque four-entry
ID discovery with at most one wrap, and parent-guarded advance BEFORE a selected
attempt. Company denial does not gate account cursor metadata. Existing history key
only/create=false, no new table/key, no company text/send grant in returned work.
Bindings resolve in the real app. [Contract and planned cycle](collaboration-notification-reply-recovery.md),
[evidence](measurements/chat-notification-reply-recovery-2026-11-02.json).

Focused1 passed14 domain cases but failed storage test compilation: ReplicaStore.replace
adapter omitted nullable ReplicaSnapshot. Test-only fix; added wrapped-page failure/
inactive-account write assertions. Focused2 passed23/four suites160.443s. Full3 passed
401.548s:3476 desktop/508 suites,230 Android-host/37,six proof-host/one,ten build-logic/
two,21 actual browser/three (14 domain +seven real IndexedDB), with explicit reuse.
1918 fresh/244 suites, zero failures/errors/skips. All15 Kotlin input hashes unchanged;
format/architecture74 modules/detekt, proof assembly and common app Wasm compile pass.
Actual SQLite/IndexedDB reopen/CAS/key-loss/cancellation proof; test credentials retained
in memory, not OS process-death reply acceptance. No app/global recovery worker activated.

IMPORTANT next: the old UNAPPLIED application-worker .txt draft is incomplete. Its
healthy last page can forget an earlier transient failure after the seek moves. Do
not apply it as-is. Implement optional durable cursor cycle (initial/inFlight/
retryRequired/finished), compatible legacy cursor decode, Begin/Settle/Finish CAS,
sticky failure across pages and minimum-sequence restart after interrupted Finish.
New cycles always start at minimum; active cycles resume after their persisted seek
and never wrap at the tail. Advance marks inFlight before attempting; settle preserves
sticky retry; Finish includes any unsettled attempt. Confirmed denied inputs remain
retained but do not force immediate cycles. Add crash-after-Advance/Settle/Finish,
transient-first/healthy-last/reopen and appended-tail regressions before wiring the
bounded independent app phase and global wake ACK union/company reservation. The
tracked contract has the design; supplementary .work/chat-notification-reply-recovery-2026-11-02/durable-cycle-plan.txt.
No cycle code/tests yet. Native display journal/issuer/callbacks and review UI follow.

Full Apple41520480368 at8ca3c7d79dfbbd98c7db5456f5aad31d2621642b RUNNING
(last simulator tests);117 verified source hashes and prepared audit under
.work/chat-notification-reply-flow-apple-2026-11-02. It covers prior reply workflow,
NOT this cursor. Control41520406550/native41520406393 pending behind824
control41519184287/native41519184124 last checked. Latest audited full Apple/native
remain completion2dd94e94/1a92 below; follow actual outcomes. No local heavy job or
emulator remains after full3. No phone interaction. Phone18/source42f5d157 and
live304/schema116/pushOFF unchanged. Continue long run/atomic commits/push each;
next validated product build >18 notify then explicit10.77.77.3:5555 --install-only,
same signer/data, no UI driving. All54 PRD groups remain open.

## Retain-first reply workflow accepted; continue durable account recovery

CollaborationNotificationReplies is now the application-owned submission/resume entry
point. It authenticates/retains input before company send admission, bounds eight
callbacks (including active) by one eight-second total deadline, serializes turns,
and makes recovery decline contention without extra waiters. Exact completion runs
before and after authenticated dispatch. Matching existing commands retire input;
changed text/old family/missing context remain held through reproduced review.
Transient failures keep unreviewed input. A failed or cancelled dispatch may have
committed the immutable command; retries reproduce it without issuing another send.
Absent is distinct from CommandRetained and NeedsReview; none grants native display.
[Contract](collaboration-notification-reply-flow.md), [evidence](measurements/chat-notification-reply-flow-2026-11-02.json).

Focused16 cases/two suites pass87.246s (14 new assembled-storage application tests,
two DI). Root/proof assembly/Wasm compile pass247.934s:3455 desktop/505 suites,
230 Android-host/37,six proof-host/one,ten build-logic/two, with explicit reuse.
364 fresh cases/67 suites, zero failures/errors/skips; five input hashes unchanged.
No failed attempts. Actual encrypted SQLite reopen, fictional HTTP and simulated
SDK; credentials retained in test memory. No new browser execution or OS-death
notification input proof; no native receiver/display/review UI wired. No generic port.

Next: independent durable account reply discovery/cursor/recovery phase. Eight
UNAPPLIED .txt production drafts plus plan under
.work/chat-notification-reply-recovery-2026-11-02. Tests are NOT written yet. The plan
requires account-only opaque ID discovery (do not company-gate the cursor), exact CAS
advance BEFORE each attempt, bounded paging with one wrap, fair progress past denied/
slow records, no endless immediate cycle over denied input, and ordinary company
recovery's reserved turn. Work stays immutable; Advance accepts separate expectedCursor
as the batch progresses. Add actual SQLite/IndexedDB, domain ownership/cancellation,
application graph and global worker reservation/ACK tests before accepting.
Then native display journal/issuer/callbacks and explicit review UI; chat push OFF.
Do not mistake any .txt draft or future recovery comment for activated worker wiring.

Full Apple/native latest audited sources remain completion2dd94e94/1a92 below and
exclude read/review/workflow. Dispatch full Xcode on this committed workflow source;
follow actual CI outcomes. Current phone18/source42f5d157 and live304/schema116 unchanged.
Keep long run, atomic commits and push each. Next validated product build >18: notify
before install, explicit10.77.77.3:5555 --install-only, same signer/data, no phone UI.
No local emulator running; local host hang investigation is historical, not accepted.
All54 PRD groups remain open. Earlier checkpoints below are historical.

## Reply input read/review accepted; next application callback workflow

Full Apple41508567222 attempt2 at2dd94e94ea6b74b9f4d9430f25348cb64a7f7f30
(docs-only after1a92) PASSED:843 simulator cases/96 suites, no failures/errors/skips;
compile698s, tests75s, full Xcode1484s. Artifact13175603014 ZIP SHA256
fbb0eba840c415b25b8b7476f02c7adafb5693fcc7ca7c71558e6036a80bd696
matches GitHub.109 exact source hashes checked. This covers exact reply completion;
read/review and later work excluded. Attempt1 failed solely because no hosted macOS
runner acquired the job; retained separately. Physical iPhone remains waived. No
APNs/CallKit/native-notification acceptance. [Apple evidence](measurements/chat-notification-reply-completion-apple-2026-11-02.json).

Native41504255602 at1a92d53c2a523dec1b943d416bce6dd32bd49d21 against
Platform304be027 PASSED five actual interop cases/three suites plus230 Android-host/
37 and six proof-host/one, zero failures/errors/skips. ZIP digest
8b0d649f71906684e2acddfceaa2828e1a2541725b2b88b5aee88f1c718152f7
verified against GitHub;109 source hashes checked. All five scenarios passed and
cleaned up: SDK receipts/encrypted messages, queued/frozen/cancelled worker inputs
across process death with176 incoming originals, and separate automatic WorkManager
restart/network recovery. Existing native scenarios do not exercise notification
reply input/receiver behavior. [Native evidence](measurements/chat-reply-completion-native-hosted-2026-11-02.json).
Earlier repaireddf0 native41502174267 also accepted with [separate evidence](measurements/chat-ci-repair-native-hosted-2026-11-02.json).
Control41504255605 at1a92 portable AND Apple adapter/host jobs finished success;
not separately artifact-audited here. Full Xcode evidence above is independently audited.
Both failed local worker attempts and failed diagnostic boot below remain failures;
local emulator host hang unresolved, no emulator running, no repeat required.

Read/review accepted with20 domain cases and updated actual SQLite/assembled-DI
coverage. Read uses current native owner and company admission before exposing text;
review reproduces command conflict, replaced family or absent acquired context, then
holds the exact input version. Matching commands need completion; transient failures
and retained denials never create permanent holds. Already-held text stays held; no
automatic sending or clearing. [Contract](collaboration-notification-reply-review.md),
[evidence](measurements/chat-notification-reply-review-2026-11-02.json).

Focused33 cases/four suites passed, but detekt first rejected review length103/max100;
extracted hold-and-reread helper. Full check2 eventually logged BUILD SUCCESSFUL47m7s
with1882 fresh passing cases/239 suites including20 browser/two. Python wrapper timed
out1800s and killed only xvfb-run; Gradle child finished without its exit code collected.
Preserved that failure and reports separately. Host I/O pressure was extreme; no
unrelated process stopped. Harness now owns/terminates its process group on timeout.
Unchanged-source check3 finished cleanly77.541s:3441 desktop/504 suites,230 Android-host/
37,six proof-host/one,ten build-logic/two,20 actual browser/two. Explicit reuse recorded;
20 browser cases rerun fresh, all failures/errors/skips zero. All eight input hashes
unchanged; format/architecture74 modules/detekt and proof assembly/Wasm compile pass.
No generic Starter port, native receiver, UI review page or recovery loop activated.

Next application reply wrapper has UNAPPLIED .txt drafts under
.work/chat-notification-reply-flow-2026-11-02 (wrapper/result/14 assembled-storage tests).
Retain before company admission; bounded serialized callbacks, exact completion before/
after dispatch; only reproduced review; preserve uncertainty. Apply and validate only
after read/review acceptance. Then durable fair account recovery and native display/
action ownership. Phone18 and live304/schema116/pushOFF unchanged. Keep long run/push
atomic commits. Next validated product build >18: notify first, explicit authorized
10.77.77.3:5555 --install-only; preserve data/signature, no phone UI driving.
All54 PRD groups open. Earlier checkpoints below are historical and superseded here.

## Local emulator stopped; worker proof unresolved, continue independent work

Native retry2 finished697.765s with three failed worker cases and unchanged frozen
APK/7825 source hashes. Emulator1167388 again exited via its HangDetector/CrashReporter
SIGSEGV. Changing swiftshader_indirect to modern swiftshader did not resolve it.
The cancelled-draft scenario reached176 accepted encrypted sends and provider history/
context reads before the hang (attempt1 reached158). The instrument subprocess uses
DEVNULL for stdout/stderr, ruling out an undrained host output pipe. Host core includes
qemud_pipe_send/AdbVsockPipe frames; a precise deadlock cause is not proven. Both failed
attempts and coredump metadata remain in Platform .work/chat-ci-repair-native-2026-11-02.

A third diagnostic BOOT only disabled the emulator VirtioVsockPipe feature (documented
as the replacement for goldfish_pipe). It never exposed a booted ADB target before
the startup deadline. No application test3 ran. Owned PID1192132 was stopped2.211s
after PID/AVD/port checks; its transport is absent. boot-3/result.json records this.
No emulator or local heavy job remains. Do not run native-3 or the original five-case
audit against these failed results; do not blindly repeat renderer/transport changes.
Do not describe worker proof as accepted. SDK and Android WorkManager from attempt1
remain their separate passing cases. [Updated evidence](measurements/chat-ci-native-investigation-2026-11-02.json).

Tracked source is no longer frozen for local proof. Continue independent reply read/
review/recovery implementation while following repaired hosted native41502174267
(emulator stage last checked) and latest native41504255602. Portable CI41504255605 at
1a92 has PASSED; its Apple adapter job is running. Full Apple41502192261 is accepted
below; next completion Apple41508567222 at2dd94e94 is queued. This host investigation
does not block independent product work or justify changing application code without
evidence. Existing .txt read/review drafts remain unapplied/unvalidated; add their
ownership/CAS/cancellation and assembled-graph coverage before acceptance.
Phone18 and live304be027/schema116 pushOFF unchanged. No phone interaction. All54
groups remain open; continue long run/push atomic commits and install next validated
product build >18 only after notice, explicit serial and --install-only.

## Full Apple through reply retention accepted; next run dispatched

Run41502192261 at exactdf0a1d408c9f895160aff86f8e0e152f2fbcefdb passes full artifact/
source/JUnit audit:830 cases/95 suites,zero failures/errors/skips;105 source hashes
verified. Artifact13172365004 ZIP SHA256b0e10fa32af7dd54dc03b8e52fdeea6a2e8976cecbad78be84df5dbe2a80b38f
matches GitHub's digest. Simulator compile606s/tests86s,full Xcode app1154s.
[Evidence](measurements/chat-ci-repair-apple-2026-11-02.json). Includes notification
action/invocation and reply-input retention compilation; actual tests include the
domain cases. Does not include newer completion1a92 or native notification acceptance.

Next full Apple41508567222 at2dd94e94ea6b74b9f4d9430f25348cb64a7f7f30 is dispatched:
same application source as1a92 plus docs.109 source hashes already verified.
Prepared .work/chat-notification-reply-completion-apple-2026-11-02/audit.py; run only
after actual completion, do not overwrite completed artifacts or cancel it with
another same-scope dispatch. Old control41497227558 is complete:portable failed the
known lazy-item assertion,Apple passed. Latest control41504255605 at1a92 is running
portable and Apple. Repaired native41502174267 is still running; source1a92 next.

Local native worker retry remains active under Platform native-2 (PID1167388 owns
emulator5562). Do not edit tracked source while its manifest is guarded. Documentation
updates are excluded. First SDK/WorkManager cases passed; three worker cases still
require accepted results, as recorded below. No phone install/change; phone18 remains.
Continue long run after CI/native audit; all54 PRD groups remain open.

## Native compatibility investigation running — keep source frozen

Current accepted application source1a92d53c2a523dec1b943d416bce6dd32bd49d21 is pushed.
All completion checks below pass. Frozen debug protocol APK343275325 bytes has SHA256
b8d1520f68bd6863c6c36b117cdfce05f6b34369699ab4dbad2ee52515468566; this is not the
product phone APK. Backend code matches304be027; checkout72447fb8 differs only in docs.
3628 Mobile/4197 Platform source hashes are frozen for local native verification.
[Current evidence](measurements/chat-ci-native-investigation-2026-11-02.json).

Native attempt1 ran719.592s:five cases/three suites,three failures. Actual SDK and
Android WorkManager pass, including receipt gateway, OS death and constrained-job
recovery. All three application-worker cases fail: cancelled-draft queued/restored
in distinct processes, then the owned emulator hung and the420s driver deadline
expired; remaining cases found no emulator. Emulator37.2.12/PID1140927 stopped with
SIGSEGV; coredump top thread is HangDetector::LooperWatcher -> CrashReporter::
GenerateDumpAndDie. Exact emulator hang cause unresolved; no application defect/fix
inferred. Not a kernel OOM kill. All source/APK hashes unchanged. First attempt
results, JUnit and coredump metadata preserved under Platform .work/chat-ci-repair-native-2026-11-02/native-1.
SDK/WorkManager success does not accept the failed worker cases.

Attempt2 RUNNING:only three failed worker cases, same frozen APK/source. Fresh
owned emulator5562/PID1167388, modern swiftshader mode used by CI, boot24.279s.
Only the known leftover protocol package from attempt1 was removed0.223s, after
serial/AVD/userdata ownership checks. No phone touched. Platform runner
.work/chat-ci-repair-native-2026-11-02/run-native-2.py writes native-2/result.json
and stops its owned emulator in finally. One heavy job at a time. Do not modify
tracked source while its7825 input hashes are guarded. Documentation is excluded.
All87 Python driver tests pass0.326s wall (unittest0.178s). Do not rerun native-1
audit.py blindly: it intentionally requires five passing cases and cannot accept
attempt1. Audit SDK/WorkManager from1 and workers from2 separately if retry passes;
record failure/renderer adjustment and current cleanup accurately.

Repaired native CI41502174267 atdf0a1d40 now running; latest1a92 native41504255602
and control41504255605 pending. Old b870 native41497227823 finished failure:receipt
denial plus three peer-seed failures under stale8b77 pin; scheduled case passed.
Full Apple41502192261 atdf0a1d40 has passed simulator compilation/tests and builds
the full Xcode app. Prepared105-source artifact audit is still required; excludes
completion1a92. Old control41497227558 portable failed the known lazy-item test;
its Apple host build was still running. Follow actual final outcomes.

Next after native proof is settled: current-owner reply read/reproduced review,
application reply wrapper, fair durable recovery and native display/action integration.
Unapplied/unvalidated .txt drafts for ReadCollaborationNotificationReply and
ReviewCollaborationNotificationReply are under Mobile .work/chat-notification-reply-completion-2026-11-02.
Do not mistake them for implemented code; review, add meaningful ownership/CAS/
cancellation tests, bind the actual app graph and run required checks when applied.
Phone18/source42f5d157 and live304be027/schema116 pushOFF remain; all54 PRD groups
open. Keep long run/push every atomic commit. Next product phone install >18:
notify first, explicit10.77.77.3:5555 --install-only; preserve data/signing, no UI driving.

## Exact reply completion accepted — 2 November 2026

CompleteCollaborationNotificationReply now reproduces the exact immutable command
before removing an exact retained input version. Account/company/conversation/ID/text
and valid command revision/state must match. It recognizes queued through sent and
authenticated cancelled archives without original history or fresh credentials.
Same-account reauthentication may recognize the old command; no old token becomes a
new send grant. Missing input (`Absent`), missing command (`Pending`) and retirement
with actual command state remain distinct. Current-family/retained-denial checks
surround every pending operation, and newer reviewed/recreated input is preserved.
[Contract and required follow-through](collaboration-notification-reply-completion.md),
[evidence](measurements/chat-notification-reply-completion-2026-11-02.json).

Focused26 cases/three suites pass166.107s:13 domain,11 actual application integration
(two added),two assembled DI. Full root/proof/Wasm/browser passes348.963s:3421 desktop/
502 suites,230 Android host/37,ten build-logic/two,six proof-host/one and13 actual browser/
one, with explicit reuse.1855 fresh/236 suites; zero failures/errors/skips. Seven
source/test hashes unchanged; format, architecture74 modules and detekt pass. No failed
attempts. Actual SQLite reopen proves queued/cancelled archives and changed-input
preservation, with test credentials retained in memory and an explicitly simulated SDK;
not OS process-death or native notification acceptance. No generic Starter port.

Next: commit/push; validate repaired native backend compatibility against all three
interop classes using an owned emulator and a frozen copy of the accepted proof APK.
Do not use the phone. Hosted repaired native41502174267/control41502174144 are still
pending behind b870; full Apple41502192261 atdf0a1d40 compiles simulator tests and
EXCLUDES this completion. Prepared final audit under .work/chat-ci-repair-apple-2026-11-02
with105 source hashes already verified against accepted measurements. Latest accepted
full Apple remains222bd441/41492596519. Do not cancel/claim pending jobs accepted.

Then current-owner input read, reproduced review, complete application reply wrapper,
durable fair recovery, display journal/issuer/native callbacks. Details are in the
linked contract and supplemental .work/chat-notification-reply-completion-2026-11-02/
next.md and reply-review-plan.md. Already reviewed inputs must not silently dispatch;
Absent grants no new send or OS cancellation; transient failure is not permanent review.
Phone18/source42f5d157 and live304be027/schema116 remain; chat push OFF, all54 PRD groups
open. Continue long run and push every atomic commit. Next product phone build >18:
notify first, explicit10.77.77.3:5555 --install-only, preserve signer/data, no UI driving.

## CI fixture corrections locally accepted; hosted verification required

Native HRIS workflow now pins Platform304be02700cf07e496421c17e90f5a471b4bd68c,
including the receipt gateway and current incoming/priority worker fixture. Prior
8b77c4f was incompatible with the newer proof APK. The failing large-text layout test
now uses collection scroll semantics for a definitely uncomposed target:27 rows,
initial target absence, then visible old text and unchanged header/composer bounds.
Back/send48dp assertions and submit disabling remain. No production UI/API changed.

Root check passes86.528s:128 fresh presentation cases/15 suites; including explicit
reuse3406 desktop/501,230 Android host/37 and ten build-logic/two, zero failures,
errors/skips. Format, architecture and detekt pass. Workflow YAML parsed and target
commit/receipt route checked. Both actual local before/after2x screenshots inspected;
recording only, not pixel baseline comparison. Two input hashes unchanged.
[Evidence](measurements/chat-ci-repair-2026-11-02.json).

Commit/push, then monitor repaired native and portable workflows to completion; do
not call hosted failures resolved from local success alone. Direct completed job log
125457030851 confirms b870 portable failed only the same lazy-item assertion
(128 cases/one failure). Latest full Apple remains222bd441/41492596519; dispatch
scope=apple at this repaired source and audit source/artifact/JUnit/full Xcode.
Continue reply completion/recovery work while hosted checks run. Phone18 unchanged;
next product install >18 only after notice, explicit serial and --install-only.

## Notification reply input accepted; CI repair next — 2 November 2026

Reply text, exact token and original native family now persist in a separate encrypted
account replica before send admission. At most 64 active inputs; no composer overwrite
or employee-command slot consumption. Repeated descriptor/text restores the original
token; changed text is a separate input. Exact versions protect review/removal. Missing
history keys never regenerate. The current-family retention use case checks ownership
after pending decode/storage, including committed input whose acknowledgment is lost.
[Contract](collaboration-notification-reply-inputs.md),
[evidence](measurements/chat-notification-reply-inputs-2026-11-02.json).

Focused attempt1:23 cases/one failure in100.107s, solely an incorrect test expectation
for inactive-account raw storage absence. Corrected attempt2:25 cases/three suites
including ten explicit reused domain cases;15 fresh/two suites,124.306s. Full root/
proof/Wasm/browser:377.197s,3406 desktop/501 suites,230 Android host/37, ten build-logic/
two, six proof-host/one and23 actual browser/two.1850 fresh/236 suites; zero failures,
errors or skips.13 source/test hashes unchanged; format, architecture and detekt pass.
Actual SQLite/IndexedDB reopen retains test credential state; not OS process death.
No native receiver, completion proof, dispatcher wrapper or display journal yet.

Urgent next: repair two automatic CI failures discovered while following earlier runs.
Native run41493788108 uses stale Platform8b77c4f, before the required receipt gateway;
update its immutable checkout to accepted/live304be027 and verify all actual native
proof cases (worker failures still need confirmation). Portable run41489333369 fails
ChatThreadLayoutTest:performScrollTo requests an uncomposed lazy item. Scroll the list
semantically, retain fixed-header/composer/touch assertions, and verify on hosted CI.
Downloaded old portable artifact13164751343 ZIP digest955cef55b4cb123478f8ec3baa7697402865051d1ec028ff11446bb703c8e52e
matches GitHub; the actual Indonesian2x screenshot was inspected. Evidence/logs under
.work/chat-ci-followup-2026-11-02. Current control41497227558 has failed portable and
still-running Apple; native41497227823 is running with the old pin. Local passing
checks do not excuse these failures. Follow final outcomes and fix before broadening.

Latest accepted full Apple remains222bd441/run41492596519:801 cases/91 suites and
full Xcode app; excludes newer actions, invocation and reply input. After CI repairs,
dispatch next full Apple on accepted source and audit artifacts/JUnit/source hashes.
Then continue exact-command proof before retiring reply inputs, bounded review/read/
recovery, native callback ownership, display journal/issuer and all54 open PRD groups.
Current private notes .work/chat-notification-reply-inputs-2026-11-02/completion-next.md
are supplementary; persist decisive contracts in Git. No generic Starter port.
Phone18/source42f5d157, live304be027/schema116 and Starter0d4432e unchanged; push OFF.
Next product build >18: notify, explicit10.77.77.3:5555, --install-only, preserve data/
signing, no phone UI driving. Continue long run and push every atomic commit.

## Settings/projection Apple build accepted — 2 November 2026

Apple41492596519 at exact222bd44147081089e4f22fbe1828e619916554f9 is fully audited:
801 cases/91 suites, zero failures/errors/skips;72 accepted source hashes verified
against that commit. Artifact13168136589 has verified GitHub ZIP digest
fc400e60a5934858c91c2661b7c1bfab9b4a030574752685a396be35bb7cd4e9.
Simulator compile795s/tests81s, full Xcode app1225s. This covers local settings and
notification projection, EXCLUDES action envelopes5fb8611b, command dispatchb8703cbe
and newer input retention. [Evidence](measurements/chat-notification-projection-apple-2026-11-02.json).
No physical iPhone/APNs/CallKit/provider/display acceptance. Physical tests waived.
This replaces the earlier pending status; all Apple jobs are now finished. Dispatch
next Apple workflow on the next accepted source after reply-input validation/push.

Ongoing uncommitted work:13 Kotlin inputs for encrypted reply-input storage and a
current-family private-input retention use case, under .work/chat-notification-reply-inputs-2026-11-02.
Authenticated checkpoint lists at most64 active input documents, independently of
composer/employee-command slots. HMAC identity makes repeated text/descriptor recover
the original token; changed text retains a separate input. Exact revisions protect
review/removal and later re-created input. No OS receiver or dispatcher wrapper yet.
First focused23 cases failed one test in100.107s: it expected a failure for inactive
account reads, while the existing raw replica contract returns absence. The test now
explicitly asserts null and verifies old input remains recoverable under its owner;
production code unchanged. Focused attempt2 is running; full/browser still REQUIRED.
Do not claim this input work accepted before actual reports. Raw storage absence is
not current-session authority: use cases must guard ownership around it.

Current implementation headb8703cbe is pushed. Phone18/source42f5d157 and live304be027/
schema116 remain; all chat push OFF. No emulator or phone interaction. Continue input
validation, then exact retained-command proof before input retirement, bounded review/
read/recovery, durable receiver wrapper, display journal/issuer/serialization and all54
open PRD groups. .work/.../completion-next.md records detailed follow-through. Native
reply caller must persist input before command dispatch and preserve it after failures.
No generic Starter port. Next product phone build >18: notify first, explicit
10.77.77.3:5555, --install-only, preserve signing/data, never drive phone UI. Keep
working after this documentation commit and push every atomic commit.

## Admitted notification command dispatch accepted — 2 November 2026

Current-session token opening now checks acquired company/direct-conversation and
exact reply/read event facts, including valid tombstones. Mute/dismiss do not need
original body retention. Captured native scopes pin the remote family throughout
pending message/read/settings work and observations, even when local session/account
IDs are unchanged. Ordinary captured reads retain their local account/session checks;
access-token rotation keeps a pinned observer open. No fresh credential or selected
company is required for these local operations.

The app singleton dispatches authenticated reply/read/mute/dismiss kinds through
existing durable use cases, within eight seconds and without a waiting queue. Reply
retains its original operation/body and actual command state. Native read covers only
the target using private metadata; unread-only policy avoids requeue after a later
ACK replaces the old receipt, while known operation conflicts still reject. Mute uses
observed settings version/preview and cannot overwrite a later unmute. Dismiss returns
an exact original-render request only. [Contract](collaboration-notification-invocations.md),
[evidence](measurements/chat-notification-invocations-2026-11-02.json).

Initial admission-focused18 cases pass151.053s. Expanded focused30 cases/five suites
pass160.896s:19 domain, nine actual shared Koin/crypto/SQLite integration cases and two
DI cases. Includes duplicate/reopen/changed reply input, cancellation after committed
reply while scheduling, superseded receipt ACK, stale mute, retained denial, bounded
callback overlap/cancellation/deadline and native-family replacement. SDK is explicitly
simulated, HTTP is fictional. Full root/proof/Wasm/browser passes363.004s:3383 desktop/
499 suites,230 Android host/37, ten build-logic/two, six proof-host/one and19 actual
browser/three, including explicit task reuse.1823 cases/235 suites fresh; zero failures,
errors or skips. Sixteen source/test hashes unchanged. No failed attempts in this
increment. DB reopen retains test credentials/tokens; this is not OS process death.

Apple41492596519 at222bd441 is building the full Xcode app after simulator tests.
It covers settings/projection, EXCLUDES action envelopes5fb8611b and this dispatcher.
Prepared artifact/source/JUnit audit: .work/chat-notification-projection-apple-2026-11-02/
audit.py, run only after success. Do not cancel this job with another same-scope
workflow. Latest accepted Apple remains42f5d157/run41480512291. Phone18/source42f5d157,
live304be027/schema116, Starter0d4432e remain; all chat push OFF. No generic port.

Next: commit/push, then encrypted native reply-input retention BEFORE wiring an Android
receiver. The command dispatcher requires its caller to persist input first; currently
no OS callback invokes it. Same token/body must recover; changed text on an old token
must remain a separate review input without overwriting the composer or first command.
Retaining family-authenticated private input is distinct from company send admission;
a later company denial must not silently lose typed text. Old-account input must never
be stored under its replacement. Retire input only against an exact retained-command
proof. Preserve uncertainty and cancellation-after-commit. A separate bounded encrypted
input replica should not consume global128 employee-command slots for bookkeeping.
Then implement exact-token issuance/display journal, serialized publication/cancellation,
privacy controls and native inline replies. Results here are not OS display leases;
match current owner and exact render before changing native cards, and allow separate
privacy cleanup after content admission is denied. Input text is never logged or put
in generic plaintext notification history. All54 PRD groups remain open; continue
long run after each milestone and push every atomic commit.

Design notes .work/chat-notification-delivery-2026-11-02/next-input-retention.md include
storage bounds and unresolved producer/capacity policy. Replica checkpoint limit32768
characters cannot hold many full token sets; use bounded encrypted document records
with authenticated checkpoint/CAS metadata. Do not notify every backfill event or
mistake scheduling hints/timestamps for message authorization/order. Prepare -> Attempted
before notify -> SubmittedToOS/Retired preserves uncertainty, not exactly-once sound.
Current priority worker completes hints after original acquisition, so display integration
must preserve its own durable work before that completion. Native privacy changes need
owned serialization/cancellation; current local use cases have no OS publication owner.

Local check-3/audit.py/record.py completed under .work/chat-notification-invocations-2026-11-02;
no repeat of accepted broad checks absent changes/failures. Next product phone build >18:
notify first, explicit10.77.77.3:5555, --install-only, preserve signing/data, no UI driving.

## Notification action envelopes accepted — 2 November 2026

Bounded native action metadata now seals under the retained history content key.
Reply/read/mute/dismiss carry stable operation UUIDs and company/conversation/room/
original/native-card/render identity. AAD binds API/account/local and native session;
old-family or changed-key tokens reject. No body or bearer credential is embedded.
Mute carries the observed settings revision and preview. Missing keys never regenerate.
[Contract](collaboration-notification-actions.md),
[evidence](measurements/chat-notification-actions-2026-11-02.json).

Focused 11 cases/two suites pass in 150.412s, including nine real-cipher storage
contract cases and two assembled DI cases. Full root/proof/Wasm/browser passes
358.711s: 3355 desktop/495 suites, 230 Android host/37, ten build-logic/two,
six proof-host/one and nine actual browser/one, including explicit task reuse.
1785 cases/229 suites are fresh; zero failures/errors/skips. Nine source/test
inputs stayed unchanged. DB reopen retains test credentials and an in-memory token;
it does not prove token journal persistence or OS process death. No Starter port.

Apple 41487045428 at 7d35e975 failed before any build step because GitHub could not
acquire a hosted macOS ARM runner; annotations identify capacity constraints.
Replacement 41492596519 at exact 222bd44147081089e4f22fbe1828e619916554f9 is running
simulator compilation. It covers settings/projection, EXCLUDES these newer actions.
Audit actual final artifact/source/JUnit/full Xcode when complete; do not cancel it
by dispatching another same-scope workflow blindly. Latest accepted Apple remains
42f5d157/run41480512291. Phone18 and live304be027/schema116 remain; chat push OFF.

Next: commit/push this increment, then admitted native action issuance/consumption,
encrypted display journal, serialized publication/cancellation, privacy controls and
native inline replies; continue all54 open PRD groups. Crypto opening is NOT current
admission. Capture the parent from SessionRepository, never Intent extras; gate local
company/retained denial/exact history around pending operations. Persist exact sealed
tokens before publishing and reuse after uncertainty; re-sealing makes new ciphertext.
Use existing durable reply/read/settings operations and exact render cancellation.
RemoteInput receiver must be explicit/internal, bounded and application-owned.
Display bookkeeping must not consume CommandStore's global128 employee command slots.
No decrypted chat content in generic plaintext notification history. Source stamps do
not form an atomic OS display lease, and candidate suppression must not clear an
unrelated card. Delivery notes under .work are supplemental; these constraints are
required regardless of their older one-use wording. No native display is active yet.

Local .work/chat-notification-actions-2026-11-02/check-2 and audit.py passed; do not
repeat accepted broad checks absent changes/failures. All .txt projection drafts were
already applied and superseded by reviewed Kotlin. Next product phone build >18:
notify first, explicit10.77.77.3:5555, --install-only, preserve data/signing, no UI driving.
Continue long run after this checkpoint and push every atomic commit.

## Local notification projection accepted — 2 November 2026

A captured-account exact event can now be prepared from encrypted original history,
retained direct-room labels, protected self identity, mute/preview settings and read
coverage. Muted/self/read/redacted sources suppress; missing data remains pending;
a confirmed tombstone suppresses even before its original is available. Defaults
expose no names/body, previews are bounded and avoid splitting surrogate pairs.
Source alias lists are detached; final original and settings comparisons detect
pending changes, and retained denial/current ownership surround each suspension.
No SDK/network, implicit retry, read mark, receipt ACK, UI or OS display is added.
[Contract](collaboration-notification-projection.md),
[evidence](measurements/chat-notification-projection-2026-11-02.json).

Focused10 cases initially passed141.782s. Review then added exact missing-original
tombstone lookup and moved final settings comparison after read-state/original reads;
three regressions were added. Final focused13 cases pass137.768s (11 domain/two
assembled DI). Full root/proof/Wasm/browser passes333.788s:3346 desktop/494 suites,
230 Android host/37, ten build-logic/two, six proof-host/one,11 browser/one, including
explicit task reuse;1778 cases/228 suites fresh, zero failures/errors/skips. Seven
source/test inputs unchanged during final validation. No new native/provider or
phone interaction test. No Starter port for this HRIS policy.

Settings7d35e975 is pushed. Apple41487045428 at exact
7d35e975d32bd04fcfdebb0487de23a03dff6174 remains QUEUED; audit eventual full Xcode
and simulator artifacts. It excludes this newer projection; dispatch Apple against
a later accepted head after it finishes, avoiding same-scope cancellation. Latest
accepted Apple remains42f5d157/run41480512291,54 hashes and780 cases plus full Xcode.
Phone18/source42f5d157 and live304be027/schema116 remain, all chat push OFF.

Next: commit/push this increment, then implement encrypted notification delivery/
action state, owned display/cancellation, UI controls and native inline reply. All54
groups stay open; continue long run. Source stamps are not an atomic OS display lease;
coordinator must serialize/revalidate and retire stale cards. Suppression is not a
blanket cancel for an unrelated card: reproject that card's canonical original.

.work/chat-notification-projection-2026-11-02 .txt drafts were ALREADY APPLIED and
then corrected in actual Kotlin. Do not reapply stale drafts. check-3/audit.py passed;
accepted full checks need no repeat absent changes/failures. Next design notes are
.work/chat-notification-delivery-2026-11-02/notes.md: do not consume the generic
CommandStore's global128 employee-action capacity for display bookkeeping; use
scoped encrypted notification state, bounded operations and exact acknowledgments.
Avoid decrypted chat text in generic plaintext notification history. Define initial
backlog/source handling rather than treating timestamp order as processing authority.
Reply tokens must survive duplicate callbacks and rotate after consumption; preserve
text on conflicts without overwriting a composer. iOS extension/store concurrency
and current-owner native cancellation remain separate required work. Next product
phone build must exceed18, notify first, explicit10.77.77.3:5555, --install-only,
preserve signing/data and never drive its UI.

## Notification settings accepted; release18 Apple audit — 2 November 2026

Encrypted per-conversation mute/preview settings and bounded local read/update use
cases are implemented. Defaults hide names/content; mute supports expiry or forever.
Stable operation plus observed generation/revision recovers lost replies and rejects
old actions after newer changes. Same-account reauthentication retains settings but
invalidates old captured OS-action ownership. Current local direct binding and
retained denial surround pending I/O; no fresh token/selected company required.
Existing history key/AAD protects one checkpoint; inactive missing-key guards remain.
No schema/key reset, hidden retry, read-receipt preference change or provider I/O.
[Contract](collaboration-notification-settings.md),
[evidence](measurements/chat-notification-settings-2026-11-02.json).

Focused21 cases pass232.667s: ten domain, nine SQLite, two actual DI. Full
root/proof/Wasm/browser passes400.043s:3335 desktop/493 suites,230 Android host/37,
ten build-logic/two, six proof-host/one,19 browser/two, including explicit task reuse.
1775 cases/228 suites fresh; zero failures/errors/skips. First focused55.332s had
two fixture failures only; captured-scope and denial sequencing fixed before all
checks passed. Database reopen uses a retained test credential store, not a new OS
process-death acceptance. No UI controls/display/cancellation/native action consumer
exists yet. This increment has no generic Starter port.

Release18/source42f5d157 Apple41480512291 is fully audited:780 cases/89 suites, zero
failures/errors/skips,54 accepted source hashes, artifact13162038114 digest
f48d985d395c78e855cab177782b9084b8f024b5142ef21d160efbbfccd49e0f.
Simulator compile758s/tests75s, full Xcode app1315s. Covers worker/viewport, excludes
these newer notification settings. [Apple evidence](measurements/chat-visible-read-apple-2026-11-02.json).
Phone remains verified18, live API/worker304be027/schema116, all chat push OFF.

Next: commit/push settings, then dispatch Apple on that exact head (previous Apple
finished; workflow concurrency would cancel a running same-scope run). Continue
notification projection, durable encrypted display/action ledger, mute/read cancellation,
UI controls and native inline reply, then all54 PRD groups. Three production and two
test .txt drafts under .work/chat-notification-projection-2026-11-02 are UNAPPLIED,
UNVALIDATED. Review source stamps/alias snapshots/Unicode bounds and sender-label
binding, then wire owning DI/tests before applying. notes.md documents renderer
races and RemoteInput duplication; a local projection alone cannot atomically order
OS display against concurrent preference writes. Settings .work/check-3 already
passed; don't rerun accepted broad tests without changes/failures. All54 groups stay
open; continue long run and push every commit. Next product phone build must exceed18,
notify first, explicit10.77.77.3:5555, --install-only, preserve data/signing, no UI driving.

## Release18 installed; receipt gateway live — 2 November 2026

Clean42f5d157 built signed devRelease18/1.0.0 (arm64, non-debuggable) and lint in
477.226s. API/Firebase target, signature,16KiB alignment and compiled backup
exclusions verified. APK87544452 bytes, SHA256
9216942d0acb4dbdc1145138bafa6cac7e62331ab5e837b3e718c2fb218cb957.
Two anonymous emulator cold starts pass with resumed readable UI and no crash;
second screenshot visually inspected. Owned emulator5562/PID871456 stopped.

Installed after notice with --install-only on10.77.77.3:5555 at16:39UTC in57.480s
including pulled-APK exact verification. Same signer, appUID10962 and original
first-install timestamp retained; no uninstall/data clear/UI launch. This is
installation/startup evidence, not human chat or calling acceptance.
[Evidence](measurements/android-release-phone-2026-11-02-build18.json).

Platform304be027 is live at unchanged schema116 after all14 full-CI jobs and fresh
verified recovery. Google/person/native/document fingerprints and existing Matrix
identity/config/provider containers preserved, dashboard2124c6fc unchanged, all
chat push flags OFF. Platform72447fb8 records the rollout. First checkpoint attempt
used inheritedJava17 and stopped before replacement; explicitJava21 corrected it,
with old runtime restored and verified before successful retry.

Apple41480512291 at42f5d157 has passed simulator tests and is compiling full Xcode;
audit final reports/artifact before acceptance. Earlier04e5cf7e Apple success excludes
worker/viewport. Continue privacy/mute, notifications/actions/inline replies and
all54 PRD groups. No generic Starter port. Chat inbox/thread UI is present; voice/
video/meeting UI still absent. Next validated phone version must exceed18, notify
first, explicit serial, --install-only, preserve data/signing and never drive UI.
Continue long run and push every atomic commit. Older pending sections below are
historical; .work release18 scripts already ran and must not blindly be repeated.

## Active viewport read marks — 2 November 2026

Chat now records exact local read coverage from a resumed, focused conversation.
The route observes visible stable keys; a current owner token and acquired original
history determine eligible colleague events. Dialogs, backgrounding, focus loss,
leaving and confirmed access loss interrupt marking. The authenticated history page
supplies its retained room binding, so an offline restart needs no live room lookup.
No offscreen gap or pending echo is marked. A long message needs at least half of
the viewport visible; smaller messages need at least half of their own height.

The ViewModel's tracker serializes cancelled storage work, retains uncertain intent,
skips already-read events and requests bounded background recovery after local
persistence. Initial viewport receipts are private to the user's own Matrix identity.
Public-sharing preferences and peer read ticks are not implemented by this increment.
No UI/Compose provider I/O, hidden polling or recipient delivery ACK is added.

Focused28 cases pass133.840s: eight ViewModel, two actual Compose
lifecycle/viewport,16 SQLite history and two assembled-container cases. Portrait375dp
with2x text and landscape720×375dp cover scroll, focus, dialog, pause/resume/disposal.
Full root/proof/Wasm compile passes389.235s: 3316 desktop, 10 buildLogic, 230 androidHost, 6 proofHost, including
explicit reuse; 1737 cases/224 suites fresh, zero failures/errors/skips.
The first browser filters selected zero cases. Corrected trailing-wildcard filters
pass31.689s separately:16 IndexedDB history and eight browser ViewModel cases,
zero failures/errors/skips, with actual fresh JUnit reports; source unchanged.
Initial two Compose failures were a missing test-only desktop Main dispatcher; the
existing Swing dispatcher dependency fixes their host without changing production.
[Evidence](measurements/chat-visible-read-2026-11-02.json).

Worker c361aa1f is pushed. Earlier Apple41475111107 at04e5cf7e PASSED full Xcode
(947s), simulator compilation613s and native tests59s:780 cases/89 suites, no
failures/errors/skips. Archive13158956537 digest500aee72a25ef2378491d42f9a900b3beb81006d7800118ed6445f4de412444d
and37 accepted source hashes verified. [Apple evidence](measurements/chat-read-receipt-apple-2026-11-02.json).
This excludes the newer worker/viewport; dispatch Apple again against this eventual
commit. Platform full Verify41475356301 at304be027: all8 backend/4 dashboard shards
and recovery passed; dashboard integration remains running. Backend artifacts audit
3159 API cases/436 suites (five existing optional skips),117 worker/24 per shard
(one existing optional skip), no failures/errors. Gateway receipt rollout remains
separate: live runtime dac6fb0d/schema116, chat push OFF, phone17.

Next: commit/push this increment, dispatch Apple on that head, build validated
release18 and install on10.77.77.3:5555 after notice, --install-only, same signer/data,
no phone UI interaction. Prepared .work/release-phone-2026-11-02-build18 scripts are
not yet run. Continue privacy/mute/notification actions/inline replies and all54
groups. No Starter port for HRIS policy. Continue long run; this is not completion.
.work/chat-visible-read-2026-11-02 stage.py/.txt drafts were already applied: do not
rerun them over accepted code. Reports/audit scripts remain supplementary to Git.

## Receipt recovery worker accepted — 2 November 2026

The existing incoming room turn now invokes admitted receipt replay: limit2 with
3-second operation sub-budget, preserving incoming history progress on ordinary
receipt failure and closing publication on confirmed denial. Remaining intents
continue a fair sweep; cancellation joins through the existing child/runtime owner.
No selected workspace/outbox needed. [Evidence](measurements/chat-read-receipt-worker-2026-11-02.json).

Focused23 cases pass66.328s; full root/proof/Wasm pass236.840s with3306 desktop,
230 Android host,10 build-logic,6 proof host cases including reused tasks;339 cases/
65 suites fresh, no failures/errors/skips. Seven new shared-container/SQLite/HTTP
cases use an SDK double. Initial focused62.360s failed only a provider-message versus
safe-gate-copy assertion; corrected classification and retained ciphertext assertions
pass. No new native provider or physical phone proof is claimed. Actual native
sender/process recovery remains the accepted ae4a1b27 proof below.

Hosted Apple41475111107 at04e5cf7e has passed simulator tests and is building the
full Xcode app; audit its final reports (it does not cover this worker increment).
Platform full Verify41475356301 at304be027 remains running. Gateway receipts still
not deployed: live API/worker dac6fb0d/schema116, chat push OFF; phone release17.

Next: apply/review staged UI work in .work/chat-visible-read-2026-11-02 AFTER this
commit. staged.json has nine source baselines and .txt drafts; additional ReadFixture/
ReadTest .txt drafts still need fixture wiring and lifecycle Compose tests. Drafts
are NOT validated or accepted. They expose the authenticated history room binding,
observe resumed/focused viewport, retain exact local read intent and request worker
recovery. Review owner replacement, cancellation and visibility carefully. Then
privacy/mute/notification actions and all54 groups; continue long run. Next validated
product build goes to10.77.77.3:5555 after notice, --install-only, preserve data and
signing, no phone UI interaction. No generic Starter port applies to HRIS policy.

## Apple validation and next integration — 2 November 2026

Mobile ae4a1b27 and Platform304be027 are pushed, with actual admitted read-receipt
replay/process recovery and all local checks described below. The Apple CI workflow
now also links/runs the collaboration domain's common tests alongside the existing
native core cases and full Xcode app build. This workflow-only increment does not
change the accepted runtime/proof sources. Dispatch `ci.yml` with scope=apple on
feat/communications after pushing; track its exact head/run and audit its reports.
No Apple success is claimed yet. Continue account-bound background/foreground
receipt integration while hosted Apple runs. Next validated product build must also
be installed on the authorized phone after notice; current release17 is unchanged.


## Admitted read-receipt replay and actual recovery accepted — 2 November 2026

[Contract](collaboration-read-receipts.md) / [evidence](measurements/chat-read-receipt-replay-2026-11-02.json):
common runtime receipt entry and a singleton finite sender now consume the encrypted
queue. Exact retained coverage/originals, live HRIS device/platform/conversation/room
admission, another pending-intent read before SDK entry and fresh admission after
SDK success precede identical local ACK.20-second budget, default4/max8 intents,
no hidden retry/ordering/coalescing; concurrent points and uncertain outcomes remain.
Confirmed remote denial is retained even when writing its gate fails. No worker,
foreground/UI, notification or scheduler call yet.

Focused133.705s passes12 domain/two assembled-container cases. Full root/proof plus
12 browser cases PASSES292.360s:3299 desktop/230 Android host/10 build-logic/six proof
host cases including explicit reuse;1732 cases/222 suites fresh, zero failures/errors/
skips. Actual native113.216s passes one HRIS/PostgreSQL/Synapse/Android scenario on
133 Mobile/25 Platform hashes. Local mark survives database reopen; provider success
followed by interrupted app ACK leaves the intent through real OS death. The next
process replays, acknowledges, reopens and repeats without another send. Exact local
coverage remains. Seven receipt POSTs succeed200 (five private/two public); prior
private isolation, three encrypted sends and all three process-death stages pass.
27 driver cases pass0.104s. Proof APK343275325B SHA256
6dbe4aceb281528b02f97eef9b519fce78ce28638f253eab2e72ec4e8040cd01.

Local dirs .work/chat-read-receipt-replay-2026-11-02 and Platform
.work/chat-read-receipt-replay-native-2026-11-02 retain reports/scripts. Staged .txt
files have been applied; DO NOT reapply old baselines. Actual fixture
.work/collaboration/37854341d48f45a0b43dc56ca73b985b has verified.json, not acceptance.json.
Owned emulator5562/PID790095 boot26.497s, proof removed by driver, stopped1.905s.
No emulator or heavy job remains. Phone remains17; private runtime remains
dac6fb0d/schema116, chat push OFF. No Apple or phone acceptance is inferred.

Commit/push this Mobile increment, then the three validated Platform proof/driver
inputs with counterpart evidence. Previous local mark increment a349c84e is pushed.
Next: dispatch full Apple/Xcode against this common integration; wire read receipt
replay into the existing account-bound background worker and foreground chat, then
privacy/mute and notification inline reply/actions, and continue all54 groups.
Explicit privacy cancellation must not masquerade as server ACK or silently drop
pending public receipts. No generic Starter port applies to these HRIS policies.
User requests the next validated product build on the phone too: notice first,
explicit10.77.77.3:5555 --install-only, retain signing/data, never drive phone UI.
Chat UI is in17; voice/video/meeting UI remains absent. Continue long run; not done.


## Local read/mark admission accepted — 2 November 2026

[Contract](collaboration-read-state.md) / [evidence](measurements/chat-read-state-policy-2026-11-02.json):
finite local read and singleton mark use cases now guard current/captured ownership,
retained content denial, the direct-conversation pair and every exact acquired event.
No fresh credentials required for owned local work; no unseen-gap/timestamp inference.
Mark has10-second cancellation and try-lock exclusion, observed-version CAS, stable
caller intent recovery, explicit capacity rejection and no hidden retry. Failed/late
responses never erase committed intent/coverage. No scheduling/provider/UI call yet.

Focused check2 PASSES128.050s with14 domain/two real-container cases. Full check3
PASSES270.921s:3287 desktop/230 Android host/10 build-logic/14 browser cases including
explicit reused tasks;1716 cases/220 suites fresh, zero failures/errors/skips. Initial
check1 stops42.074s at incompatible generic types in a new test assertion, corrected
before tests executed. All nine source/test inputs and report/log hashes recorded.

Next: runtime receipt contract/Android adapter/fake additions are PREPARED ONLY in
.work/chat-read-receipt-replay-2026-11-02/staged.json (14 hashed source baselines plus
.txt files). Additional .txt drafts contain DeliverCollaborationReadReceipts, result,
fixture and12 tests. They are not applied/validated/accepted. Apply only after this
commit and verify baselines; review the draft before compiling. Replay must recheck
live owner/device/exact room before writes and after provider success, retain the
original intent on uncertainty and ACK only its identical queued entry. Then wire
worker, foreground reads, notification/privacy/mute/inline replies and all54 groups.
New common changes need full Apple/Xcode coverage. No generic Starter port here.

Phone remains release17, live API/worker dac6fb0d/schema116, chat push OFF. User now
explicitly requests installing the next validated build too: notify immediately
before every install, explicit10.77.77.3:5555 --install-only, preserve data/signing,
never drive the phone UI. Chat UI exists in17; voice/video/meeting UI remains absent.
Latest user confirmation was release11's crash fix, not a17 chat/phone UI acceptance.
Push this commit and continue long run; this is not task completion.


## Encrypted local read state accepted — 2 November 2026

[Contract](collaboration-read-state.md) / [evidence](measurements/chat-read-state-2026-11-02.json):
exact read markers and a128-intent queue now share an authenticated atomic checkpoint.
One mark adds at most128 exact acquired references; it does not infer unseen coverage
or provider ordering. Pending points remain until their identical acknowledgement;
late ACK preserves other queued points. Active/last-acknowledged replay deduplicates,
known ID rebinding and queue overflow reject without evicting facts. Shared history
key guard includes the separate read namespace and inactive accounts. No schema/key
replacement, timestamps, data reset or age eviction. New repository resolves in the
real shared container; common local admission/replay/UI integration is still absent.

Initial single-pending draft passed125.002s (nine SQLite/two DI cases), then review
required retaining all unsent points instead of assuming monotonic supersession.
Full check2 failed247.689s on an existing RoomLookupTransport test's immediate read
of a Boolean written by MockEngine after caller cancellation; new11 storage cases
passed. Fixture now awaits a separate completion signal on Default with a5-second
bound and verifies no result publication; HTTP production unchanged.

Check3 PASSES161.148s:3273 desktop/230 Android host/10 build-logic/11 IndexedDB cases,
with explicit cached/up-to-date statuses;1373 cases/156 suites freshly executed.
Check4 separately passes the corrected five browser transport cases25.118s. Nine
source/test input hashes and all attempts/reports retained. No emulator/phone work.
Native receipt counterpart Mobile c0ad592f and Platform f87a4562 are pushed; real
SDK109.111s proves five HRIS receipt POSTs, private isolation and store recovery.
Phone17 and live dac6fb0d/schema116 remain unchanged, chat push OFF.

Next: expose receipt methods through common runtime ownership; add local read-state
observation/mark use cases with captured-account/content-denial guards and exact
owned-history validation, then live-admitted bounded queued replay. Wire the worker
and UI/notification actions afterward. Retain all unsent points without timestamp
coalescing. Privacy changes need explicit suppression/cancellation of queued public
receipts; do not label an unsent cancellation as a server acknowledgement. Follow
with mute/privacy/actions/inline replies and all54 groups. New common changes need
full Apple/Xcode coverage; physical iPhone waiver still applies only to device tests.
No generic Starter port. Continue long run and push every commit; not task completion.


## Native public/private read receipts accepted — 2 November 2026

[Contract](collaboration-read-receipts.md) / [evidence](measurements/chat-read-receipt-client-2026-11-02.json):
Android owns finite receipt reads/writes through existing parent-child cleanup.
A separate lazy native request starts only after current caller acquisition;
cancellation/parent closure joins it before closing the room. Failed cleanup stays
owned. Eight host lifecycle/validation cases pass. This adapter is not yet exposed
through the common runtime repository or invoked by product read/notification actions.

Actual SDK→HRIS→Synapse proof PASSES109.111s on114 Mobile/25 Platform inputs.
Five empty receipt POSTs (three private/two public) return200. Private metadata is
invisible to the peer; the SDK retains own metadata through actual process death and
reads it in the next process before sync. Existing encrypted exchange, queue/held-room
isolation, acknowledgement/completion recovery and three process restarts also pass.
Proof APK343,275,325 bytes, SHA2564bd9200487608c1efb258d29ddb3762e92bfe32079934307010fb690254cd035.
No FCM/OS notification/phone or Apple provider claim. Owned emulator5562/PID705941
is stopped and proof package absent; cleanup retained in measurement.

Full root/proof check2 passes275.337s:3262 desktop/230 Android host/10 build-logic/
six proof-host cases with explicit cache/up-to-date task statuses;1683 cases in218
suites freshly executed. Focused check1 passed eight cases but failed detekt's
101-line proof function; extracted completed-exchange evidence before the full pass.
No production failure or repeated native attempt. Python driver26 cases pass0.159s.

Platform receipt gateway8c87839a is pushed with focused99.155s/broad809.053s and real
Synapse7.216s evidence. Four counterpart native proof/driver changes are validated
locally and must be committed/pushed with their evidence immediately after this commit.
Live API/worker remain dac6fb0d/schema116, all chat push OFF; installed phone17 unchanged.
Keep private Matrix identity/keys/checkpoints and Google/native sessions intact.

Next: expose these operations through the common runtime owner; implement encrypted
local read intent/state and admitted replay, then mute/privacy/account-bound notification
actions and inline replies. Do not infer read ordering from timestamps or unknown gaps.
Continue all54 PRD groups; no whole group closes here. Apple shared/complete Xcode
validation must cover subsequent common changes. No generic Starter port applies to
this Matrix/HRIS increment. Notify before each phone update, explicit10.77.77.3:5555
--install-only, never drive the user's UI. Push every commit and continue long run.


## Native priority recovery accepted — 2 November 2026

[Evidence](measurements/chat-priority-native-2026-11-02.json) binds 108 Mobile and
seven Platform inputs to proof APK SHA256
a54d65517c55a4a94bd22303dd5ba1380aab0bcf8544dd96e1096e3a3b113a5b (343,275,325 bytes).
Actual HRIS/PostgreSQL/Synapse/Android SDK passes all three lifecycle variants:
cancelled draft in 289.577s, queued and frozen draft in 569.699s. Each recovers the
oldest of 175 offline peer events in one priority turn, retains 12 originals, preserves
the global wake and all company/room/Browse/Incoming positions across container reopens,
then recovers 176 originals in 11 fair incoming turns. Foreground independently pages
11 times over 186 references. One worker send and 175 peer ciphertexts, no duplicate
worker send; cancelled intent absent. Owned runtime and fixture cleanup pass.

Full check/proof build 3 passes 97.046s: 3,262 desktop, 222 Android host, 10 build-logic
and six proof-host cases, zero failures/errors/skips. Initial checks failed on a
101-line method and an outdated company-fixture constructor; phase ownership was
extracted and the synthetic company fixture explicitly supplies an empty priority
lane. No production source changed in this commit. Company WorkManager/process repair
also passes 45.199s, with synthetic work and no provider claim. Initial native run
correctly refused the company fixture's remaining owned APK before native SDK execution;
its installed hash was verified before removing only that package. Guard unchanged.
A missing collector input manifest was corrected before starting the second invocation.
All attempts and input/report hashes remain in the measurement.

The receiver is invoked with strict synthetic metadata; FCM/OS delivery, notification
privacy/actions, inline replies and capacity remain unaccepted. Counterpart Platform
proof is committed/pushed as bc9774eb. Owned emulator 5562/PID 553348 is stopped and its
proof package removed; phone untouched. No HRIS policy is portable to Starter here.

Release17 at clean c6347c75 is now INSTALLED on explicit10.77.77.3:5555 after notice,
using --install-only. Update/pull-back verification takes62.534s; installed SHA256
b94aedf2b1eb81949d8eeacc82fd2906f0c85b27641e3bcc65fd8884e5fb34d2 exactly matches the
87,495,300-byte accepted APK. UID10962/first-install time retained, non-debuggable,
no uninstall/data clear/UI launch. [Build/install evidence](measurements/android-release-phone-2026-11-02-build17.json).
Two actual-artifact emulator cold starts passed earlier via libndk_translation,
showing onboarding; no new phone login/chat/FCM claim. Future delivery must use a
higher versionCode, notify first and retain explicitserial install-only.

Platform full Verify41454857226 COMPLETE at dac6fb0d: all14 jobs pass, 3,153 active
API/116 active worker cases,1,111 mocked and56 actual browser workflows, schema116
recovery. Verified private API/worker/dashboard rollout and fresh103→116 backup/clone
checks pass. Existing Google/person/native/document state retained. Employee/operator
chat grants and read-only contact/device/directory checks pass, with applied roles
preserved. Temporary operator sessions revoked. API/worker now schema116/dac6fb0d,
dashboard immutable2124c6fc; stable private Matrix provider and keys retained.
All chat push flags remain OFF; no real-account messages or provider enrollment by
the operator checks. Platform f0929362 records exact source/images/checkpoints.

Apple41446374520 atcc4c11f1 is fully audited:112 native core cases/30 suites,
106 combined source hashes and full Xcode. Physical iPhone testing waived. Starter
backup port0d4432e passed and is pushed; no HRIS receipt policy port.

Next: Platform receipt work is staged as17 .txt inputs under .work/chat-receipts-
2026-11-02. It will add exact public/private receipt delivery and scoped sync
projection before Mobile read/mute/privacy/action integration. No new receipt code
is accepted yet. Continue encrypted inline replies and all54 open groups; this
phone/server delivery is not task completion. User's latest startup confirmation
was for release11; do not invent a release17 UI confirmation or drive their phone.

## Release17 accepted candidate and generic port — 2 November 2026

[Evidence](measurements/android-release-phone-2026-11-02-build17.json): clean c6347c75
arm64 devRelease17 build/lint passes433.769s (Gradle7m10s), zero lint issues. APK
87,495,300 bytes, SHA256b94aedf2b1eb81949d8eeacc82fd2906f0c85b27641e3bcc65fd8884e5fb34d2,
same installed development signer, non-debuggable, exact Firebase/private API and
revision metadata,16KiB alignment. Actual resource table resolves shortened backup
XML path; both nine-domain exclusions and false backup attributes are inspected.
Two local verifier assumptions (unshortened path/old aapt boolean text) were corrected
without rebuilding or changing application source. Prior release16 remains intact.

Starter0d4432e is pushed with identical XML/attributes after independent root/resources/
lint70.128s:356 desktop/six build-logic, zero failures/errors/skips,44 existing warnings.
No shared Kotlin/Apple or HRIS policy port. Mobile Apple41446374520 remains active at
cc4c11f1 in Xcode; audit once complete. Platform full Verify41448616746 at2334e266 still
runs; do not cancel/duplicate. Phone remains15, no installation. Before deploying17,
finish compatible server packages/images/checkpoint/clone/rollout/grants and refresh
phone preflight; notify and use explicitserial install-only, never phone UI driving.

Next native-priority proof is staged only in .work/native-priority-2026-11-02:
oldest of175 offline encrypted peer events, actual receiver/queue/HRIS/SDK, container
reopens, retained global wake and unchanged traversals. Apply Mobile fixture after
this build; Platform fixture changes must not invalidate rollout source guards.
No claim of actual FCM/OS priority delivery. Continue notifications/actions/replies
and all54 open groups after this candidate; this is not task completion.

## Android backup exclusions validated — 2 November 2026

[Contract](android-data-extraction.md) / [evidence](measurements/android-data-extraction-2026-11-02.json):
Android12+ explicitly excludes all nine local storage domains from cloud backup and
device transfer, while legacy allowBackup/fullBackupContent are false. No migration,
deleting stores, key transfer or user-managed encrypted backup feature. Root/release
resources/lint passes148.202s on the two unchanged manifest/XML inputs, with zero
Android lint issues. Actual linked binary XML and merged attributes inspected; the
measurement distinguishes executed/cache/up-to-date reports. No physical transfer.

Push this configuration, then port identical XML/attributes to Starter and run its
own root/Android checks. Shared/Apple Kotlin and Gradle sources are unchanged, so
active Apple41446374520 atcc4c11f1 still covers those exact inputs; do not duplicate.
Existing clean release16 artifact remains intact but excludes these rules. Prepare
release17+ from a clean accepted commit with the same signer/private API after the
port checks, then install only after compatible server rollout and prior notice.
Phone currently15, explicit10.77.77.3:5555 --install-only, no UI driving.

Platform2334e266 fixes only the outage fixture's immediate-retry assumption;116 active
worker cases pass298.222s. Replacement full Verify41448616746 is ACTIVE at that exact
source; the prior41442782804 failure remains recorded. Prepared rollout scripts now
target replacement source/run, but no HRIS checkpoint/migration/deploy/grant yet.
Private Matrix/pre-enrollment recovery and immutable dashboard candidate are ready;
HRIS schema103/API-worker2c2a59e3/dashboard1e0522c0 still run. Continue after this port;
notifications/actions/replies, actual priority SDK and all54 PRD groups remain open.

## Release16 staged; compatible server rollout pending — 2 November 2026

[Build/evidence](measurements/android-release-phone-2026-11-02-build16.json): exact
cc4c11f1b538d48680ee892f6709925bbe5e18ec arm64 devRelease16 build and lint pass464.885s,
1,969 Gradle tasks (614 executed/1,355 up-to-date). APK87,494,648 bytes,
SHA256291fbe05606191876cec1dae8eb02c0b1872210a338d92e8b3a4f125f820c07c. Signature matches
installed certificate aca5ce6a…721f3, non-debuggable, exact Firebase dev registration and
private API verified in binary, fixture endpoints absent,16KiB alignment. Source clean
before/after. Read-only phone preflight confirms15, connected10.77.77.3:5555 and same
signer. NO INSTALL yet. Notify first, use explicitserial --install-only, never drive UI.
Prepared scripts/artifact are .work/release-phone-2026-11-02-build16; do not overwrite
that artifact with a different build. Recheck package before install if state changes.

Apple41441078558 at d93e8cd5 COMPLETE:112 core cases/30 suites, zero failures/errors/skips,
96 combined incoming-wave/receiver/lookup/queue source hashes verified. Swift11s,
simulator compile672s/tests150s, full Xcode1320s and associations1s all pass. Archived
XML/source hashes are in the build16 measurement. Current Apple41446374520 at EXACT
cc4c11f1 is ACTIVE and covers the subsequent priority-processing source. Do not cancel
or duplicate. Physical iPhone acceptance remains waived; full app compile required.

Platform isolated Synapse/PostgreSQL runs privately and pre-enrollment recovery now
passes with archived keys only. Full Verify41442782804 at2124c6fc is still active (four
dashboard/three backend shards pass, backend1/downstream pending). Compatible dashboard
built into immutable private assets. No HRIS migration/grant/deployment yet; schema103/
API-worker2c2a59e3/dashboard1e0522c0 still run. Continue guarded accepted packages/images,
fresh coordinated checkpoint, isolated103→116 upgrade and explicit API/worker/dashboard
rollout; then employee/operator collaboration.chat grants preserving role snapshots,
and authorized phone16 installation. Platform's operator plan/measurement/handoff
contain exact protected paths and state. All chat push flags remain off.

Continue native SDK priority proof and notification read/mute/privacy projection,
durable display/actions and encrypted inline replies. All54 groups remain open, no
Starter port or owned emulator. This staged delivery is not completion; keep working.

## Push priority recovery validated — 2 November 2026

[Contract](collaboration-priority-recovery.md) / [evidence](measurements/chat-priority-processing-2026-11-02.json):
The worker gives one encrypted hint a20s turn with fresh hinted company/device admission,
admitted room lookup and independently checked exact binding. It acquires one original
anchor, then verifies a retained original alias/tombstone under local owner/denial gates.
No directory version is invented from push revision; normal company/room/Browse/Incoming
seek remains independent. Runtime/timeline cleanup is joined, incoming15s/room12s unchanged.
An exact snapshot CAS retires only acquired facts or ineligible scheduling metadata.
Every unsuccessful acquisition retains priority, including expired native credentials.
Normal fair company recovery still runs and the global wake remains until both lanes finish.

Focused1 passed200.440s:642 domain/57 suites,361 data/47,104 app collaboration/13,
Android release/static. Inspection then found premature completion of Blocked results.
A new real DI/SQLite expired-credential regression failed as intended45.795s against
unchanged production (expected Blocked, actual Complete). Corrected unsuccessful-result
retention and added full-worker fairness coverage. Full3 passes344.646s on16 unchanged
Kotlin inputs:3,262 desktop485 suites,222 Android host36,981 affected browser101 and
10 build-logic2; zero failures/errors/skips. The measurement archives all three attempts,
source/report hashes and task statuses. Seven actual app-container/SQLite priority cases
cover no cached list/workspace, mismatched binding, confirmed denial, unresolved original,
capacity, newer revision, cancellation/reopen and expired local credentials. SDK is a double.

Apple41441078558 at exact d93e8cd5eef294d3488a7ccb7f64cac6222ed241 remains ACTIVE:
Swift extraction11s, simulator compile672s/tests150s passed; full Xcode in progress.
Do not cancel or dispatch another Apple run until complete. Audit final artifact counts
and source hashes, then dispatch this next pushed head. Current16 inputs are excluded.
Earlier receiver dd1a449b Apple41436278627 is completely audited in queue evidence below.

Platform source2124c6fc passes both Collaboration control jobs41437984098. Full ten-job
Verify41442782804 is now active at that exact source for packages/recovery/private rollout.
Do not infer success or cancel it. Private rollout preparation .work/chat-private-rollout-
2026-11-02/plan.txt in Platform is READ ONLY so far: persistent internal Matrix/PostgreSQL,
protected JWT/config overlay, fresh coordinated checkpoint and isolated103→116 upgrade
before live changes. Existing Google/native/document state must be retained. Chat push stays
off until read/mute/privacy notification projection and durable actions/inline replies exist.
Continue that implementation and native SDK priority proof alongside rollout preparation.

Private schema103/API-worker2c2a59e3, phone15 and Starter5679c51 unchanged. Next authorized
phone build16+ needs notice first, explicit10.77.77.3:5555 and --install-only, no UI drive.
All54 groups remain open. No owned emulator, active goal or subagent. Continue after push;
this is not PRD completion.

## Encrypted push priority queue validated — 2 November 2026

[Contract](collaboration-push-priority.md) / [evidence](measurements/chat-push-priority-2026-11-02.json):
Validated envelopes now retain bounded room/event scheduling hints in a16-entry
account/native-family queue. Shared protected history key, distinct AEAD/partition,
24,000 plaintext/32,768 encoded limits; no generic storage bound enlargement. Local
session remains transactional ownership and native family binds ciphertext. Newer
company/device revisions coalesce in place; expired/future signals still request the
full sweep. Independent global wake precedes optional750ms/three-CAS priority work,
then fixed1.5s scheduling inside the3s receiver. Priority failure does not suppress
full recovery. Reads/completion guard local ownership independently of credential age;
exact snapshot CAS and fresh empty generations prevent stale acknowledgment.

Initial focused1 fails only a cross-module nullable-property smart cast in the app
integration test,127.280s. All631 domain/56 suites and361 data/47 already pass. Test
assertion corrected with requireNotNull; production unchanged. Focused2 passes the
same domain/data reports plus15 app push/6 suites and Android/static105.705s. Full3
root/Android release/affected browsers/app Wasm passes327.155s on20 unchanged Kotlin
inputs:3,240 desktop483 suites,222 Android host36,970 browser100,10 build-logic2;
zero failures/errors/skips. Task status map distinguishes executed and accepted
UP-TO-DATE reports. Ten SQLite/IndexedDB cases include maximum escaped payload,
reopen, same-family renewal, separate families, tampering, lost key, concurrency,
late account change and cancellation;20 new domain cases cover policy/access/budgets.
Real app DI/SQLite receives without selection/SDK, reopens, replaces revision and
rejects an older snapshot's completion.

Apple41436278627 atdd1a449b9a00a7323d453a48584b179c8e007416 is COMPLETE and audited:
112 core cases/30 suites, ten standalone Swift cases8s, simulator compilation431s/
tests77s, full Xcode947s and associations1s. All68 combined incoming-wave/receiver
source hashes match that exact commit. It excludes room lookup71c7f80c and this queue.
No active Apple run now: dispatch the next exact pushed head, audit its final artifacts.
Physical iPhone acceptance remains waived; core Apple tasks exclude collaboration/app
desktop tests.

Next .work/chat-priority-processing-2026-11-02 contains UNAPPLIED .txt candidates:
fresh hinted company/device admission without inventing directory versions;
ReadCollaborationHistoryEvent for exact retained original alias/tombstone under local
gates; incoming priority shares runtime/timeline/20s parent-priority budget, checks
actual room binding and never advances normal company/room/history seek. Main worker
must still give fair recovery its turn and retain the global wake for pending priority.
Candidates need application review, DI/tests, focused/root/browser checks and push.
Then implement read/mute/privacy notification projection, durable actions/inline reply,
compatible private backend/Matrix rollout and authorized release16+ installation.

Private schema103/API-worker2c2a59e3 and phone15 unchanged. Chat push stays disabled.
Notify before phone install; explicit10.77.77.3:5555, --install-only, no UI driving.
All54 groups remain open. No Starter port, owned emulator, active goal or subagent.
Continue immediately after push; this is not PRD completion.

## Owned room lookup validated — 2 November 2026

[Contract](collaboration-room-lookup.md) / [evidence](measurements/chat-room-lookup-2026-11-02.json):
RefreshCollaborationList.byRoom resolves an admitted direct summary from an opaque
room hint without directory scan, workspace selection, room creation or SDK startup.
It shares current scope, publication mutex, encrypted list merge and retained-denial
gates. Native transport has one encoded roomId query parameter and 4,096-byte response
bound; repository validates request/company/participants. Platform companion
`2124c6fc` is pushed and passes current native/RLS/HTTP/worker checks with no migration.

Focused check1 passes611 domain/53 suites,351 data/46 and one real app-container
lookup/reopen case, plus Android release/static checks,179.945s. Full root/release/
collaboration browsers/app Wasm passes310.687s on the same15 Kotlin inputs:3,210
desktop479 suites,222 Android host36,940 browser96 and10 build-logic2, zero failures/
errors/skips. The measurement distinguishes newly executed tasks from valid Gradle
UP-TO-DATE reports; domain/data desktop were already executed by focused1. No source
correction or failed check in this increment.

Apple41436278627 at exact pushed receiver headdd1a449b9a00a7323d453a48584b179c8e007416
is ACTIVE. Ten standalone Swift extraction cases pass8s; simulator compilation431s
and tests77s pass; full Xcode app is in progress. Do not cancel or dispatch another
same-scope run until completion. Audit final XML/artifact/source hashes. This run
excludes the current room lookup sources. Older41433354322 failed before runner
allocation, as documented below, not application code.

Next: apply the bounded encrypted push-priority queue, current hinted company/device
admission and independently checked room binding, then privacy/read/mute projection,
durable notification actions and encrypted inline replies. Candidate .txt files and
concrete design are supplementary in .work/chat-push-priority-2026-11-02; none has yet
been applied or tested. Keep full fair sweeps and generation fencing. Private schema103/
API-worker2c2a59e3 and installed phone15 remain unchanged; compatible rollout plus
release16+ can expose validated foreground/offline chat while chat push stays off.
Notify before install, explicit10.77.77.3:5555 and --install-only. All54 groups remain
open. No Starter port, owned emulator, active goal or subagent. Continue after push.

## Native push receive routing validated — 2 November 2026

[Contract](collaboration-push-receive.md) / [evidence](measurements/chat-push-receive-2026-11-02.json):
Android data-only and Apple remote callbacks share ApplicationPushReceiver. Chat's
strict10/12-field parser accepts canonical positive Long revisions as strings;
old valid hints can request recovery. Local account/audience/native-family ownership
is independent of credential age. QueueCollaborationPushSignal commits a fresh
durable global wake before fixed scheduling, guards late identity changes/cancellation,
and never reads workspace, starts SDK, acquires content or displays packet data.
Inbox routing remains separate. Apple caller owns cancellation/completion and25s
processing budget; receive3s/schedule1.5s. Real app DI/SQLite checks no-selection/no-SDK
and fresh wake persistence across container reopen, including stale-ACK rejection.

Focused1 passes601 domain/52 suites and15 app push/6 suites plus Android/static checks
216.813s. Review found a too-small JS-safe-integer revision cap; corrected to positive
Long and added producer Long.MAX_VALUE-1/maximum-envelope case. Full2 passes3,195
desktop476 suites,217 Android host35,602 domain browser52,10 build-logic2 plus root/
Android release/Wasm in407.797s. Its app-browser filter selected zero actual cases;
one skipped task placeholder retained. Unfiltered browser3 passes 26/7 suites,
including six new receiver cases, in34.754s on the same18 inputs. No actual final
case failed/errored/skipped. Source/report hashes and both corrections are recorded.

Prior incoming-wave Apple41433354322 at8972dc962833526a4b0d3b67c376fc9ce04496a6 failed
before runner allocation after15min queued. Annotation: hosted runner not acquired;
macOS arm64 capacity constrained. No test ran. New workflow adds ten standalone Swift
extraction cases before full app compile. Dispatch the new exact head after push;
there is no active Apple run now. Previous cacae157 audit remains valid for that source.

Platform companion room-lookup work is IN PROGRESS in .work/chat-room-lookup-2026-11-02:
summary-by-room uses current native admission and existing participant-owned binding,
no directory scan/migration. Five inputs. Check1 failed test compilation only (private
fixture DSL); fixed with fixture-owned roomScopes factory. Check2 running; inspect
its saved check2 log/JSON before any further heavy local check. Shared DI changed, so
API HTTP and :apps:worker:test are required in that command. Do not claim it accepted.

Continue bounded encrypted push priority hints and current-admission room resolution,
read/mute/privacy notification projection, durable actions and encrypted replies.
Global full sweep alone is not notification latency/scale acceptance. Consider compatible
private backend/Matrix rollout plus release16+ to expose validated chat progress while
keeping chat push off until usable. Notice before phone install; explicit serial and
--install-only. Phone15/private schema103/API-worker2c2a59e3 remain unchanged.
All54 PRD groups remain open. No Starter port, owned emulator, goal or subagent.
Do not stop at this checkpoint.


## Bounded incoming waves and real Android catchup validated — 2 November 2026

[Contract](collaboration-incoming-history.md) / [evidence](measurements/chat-incoming-history-waves-2026-11-02.json):
incoming recovery now retains provider `from`/`to` boundaries, a completed wave head,
and a fair fixed-high-water pending-reference pass. Browse retains its exact old
partition/AAD/schema. Incoming schema2 canonically encodes opaque ASCII positions
inside AEAD, preserving the existing32,768-character database checkpoint cap. Original
facts remain shared, and seek advancement never acknowledges unresolved plaintext.
Room12s/lane15s budgets, owned/captured access and joined resource cleanup remain.

Focused5 passes584 domain,346 data and94 selected app cases plus Android release/
proof Kotlin/static checks120.551s on47 inputs. Full7 passes425s Gradle /428.941s wall
on51 inputs:3,169 desktop472 suites,217 Android host35,908 collaboration browser92,
6 proof host1,10 build-logic2; zero failures/errors/skips. Earlier failures retain two
compiler fixes, worst-case JSON checkpoint overflow/canonical encoding correction,
two renamed fixture references and one proof-method detekt extraction. Subsequent
changes are confined to three proof files: APK/host/static8 passes96.152s on52 inputs,
6 host cases. Production inputs remain those accepted by full7; no second root run
on altered proof-only code is claimed.

Real SDK/HRIS/PostgreSQL/Synapse native runs2+3 pass all3 variants on the SAME APK
cb5830040a023fbf4a2ab040800f120521b0286da1d4949f3448966d7b525e50 (343,275,325 bytes),
with52 Mobile and7 Platform inputs unchanged. CANCELLED_DRAFT passes394.103s;
QUEUED/FROZEN_DRAFT pass671.255s together. Each recovers175 peer originals plus the
prior outgoing original, exhausts186 references in11 turns and survives11 container
reopens. Outbox empty, no workspace selection, fresh native admission, Browse still
untouched and runtime released are asserted each turn. Foreground ViewModel completes
11 independent Browse pages;10 state references remain. Server verifies all ciphertext
and exactly one outgoing worker send. Cleanup succeeds for every final case.

Native1 preserves3/5 failures in716.614s: SDK death/actual OS scheduling regressions
pass; cancelled/queued expanded cases hit old210s instrumentation timeout; frozen
fails before installation on an empty AVD reply/IndexError. New bounded phase/turn
counters show successful complete fixture times245.182–260.457s, including175 queue
pause checks and container reopen work. Worker-resume fixture allows420s inside480s
Python/10min JUnit bounds; production deadlines unchanged. Empty AVD replies now fail
as controlled RuntimeError before device/artifact mutation, with no fallback/retry.
84 driver cases pass0.363s. Owned191463 unexpectedly exited; cause not established
and no kernel termination record found. Later owned225320 and240903 stopped1.711s
and1.355s. No emulator remains owned. Unrelated5560/phone15 untouched.

Apple41421351125 at cacae157a83c0653428156fc47e53e97bbe1db9d is COMPLETE/audited:
112 core cases30 suites,6 wake cases0.070s, compile452s/tests82s/Xcode861s/associations
0s;16 source hashes verified. It excludes these new waves. Dispatch the next exact
committed head after push. Core CI excludes app desktop cases; physical iPhone waived.
Platform companion proof/evidence is in the same-date measurement file in that repo.
Publish both atomic increments before continuing. No generic Starter port is due.

**Continue immediately:** FCM receive routing, bounded authenticated priority hints
plus fair full sweeps, notification privacy/read/mute/actions and durable encrypted
replies; compatible private rollout and release16+ installation with notice and
`--install-only`. Android currently sends every data-only push to the inbox parser;
iOS also extracts only inbox fields. New receiver must record a fresh domain recovery
wake before scheduling, preserving an in-flight sweep's generation fencing. No packet
company/room is authority. Local receive ownership must not depend on credential age.
`.work/chat-push-receiver-2026-11-02` has unexecuted .txt candidates/test notes only.
Fixed all-company wake alone is not latency/scale or notification acceptance.

All54 PRD groups remain open. Last verified private schema103/API-worker2c2a59e3,
branch116, disabled chat push and phone15 remain. No rollout/phone operation here.
No active goal or subagents. Do not stop after this checkpoint.

## Incoming traversal isolation and concurrent key creation validated — 2 November 2026

[Contract](collaboration-incoming-history.md) / [evidence](measurements/chat-incoming-history-purpose-2026-11-02.json)
add explicit Browse/Incoming purposes to provider acquisition and encrypted traversal.
Browse retains its exact partition/AAD/schema; Incoming has separate authenticated
payload/index context and seek/reference state. Original content remains shared.
Existing captured reference operations carry the key unchanged. No worker catchup
caller is added yet. Four domain, four cross-purpose storage and eleven inherited
incoming storage cases cover admission, cancellation, legacy bytes, transplant
rejection, interleaved restart/resolution, independent writes and all existing
CAS/key-loss/account/reopen/bounded-query behaviors on SQLite and IndexedDB.

The first full check failed one existing concurrent company-progress case among336
data cases in244.747s; all15 new storage cases passed. That run did not reach new
domain/Android/browser tasks (retained567 domain XML is not new execution evidence).
Inspection found the shared history-key guard can observe no key, then a different
repository's newly committed ciphertext. A deterministic barrier test reproduces
Failed instead of CAS loser Success(null) before the correction (39.46s).
The original random failure did not retain its technical cause; the new scenario
explicitly establishes the guard race. The guard now rereads a retained key once;
missing/malformed credentials still fail without creating keys over old records.
Final full root/Android release/affected browser passes333s /336.845s with16
unchanged Kotlin inputs:3,144 desktop468 suites,216 host35,886 browser89 and10
build-logic2; zero failures/errors/skips. Both failed attempts remain in evidence.

Apple [41414832120](https://github.com/fajarxfce/hris-mobile/actions/runs/41414832120)
at3062bff25c678639204bcf4a5e2e95be85c155d4 is COMPLETE and audited:112 core cases/30
suites, six wake cases0.124s, compile610s/tests123s/Xcode1323s/associations1s,12 source
hashes verified. It excludes later ee888802 and this isolation/key change; core tests
do not execute app worker desktop cases. Dispatch the next exact committed head now.

Platform [04b0b6c8](https://github.com/fajarxfce/hris-platform/commit/04b0b6c8) is pushed:
actual incoming provider/empty-outbox/no-selection proof and all81 driver cases.
A NEW UNCOMMITTED MatrixRoomHistoryInteropTest extension is running locally in
`.work/incoming-history-boundary-2026-11-02` (one heavy local check at a time).
The gateway already supports encrypted `from` AND `to` positions, bound to company/
account/device/server/room. Prefer these provider boundaries over overlap heuristics.
The new test attempts47 additional metadata events, five more during partial paging,
adapter recreation, exact wave separation and an empty caught-up wave. It is not yet
accepted. It uses synthetic encrypted payloads, not SDK plaintext/E2EE acceptance.

**Continue immediately:** verify that provider boundary test, add optional untilPosition
through Mobile's API/runtime/page contracts, and persist each incoming wave's first
start, previous completed boundary, next seek and fair pending-reference cursor.
Do not reset unfinished scans or Browse on each wake. Original/tombstone reconciliation
remains distinct; state-only/missing-key references cannot starve later work. Native
SDK caps16 retained focuses per client, so respect foreground/borrowed ownership when
recovering capacity. Then prove an actual incoming gap beyond128, FCM privacy/actions/
encrypted replies and compatible private server rollout. Do not stop after this push.
All54 groups remain open; phone15, private schema103/API-worker2c2a59e3, branch116 and
chat push disabled remain. No Starter port is due for these HRIS-specific changes.
No emulator or user phone operation by this increment; next employee release16+.

## Actual incoming SDK/provider proof passed; generic Matrix retry corrected — 2 November 2026

[Evidence](measurements/chat-incoming-provider-2026-11-02.json) records five real
Android/HRIS/PostgreSQL/Synapse scenarios in384.631s, zero failures/errors/skips,
plus Platform format/architecture. In QUEUED/FROZEN_DRAFT/CANCELLED_DRAFT cases the
sender runtime closes before32 encrypted peer seeds. Fresh real native directory,
admission and one summary page then drive the actual incoming worker with an empty
outbox and a workspace repository that rejects selection reads/observers. Each takes
one pass;12 originals (including the prior outgoing original) remain after container
reopen, and the final borrowed runtime is released. Foreground paging then verifies
all32 peer originals plus the outgoing original. SDK process-death and actual scheduled
offline/ordinary-death regressions also pass. Incoming itself is explicitly invoked;
this is not incoming FCM/Doze/terminated-app or all-company provider acceptance.

Actual first native run failed4/5 in356.106s. Raw held-room join received503 provider_busy;
fixture now bounds idempotent M_UNKNOWN retries to3 with1s gaps. Three incoming cases
reached foreground, then Unavailable/Unclassified: inspection found fixture did not
republish foreground retention after reopening a background-only host. Activate now
publishes that seeded decision; a validated incoming checkpoint is retained separately.
First diagnostics lack the exact failure code/final incoming metrics; do not invent them.
Production mapper now classifies Matrix M_UNKNOWN as Service/retryable so transient
server failures cannot masquerade as user-action conditions. Explicit denials and
unrecognized payload sanitization remain. One new shared mapper case passes.

Initial root/proof check1 passed75.3s on4 proof inputs. Final root/proof/Android release/
affected data browser check2 passes297s /301.21s on7 inputs:3,124 desktop465 suites,
216 Android host35,6 proof host1,299 data browser39,10 build-logic2; zero failures/
errors/skips. Platform81 Python driver tests pass0.167s;4 native inputs frozen.
Exact logs/reports/source hashes and APK5f17598f (343,275,325 bytes) bind both runs.
All native driver cleanup succeeds. Owned5562/PID99409 stopped1.003s; prior79619
stopped1.404s. Unrelated5560 and phone15 untouched. No generic Starter port is due.

[Apple41414832120](https://github.com/fajarxfce/hris-mobile/actions/runs/41414832120)
is ACTIVE at3062bff25c678639204bcf4a5e2e95be85c155d4: simulator tests passed, Xcode
building. It covers the incoming app coordinator but excludes these later proof/mapper
changes. Do not dispatch a cancelling run. Audit XML/source/full Xcode on completion,
then dispatch the next implementation head. Core Apple tests exclude app desktop cases.

**Continue immediately:** independent durable recent-history traversal/frontier and
pending-reference progress, then FCM privacy/actions/encrypted replies and compatible
backend rollout. Latest window alone is not a long-offline catchup. Do not reset the
foreground Older traversal on every wake. Existing Read/ReconcileHistoryReferences
accept captured scope; native readProviderHistory pages20 and original windows128.
Review separate traversal purpose/partition/AAD with legacy Browse unchanged, durable
scan bounds/overlap and pending-reference fairness before wiring incoming. Preserve
missing-key content and distinguish metadata coverage from decrypted originals/receipts.
A prototype discussion is local only, not implementation. All54 PRD groups remain open.
Phone15/private schema103/API-worker2c2a59e3/branch116/push disabled remain; next employee
release16+ requires compatible server rollout and validated feature behavior. Continue.

## Incoming worker and fair company turns validated — 2 November 2026

[Contract](collaboration-incoming-recovery.md) / [evidence](measurements/chat-incoming-worker-2026-11-02.json):
actual app binding now runs incoming before outgoing with separate15-second lanes
and the same original admission. Root quantum is one company,50s including admission,
within90s processing/180s wait+processing. Incoming checks fresh setup, seeks one room,
persists Attempt before external work, acquires an owned latest original window with
no outbox/selection, and closes timelines/runtime/monitors on completion or cancellation.
Room retry/continuation survives restart; provisioning never creates rooms implicitly.

Fourteen actual DI/domain/SQLite app cases plus seven virtual-time coordinator cases
cover no-outbox restart, both lanes together, failure fairness, empty/provisioning,
readmission, contention, deadlines, cancellation, late runtime/account/denial and joined
cleanup. HTTP/SDK are fictional here. Focused2 passes567 domain,320 data,91 app plus
Android release Kotlin/proof APK/static97s /101.311s on12 unchanged Kotlin inputs.
Focused1 failed two incorrect null-position assertions; three assertions now expect
the intentionally retained empty generation-bearing position. Production unchanged.

Actual WorkManager/domain/Room/Keystore regression passes all four scenarios46.417s:
burst3.502s,stage0.876s,resume31.545s,backlog1.700s. New one-company quantum covers
21 healthy callbacks in21 OS completions with zero Retry; uncertainty still backs off.
Twelve changed inputs plus three unchanged committed proof sources and APKd026d7ff
are bound in evidence. SDK/directory/admission/company callbacks remain synthetic.
Proof process and owned5562/PID65117 stopped (1.404s); phone15/unrelated5560 untouched.
Full3 root, Android/proof, affected browser/app-Wasm passes184s /188.013s:
3,123 desktop,215 host,865 browser,10 build-logic; zero failures/errors/skips.

Apple41409790492 at5a39b53f is complete/audited:112 core cases/30 suites, six wake
cases0.083s, compile672s/tests74s/Xcode1269s/associations1s. Ten exact source hashes
and XML verified. It excludes later room af5cb4dc and this incoming increment; dispatch
new exact-head Apple after push, without cancelling an active run. Core Apple does
not execute these app desktop cases. Physical iPhone testing remains waived.

**Continue immediately:** prove incoming via actual SDK/HRIS/Synapse independently of
outbox/selected workspace, then independent durable recent catchup, opaque FCM hints,
notification privacy/actions and encrypted replies. Current latest window<=128 does
NOT cover a long offline gap and must not reset foreground Older traversal. Existing
HrisWorkerMatrixProbe seeds32 encrypted peer events before foreground acquisition;
close the sender app/runtime before seeding and prove captured incoming restoration,
local history across reopen, and original identity before foreground paging. Use fresh
real recovery directory/admission, not fabricated content grants. Keep seeded login and
provider binding limitations explicit. Current Platform drivers are being updated to
accept HRIS_COLLAB_NATIVE_SERIAL=emulator-5562 while checking the exact HRIS AVD.
Never use unrelated5560. Preserve all54 PRD groups. Backend private103/branch116 and
chat push disabled remain; compatible rollout required before employee release.
No generic Starter port is due. Continue after every commit/push.

## Room recovery and captured reference scope validated — 2 November 2026

[Contract](collaboration-room-recovery.md) / [evidence](measurements/chat-room-recovery-2026-11-02.json):
account/company-owned room progress now retains seek, unfinished attempt and independent
retry/continuation flags. Attempt persists before external work; Complete preserves
prior outcomes; validated-directory Finish atomically resets and returns Covered,
Continue or Retry. Fresh generations fence competing writers and repeated positions.
Protected history keys, bounded authenticated payloads and account fences are shared
with existing storage. Age/reauthentication retain facts; known denial, cancellation
and late account replacement cannot publish false coverage. No incoming caller yet.

Read/ReconcileCollaborationHistoryReferences now accept Captured local scope through
their existing implementation. String overloads still delegate to Selected. All raw
reference/fact/resolution calls carry the captured account/company, even with no or a
different selected workspace. Provider browse position is unchanged. Exact original/
tombstone matching, bounded metadata, denial priority, late-write facts and subscription
cleanup remain. Already-cancelled entry stops before admission/reference storage.

Room-only focused1 passes559 domain,320 data,two actual DI cases plus Android release/
proof Kotlin and static checks154s /157.623s on13 unchanged inputs. Then scope support
added two use-case overloads, fixture access tracking and eight regressions. Final17-
input full2 root, affected browser, Android release/proof Kotlin and app-Wasm passes
371s /374.686s:3,102 desktop,215 Android host,865 affected browser,10 build-logic;
zero failures/errors/skips. New cases:12 room domain,9 shared storage,8 captured scope.
Both report/source boundaries are recorded; no failed check occurred in this increment.
SQLite/IndexedDB are real; no new WorkManager/SDK/HRIS/FCM proof is implied here.

**Continue immediately:** app-owned incoming coordinator using durable room progress,
existing Background LoadSetup/RefreshList/AcquireRuntime/Enter/AcquireHistory and
monitorCollaborationBackgroundDelivery. It must acquire incoming content even when
there is no outbox, release each timeline/runtime, preserve the native-parent monitor,
never switch workspace, and keep outgoing and incoming finite/fair. Current outer
worker includes admission in its30s company budget and pages two companies in90s;
do not give two independent30s lanes that starve the second. Review/test an explicit
combined budget or one-company quantum before wiring both lanes. Existing synthetic
native proof and app worker tests must be updated and rerun if those limits change.

RefreshList server pagination is stable UUID ascending. Persist Attempt before each
room operation so one failure/interruption cannot permanently exclude later rooms.
A latest original window is bounded128 events and is NOT full long-offline recovery.
Incoming recent catchup needs its own durable provider traversal; do not repeatedly
reset the foreground Older cursor with Latest. Existing AcquireProviderHistory uses
that shared browse cursor. Opaque FCM priority hints may reduce scanning latency but
never grant content access; collapse recovery still must cover all admitted companies.
Then actual provider/captured incoming, FCM/privacy/actions/encrypted replies, compatible
server rollout and employee release. Preserve all remaining P0-P8/54 groups.

Apple41409790492 is active at5a39b53f0ece41d314738090e330bd7004e528db; do not cancel it
with another dispatch. It excludes this room increment. Prior41405822268 is audited
112 core cases/full Xcode at338fea17. No new employee install: phone15 remains;
private Platform schema103/API-worker2c2a59e3 and disabled chat push remain. Branch116
is not live. Owned5562/proof process stopped; preserve unrelated5560. No generic
Starter port is due for this HRIS policy. Old seven .txt candidates are obsolete;
source is authoritative. Continue after each push while hosted checks execute.

## Healthy company backlog fixed and proved — 2 November 2026

[Evidence](measurements/chat-company-continuation-2026-11-02.json): successful bounded
company batches now persist continuation separately from failure/uncertain attempts.
At directory end, durable restart precedes same-wake scheduling; healthy backlog
avoids OS failure backoff, while real failures still require it. Payload schema2
reads schema1 without losing its wake/seek/failure; unsupported schemas cannot clear
progress. Two new domain, three shared storage and four app cases cover the change.

Focused2 passes547 domain,311 data,22 worker/DI plus Android release Kotlin/proof APK
and static checks in137s /140.844s. Focused1 had two invalid raw migration fixtures
that reused a generation; corrected fixture uses fresh IDs and matching AAD, with
production unchanged. Native1 passed burst/stage but failed resumed callback order.
Inspection found fictional admission used two clock reads and could exceed60s;
that invalid fixture was corrected to one instant, with bounded diagnostics added.
The failed run did not retain skipped callback IDs, so do not invent them.

Final native build3/static passes68s /71.904s. Native2 passes all four scenarios in
37.916s: burst2.238s,stage0.830s,resume31.340s,healthy backlog1.593s. Three healthy
batches cover all seven companies in order (21 callbacks/12 task completions) with
zero Retry results; interrupted work retains its real backoff. Actual WorkManager,
consumer/domain/Room/Keystore are used with synthetic directory/admission/company
callbacks. No SDK/provider/FCM acceptance. Proof process and owned emulator5562/PID15507
are stopped; phone15/employee package and unrelated5560 untouched.

Final full4 root/affected browser/app-Wasm passes292s /295.868s with10 unchanged
inputs:3,073 desktop,215 Android host,836 affected browser,10 build-logic; zero
failures/errors/skips. Exact reports/source/APK hashes and failed attempts are
retained. No new employee APK/live server activation is implied. HRIS policy stays
in Mobile; no generic Starter port is due.

Apple41405822268 at338fea17b5738698f45c824cd1d6a06142050946 is NOW COMPLETE/AUDITED:
112 cases/30 suites, six wake cases0.122s, compile608s/tests67s/Xcode1303s/associations1s.
Fourteen source hashes and all XML match. Worker desktop tests are not in its core
simulator suite; it excludes this later continuation correction. No hosted run is
active now; dispatch a new exact implementation head after pushing this increment.

**Continue immediately:** durable per-company room recovery, then incoming acquisition
independent of outgoing backlog, actual provider and opaque FCM/notification/reply.
Seven UNAPPLIED candidates exist under `.work/incoming-recovery-2026-11-02/`:
entities/policy/contract/read/update/codec/repository.txt. They are a draft, not tested.
Review, add domain/storage/DI tests and apply only after this increment is committed.
The proposed row uses account/company ownership, generation CAS, saved room seek,
in-flight attempt and independent retry/continuation flags. Attempt precedes external
work; Complete preserves flags; Finish requires validated directory end and resets
only after a durable CAS. Ordinary expiry/reauth retain local facts, known denial and
replacement account gate access. Do not reuse/reset foreground Older history position
for every background wake; long offline gaps need independent catchup progress.
Read/ReconcileCollaborationHistoryReferences still lack Captured local overloads.
Current callbacks still invoke outgoing delivery only. Backend schema116 remains
undeployed (private103); retain disabled chat push through actual receive acceptance.
All54 PRD groups remain open. Continue after every push, including while CI executes.

## Native all-company consumer proof passed — 2 November 2026

[Evidence](measurements/android-company-recovery-2026-11-02.json) binds17 unchanged
source/test inputs and proof APK26f8c50c. Build3 APK/static passes67s /70.996s.
Actual WorkManager/worker/domain/Room/Keystore scenarios pass35.839s: main1.905s,
stage0.862s, resume31.338s. Forty rejected schedules retain the newest wake; max2
OS tasks and1 company processor; two complete company sweeps ACK their own wakes
without healthy company-page retries. After process death, later companies advance
before an uncertain first attempt is retried on the original wake. All proof jobs,
observers, database/key owners, proof process and owned emulator5562 are stopped.
Phone/employee package and unrelated emulator5560 were untouched.

Preserved failures: build1 Detekt LongMethod, fixed in fixture only; native1 asserted
successful ACK before worker completion while pending=false was already persisted.
Build3 moves that assertion after quiescence and adds bounded diagnostics. Production
consumer338fea17 is unchanged. Synthetic directory/admission/company callbacks do
not establish SDK, HRIS or FCM acceptance. Scratch .txt candidates are obsolete.

**Continue now:** distinguish healthy per-company `SyncResult.Retry(null)` backlog
from failures/uncertain attempts in durable company progress. Current consumer adds
backoff to both, although SyncResult null explicitly means successful bounded work.
Use a separate continuation flag, preserve real failure priority across pages and
restarts, test storage migration and actual WorkManager healthy multiple batches.
Then implement independent incoming room discovery/acquisition with durable fair
room progress, FCM, privacy/actions and encrypted replies. Do not stop at this proof.

[Apple CI41405822268](https://github.com/fajarxfce/hris-mobile/actions/runs/41405822268)
at338fea17b5738698f45c824cd1d6a06142050946 remains active: simulator tests passed,
Xcode app building. Do not dispatch a cancelling same-branch run. Audit downloaded
XML/source hashes and complete Xcode; hosted core tests do not run worker desktop
cases. Phone15/private backend/push-disabled state unchanged; all54 PRD groups open.
Starter5679c51 generic port complete; this HRIS fixture requires no Starter port.

## All-company consumer registered and locally validated — 2 November 2026

[Contract](collaboration-company-worker.md) / [evidence](measurements/chat-company-worker-2026-11-02.json):
the fresh recovery key now uses bounded Android scheduling and a singleton app
worker. One processor/one resource-free waiter capture current processed-parent
ownership; parent replacement cancels/joins old work, while selected-company changes
do not. Startup persists a wake for a changed native parent; same-parent renewal
preserves it. Periodic execution can create an absent hint without recursive enqueue.
Each bounded page covers at most two companies with durable Attempt/Complete/Finish,
fresh admission and exact old/new wake acknowledgment. Healthy pagination schedules
same-generation continuation instead of deliberately increasing platform backoff.

The current company callback is the existing captured outgoing delivery worker.
It still exits with no outgoing work. Independent incoming discovery/acquisition,
fair room progress, FCM, notification privacy/actions and encrypted replies remain.
No backend activation, employee APK or provider acceptance is implied by registration.

Focused validation passes545 domain and36 application cases plus release/proof
Android Kotlin/static checks in148s /152.151s on13 unchanged inputs. Full2 found
one old startup-test expectation among278 app cases (172s /176.151s): it awaited
any one request and expected only selected delivery. The corrected test awaits that
selected key, checks exactly recovery plus delivery keys, verifies a durable wake
and zero SDK deliveries/background acquisitions. Production source did not change.
Full3 root/affected browser/app-Wasm now passes in305s /309.378s on14 unchanged
inputs:3,064 desktop,215 Android host,831 browser and10 build-logic, zero failures,
errors or skips. New coverage includes16 worker,one lifetime and three domain cases.
Failed and successful report/source hashes remain in the measurement.

**Continue immediately with native proof.** Two UNAPPLIED fixture candidates are in
`.work/company-worker-native-2026-11-02/CompanyRecoveryProofOwner.txt` and
`CompanyRecoveryProbe.txt`. They use the actual app worker, domain operations,
AndroidWorkScheduler/WorkManager, encrypted Room progress and Android Keystore with
fictional directory/admission/company callbacks. Add three instrumentation branches:
company-recovery, company-recovery-stage and company-recovery-resume. Build the proof
APK/static checks, freeze source/artifact hashes, then run on a verified owned
emulator only. Main checks two OS tasks/one company processor,40 failed enqueues
retaining newer wakes, healthy continuation and exact-generation completion. Stage
persists an unfinished first-company attempt; force-stop before Resume, then verify
later companies advance before retry and the original wake is acknowledged. This
is consumer/OS/storage proof, not SDK/HRIS/FCM acceptance. Review candidates before
applying; their cleanup closes admission before draining actual workers.

Next run actual SDK/captured delivery and incoming receive independently, then
notification/reply work. Use current directory/admission; do not change the selected
workspace. Physical iPhone was waived, implementation/hosted Apple checks were not.
No new Apple run is dispatched for this worker yet. Starter5679c51 generic port is
complete (92 Apple cases/26 suites at136d2e5a). HRIS policy stays in Mobile.

Phone remains15. Private schema103/API-worker2c2a59e3 remain, push disabled, all54
PRD groups open. Owned5562 remains stopped;5560 is unrelated CodePush. Recheck all
emulator ownership before reuse. Temporary8GiB swap was safely disabled/removed
in47.997s after local builds finished; original /swap/swapfile was preserved.
Continue after each push; do not stop at this checkpoint.

## Account-owned company recovery progress validated — 2 November 2026

[Contract](collaboration-company-recovery.md) / [evidence](measurements/chat-company-recovery-2026-11-02.json):
account-scoped encrypted seek/in-flight/retry state now survives restart and same-
account reauthentication. New wakes do not reset an active finite sweep. Attempt
persists before external work; uncertain attempts and transient failures require
another sweep. A clean Finish stores inactive progress before returning only the
original wake for separate exact-generation acknowledgment. Late account changes,
competing writes, tampering and lost protected keys cannot publish a false finish.

The first focused run failed test compilation (IdentityServer fixture omitted
required deviceName, 40s /43.528s); no production correction was needed. Corrected
focused checks pass 542 domain,308 data,two assembled-DI cases plus Android proof
compilation/static in152s /155.704s. Full root/affected browser/app-Wasm checks
pass in296s /299.533s on all13 unchanged source/test inputs:3,044 desktop,215
Android host,828 browser and10 build-logic, zero failures/errors/skips. Twelve new
domain and eight storage cases cover SQLite/IndexedDB reopen and cancellation.
No all-company consumer or FCM entry caller is wired by this increment.

**Continue immediately:** implement the app-owned company worker and its actual
entry. Use processed parent state, current directory/admission and captured work;
never change selected workspace. RefreshCollaborationList already accepts Background.
The old DeliveryWorker exits when no outgoing work exists, so independent incoming
room discovery/history acquisition is still necessary before notification acceptance.
Keep fair room progress and actual provider proof separate from company coverage.

Two OS jobs can run. Admit at most one processor and one resource-free waiter;
capture authority after acquiring the processing mutex. Track waiting separately
from admitted count to avoid mistaking an exiting task for future work. Cancel/join
on parent replacement; workspace changes do not replace a native parent. Healthy
pagination should schedule a same-generation continuation and complete, rather than
building exponential backoff. Waiting/backoff/finishing races require tests and
actual Android proof. Current supplementary design is in
`.work/company-worker-2026-11-02/design.txt`; no next-increment source is applied yet.
Do not revive the old no-wait consumer proposal from historical scratch notes.

Starter [5679c51](https://github.com/fajarxfce/compose-fluent-starter/commit/5679c51)
records independent Apple41400133440 at exact136d2e5a:92 cases/26 suites, six wake
cases0.178s, full Xcode586s, zero failures/errors/skips. That port is complete;
this HRIS policy stays in Mobile. No new employee APK/server rollout: phone15,
private schema103/API-worker2c2a59e3, push disabled, all54 PRD groups open.
Owned5562 remains stopped;5560 is unrelated CodePush. Temporary8GiB swap remains
active pending safe headroom. Continue after each commit/push.

## Starter port pushed and hosted validation running — 2 November 2026

Mobile e00ab4ee is pushed. Starter [136d2e5a](https://github.com/fajarxfce/compose-fluent-starter/commit/136d2e5a607fb20b6f5ccd07bd88ed14b0a6b668)
now contains three identical generic scheduler/test files and the previously
Mobile-proven Apple wake fixture. Independent checks pass356 desktop,13 worker-
browser,six build-logic,Android/lint/Web in148s /152.12s. Its [Apple CI41400133440](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/41400133440)
is in progress at exact 136d2e5a607fb20b6f5ccd07bd88ed14b0a6b668; do not cancel it with another dispatch. Audit
six AppleWorkWakeStoreTest cases, all downloaded XML and Xcode before accepting.
[Port ledger](template-updates.md) and measurements retain exact boundaries.

Continue implementing HRIS account-owned company recovery progress and the actual
all-company consumer. No new source for this next increment is applied yet. Native
parent publication, directory/admission, captured delivery, raw wakes and bounded
Android scheduling are ready. RefreshCollaborationList already supports Background
scope; do not duplicate it. Cursor state must survive restart without a synthetic
company/session, preserve account boundaries and prevent a failing company starving
others. Advance/attempt/finish crash gaps must not falsely acknowledge all recovery.
Use encrypted account-scoped metadata under the protected history key namespace;
start new finite sweeps without resetting progress on every incoming wake.

Keep scheduler limits explicit: two OS jobs can run, so consumer admission owns
processing. Reusing an ENQUEUED backoff request proves eventual scheduling, not
immediate high-priority FCM latency; that integration/measurement remains separate.
Registered HRIS recovery task, real receive/decrypt, notification privacy/read/mute/
deep links and encrypted reply still remain. No backend activation or employee APK
update: phone15, all54 PRD groups open. Owned5562 stopped;5560 is unrelated CodePush.
Continue after each push and while hosted CI executes.

## Bounded Android wake scheduling proved — 2 November 2026

[Contract](bounded-work-scheduling.md) / [evidence](measurements/android-collapsed-work-2026-11-02.json):
opted-in fresh task keys use two independently named slots. A query after the
caller commits a durable wake reuses a waiting slot or fills a vacant slot with
KEEP; running slots are not reused. Ordinary keys retain APPEND_OR_REPLACE. Keys
and admitted callers are bounded to32; two running slots reject another request.
Application processing must handle two OS jobs without duplicate native ownership.
No employee task opts in yet; no all-company consumer/FCM activation is claimed.

Eight new common cases pass:13 worker desktop/13 browser in152s /155.762s with
proof Android compilation/static. Full2 passes3,024 desktop,215 Android host,13
worker-browser and10 build-logic in263s /267.084s on five unchanged inputs. A later
fixture-only review correction makes cleanup bounded and noncancellable, tracking
initial reads too; proof APK/static checks pass66s /70.22s. All three production/
shared test inputs remain identical to the full run. Actual Android run1 then
passes all three scenarios in22.458s on five unchanged final inputs/artifact:
40 competing requests (four accepted),20 backoff requests reuse one pending ID,
failed/cancelled slots recover, the other running slot survives, old completions
cannot erase later generations and a real process stop retains a recoverable wake.
SQLite input/network/backoff/no-dependency checks and cleanup pass. Synthetic proof
APK fd6dccc8 is343,275,325 bytes; it is not an employee APK or FCM acceptance.

**Continue immediately:** push this commit, then port the three generic core/worker
files plus the separately Mobile-proven AppleWorkWakeStoreTest wrapper to Starter.
AndroidWorkScheduler baseline matches exactly. Preserve Starter demo/configuration;
HRIS policy and the debug proof application remain in Mobile. Run independent root/
worker-browser/Android/lint/production Web checks, record exact source/report hashes,
push, then dispatch one Starter Apple-only CI and audit its downloaded XML/Xcode.
Continue HRIS consumer implementation while that hosted run executes.

Mobile [Apple run41396150119](https://github.com/fajarxfce/hris-mobile/actions/runs/41396150119)
at be8ebc7b is independently audited:112 cases/30 suites,zero failures/errors/skips,
all six AppleWorkWakeStoreTest cases in0.117s, compile584s/tests84s/Xcode1166s and
associations1s. [Storage evidence](measurements/work-wake-storage-2026-11-02.json).
This earlier source does not include current HRIS policy or generic slot scheduler.

Next actual work: native parent publication -> registered all-company consumer,
fair company/room cursors, incoming discovery/SDK sync independent of local outgoing
messages, then notifications/privacy/deep links/encrypted replies. Use current
native directory and fresh per-company admissions; no FCM data can select authority.
RefreshCollaborationList already accepts operation scope; trace Background support.
The old tag/chain proposal was rejected BEFORE implementation because WorkManager
can delete whole WorkSpecs and tags; do not revive it from historical notes.

Owned emulator5562/PID4105492 stopped; absence verified. Port5560 is now unrelated
CodePush AVDcodepush-ci08/PID4082879, preserved. Recheck ownership before any reuse.
Phone remains15 from30d7faab; private server unchanged/push disabled. All54 PRD
groups remain open. Temporary swap still active pending safe headroom. Continue
past every commit/push; local `.work/collapsed-scheduling-2026-11-02` is supplementary.

## HRIS durable recovery request policy validated — 2 November 2026

[Contract](collaboration-recovery-wakes.md) / [evidence](measurements/chat-recovery-wake-2026-11-02.json):
fixed-key wake mapping, fresh UUID generations, capacity32 and Request/Resume/Read/
Complete operations now preserve persisted hints through scheduling failure,
timeout, cancellation and competing completion. Ten domain/six data cases plus
assembled application DI/SQLite checks pass. Focused checks pass in154s /158.169s
(530 domain,300 data,two DI); full root/affected browser/app-Wasm passes in316s /
319.907s on12 unchanged inputs: 3016 desktop,215 Android host,
808 affected browser and10 build-logic cases, zero failures/errors/skips.
These use cases have NO registered all-company consumer/FCM entry caller yet.

**Continue immediately:** implement bounded Android scheduling. Three source/test
candidates and eight common concurrency/cancellation cases are prepared UNAPPLIED
under `.work/collapsed-scheduling-2026-11-02/candidate.json`; design.txt is corrected.
Earlier tag/APPEND_OR_REPLACE reasoning was insufficient: WorkManager deletes whole
WorkSpecs on failed/cancelled leaves, cascading tag deletion. Do not use that design.
Use two independently named one-time slots per fresh opted-in key. Query unfinished
states for both names after the durable write. Reuse a waiting slot; otherwise fill
a vacant slot with a fresh-ID request and KEEP for that independent name. A running
slot is never reused; two occupied running slots fail and leave durable recovery.
Existing ordinary keys retain APPEND_OR_REPLACE. Bound admitted callers/keys to32.
Actual Android proof must cover two simultaneous jobs, competing scheduler instances,
backoff, slot cancellation/failure, cancellation/overflow and process-death repair
before accepting or porting this generic scheduler. No HRIS activation yet.

Starter dab675a is pushed with15 identical raw database/test/schema files; independent
301s /305.231s root/browser/Android/lint/Web proof passes348 desktop,42 browser,six
build-logic,zero failures/skips. Apple wrapper still awaits Mobile hosted proof.
[CI41396150119](https://github.com/fajarxfce/hris-mobile/actions/runs/41396150119)
at exact be8ebc7b has completed compile/tests and is building Xcode at this observation.
Audit downloaded XML (six AppleWorkWakeStoreTest cases) and final steps before acceptance.
Do not dispatch another same-branch Apple run until done. Then port its isolated
wrapper to Starter and run independent Apple CI. This older run is not proof of
this later HRIS recovery policy. Continue local implementation meanwhile.

Then connect parent publication, fair native-directory/room cursors and actual
incoming acquisition independent of local outgoing messages. RefreshCollaborationList
already accepts operation scope; trace Background support before duplicating it.
Receive/decrypt/notification/privacy/deep links/encrypted replies remain. Phone
stays verified15 from30d7faab, server unchanged, push disabled, all54 PRD groups open.
Owned5560 remains stopped; preserve unrelated jobs/5558. Continue after every push.

## Durable raw work wakes validated — 2 November 2026

[Contract](work-wake-storage.md) / [evidence](measurements/work-wake-storage-2026-11-02.json):
AppDatabase now exposes bounded application-global key/generation/pending metadata.
It contains no account/company identity, credential or message. Requests collapse
per stable task key; same-current-generation replay retains existing acknowledgment.
An old completion cannot erase a newer generation. Transactional key capacity allows
existing keys to advance at capacity, with no implicit pruning of completed keys.
Room11→12 and IndexedDB10→11 upgrades preserve prior commands and inbox records.

The first63s /66.872s run found one real cancellation bug among44 SQLite cases:
Room committed an already-cancelled call before propagating cancellation. Explicit
checks now precede DAO operations; the common regression also covers acknowledgement.
Once I/O starts, a durable commit can still race cancellation and must be recovered.
Corrected focused checks pass in321s /325.382s:44 SQLite,42 browser and two actual
DI cases, proof Kotlin compilation and static checks. Full root/database-browser/
app-Wasm checks pass in298s /302.234s on17 unchanged inputs:3,000 desktop,
209 Android host,42 database-browser and10 build-logic cases, zero failures/skips.
Mobile's application container binds WorkWakeStore. Six isolated Apple wrappers
are included but NOT executed on Linux; hosted acceptance remains separate.

**Continue immediately:** after pushing this commit, dispatch Mobile ci.yml with
scope=apple, capture the exact head SHA/run ID, and audit the downloaded XML,
including all six AppleWorkWakeStoreTest cases. Last prior Mobile CI41334974289
is completed, so no active branch CI was found during preparation. Avoid cancelling
unrelated work. Continue local implementation while hosted checks execute.

Port the15 shared raw source/test/schema files to Starter only after this Mobile
proof. All14 pre-existing/new baselines match there. Prepared supplementary Starter
`.work/durable-wake-2026-11-02/port.py` verifies clean committed/pushed Mobile source
and exact hashes; its check.py runs independent root/database-browser/Android dev/
lint/production Web checks. Exclude Mobile ApplicationStorageModule: Starter exposes
raw stores via AppDatabase and has different app composition. Exclude the new Apple
wrapper until its Mobile hosted proof, then port it and run Starter Apple CI. Keep
Starter demo/application configuration intact and record the reviewed port in Git.

Then connect actual HRIS recovery. This primitive still has NO wake repository/use
case, OS scheduler, all-company coordinator or FCM receiver. Use a fixed registered
task key, fresh non-reused generations and operation-specific capacity mapping;
persist before requesting execution. Consume native directory/current admission,
keep fair company/room cursors and sweep startup/reconnect/periodically, including
after account change. Incoming discovery must work without outgoing local messages.
Current one-time Android scheduler uses APPEND_OR_REPLACE. Preserve its finishing
race protection while bounding requests: inspect only unfinished jobs after durable
wake commit, and test concurrent pending/running transitions, retry/backoff,
overload and write/enqueue/ack process-death gaps on actual WorkManager. Duplicate
APPEND of a retained WorkRequest ID can form self-dependency; do not assume otherwise.

Supplementary continuation: `.work/durable-wake-2026-11-02/next.txt` and the parent
publication scratch design/upstream source. Phone remains verified15 from30d7faab;
no newer APK/install or server rollout occurred. Native receive/decrypt, notification
privacy/read/mute/deep links and encrypted replies remain open. Backend push stays
disabled, private server stays schema103/API-worker2c2a59e3, and all54 PRD groups
remain open. Owned5560 stopped; preserve unrelated5558/jobs. Temporary8GiB swap
remains active pending safe RAM headroom. Continue after each push.

## Processed parent publication validated — 2 November 2026

[Contract](collaboration-parent-readiness.md#processed-publication-and-application-lifetime) /
[evidence](measurements/chat-parent-publication-2026-11-02.json): the same exclusive
Maintain collector now publishes processed retention, ownership result and cleanup
result separately. The legacy entry maps that stream with unchanged denial priority.
Application state distinguishes Pending/Observed/Stopped; close invalidates it
immediately and terminal atomic updates prevent late publication. Source exception
or completion clears ownership without implicit restart. Parent-only observation
does not schedule selected-company work or claim native acquisition readiness.

Four new domain and six application cases pass within520 domain and49 actual
application collaboration tests, including assembled DI. Proof Kotlin compilation,
format/architecture/detekt pass in215s /218.858s. Full root, affected browser and app
Wasm compilation pass in298s /301.988s on six unchanged inputs:2,993 desktop,
209 Android host,792 browser and10 build-logic cases; zero failures/errors/skips.
No new JNI/APK/install for this increment; phone remains verified15 from30d7faab.

**Continue immediately:** durable collapsed wakes and all-company fair recovery.
A nine-file raw storage candidate is UNAPPLIED/UNTESTED and incomplete under
`.work/durable-wake-2026-11-02/candidate.json`. It adds application-global bounded
work-wake metadata without synthetic account/session scope, Room11→12 and
IndexedDB10→11 additions. Same-generation requests repeat unchanged; acknowledgment
checks the exact pending generation, preserving newer requests. Complete common
concurrency/capacity/cancellation contracts, native migration/reopen and browser
adapter/reopen validation before applying or accepting it. It contains no message,
account, credential or grant payload. Registered keys/generation creation and
scheduling remain caller policy. Wire it into actual recovery after validation.

Scheduling trace/design: current one-time AndroidWorkScheduler uses
APPEND_OR_REPLACE. Official WorkManager2.12 source shows duplicate APPEND of a
retained WorkRequest ID can create self-dependency; do not assume idempotency.
A candidate bounded strategy persists the wake before querying unfinished work,
reuses a pending ENQUEUED/BLOCKED task, and appends a successor when only RUNNING
work exists. Validate ordering, concurrent enqueue, process death, retry/backoff
and capacity with actual WorkManager before acceptance. Bound query/enqueue admission
and unfinished work; preserve the existing finishing-worker race fix. Notes and
read upstream sources are supplementary under `.work/parent-publication-2026-11-02`.

Then use fresh native-directory/admission with fair company/room cursors, periodic/
reconnect sweeps, real receive/decrypt, notification privacy/read/mute/deep links
and encrypted inline replies. Native incoming receive may need captured list
refresh beyond the existing outgoing/draft worker. Backend push stays disabled;
all54 PRD groups remain open. No live rollout or generic Starter port yet. Proven
generic wake foundations should return to Starter after Mobile validation. Owned
emulator5560 is stopped; preserve unrelated5558/jobs. Temporary8GiB swap remains
active pending safe RAM headroom. Continue after each push.

## Android release15 delivered after native recovery checks — 2 November 2026

[Evidence](measurements/android-release15-2026-11-02.json): clean Mobile
`30d7faab73681cd04100dc4cea74012b62fd87b5` passes all five actual Android
SDK/HRIS/Synapse regressions in364s /365.046s, with all cleanup flags true.
This covers SDK E2EE exchange, selected worker queued/frozen/cancelled recovery,
scheduled process death and controlled native reference borrowing. It does not
accept the new all-company worker or incoming FCM path. Platform source was
`a9706280`; no live deployment occurred.

Proof assembly passed in46s /49.576s. Universal devRelease15 assembly/lint passed
in627s /630.891s; two actual StarterApplication cold starts each stayed alive12s,
resumed and rendered the onboarding page (35.690s total). Both screenshots were
inspected. ARM64 assembly/lint passed in73s /76.488s; all eight DEX files match the
startup-tested universal artifact. Lint has zero errors and one existing warning.

Authorized phone10.77.77.3:5555 now has **devRelease15**, version1.0.0,
package`dev.fajar.hris.mobile.dev`,87,363,576 bytes, SHA-256
`39a8bb9cfbaf95c394e228d16aebc2085448d8c4c3d2b0f051fadc13eca6a79e`.
Installation began05:14:03 UTC and finished with verification in112.767s.
Existing signer, UID10962 and first-install time remain. Install-only: no launch,
uninstall or data clear. A slow read-only preflight APK pull was interrupted and
retained in the evidence. Full installed-file SHA-256 computed on the phone matched
the retained signed14 APK before update and the new15 APK afterward; no post-install
full APK pull is claimed. The user confirmed11 opens, not a separate15 interaction.

**Continue immediately:** apply and validate the reviewed six-file/ten-case
processed-parent publication candidate in supplementary
`.work/parent-publication-2026-11-02/candidate.json` using its baseline hashes.
It is UNAPPLIED and UNTESTED at this checkpoint. Reuse the same Maintain collector;
separate processed retention, ownership denial and cleanup failure. Application
state must distinguish Pending/Observed/Stopped, invalidate on close/completion and
reject late publication without duplicate observers or automatic restart. Run the
focused actual app/DI/domain/proof checks and required root/browser checks, then
commit/push and continue durable all-company wake/fair cursor implementation.

Trace correction: one-time AndroidWorkScheduler already uses APPEND_OR_REPLACE;
only its periodic work uses KEEP. Preserve the existing finishing-worker race fix
while bounding/coalescing future FCM scheduling. Do not invent a session/account
scope for global wake metadata. Test persist/schedule/ack crash gaps explicitly.

Owned emulator5560 is stopped; unrelated5558 remains untouched. Owned temporary
8GiB swap remains active; remove only with sufficient RAM for used pages plus safe
headroom. Keep heavy builds sequential. Native receive/decrypt, notifications,
privacy/read/mute/deep links and encrypted inline replies remain open. Backend push
stays disabled, all54 PRD groups remain open, and no generic Starter/live rollout
is implied. Continue after each push; physical iPhone acceptance alone is waived.

## Native parent startup and monotonic ownership validated — 2 November 2026

[Contract](collaboration-parent-readiness.md) /
[evidence](measurements/chat-parent-readiness-2026-11-02.json): the single Maintain
collector publishes a valid parent before the first workspace emission. Same-parent
token updates seed the last observed workspace instead of clearing foreground.
A replacement parent starts empty. One bounded snapshot holds a monotonic effective
time floor and minimum hard-family expiry across repeated observations, read failures
and clock rollback. Initial stream expectations explicitly include parent-only state.

Seven new cases pass within516 domain and43 actual app collaboration tests, including
both DI cases. Proof adapter compilation and static checks pass in157s /160.787s.
Final three-input root/browser/app-Wasm checks pass in345s /348.943s:
2,983 desktop, 209 Android host, 788 browser and 10 build-logic cases,
zero failures/errors/skips in either run. Existing denial, renewal, observer exclusion
and late cleanup cases remain. No new JNI, employee APK or phone install yet.

**Continue immediately:** actual Android/provider regression and a meaningful release15
update to the authorized phone. Mobile prepared runners are in
`.work/android-recovery-2026-11-02-build15`: build.py (proof/release), start-emulator.py
and smoke.py; phone scripts are in `.work/release-phone-2026-11-02-build15`.
All are supplementary and unexecuted at this checkpoint. Build the proof APK from
clean committed source first; compileDebugKotlin alone does not refresh its bytes.
Platform prepared `.work/android-recovery-2026-11-02-build15/run-native.py` runs all
five actual SDK/HRIS/Synapse cases on owned emulator5560. Verify its input hashes and
artifact before/after, preserve every interruption/failure and cleanup record.
Details: `.work/parent-readiness-2026-11-02/next-android.txt`.

Then build signed devRelease15 with2048MiB/inprocess compilation, perform two actual
application cold starts on owned emulator, and build arm64 from the same clean head.
Require matching DEX between universal startup-tested APK and phone artifact. Notify
before installation; use explicit10.77.77.3:5555 and install-only, same signer/data.
Pull installed bytes and verify UID/firstInstall retention. Current phone remains14
from8c557716 until new evidence confirms installation. Stop only owned emulator when
done. Owned8GiB temporary swap remains active/full with about10GiB RAM available;
keep heavy builds sequential, preserve unrelated CodePush/AVD5558, clean swap only
when used pages and safe headroom fit RAM. Never delete active swap.

Continue explicit processed-parent publication for the future all-company coordinator:
ApplicationCollaborationLifetime.state defaults to Success(null), which is not proof
of publication. Reuse the same collector; keep failed cleanup separate from parent
identity so an eventual drain cannot leave a permanently false readiness marker.
Do not add a second observer or an implicit retry loop. Durable all-company wake
generations/fair cursors, directory/reconnect/periodic sweeps, receive/decrypt,
notification privacy/read/mute/deep links and encrypted inline replies remain open.
Backend delivery stays disabled. No generic Starter or live rollout; all54 PRD groups
remain open. Continue after every push. Physical iPhone acceptance is waived only.

## Shared admitted background worker validated — 2 November 2026

[Contract](collaboration-admitted-worker.md) /
[evidence](measurements/chat-admitted-worker-2026-11-02.json): the existing worker
accepts an ephemeral background admission. It loads exact setup/readmission before
captured local recovery and finite runtime borrowing, then shares all existing
room/outbox/history algorithms. It never reads or changes workspace selection in
that entry. The remaining original wall/monotonic budget includes setup/acquisition;
per-room12s limits remain. Both scheduled selected and background entries return
Retry(busy) immediately when occupied instead of joining a waiting queue.

The worker owns one finite reference and independent credential/runtime/local
access monitors. Interruption cannot wait for an initial access-flow emission;
failed references cannot start delivery. Pending work and observations are cancelled
and joined before reference release. Background history focus capacity defers
recovery without foreground reclamation or resending; foreground ownership survives
reference release. Platform job inputs stay task-key-only, never serialized grants.

All16 new application cases pass within43 actual app collaboration tests;509 domain
cases, two assembled DI cases, proof Kotlin compilation and static checks pass in
71s /74.704s. The first52s /56.244s run retained two assertion failures: an old test
expected the second worker invocation to wait, and a new test omitted the already
closed staging queue from its count. Both assertions now reflect explicit behavior;
product Kotlin stayed unchanged. Final seven-input root and app Wasm compilation
pass in327s /330.664s: 2,976 desktop, 209 Android host and 10 build-logic
cases, zero failures/skips. This app-only increment does not rerun browser tests;
prior d8ebe56f records781 passing affected browser cases. No new JNI/APK/phone run.

**Continue immediately:** fix MaintainCollaborationRuntimeOwnership initial parent
readiness while Workspace.observe is pending, preserve the last actual workspace
for same-parent token rotation, and anchor effective time/hard expiry monotonically
across repeated session/workspace emissions and clock rollback. Reuse the existing
single application collector; do not replace WorkspaceRepository. Detailed design
notes are supplementary at `.work/background-worker-2026-11-02/next-parent-notes.txt`.
A three-file/seven-case parent candidate is UNAPPLIED/UNTESTED at
`.work/background-worker-2026-11-02/parent-candidate.json`; verify baseline hashes
before applying and review its initial-parent-only observation expectations. Preserve prior collector/cleanup,
denial and timer regressions; test pending initial observation, replacement/logout,
old-source cancellation, foreground preservation and nonresurrection after expiry.

Then persist collapsed all-company wake generations and fair company/room cursors,
consume owned native-directory pages plus reconnect/periodic full sweeps, and wire
real receive/decrypt, notification privacy/read/mute/deep links and encrypted inline
reply. Background worker entry alone is not incoming-FCM acceptance. Keep backend
delivery disabled. Private server is still schema103/API-worker2c2a59e3, not branch116.
Phone remains verified14 from8c557716; next meaningful install15+ must be notified,
explicit serial, same signer/data, install-only. No generic Starter or live rollout.
All54 PRD groups remain open; continue after each push. Owned temporary swap remains
active pending safe cleanup; preserve unrelated CodePush jobs and AVD5558.

## Captured local delivery and frozen-draft recovery validated — 2 November 2026

[Contract](collaboration-captured-recovery.md) /
[evidence](measurements/chat-captured-recovery-2026-11-02.json): discovery/work
records, cursor advancement, queue/confirm/suspend and access observation share
Selected/Captured local scope. No synthetic workspace IDs or remote TTL for owned
facts. Original128-record/eight-draft bounds, stable fairness/CAS, frozen submission
ID, review holds and concurrent-editor protection remain shared. The application
has a captured draft-recovery entry and separate local/remote inner delivery scope;
its scheduled delivery entry is still selected-company-only at this checkpoint.

Nine new domain and two actual application cases pass within509 domain/27 app
collaboration tests, including both DI cases. Real encrypted frozen-draft recovery
after restart forbids workspace reads and preserves one original intent. Proof
adapter compilation and static checks pass in144s /147.572s. Initial compilation
stopped at a new test's collection type inference (36s /40.013s); the assertion was
made explicit and a new fixture's invalid enum name corrected. No product logic
changed for those corrections; the failed attempt remains in the evidence.
Final18-input root/browser checks pass in287s /290.976s: 2,960 desktop,
209 Android host, 781 browser and 10 build-logic cases, all zero failures/skips.
No new JNI, APK build, phone install or server change; phone remains verified14.

**Continue immediately:** an UNAPPLIED/UNTESTED six-file,16-test background-worker
candidate is at `.work/captured-recovery-2026-11-02/background-worker-candidate.json`.
Verify baseline hashes before applying. It adds a typed background entry to the
same worker, loads setup before local discovery and finite runtime borrowing, owns
only the borrowed reference and observes independent credential/interruption/local
access signals. Initial failed references cannot start work; native failure must
not wait for the first access-flow emission. Selected/background entries use the
same no-wait mutex and bounded shared room pass. Original grant budget includes
setup/acquisition. The fake SDK opts in explicitly; no provider acceptance claimed.
Its16 cases cover actual app persistence/room replay, history capacity deferral,
parent/company/SDK interruption, cancelled late acquisition, concurrency, deadlines,
foreground preservation and monitor cancellation/disposal. Validate actual app
collaboration cases, DI/proof compile, static/root checks and app Wasm compilation.

Then fix initial application-parent readiness while workspace observation is still
pending, preserving foreground retention across token rotation and monotonic parent
expiry. Implement durable all-company collapsed wakes, fair directory/room cursors,
reconnect/periodic full sweeps, notifications/privacy/read/mute and encrypted inline
reply. No WorkspaceRepository replacement in production; no foreground reclamation
from a background pass. Backend delivery stays disabled; all54 PRD groups remain
open. Continue after each push. No generic Starter port or live rollout. Owned
temporary swap remains active pending safe cleanup; preserve unrelated jobs/AVD5558.

## Background setup and conversation control validated — 2 November 2026

[Contract](collaboration-background-control.md) /
[evidence](measurements/chat-background-control-2026-11-02.json): LoadSetup and
Prepare/Replay/ReconcileConversation share Selected/Background remote admission.
Background never reads workspace selection and binds room requests to the exact
admitted device. Setup readmission requires the matching active device/platform and
nonregressing version before gate CAS. Retained denial and committed-intent rules
remain in force through cancellation, expiry and native-parent replacement.

Historical command failure is deliberately separate from a newly received denial.
A fresh admitted retry keeps its original operation without closing the company
again merely because its old explanatory failure was a denial. Newly observed HRIS
denials still retain the gate under failed cleanup or owner reads.

All 16 added cases pass within 500 domain and 118 presentation tests; both actual
app DI cases, proof adapter compilation and static checks pass in152s /155.518s.
Initial nine-input/12-case checks also passed496 domain,118 presentation and two DI
cases in154s /158.268s before the setup extension. Final ten-input root/browser checks
pass in249s /252.715s: 2,949 desktop, 209 Android host, 772 browser
and 10 build-logic cases, zero failures/errors/skips. No new JNI or APK run.
Phone remains verified14 from8c557716; no install or server change here.

**Continue immediately:** captured local delivery/draft discovery/cursors and
queue/confirm/suspend, then shared application worker integration. An eighteen-file
candidate with nine new domain and two actual application cases is UNAPPLIED and
UNTESTED at `.work/background-control-2026-11-02/next-local-candidate.json`.
Verify all baseline hashes before applying. It carries explicit local scope in work
records, preserves selected String entry points, reuses access observation and
prepares the existing worker's inner pass with separate local/remote scopes. It
adds a captured draft-recovery entry; scheduled delivery remains selected-only.
Validate domain, all actual app collaboration worker cases, proof compilation,
static checks and full root/browser checks. No fake workspace IDs or duplicated outbox.

Then hold an admitted finite background runtime reference across the shared worker,
observe interruptions/credential failure and release only that reference. Do not
call foreground Ensure/reclamation while borrowed; history capacity defers bounded
background recovery. Test no-wait worker admission and initial application-parent
readiness without a first workspace emission, preserving foreground retention and
monotonic parent deadlines across token rotation. Durable all-company collapsed
wake generations, fair cursors, directory/reconnect/periodic sweep, notifications
and encrypted inline replies remain required. Delivery stays disabled; all54 PRD
groups remain open. Continue after checkpoints. No generic Starter or live rollout.
Owned temporary swap remains active pending safe cleanup; preserve unrelated jobs.

## Captured local message/control ownership validated — 2 November 2026

[Contract](collaboration-captured-local-scope.md) /
[evidence](measurements/chat-captured-local-scope-2026-11-02.json): message observation,
receipt recovery/projection/retirement and retained setup/direct/room reads accept
Selected or Captured local scope. Existing String callers retain workspace behavior.
Captured access checks the active local session/account/audience and canonical company,
then the retained company gate, without workspace reads or remote permission deadlines.
Control reads also bind commands to the current native family without a live TTL.

All15 new cases pass within484 domain tests, including expired-credential recovery,
32-item retirement, late owner/cancellation, retained receipt/completion facts and
observer disposal. Actual DI's two cases, proof adapter compilation and static checks
pass in136s /139.684s. Initial12-file focused checks passed480 domain/two DI cases in
139s /143.072s before adding three control files/four cases; both scopes are recorded.
Final15-input root/browser checks pass in263s /267.22s:
2,933 desktop, 209 Android host, 756 browser and 10 build-logic cases, zero failures/skips.
No new JNI, employee APK build or phone install. Phone remains verified14 from8c557716.

**Continue immediately:** typed remote Prepare/Replay/ReconcileConversation, exact
background RequestRoom device binding and retained company gates. A nine-file,
12-test candidate is UNAPPLIED/UNTESTED in
`.work/captured-local-2026-11-02/remote-control-candidate.json`; verify its baseline
hashes before applying. It adds required list-repository DI and updates the existing
domain/presentation constructors. Validate domain/presentation, actual DI/proof
compile and full root/browser checks after applying.

Critical distinction: a historical command failure is not a newly received server
denial. Replay combines prior failure with transport/storage failures. Reusing that
combined value to restrict the company would reclose a freshly admitted gate and
prevent retry forever. The candidate separately carries contextResult and the actual
receivedResponse; only the latter confirms a new denial. Tests include explicit
readmission followed by successful retry, historical denial plus transport failure,
and new MFA/company denial under cancellation/failed cleanup. Supplementary detail:
`.work/captured-local-2026-11-02/next-control-notes.txt`.

Then convert local delivery/draft discovery, cursor/queue/confirm/suspend operations
and generalize the existing bounded worker. Do not fabricate workspace IDs or copy
outbox logic. Captured scope must come from owned records, never raw FCM fields; it
does not authorize remote I/O. Reuse ApplicationCollaborationLifetime and test initial
parent readiness while workspace observation is pending, maintaining foreground
retention across token rotation. Durable all-company wake/reconnect/periodic recovery,
notification privacy/read/mute and inline replies remain required. Delivery stays
disabled; all54 PRD groups remain open. No generic Starter port or live rollout.
Owned temporary swap remains active pending safe cleanup; preserve unrelated jobs.

## Background staging/delivery and SDK denial classification validated — 2 November 2026

[Contract](collaboration-background-outbox.md) /
[evidence](measurements/chat-background-outbox-2026-11-02.json): StageText and
DeliverMessages now use shared Selected/Background admission without consulting
workspace selection. Both require the exact admitted device. Existing uncertainty,
transaction mapping, receipt append, projection and cancellation rules remain shared.
Staging now reuses content-denial classification: SDK-token unauthorized stops the
native operation but does not close all local company history. Confirmed HRIS
company/session/MFA denials still retain their gate even under failed cleanup.

All 12 added cases pass within 469 domain tests, and both actual app DI cases pass.
Proof adapter Kotlin compilation, format/architecture and detekt pass in130s Gradle
/134.385s wall. The initial43s /46.539s run had one new-test expectation failure:
Sent mappings intentionally participate in later SDK queue inspection/alias recovery.
The corrected test models a drained second window and verifies the original mapping,
receipt and projection before that window; the fixture prohibits enqueue. No product
algorithm was changed to mask that assertion. The failed run remains in the evidence.

Final root/affected browser checks pass in254s /257.828s with five unchanged
Kotlin inputs: 2,918 desktop, 209 Android host, 741 browser and 10 build-logic cases.
No new JNI run or employee APK build/install here. Phone remains verified release14
from8c557716, which predates these overloads and the SDK classification correction.

**Continue immediately:** captured local ownership for message observation, receipt
recovery/projection and retirement, independent of remote grant expiry. A 12-file,
11-test candidate is UNAPPLIED/UNTESTED in
`.work/background-outbox-2026-11-02/next-local-candidate.json`; verify baseline hashes
before applying. Its current shape keeps Selected behavior and adds Captured access
with active account/session/audience fencing and retained gate checks. Review this
policy before broadening local discovery. Captured access is not remote authority
and must never be trusted directly from FCM fields. It has no remote TTL.

Then convert local ReadControl/discovery/cursor/drafts and remote room-control
commands, including exact background device binding and retained-denial handling.
Generalize the existing application worker; do not duplicate its bounded outbox or
replace WorkspaceRepository. Reuse the application-owned runtime collector and test
initial parent readiness while workspace observation is pending, preserving a
foreground client across token rotation. Incoming all-company collapsed hints,
reconnect/periodic sweep, notification privacy/read/mute and inline reply remain next.
Keep backend delivery disabled; all54 PRD groups remain open. No generic Starter
port or live rollout. Continue after every pushed checkpoint. Owned temporary swap
remains active pending safe cleanup; preserve unrelated CodePush jobs/AVD5558.

## Shared background native entry and history validated — 2 November 2026

[Contract](collaboration-background-history.md) /
[evidence](measurements/chat-background-history-2026-11-02.json): EnterConversation,
AcquireHistory and AcquireProviderHistory now share Selected/Background scope.
String callers retain their existing selected-workspace path. Background checks the
current native parent, original monotonic admission and exact device without reading
or changing workspace selection. Native child cleanup, bounded original merges,
provider-page CAS and retained denials remain shared. Local owned facts stay intact.

All 13 new cases pass within 457 domain tests. Actual container bindings pass both
cases; the initial run compiles the proof adapter and passes format/architecture,
then reports one detekt LongMethod violation (101 lines). The returned timeline's
ownership predicate was extracted without changing its conditions. That initial
202s /205.445s run remains recorded. Final root and affected browser checks pass in
326s Gradle /329.525s wall with all seven Kotlin inputs unchanged:
2,906 desktop, 209 Android host, 729 browser and 10 build-logic cases.
This increment has no new JNI run, employee APK build or phone install. Phone stays
on verified release14 from8c557716; its source predates these overloads.

**Continue immediately:** shared Stage/Deliver background scope plus the SDK-token
failure classification correction. A five-file candidate with 12 cases is
UNAPPLIED/UNTESTED in `.work/background-history-2026-11-02/next-outbox-candidate.json`;
verify its baseline hashes before applying. Preserve observed-send receipt append
before remote continuation, so expiry/cancellation cannot erase proof or cause a
second send. No native-parent close handle belongs to a child writer.

Then convert room-control commands and local discovery/reconciliation/draft cursor
operations with separate captured local ownership and remote admission. Local reads
must not inherit a60s remote TTL. Reuse the existing bounded delivery worker and
application lifetime; test initial parent readiness while workspace observation is
pending, retaining the foreground owner across token rotation. Detailed scratch
notes are in `continuation-design.txt`; Git contracts are authoritative. Continue
durable all-company hints/reconnect/periodic recovery, notification projection and
inline replies. Keep backend delivery disabled; all54 PRD groups remain open.
No live rollout or generic Starter port. The owned temporary swapfile below remains
active pending safe cleanup; preserve unrelated CodePush jobs/AVD5558.

## Release 14 installed on the authorized phone — 2 November 2026

[Installation evidence](measurements/android-release14-2026-11-02.json): clean
source `8c557716` built arm64 devRelease14 in 62s Gradle /65.98s wall with lint
passing. The 87,347,192-byte APK (`be3d4211`) has all eight DEX files identical to
startup-tested universal14. It retains the existing development signer and private
WireGuard API; no fixture endpoint or debuggable flag is present.

After notification and signer preflight, the install-only update to
`10.77.77.3:5555` completed and was verified by pulling the installed APK. Its
full hash matches. Version14, application UID and original first-install time are
verified; no uninstall, app-data clear or UI launch occurred. Installation plus
verification took85.325s. The user's latest confirmation was that release11 opens;
release14 has automated startup/code-equivalence proof, not a new user confirmation.

**Continue immediately:** apply the seven-file typed native/history scope candidate
mentioned below only after verifying its baseline hashes. Validate shared history
and child-resource cancellation rules, then continue shared outbox and durable
all-company incoming recovery. Phone now has14; older phone11 notes below are
historical. Keep backend delivery disabled and continue after each pushed checkpoint.
No live server rollout, generic Starter port or full PRD-group acceptance.

## Finite background runtime references validated — 2 November 2026

[Contract](collaboration-background-runtime.md) /
[evidence](measurements/chat-background-runtime-2026-11-02.json): background work
can borrow an existing published Android client or restore its protected store,
without a parent close handle or changing the selected company. Native parent and
foreground retention are separate. Five reserved native slots include pending
opens/unfinished drain; two finite references have no waiting queue. Acquisition
completion fences native destruction, and history reopening excludes concurrent
borrowing for its entire decision. Monotonic admission budgets include network and
acquisition time. Cancellation, late parent changes and retained denials are covered.

Focused checks pass 444 domain, 209 Android host and six proof-cleanup cases in
140s Gradle /143.769s wall; actual DI's two cases also pass. All five native cases
are now accepted on the same proof APK `91638cf6`, across two recorded runs against
Platform2308b337 (implementation8b77c4fa). The first run passed SDK, scheduled and
cancelled-draft scenarios, then the kernel killed owned emulator5560/PID3805888
for host OOM. Its two interrupted cases remain recorded as failures. The queued
and frozen-draft retry passes in169s /169.876s; both clean up successfully. The
interrupted fixture was removed only after verifying its exact APK. Actual native
borrowing preserves the cryptographic identity and the foreground client.

Required checks now pass: 2,893 desktop, 209 Android host, 716 browser and ten
build-logic cases, plus ten runner/17 distribution Python checks. Run7 completed
root/domain checks but two large IndexedDB fixtures exceeded Mocha's default2s.
A module-local10s budget is verified in generated Karma configuration; all272 data
browser cases then pass. Run8 subsequently hit D8's reduced1280MiB heap; run9's
remaining release targets pass at2048MiB in100s /103.642s. Failed runs remain in
the evidence. Keep `-Pkotlin.compiler.execution.strategy=in-process` for upcoming
checks to avoid changing all compiler inputs again; use2048MiB for APK assembly.

Universal devRelease14 is281,097,776bytes, SHA256 `64b05d53`. App lint has zero
errors/one existing warning. The actual StarterApplication passes two12s stable
cold starts with resumed/readable onboarding and inspected screenshots in35.695s.
The owned emulator was stopped and absence verified. Phone remains user-confirmed
release11 pending the separate arm64 update; there is no phone-install claim here.

**Continue immediately:** commit/push this checkpoint, then use the prepared
`.work/release-phone-2026-11-02-build14` tooling to build from the clean commit,
verify signer and DEX equivalence to the startup-tested universal14, preflight the
phone, notify, and install-only on10.77.77.3:5555. Preserve app data/signing and
verify installed bytes. Push installation evidence, then resume implementation.

Next: typed Selected/Background native entry/original/provider history, shared
outbox orchestration, durable all-company wake/reconnect/periodic recovery, incoming
notifications/privacy/read/mute/deep links and inline replies. A seven-file,
13-test candidate is **UNAPPLIED/UNTESTED** in
`.work/runtime-borrowing-2026-11-02/typed-native-scope-candidate.json`; check its
baseline hashes before applying. Git contracts remain authoritative. Reuse
ApplicationCollaborationLifetime, already started by StarterApplication; do not
start a second observer. Test initial parent readiness while the first workspace
observation is pending before wiring incoming recovery. No selection swapping or
copied integrity logic. Keep backend delivery disabled; all54 groups remain open.
No generic Starter port or live backend rollout in this increment.

Host resource note: an owned8GiB Btrfs swapfile
`/var/tmp/hris-validation-2026-11-02.swap` is active, mode0600, priority-3 and not in
fstab. It prevented further host-wide OOM during concurrent unrelated CodePush
work. Keep it until RAM can safely absorb its used pages plus a margin, then remove
only this swapfile. Preserve unrelated jobs/AVD5558 and existing swap. Creation and
kernel evidence are retained in this increment's measurement and scratch records.

## Native runtime publication and five native regressions validated — 2 November 2026

[Contract](collaboration-runtime-publication.md) /
[evidence](measurements/chat-runtime-publication-2026-11-02.json): completed SDK
acquisition stays private until synchronous publication after final owning-use-case
admission. All **four** acquisition paths are covered: Ensure, Install, Restore and
ReopenHistory. Install also waits for command retirement. No suspension separates
publication from transfer; failure/cancellation keeps cleanup with the original
caller. This is resource ownership, not permission to send or read remotely.

Focused430 domain/190 Android host/six proof cleanup cases, actual proof APK assembly,
lint, format/architecture and detekt pass in156s Gradle /160.313s wall. Proof lint
has zero errors/47 warnings. The earlier pusher interface addition had left the
proof adapter without its new method; it is now explicit and compiled. Four new
host cases use inert FFI handles; they are separate from actual native evidence.

The same proof APK `6f88fcd6` passes all five actual SDK/HRIS/Synapse cases against
Platform8b77c4fa in369s /369.345s. SDK56.804s, scheduled46.985s, three worker cases
240.250s; all results/cleanup pass and31 evidence hashes are retained. Queued,
frozen-only and cancelled-draft cases each retain older history and verify33
encrypted events. CI now pins that Platform revision; hosted execution is not yet
audited. The owned emulator5560 is stopped and absence verified.

Full root and affected browser checks pass in236s /239.946s:2,879 desktop,190
Android host,702 browser and10 build-logic tests, plus10 Android-runner/17
distribution Python checks. Sixteen Kotlin inputs stayed unchanged throughout;
the later CI-pin-only change makes17 full-check inputs. No employee APK build or
phone installation here. Phone remains user-confirmed release11; earlier
universal13 startup proof predates this change. Next useful update14+, notify
first and preserve signer/data with explicit10.77.77.3:5555 install-only.

**Continue immediately:** finite background runtime borrowing alongside foreground
retention, using one SDK/store per owner/device. Carry current native parent
separately from optional selected-company owner. Parent expiry must still fire
after foreground admission expires; token rotation must retain the same parent.
Reserve capacity/store identity before suspending acquisition and invalidate late
opens on parent replacement/shutdown. Unfinished cleanup retains capacity5. A
borrower gets a finite releasable reference, never a parent close handle; releasing
it must preserve a foreground client. Test pending opens, expiry, replacement,
denial and failed drain. Detailed supplementary notes are in
`.work/runtime-publication-2026-11-02/next-runtime.txt`.

Then extend existing Selected/Background scope through history/outbox instead of
copying their integrity/denial logic, and implement durable all-company collapsed
wake/reconnect/periodic recovery, receive/decrypt/privacy/read/mute/deep links and
inline replies. Classic SyncV2 remains required; NotificationClient needs sliding
sync. Keep push delivery disabled until real receive/recovery acceptance. No live
rollout or generic Starter change; all54 PRD groups remain unaccepted. Continue
after each pushed checkpoint.


## Shared conversation reads accept background scope — 2 November 2026

[Evidence](measurements/chat-background-read-scope-2026-11-02.json): typed
Selected/Background scope now feeds existing RefreshCollaborationList and
ReadCollaborationConversations. Existing String entry points preserve foreground
behavior. Background capture checks current account/native parent and the original
finite proof without reading/changing workspace selection. Publication, denial CAS,
cancellation and late-result rules remain shared. Seven new background cases pass,
including later use at40s, expiry/replacement, cancelled denial and late merge.

Focused425 domain/294 data/two actual DI cases pass in163s /166.687s. Full root,
affected browser suites, Android host, format/architecture and detekt pass in246s
Gradle /249.772s wall; seven inputs unchanged. Exact aggregate counts are in the
record. No APK build or phone install in this increment. Phone remains accepted11;
prior universal13 startup evidence is at a8558228 and predates this change.

**Continue immediately:** explicit native runtime publication after final owning
use-case admission/command retirement, before another foreground/background caller
can reuse it. Currently completeAcquisition makes the SDK entry visible before
Ensure/Install/Restore finishes its pending ownership checks; background borrowing
must not enter that window and then lose its parent to the earlier caller's cleanup.
Add a synchronous publish/accept step to the lease and those three owning use cases,
with native lease and pending/cancellation/retirement tests. Then finite background
borrow/reference ownership alongside foreground retention, parent expiry/replacement
and shutdown, reusing the same SDK/store. Extend typed scope through existing
history/outbox use cases instead of copying their integrity and denial rules.

The earlier runtime-next.txt notes remain useful. Its typed-scope-prepared.json is
now applied and refined; do not apply it again. This first scope increment covers
control/list reads only. Durable all-company hint/reconnect/periodic recovery,
FCM receive/decrypt/privacy/read/mute/deep links and inline replies remain next.
Keep delivery disabled; all54 catalog groups and remaining P0–P8 remain open. No
generic Starter change or live rollout. Continue after each pushed checkpoint.



## Native-parent discovery and company admission client validated — 2 November 2026

[Contract](collaboration-native-recovery.md) / [evidence](measurements/chat-native-recovery-client-2026-11-02.json):
one bounded device page and separate active-device company admission now work without
reading/changing foreground selection. Local/native-parent identity, current scope,
strict metadata, versions and request-time-calibrated deadlines are checked. Each
use case owns one15-second request without a waiting queue. Received company/MFA
denial stays gated despite cleanup failure; successful observation never clears it.

Nine new domain and six transport cases plus actual container bindings pass.
Focused418 domain/294 data/two DI cases pass in164s /167.899s. Full root check,
affected browser suites and signed universal devRelease13 assembly/lint pass in
546s Gradle /549.631s wall:2,867 desktop,186 Android host,690 browser and10 build-logic
cases; root Python10 runner/17 distribution checks. Fourteen inputs unchanged,
format/architecture74modules/detekt pass, lint zero errors/one existing warning.
Both actual-app cold starts remain stable12s with readable/resumed onboarding and
inspected screenshots,38.649s smoke. Owned emulator5560 stopped. Older unrelated
browser reports are excluded; no hosted/native-provider or phone-install claim.

Platform8b77c4fa has matching actual HTTP/SQL/API+worker proof. Continue typed
Selected/Background operation scope through the existing acquisition/outbox use
cases, then finite SDK background borrowing alongside foreground ownership. Never
swap WorkspaceRepository selection, duplicate a cryptographic store, expose a parent
close handle to a borrower or let late work survive parent replacement. Concrete
notes and a four-file UNAPPLIED/UNTESTED candidate are in
.work/native-recovery-client-2026-11-02/runtime-next.txt and typed-scope-prepared.json;
Git contracts remain authoritative. The candidate covers list/conversation reads
only and must gain tests, strict grant validation and runtime integration.

Use classic SyncV2 history for notification recovery; built-in NotificationClient
requires sliding sync. Durable all-company hint/reconnect/periodic recovery, receiver,
privacy/read/mute/deep links and inline replies still remain. Keep backend delivery
disabled. Phone remains accepted release11; next useful product update12+, notify
first, explicit10.77.77.3:5555 install-only and same signer/data. Release13 above is
only a local validation artifact. No generic Starter port or live schema/provider
rollout. All54 groups remain unaccepted; continue P0–P8 after each pushed checkpoint.



## Managed pusher client validated; background recovery next — 2 November 2026

[Contract](collaboration-notifications.md) / [evidence](measurements/chat-pusher-client-2026-11-02.json):
explicit pusher activation now checks current company/native-parent ownership,
active device and retained admission denial. Android borrows the owned SDK bearer,
registers one finite request as a runtime child and posts to the fixed gateway
endpoint. No implicit enrollment, restore, refresh or retry. Application DI resolves
its singleton. Automatic worker invocation and incoming notifications remain next.

Full root check, affected browser suites and signed universal release12 assembly/lint
pass: 2,852 desktop, 180 Android host, 675 browser and ten build-logic tests; zero
failures. Lint has zero errors/one DataExtractionRules warning. Final build 562s
Gradle /565.616s wall, 21 unchanged source inputs. Both actual StarterApplication
cold starts remain stable for12s with rendered/resumed onboarding; screenshots
inspected, smoke36.563s. Owned emulator5560 stopped. This is local evidence, not a
hosted native provider/FCM proof. Earlier fixture failures are retained in the record.

Platform e1da924b has the bounded default-disabled push worker. Its own-parent
paginated device directory and separate protected company admission endpoint are
undergoing actual API/worker/SQL tests. Continue the Mobile typed background
admission and finite runtime ownership, durable collapsed wake/reconnect recovery,
receive/decrypt/privacy and inline replies without changing visible workspace.
Current SDK NotificationClient requires sliding sync while product uses SyncV2;
do not assume compatibility or expose an unscoped sliding-sync gateway.

Keep backend delivery disabled until the receive/recovery path is validated. Phone
release11 remains user-confirmed working; no phone install in this API-only increment.
Next validated product update12+, notify first, explicit10.77.77.3:5555 install-only
and preserve signer/data. No generic Starter change in this HRIS adapter. All54
catalog groups remain unaccepted; continue P0–P8 after each pushed checkpoint.


## Release11 installed; user confirms startup recovered — 2 November 2026

Clean source56a46938 is installed in place over release10 on 10.77.77.3:5555.
[Evidence](measurements/android-release-startup-2026-11-02.json): arm64 build/lint
66s Gradle /70.331s wall, APK87,330,800 bytes, SHA256
`e914fad7f195d74ab55889b404c1c9da72cddd5deefb3c3522bf1a0de1c91579`.
All eight DEX files match the universal release that passed both actual-app cold
starts. VCS/private API/Firebase/signature/16KiB alignment verified. Installation
and post-pull verification59.620s preserve appId10962 and firstInstallTime; no
uninstall, data clearing or phone UI launch. User subsequently confirms **“Sudah
bisa dibuka”**. The force-close fix has physical user acceptance. Next product
phone update must use12+ and the same notification/install-only protocol.

Continue Platform push delivery. Focused55 domain plus18 SQL cases have just run;
one SQL retry fixture failed because it tried to rewrite available_at outside a
fenced lease transition. Preserve that database protection and fix the fixture's
clock/backoff approach before further tests. Seven other new delivery cases and
all ten callback cases pass. Handler/policy/DI and tests remain uncommitted there.
Worker adapter/flag, API+worker context validation, Mobile notifications/reply and
all-company recovery remain next, followed by remaining P0–P8 and reusable ports.


## Android release startup crash corrected — 1 November 2026

User-reported release10 force close is confirmed by its application crash stack:
StarterApplication resolves CollaborationDeliveryWorker, which constructs the SDK
endpoint from IdentityServer.audience (`.../api/v1/`). The strict root-only endpoint
rejects that path before any Activity appears. The Android host now uses an explicit
`fromApiBaseUrl` factory that validates the original configuration and retains only
its origin for the gateway. API audience, account partitioning, TLS/Dev opt-in and
strict directly configured root validation stay intact.

[Evidence](measurements/android-startup-gateway-2026-11-01.json): three regression
cases, full root check, 2,844 desktop +173 Android host +10 build-logic cases and
release assembly/lint pass (zero errors, one warning), 856s Gradle /859.865s wall.
Three source inputs remain unchanged. Actual **StarterApplication**, not the separate
provider proof app, passes two 12-second stable cold starts with readable/resumed
onboarding UI and inspected screenshots. Universal release11 test APK SHA256
0054ee2314c9ffa243ca22779bc8663ec5e097886920d4a905a49cd520d5ce12.
An emulator watchdog exit before install and obsolete Android35 activity-field
probe were fixture failures; no additional product change. Final run35.173s.
The isolated emulator is stopped before the next build.

**Continue immediately:** clean-source arm64 release11 build/install-only on
10.77.77.3:5555 with notification, existing signer/data and pulled-byte verification.
Helpers are prepared in `.work/release-phone-2026-11-01-build11/`. Then resume
Platform queue/FCM delivery. Platform211d5d65 is pushed; its uncommitted handler/
policy/DI and new SQL/policy tests are formatted but not yet run. Callback/TLS/queue
are already implemented. Worker adapter/flag, Mobile receive/decrypt/reply, all-company
recovery, Starter ports and remaining P0–P8 continue; all54 groups remain unaccepted.


## Hosted five-case cancellation recovery verified — 1 November 2026

[Hosted evidence](measurements/chat-cancellation-native-ci-2026-11-01.json) audits
41351236140 at Mobile 302aa192 / Platform d48ac2f8: all five native cases and six
cleanup cases pass, with 32 evidence hashes. Proof APK SHA-256
`5438147bc67881ec9b2a3f4547e261c1035f5f21336709b4aa9c471dd9427829`.
SDK suite 61.318s, scheduled 85.907s, three-worker suite 186.936s; worker method
times cancelled 54.154s, queued 53.463s, frozen 53.083s. Cancelled identity/archive
and draft revision survive real death beside one independent command; no delivery
receipt or replay wake appears, recovery clears only that draft and its original
is absent from acquired history. Each worker confirms one independent encrypted
original plus 32 peer seeds. This is not remote deletion or native queue erasure.
Exact pass counts and observed pending-key 503 counts are retained in the evidence.

Continue push implementation. Platform e104414e now has validated immutable encrypted
pusher binding storage/schema-115 recovery. Outgoing activation through current
HRIS admission and a fixed vendor endpoint is undergoing real API/worker checks;
private callback ingress, durable recovery, Mobile receive/reply and reusable
Starter ports remain next. No live provider/schema rollout or full PRD acceptance.
Phone remains installed release 10 from c0371a38; these are fixture/docs-only Mobile
changes. Next validated app product checkpoint must be code 11+, notify first,
explicit WireGuard serial and install-only. Continue after every commit/install.

## Installed-source native regression audited — 1 November 2026

[Hosted evidence](measurements/chat-cancellation-ui-native-ci-2026-11-01.json) audits
41347895253 at c0371a38, the release-10 product source installed on the phone.
Its older Platform bad75bd0 fixture passes SDK 60.410s, scheduled 88.926s and queued/
frozen pair 105.883s, six cleanup tests and 26 artifact hashes. Proof APK SHA-256
`86d3115afa863b36759b59d477c73e5365425cb31d61d0de5663320314c1dcd4`.
Both workers take one pass, acquire 33 originals/four history actions/43 references
(ten state-only); frozen recovery retains exact identity/revision and zero initial
commands. This headless run does not exercise the cancellation dialog or cancelled
original, and predates the corrected pending-key response. Separate newer local
five-case proof remains authoritative for those cancellation/publication boundaries.

The newer five-case 41351236140 at 302aa192 / Platform d48ac2f8 is now running.
Continue notifications: Platform a31cd57a adds validated FCM visible/background
profiles (15 push, 13 policy, eight real worker cases); fixed opaque Matrix pusher
transport is being validated next, followed by admitted binding/durable callback
recovery and Mobile receive/reply. No live provider rollout or PRD acceptance.
Phone remains verified release 10. Install the next validated product checkpoint
as code 11+ with notification, explicit WireGuard serial and install-only; do not
drive its UI. Continue the full implementation after each commit/install.

## Cancelled-draft native proof and publication retry corrected — 1 November 2026

[Five-case evidence](measurements/chat-cancellation-native-2026-11-01.json): 356s Gradle / 357.121s wall,
68 Mobile/five Platform inputs, APK `3c15eb832190528e92cd85c8385e3367815cd6cc6ae809f7dd26525af4c17275`.
CANCELLED_DRAFT retains an authenticated cancellation and matching frozen draft through real
death beside one independent command. Replay does not schedule or create a receipt; recovery
clears only that draft, excludes its history and sends the independent original once plus
32 peer seeds. QUEUED/FROZEN_DRAFT, SDK and WorkManager remain separate passing cases.

The first run failed frozen-only after bounded delivery retries (409s / 409.864s); the other
four cases including cancellation passed. Trace: temporary key upload/query 503, then
sendToDevice 400/device_keys_required, later successful uploads but no room send. Real-SQL
reproductions confirmed a valid version 0 reservation was being classified as malformed.
It now returns UNAVAILABLE/device_keys_pending (HTTP 503), preserving confirmed-key/fingerprint
and current owner/permission gates. No reservation still gives validation; no trust, schema,
DI or operation-deadline change. Three focused cases pass; full 40 domain/210 data/26 HTTP,
format/architecture pass 423s / 424.148s. Initial two failed reproductions and full native
failure are retained. The final QUEUED native case actually receives one pending-key 503, retries in two worker passes and completes exactly 33 encrypted sends; frozen/cancelled cases each take one pass. All cleanup succeeds and the owned emulator is stopped. Mobile proof/quality passed 145s / 148.995s, cleanup six; 69 Python cases pass.

Hosted [448ab3ad run 41344248284](measurements/chat-frozen-native-ci-2026-11-01.json) and
[648410c4 run 41345994459](measurements/chat-cancellation-policy-regression-native-ci-2026-11-01.json)
are audited separately, 26 hashes each. Both use the earlier four-case Platform fixture;
neither proves this newer native cancellation/pending-publication boundary. UI run 41347895253
at c0371a38 was still running with the old pin; audit its actual source if it completes.

Platform `d48ac2f866e7f6395cad035424b9fe76d9fdb743` is committed, pushed and pinned by Mobile collaboration-hris CI.
The full job budget is now 45 minutes (the old four-case hosted job took 29m30); operation
and test retry limits are unchanged. YAML and the remote source ref were verified.
**Continue immediately:** commit/push this Mobile fixture/evidence, then implement FCM/pusher
admission, opaque durable notification recovery and native inline replies. Platform `.work/collaboration-push-next.txt` records official Matrix v1.16 and current
transport/owner findings; move concrete implementation contracts into Git. Current FCM mapper
is inbox-only NORMAL/silent-Apple; current Mobile runtime and inbox push use selected company.
Do not silently switch workspace or claim all-company background support. Notifications must
use current account/company/device/room admission and the same encrypted message outbox.
Port only verified generic notification/scheduler primitives to Starter. All 54 groups remain
unaccepted; continue all P0–P8. Live backend/provider deployment is unchanged.

Phone remains verified release 10, source c0371a38, installed 20:44 UTC over 9 with exact pulled APK,
existing signer/data and no UI launch. These new Mobile edits are fixtures only, so another
phone build is unnecessary. Next validated app-feature install must use code 11+, notify first,
install-only on 10.77.77.3:5555. Never drive the phone UI; use the owned emulator for tests.

## Release 10 installed and verified; native cancellation proof next — 1 November 2026

Clean source `c0371a38d1ae2f8c890c300648ec849089c3836a` is installed in place over code 9 on
10.77.77.3:5555 at 20:44 UTC. Build passes in 241s Gradle / 245.272s wall; release lint
zero fatal/errors and one warning. Arm64 APK 87,330,800 bytes, SHA-256
`e366cdcd3eaa997c44004985f53b7de9d15dd75c18c21f0f4d70c0bbd84589c9`. All 64 reviewed inputs, embedded VCS,
private API/Firebase, existing signer and 16 KiB alignment match. Install and verification
42.888s; pulled APK matches exactly. App identity and first-install time are preserved,
with no uninstall, data clearing or UI launch. [Release evidence](measurements/android-release-cancellation-2026-11-01.json).
Future updates require code 11+, notification first and install-only.

**Continue immediately:** actual cancellation recovery with the real SDK/HRIS/Synapse.
Add CANCELLED_DRAFT alongside separate QUEUED/FROZEN_DRAFT cases: retain a cancelled frozen
original through death while one independent queued message sends once. Bind cancellation
ID/body hash/revision; archived replay must not wake, receipt remains absent, worker clears
only the matching frozen draft, and exact gateway sends remain one plus 32 peer seeds.
Platform fixture/driver changes are WIP; 69 Python cases and test compilation/quality pass
(30.671s wall). Apply the prepared Mobile helper, validate a matching proof APK, run the
owned emulator, retain source-bound evidence and pin the new Platform commit in hosted CI.
`.work/native-cancellation-next.txt` and `.work/apply-native-cancel-mobile.py` supplement this
contract. Hosted frozen audit41344248284 is ready for Git; it predates cancellation.
Then FCM/pusher/gateway integration, inline replies and all remaining P0–P8. No complete
feature group or live backend/provider rollout is claimed by this phone update.

## Queued cancellation UI validated; install release 10 next — 1 November 2026

The [cancellation contract](collaboration-cancellation.md) now includes explicit confirmation,
exact-selection retry after lost replies, duplicate suppression, a five-second waiter deadline,
changed/started-message rejection and late account/disposal fences. Only eligible owned Queued
rows expose the action. The current draft is preserved; modal interactions cannot stack.

[UI measurement](measurements/chat-cancellation-ui-2026-11-01.json): 64 cumulative inputs,
17 changed source files. Full check passes 205s Gradle / 208.895s wall with 2,840 desktop,
401 domain/263 data/101 presentation browser, 170 Android host, six cleanup cases, proof
APK/lint (zero fatal/errors, 47 warnings) and format/architecture/detekt. Focused cancellation
ViewModel ten and thread layout eight cases pass. Initial compilation failed only two test
generic-inference expressions; corrected and retained. A subsequent new desktop-only test
runs an actual 320 × 640 dialog at 200% font; desktop/quality passes 48s / 51.475s with fixed
Close/Retry controls while the long body scrolls. Three screenshots were visually inspected;
full and supplemental commands keep separate source/report attribution.

**Continue immediately:** commit/push, then clean signed release 10 using
`.work/release-phone-2026-11-01-build10/run-reviewed-build.py`. Notify before installing,
use install-only on 10.77.77.3:5555, verify the pulled APK and commit/push release evidence.
Phone remains release 9 until that succeeds. Then actual native cancellation proof,
FCM/replies and all remaining P0–P8. No complete feature group or live provider rollout.

Hosted four-case run 41344248284 at 448ab3ad / Platform bad75bd0 has completed successfully;
download and audit its exact artifacts next. It predates cancellation. The earlier three-case
41340935053 audit is already recorded. All previous checkpoints below are historical.

## Hosted recovery regression audited; continue cancellation UI — 1 November 2026

[Run 41340935053 audit](measurements/chat-draft-recovery-native-ci-2026-11-01.json) at 1ddfba6e /
Platform 79326538 passes SDK 54.085s, direct 63.689s and scheduled 62.336s, six cleanup cases
and twenty downloaded evidence hashes. APK `cd32c3984577324f919dee9eaa14e0e65bcb881f47fd2544aefc150ad24263c7`.
Ordinary queued/foreground proof covers 33 originals, four history actions, 43 references,
ten state-only and zero admission failures; WorkManager automatically recovers ordinary death
at attempt 1. It contains application draft recovery but does not directly test frozen-only
initial state. New four-case run 41344248284 at 448ab3ad / Platform bad75bd0 is still running;
95964599's pending run was superseded, not a product failure. Audit the completed source.

Queued cancellation policy/storage/application proof is now committed and pushed as 648410c4.
Implement its confirmation UI next using the immediately following checkpoint and
`.work/cancel-ui-next.txt`; then release 10+ install-only and continue the entire PRD. Phone is
still verified release 9. Platform audit d08f07c4 and ledger correction 4d064871 are pushed.
The ledger now points to completed native/draft evidence while retaining all 54 unaccepted
feature groups. No live backend/provider deployment occurred.

## Queued cancellation policy proved; implement confirmation UI now — 1 November 2026

The [cancellation contract](collaboration-cancellation.md) now uses existing full-record archive
CAS to arbitrate against Staging. Cancelled is archive-only with an authenticated version/kind
and distinct purpose; legacy sent proofs remain compatible. Same-ID insertion stays fenced,
Queue replay does not wake, and Stage avoids native acquisition for an already-cancelled ID.
Captured access and complete expected Queued record gate the explicit use case. Expired owned
local access remains usable; confirmed denial/caller cancellation/late ownership stays gated.
Frozen draft recovery clears only that exact cancelled intent, preserving newer drafts and
independent messages. No core schema/Starter business port or native queue erasure.

[Measurement](measurements/chat-cancellation-policy-2026-11-01.json): 56 cumulative inputs,
18 changed Kotlin files, 541 report hashes. Full check passes 348s Gradle / 352.19s wall:
2,827 desktop/436 suites; 401 domain, 263 data, 90 presentation browser; 170 Android host;
six cleanup cases, proof APK/lint (zero fatal/errors, 47 warnings), format/architecture/detekt.
Focused tests: eleven storage, nine policy, three real application SQLite/DI worker cases
plus two Stage cases. The first 143s/146.642s run failed test compilation because assertSame
needed an explicit Any type for two interfaces; corrected only the test and retained evidence.
The worker transport is fake; do not reassign earlier actual native proofs to this source.

**Continue immediately:** commit/push, then implement cancellation UI. Only unmatched owned
Queued/revision-0 rows expose the action. VM retains a complete immutable selected record and
captured access; confirmation invokes CancelCollaborationText, suppresses duplicate clicks,
and retries lost replies using that same selection even if the pending row disappeared.
Changed/started work is unavailable, never success. Scope loss/Stopped/disposal clears private
selection and cancels obsolete waiters; object identity plus access fences uncooperative late
results. Neutral Close wording must not imply undoing a committed cancellation. Keep controls
reachable with long text, 320 dp and 200% font; capture/inspect the separate dialog root at its
actual dimensions. Do not stack draft and cancellation dialogs. Add ViewModel/projection,
semantics/disposal/stale-selection tests, root/browser/Android checks and source-bound evidence.
`.work/cancel-ui-next.txt` retains more details. Then clean release 10+, notify/install-only,
verify the phone and continue native cancellation proof, push/replies and all P0–P8.
Phone remains verified release 9; no cancellation UI is installed yet. No full PRD group accepted.

## Release 9 installed; safe queued cancellation next — 1 November 2026

**Phone updated and verified.** Clean source `448ab3ad` is installed over code 8 at 20:01 UTC
on `10.77.77.3:5555`. Release build passes in 227s Gradle / 231.48s wall; lint zero
fatal/errors and one warning. Arm64 APK 87,314,200 bytes, SHA-256
`acc895f4d7fbb33470678bd0133f1d06edd7d680726c525ac4ca83365bc87db8`. All 41 reviewed source
hashes, embedded VCS, private API/Firebase, existing signer and 16 KiB alignment match.
Install plus verification takes 51.397s. Pulled APK matches exactly, app identity and original
first-install identity remain intact; no uninstall, data clearing or UI launch.
[Installation evidence](measurements/android-release-draft-retry-2026-11-01.json).
Future phone updates require version code 10+, notification first and install-only.

**Continue immediately:** safe queued-only cancellation. Use the existing full-record command
archive CAS so an original ID cannot be resurrected by draft or UI retry. Add archive-only
Cancelled state with authenticated version/kind and purpose-bound proof; preserve legacy sent
completion format and never interpret cancellation as delivery. Ordinary state updates cannot
create Cancelled. Cancel only a complete observed Queued record under captured access and
current local admission. Staging wins or cancellation wins atomically; uncertain native work
is always retained. Queue replay must not wake delivery, Stage must avoid SDK acquisition for
an already-cancelled original. Add SQLite/browser race/reopen/lost-response/key/owner tests,
domain policy and real application worker regression, then confirmation UI with stale-state,
disposal and large-text checks. `.work/queued-cancellation-next.txt` holds detailed notes.
Then incoming push/replies, rich messaging, backups, native/web, calls/meetings and all P0–P8.
No full PRD group or live provider deployment is claimed by this phone checkpoint.

## Draft contention feedback validated; release 9 next — 1 November 2026

Technical shared-partition movement and exhausted three-attempt CAS now return
`client.collaboration_draft_busy`. Composer Retry keeps the immutable write/text and does
not request reload; actual competing-target edits remain changed/reload. Worker discovery
retries both at initial and final boundaries. No encryption, schema or draft format change.

[Evidence](measurements/chat-draft-contention-2026-11-01.json) binds 41 cumulative inputs and
536 reports. Final full integration passes in 190s Gradle / 194.218s wall: 2,802 desktop,
390 domain/252 data/90 presentation browser, 170 Android host, six cleanup cases, proof APK,
lint zero fatal/errors (47 warnings), format/architecture/detekt. Focused storage 18, draft
ViewModel 14 and app worker six cases pass. The initial 202s run failed one leftover test
assertion expecting the old value after newly added successful retry; corrected the expected
replacement and retained the failed inputs/log/XML. This correction required no runtime edit.

**Continue immediately:** commit/push this fix, build clean signed release 9 using
`.work/release-phone-2026-11-01-build9/run-reviewed-build.py`, notify and install-only on
10.77.77.3:5555, then verify the pulled APK and commit/push installation evidence. Phone is
still release 8 until that succeeds. Do not launch or drive its UI. Continue safe queued
cancellation using immutable cancellation archives (`.work/queued-cancellation-next.txt`),
then incoming push/replies and all remaining P0–P8. No full PRD group is accepted.

Hosted native run 41340935053 at 1ddfba6e is still running; 41343165448 at 95964599 is pending
and may be superseded by the next push. Audit the actual completed source. New native CI
uses Platform bad75bd0 and four cases including frozen-only death; the older queued run
does not establish that new boundary. Local actual proof is already committed as 95964599
with its own source hashes and must not be relabelled as validation of this newer fix.

## Native frozen-draft recovery proved; fix contention feedback next — 1 November 2026

[Actual native evidence](measurements/chat-frozen-native-2026-11-01.json) binds 44 Mobile/four
Platform inputs, forty evidence hashes and proof APK `1a6645456001c0c06c1db8ec12ed405c2d9015986ff7366ca9fe32e1b2d2363d`.
Final run 227s/227.82s: four cases/three suites pass. SDK 48.362s, WorkManager 44.822s,
worker pair 110.945s (queued 50.339s/frozen 40.341s methods). Both workers finish in one pass,
with one encrypted original plus 32 peer seeds, four history actions, 43 references/10 state-only,
30/28/30 admissions and zero failures. Frozen-only checkpoints prove zero commands and the
same draft revision/body/ID across real death; matching confirmation clears it. Cleanup
passes and the owned emulator is stopped. Source still matches installed release 8 product
inputs; only fixture changes are newer, so no phone rebuild follows this proof.

Initial failures remain recorded: detekt rejected a 130-line fixture function (now split),
shared checkpoint validation omitted WorkManager PREPARED (restored with regression), and
the second worker case failed at provider login before Android. Its exact failure code was
not captured; provider and Spring trace are now isolated per test and safe kind/code
assertions diagnose future failures. Do not call the original failure an observed 429.
Mobile checks 80s/84.218s pass proof APK/lint/six cleanup cases/quality. Platform checks
28s/28.739s and 66 Python cases pass. Platform `bad75bd0d73c88afa66db7f16493e5e513ba9250` is pushed and
pinned in Mobile CI. Commit/push this companion fixture and audit its hosted run separately.

Prior [CI 41338809672](measurements/chat-draft-index-native-ci-2026-11-01.json) at 500a8edd
passes SDK 60.948s/direct 65.728s/scheduled 75.829s, APK 649f1640, twenty evidence hashes.
It includes indexed storage but predates app recovery; it does not prove frozen-only recovery.

**Continue immediately:** distinguish retryable technical draft contention from a changed
target. Current stable-read checkpoint movement uses client.collaboration_draft_changed,
which makes DraftRetry ask to reload even if only another conversation changed. Introduce
client.collaboration_draft_busy for valid-read movement and bounded technical CAS exhaustion;
keep true target conflicts as changed/reload. Worker retries both; UI retries the immutable
write with its text/ID intact. Add SQLite/browser, app-worker and ViewModel regression cases,
then required integration checks, commit/push and release 9+ install-only at a validated feature
checkpoint. `.work/draft-contention-ui-next.txt` holds details. Continue safe cancellation,
FCM/replies, rich messaging, backups, calls/meetings and all other unaccepted P0–P8 afterward.
No complete PRD group or live provider deployment is claimed. Phone remains release 8.

## Release 8 installed; continue native frozen-draft proof — 1 November 2026

**Phone updated.** Release 8 from clean source `43cce8c0031c310d714890f4912052fe3316cdae` is installed
in place over code 7 on `10.77.77.3:5555` at 19:22 UTC. Build passes in 230s Gradle /
234.022s wall, lint zero fatal/errors and one warning. Signed arm64 APK is 87,314,204 bytes,
SHA-256 `c2858829bcfe87139adde011333369e639f2700d215a652c2b1a569fb1e9f2c4`.
Private API/Firebase, embedded VCS, non-debuggable flags and 16 KiB alignment are verified.
Post-install pull exactly matches bytes/hash; package/signer/application identity and original
first-install identity are preserved. No uninstall, data clearing or UI launch. [Release evidence](measurements/android-release-drafts-2026-11-01.json).
This is the first installed composer-v2 build; do not downgrade to earlier APKs. Future
updates must use code 9+ and notify first, install-only. Helpers/artifacts remain in
`.work/release-phone-2026-11-01-build8`.

**Continue immediately:** actual native frozen-only process-death proof. WIP adds an explicit
FROZEN_DRAFT initial state to the Mobile proof app and Platform driver; pending command count
must remain zero before/after death and the exact draft revision/body/ID must survive. The
new server case retains a separate ordinary QUEUED case and uses isolated Spring trace state.
Python driver 65 cases pass; Kotlin/server compilation and matching proof APK/native execution
are still being completed. These fixture changes were made after release verification and
are not part of the installed APK. Capture source/results, retain failures and commit/push
them after proof. Then safe pending cancellation, push/replies and all remaining P0–P8.
No full communication group is marked complete. Physical iPhone tests remain waived only.

## Hosted native regressions and Starter Apple port audited — 1 November 2026

[Native f904 run 41335935547](measurements/chat-window-native-ci-2026-11-01.json) passes
SDK/direct/scheduled in 53.186s/56.09s/82.571s, APK `85583fca`, eighteen downloaded hashes.
[Native cca28 run 41338026431](measurements/chat-draft-composer-native-ci-2026-11-01.json)
passes in 42.32s/56.409s/59.092s, APK `c8185d69`, twenty hashes including sanitized gateway
observations. Both use Platform `79326538`, six cleanup cases, 33 original-history assertions
and automatic ordinary-death WorkManager recovery. Only the oldest original is individually
asserted after Stop. The prior bf83 exhausted-retry failure remains retained and unexplained.
The cca28 source includes composer-v1 UI; neither fixture exercises draft transition recovery,
and neither source includes current v2/application recovery. New source `1ddfba6e` is pushed.

[Starter Apple run 41338065450](measurements/apple-replica-hosted-2026-11-01.json) at `c46151e7`
passes 86 simulator cases/25 suites, including two actual SQLite replica tests in 0.715s.
Compile/tests/Xcode take 594s/68s/627s; associations pass, portable/Windows intentionally skip.
The fixture matches preceding Mobile proof. Starter audit `fdd7f7b` is pushed. No physical
iPhone execution, live provider deployment or additional phone installation is claimed here.

Continue immediately with clean release 8+ from the validated recovery input hashes, notify
and install-only; then actual native freeze-only proof and the rest of the communications PRD.
The phone remains on verified release 7 until that separate installation succeeds.

## Prepared-draft recovery integrated and validated — 1 November 2026

The application delivery worker now recovers up to eight indexed frozen drafts before draining
ordinary outbox work, using the original body/ID and captured account ownership. Its separate
encrypted cursor advances before attempts and wraps once. Freeze wakes the existing payload-free
task; startup independently wakes it. Confirmed unqueueable contexts retain review-required
originals outside the automatic index, so more than one failed batch cannot starve valid work.
No command is erased. Back flush is bounded to 1,500 ms and keeps an admitted writer alive.

[Contract](collaboration-drafts.md) and [source/report evidence](measurements/chat-draft-recovery-2026-11-01.json)
bind 41 cumulative inputs. Final integration passes in 286s Gradle / 290.427s wall:
2,801 desktop cases/433 suites; 390 domain, 252 data and 89 presentation browser cases;
170 Android host cases, six proof cleanup cases, proof APK, lint (zero fatal/errors,
47 warnings), format/architecture/detekt. Focused counts: six app/SQLite worker cases,
eighteen storage cases, nine recovery policy cases and thirteen draft ViewModel cases.
All report counts have zero failures/errors/skips. Initial full integration also passed
(423s/426.6s, 2,799 desktop); review then identified read contention incorrectly classified
as storage failure. Final tests prove initial/final discovery retries and no stale-page
publication when another conversation advances the checkpoint. Real malformed/key failures
remain blocked. The original passing inputs/log are retained in the final measurement.

**Continue immediately:** commit/push, clean signed release 8+ build from these reviewed
inputs, notify then install-only on 10.77.77.3:5555, verify the pulled APK and continue.
Release 7 is currently installed; nothing newer has yet been built/installed. The prepared
build8 helpers are local under `.work/release-phone-2026-11-01-build8`; run-reviewed-build
checks this measurement and a clean tree. Do not install old versions 5/6/7 over a newer app.

Application recovery tests use the real SQLite/DI/use cases with a fake native transport.
Add actual SDK/server freeze-only process-death proof next; current hosted native fixtures
still exercise ordinary queued recovery. Preserve the existing WorkManager queued scenario
and add an explicitly identified frozen-draft scenario, not a renamed queued assertion.
Then safe pending cancellation, incoming FCM/replies and all remaining P0–P8. No complete
PRD group, generic Starter policy port or new core schema is claimed.

## Indexed prepared drafts validated; app recovery next — 1 November 2026

`composer-v2` replaces the uninstalled v1 prototype with one account/company partition and
one encrypted document per conversation. The prepared tag and frozen body commit together;
indexed reads authenticate up to eight candidates plus one lookahead. Three bounded technical
CAS attempts tolerate unrelated-document checkpoint movement, never overwrite changed target
text. The same protected history key/AAD scope and empty logical revision fences remain.
No core database schema or Starter policy port. Verified installed release 7 has no drafts;
no first draft-capable build has been made or installed.

[Evidence](measurements/chat-draft-index-2026-11-01.json): 113s Gradle / 116.59s wall, fifteen
SQLite cases, 249 data browser and 381 domain cases per JVM/browser, quality checks pass.
603 mixed drafts yield 201 frozen candidates, maximum ten decryptions/page; reopen, atomic
index/body under a lost reply, competing conversations, bounded CAS and forged lookahead are
proved. Initial compilation failure from a nullable cross-module property was corrected and
retained. Full root/Android checks still follow application recovery integration.

**Continue immediately:** add owned indexed draft recovery/cursor use cases, wake the existing
payload-free delivery key after freeze, and have the application worker coordinate Queue and
Confirm before ordinary outbox delivery. Preserve original IDs on lost replies and clear only
matching confirmed drafts. Keep batches/deadlines/fairness explicit; permanently unavailable
individual conversations must not spin or starve others (consider a durable review-required
flag excluded from automatic discovery, with explicit retry retaining the ID). Storage or
scope failures must not erase intent. Then root/browser/Android/proof/lint, release 8+ with
install-only, and remaining PRD. A bounded Back flush/leave choice should also remain usable
while an uncooperative local write is pending; test that boundary during integration.

Native run 41335935547 at f904 is PASS; artifacts are downloading into `.work/ci-41335935547`
for audit. It predates composer UI/v2; do not infer those are accepted. Native cca28 run
41338026431 is in progress and contains controlled retry diagnostics. Starter Apple run
41338065450 at c46151e is compiling tests; Mobile Apple audit is already committed.

## Apple replica execution audited; continue indexed draft recovery — 1 November 2026

[Hosted source/XML audit](measurements/apple-replica-hosted-2026-11-01.json) proves run
41334974289 at Mobile `c0e91d6b`: 106 cases/29 suites, zero failures/errors/skips, including
two real SQLite replica cases in 1.047s. Compile/tests/Xcode take 628s/83s/941s; simulator
application and generated association checks pass. This source predates every draft addition.
The exact temporary-database fixture and two CI tasks are now ported to Starter after its
local quality checks. Starter's own hosted proof is being dispatched; audit it separately.

Composer commit `cca28f06` is pushed with focused UI/browser/DI checks, not root/Android
acceptance yet. Continue indexed prepared-send storage and application-owned recovery from
the preceding handoff, then all required integration checks and a newer install-only release.
The phone remains on verified release 7; no additional installation occurred for test/docs work.

## Composer connected and focused recovery tests pass — 1 November 2026

[Composer evidence](measurements/chat-draft-ui-2026-11-01.json) binds 14 inputs and the inspected
320 dp/200% text screenshot. 215s Gradle / 218.71s wall passes 102 JVM presentation cases,
88 browser cases and two application-binding cases plus format/architecture/detekt. Twelve
new scenarios cover loading, coalescing, uncertain edits, late emissions, recreation at
freeze-before-queue and queue-before-ACK, lost clear response/newer draft, invalid input,
explicit navigation/reload choices and hidden ownership loss. Initial 67s attempt had one
old assertion expecting thread failure; the composer-specific error assertion and localized
validation guidance were corrected. That failure is retained in the measurement.

One serial writer owns autosave. Sending flushes the latest text, persists original ID/body,
queues and confirms exact durable command/archive. Known frozen IDs skip re-preparation on
retry so a later clear/new draft cannot cause another insertion. Successful observer values
initialize once; they never overwrite active text. New input and immutable uncertain write
are separate. Back/Setup flush edits, with in-product confirmation on unsaved exit/reload.
**No new phone build:** release 7 remains installed. Full root/Android checks follow the
pending indexed recovery integration; focused tests are not full application acceptance.

**Continue immediately:** implement bounded indexed prepared-draft discovery and app-owned
Queue/Confirm recovery before the first draft-capable release. Preferred design is one
account/company replica partition with a document per conversation, authenticated prepared
tags committed atomically with the frozen body, and existing indexed queries; no new core DB
schema. The current per-conversation v1 has never been in the verified installed APK. Preserve
empty revision fences and complete-target CAS, handle unrelated-conversation checkpoint
contention with bounded technical retry, retain key-loss/ownership protections, and prove
603 mixed drafts plus freeze-only worker restart. `.work/chat-draft-index-next.txt` contains
concrete design notes. Do not use a separate non-atomic index or decrypt every conversation.
Then full root/browser/Android/proof/lint, signed release 8+ install-only, and all remaining PRD.

Mobile Apple run 41334974289 at `c0e91d6b` is now audited PASS: 106 cases/29 suites (two new
SQLite cases, 1.047s), compile 628s, tests 83s, Xcode 941s. Its fixture/CI step is being ported
to Starter, whose local quality check is running. Commit separate Apple evidence and dispatch
Starter hosted execution. Native f904 run 41335935547 was compiling server fixtures; diagnostic
bfbb run 41337171686 is pending. Keep audits tied to immutable sources; neither covers this UI.

## Failed native retry diagnostics preserved — 1 November 2026

[Audit and follow-up](measurements/chat-window-ci-failure-2026-11-01.json) retain run 41334029287
as failed, with SDK/scheduled passes and unknown direct delivery. The direct probe now throws
the final controlled HrisProofFailure category instead of a generic exhausted check. Room,
history, delivery and timeout aliases are bounded; retry count/waits are unchanged. Native CI
uploads the already-sanitized `gateway-observations.json` written by Platform's finally block.
Validation: 190s proof assembly, six cleanup tests, format/architecture/detekt pass; YAML parsed.
No runtime fix or new acceptance is claimed. Push and audit a matching hosted run while
continuing composer UI/recovery. Latest product APK remains installed release 7.

## Draft transition policy validated; composer wiring next — 1 November 2026

Five new domain use-case/helper files and twelve scenarios implement owned Observe/Edit/
Prepare/Confirm. Local age does not hide drafts; denial/cancellation/late owner checks bracket
I/O. Editing retries its caller-retained revision; freeze uses the prior editing revision as
submission ID and a fresh draft revision. Confirmation requires exact durable command or
archived completion and preserves newer text. [Evidence](measurements/chat-draft-policy-2026-11-01.json):
75s Gradle / 78.44s wall; 381 JVM and 381 browser cases in 32 suites each, quality checks pass.
No UI/DI integration yet. Release 7 is still installed. Continue immediately with a bounded
composer save owner, process reopen/retry, explicit prepare-to-outbox crash recovery, then all
remaining PRD work. Do not treat this policy checkpoint as completion.

Hosted native 41334029287 at `bf83c71e` **failed**: SDK 47.387s and scheduled 56.862s pass,
direct worker 163.103s exhausts four bounded attempts at HrisWorkerMatrixProbe:184 before
foreground assertions. All use proof APK `8d391c22`; driver cleanup true. Final retry reason
was lost to a generic check, so direct delivery is unknown. Artifacts/log are retained under
`.work/ci-41334029287`; audit/record and add bounded controlled retry diagnostics before
investigating/re-running. Apple 41334974289 at `c0e91d6b` was building its simulator app;
compile passed, test execution has not yet been audited. Do not port its fixture before proof.

## Composer storage validated; policy/UI next — 1 November 2026

[Draft storage](collaboration-drafts.md) adds eight domain/data/test inputs: immutable draft
slots, validation, raw repository contract and encrypted atomic snapshots under each history
partition's `/composer` suffix. Existing history credentials/AAD isolation protect all hosts;
no new key wiring. Empty revisions fence late writes; complete-record CAS and exact replay
survive lost replies. Get/replay recheck the checkpoint after decryption, and inactive
composer-only data prevents key regeneration. Maximum UTF-8 text uses a document, not the
smaller checkpoint. [Evidence](measurements/chat-draft-storage-2026-11-01.json): 101s focused
SQLite/browser/quality checks, ten new storage scenarios on each engine, no failures.
This is storage only; no user flow or new phone build yet. Root/application checks follow
its actual integration. Release 7 remains installed.

**Continue immediately:** add owned Observe/Edit/Prepare/Confirm draft use cases and UI.
Prepare can use the prior editing revision UUID as its stable message ID and a fresh draft
revision, so a lost freeze response can match `current.submissionId == expected.revision`
without conflating a later identical-text draft. Freeze the exact body before queueing;
confirm against `messages.get`, which already reads authenticated archived completions.
Clearing must preserve a newer draft; explicit retry reuses the saved ID. Bound save work,
retain dirty input across late observations, and test failure/account/cancellation races.
Decide/implement prepared-send recovery across the prepare-to-outbox crash gap; never claim
ordinary queued-worker recovery proves that separate boundary. Then safe pending cancellation,
incoming push/replies and all P0–P8. No generic Starter port for this HRIS storage policy.

Apple CI [41334974289](https://github.com/fajarxfce/hris-mobile/actions/runs/41334974289) runs at
`c0e91d6b` and was compiling simulator tests. Audit its exact XML before porting the new
iosTest/CI step to Starter. Native [41334029287](https://github.com/fajarxfce/hris-mobile/actions/runs/41334029287)
was executing emulator cases at `bf83c71e` (the installed release source). Both run against
immutable earlier sources; do not attribute them to this draft addition.

## Apple replica execution prepared — 1 November 2026

`AppleReplicaStoreTest` adds two isolated real Room/BundledSQLite cases under iosTest.
A UUID-named temporary directory keeps the application database untouched. Shared 603-record
reverse queries, publication/account/cancellation/bounds and close/reopen/reauthentication
are covered. Mobile CI now explicitly links and runs `core:database` simulator tests.
[Inputs/checks](measurements/apple-replica-2026-11-01.json): 34s local formatting/architecture/
detekt and parsed workflow pass. **No native execution result yet:** dispatch Apple CI at
this exact pushed source and audit the resulting XML before porting the new test/step to
Starter. No production change; release 7 remains installed. Continue durable draft/submission
recovery while hosted checks run, then all remaining P0–P8; do not stop at this fixture.

## Release 7 installed; hosted foreground proof audited — 1 November 2026

**Phone is updated.** Release 7 from Mobile `bf83c71e1688ce5f57ba1dad39f95900b82f8276`
was installed in place over code 4 on `10.77.77.3:5555` at 18:02 UTC. Versions 5/6 were
never installed. Build: 249s Gradle / 253.35s wall, clean before/after, private API/Firebase,
16 KiB alignment and embedded VCS verified; lint zero fatal/errors and one warning.
APK is 87,264,680 bytes, SHA-256 `1ab847df1e169cfaf5563296159d772148896e193aaa5cc1c969b05ba1f4a47b`.
Preflight confirmed the existing signer. Post-install pull matches exact bytes and confirms
version 1.0.0/code 7, non-debuggable, same application identity and first-install identity.
No uninstall, data clearing or UI launch. [Release evidence](measurements/android-release-window-2026-11-01.json).
The build7 helpers/artifacts are retained under `.work/release-phone-2026-11-01-build7`.
Do not install older prepared builds. Notify before each future install; keep install-only.

Native hosted [run 41331799148](https://github.com/fajarxfce/hris-mobile/actions/runs/41331799148)
is fully audited: Mobile `2b2d81b3`, Platform `79326538`, APK `d94ba860`, Android API 35.
All three cases pass: SDK 56.228s, direct worker/foreground 67.626s, scheduled worker 90.851s;
six cleanup unit cases also pass. Direct recovery verifies 33 encrypted originals, four
history actions, 43 references/ten state-only references, zero admission failures and complete
cleanup. The oldest original is explicitly retained after Stop; all 33 are checked before
Stop. Scheduled recovery keeps the OS job through ordinary death, starts automatically,
restores networking and finishes SUCCEEDED. [Audited 18 files](measurements/chat-foreground-ci-2026-11-01.json)
retain the earlier run 41325612922 ClosePending failure and its unknown direct delivery result.

This hosted result precedes the new 150/50 window: matching native run 41334029287 at
`bf83c71e` is still separate. Starter port `cd7022e` is pushed. No live server deployment.
**Continue:** apply `.work/apple-replica-test.txt` to an isolated iosTest and wire Mobile CI,
execute hosted simulator proof before porting that additional test/step; then durable drafts,
exact submission recovery, safe cancellation, incoming push/replies and every unaccepted
P0–P8 group. Physical iPhone tests remain waived. The prepared `.work/chat-drafts-next.txt`
is guidance only. Do not end the long run at this installed checkpoint.

## Bidirectional local history beyond 500 originals — 1 November 2026

The ten-page stop is removed. One coherent encrypted window reads at most 150 originals
plus one authenticated lookahead, retaining 50 overlapping keys on each move. Older/Newer
can traverse the 603-original SQLite/IndexedDB fixture in both directions across reopen;
there is one active observer and no growing cursor stack. Reaching the head resumes its
live query. Old windows exclude unrelated pending/live rows while matching edits/redactions
remain visible. Sending returns to the head after queue success and publication. Drafts
survive window navigation only; process-durable composer work remains next.

[Contract](collaboration-local-window.md), [measurement](measurements/chat-history-window-2026-11-01.json)
and [inspected screenshot](images/chat-thread/chat-thread-window-large-text.png) bind 30 inputs.
Final 595s checks pass root/quality/browser/Android-host/proof APK/lint: 2,742 desktop cases
in 428 suites, 369 domain / 234 data / 76 presentation browser cases, 170 Android-host and
six cleanup cases, zero failures/errors/skips. Proof lint has zero fatal/errors and 47 warnings.
The initial 93s attempt passed domain/data but failed the new lazy-list test's scroll action;
its reports/source are retained, and the corrected UI test covers 320 dp/200% text, stable
overlap and interruptible return to latest. Proof APK `8fc2abc6` is built, not installed.

Eight reusable raw-query files match the Starter port exactly. Both pass real SQLite and
IndexedDB checks; Starter's independent 268s run passes 341 desktop / 35 browser cases,
Android/lint (zero errors) and production Web. [Portable evidence](measurements/replica-reverse-query-2026-11-01.json).
No schema migration or HRIS policy was copied. Apple has no new simulator result for this
increment: add an isolated database iosTest and execute it in Mobile CI before porting that
additional wrapper. The existing physical-iPhone waiver does not remove this verification.

**Continue the long run.** Build release 7 from the clean pushed tree using the prepared
`.work/release-phone-2026-11-01-build7/run-reviewed-build.py`, verify its artifact and deliver
with preflight/install-only when phone `10.77.77.3:5555` returns. Notify before install; preserve
signer/data and never drive the phone UI. Release 6 remains ready/uninstalled, last confirmed
installed code is 4 and the reconnect question is pending. Do not reuse build6 install helpers
for release 7. Latest ADB attempt still timed out. No live service was changed.

Audit native CI run 41331799148 (Mobile 2b2d81b3 / Platform 79326538), currently executing
its emulator cases; it precedes this larger window. Earlier 41325612922 failed direct-worker
ClosePending while SDK/scheduled passed; downloaded artifacts and a bounded audit are in
`.work/ci-41325612922` and `.work/native-ci-predecessor.json`, awaiting the companion hosted
record. Keep every failure boundary. Next implement encrypted durable composer snapshots,
exact submission-ID recovery and safe cancellation, incoming push/replies and all remaining
P0–P8. `.work/chat-drafts-next.txt` is design guidance, not implemented functionality.

## Actual foreground history and server key-publication race — 1 November 2026

The native fixture now resolves the real ChatThreadViewModel after application-worker
recovery. It sends 32 encrypted peer seeds, confirms the oldest original is initially
absent, pages provider history, verifies all 33 originals and retains the oldest row
after Stop. Cleanup drains the existing close contract and preserves primary failures;
six unit tests cover bounded retry, all-resource cleanup and cancellation. Product
shutdown deadlines are unchanged. No forced peer key refresh remains in the fixture.

[Contract](collaboration-native-worker.md) and
[measurement](measurements/chat-foreground-native-2026-11-01.json) bind 10 Mobile/13
Platform inputs and all failed attempts. The same APK `d685231d` failed before and
passed after Platform's schema-114 publication correction: 94s, four older actions,
43 references, ten state-only references, no unresolved message references, admissions
30/28/30 with zero failures and complete cleanup. SDK/scheduled regressions pass in
120s combined (55.826s/46.057s test time): three/one encrypted sends, held room isolated,
automatic ordinary-death startup and restored network settings. These are local results;
new hosted artifacts still require their separate audit.

Platform `79326538942e83ef89bf6a2b4c251888db7fdd6d` is pushed and pinned by native CI.
Read-only discovery verifies provider keys against the prior immutable reservation;
room send, one-time claims and to-device send still require publication confirmation.
Final server checks: 110 registration/crypto-admission and 26 API cases pass, with
schema-114 upgrade/restore and recovery-gate evidence retained. The broad run's three
failures and their correction remain recorded. Mobile's final 305s run passes APK,
lint, formatting, architecture, detekt and root checks: 2,733 desktop cases/427 suites
plus six cleanup tests, zero failures/errors/skips. CI now runs and uploads cleanup
unit XML alongside all three native cases.

**Continue, do not stop at this checkpoint.** Push the companion commit and audit
hosted CI, then bounded local browsing beyond ten pages, durable drafts, safe pending
cancellation, incoming push/replies and all remaining P0–P8. No complete PRD group,
Apple/web acceptance, live deployment or Starter port. The emulator is stopped.
Release 6 remains prepared but uninstalled: latest WireGuard ADB attempt timed out,
reconnect question is pending and last confirmed installed code was 4. Use the build6
preflight/install-only helpers, notify before install, preserve signer/data and never
drive phone UI. Product release source remains `2d6753ee`; this increment's Mobile
changes are fixture/test infrastructure.

## Release 6 ready; three hosted native cases audited — 1 November 2026

Latest prepared private APK is now release **6**, superseding the uninstalled release 5:
`.work/release-phone-2026-11-01-build6/hris-mobile-dev-release-1.0.0-6-arm64.apk`.
SHA-256 `63cba8dccec9c50e73b13f39f4645304b98cec7b84d79b8091f5f5e9299d27ba`,
87,264,632 bytes, package `dev.fajar.hris.mobile.dev`, version 1.0.0/code 6. It is non-debuggable,
non-testOnly, arm64-only and ZIP-aligned at 16 KiB. API/Firebase, the previous installed
signer and embedded VCS commit `2d6753ee64f4616adec6ad05783ad2d197a535e9` match. Source
stayed clean before/after the 295s Gradle build (299.203s wall). Lint: zero errors/fatal,
one existing DataExtractionRules warning. [Release evidence](measurements/android-release-history-2026-11-01.json).

**Not installed.** Phone `10.77.77.3:5555` still times out; reconnect question pending,
last confirmed installed code remains 4. Updated `preflight.py` and `install.py --install-only`
are in the build6 directory. Notify before installation, verify the current signer/version
and exact identity, then verify pulled APK bytes and preserved data identity. Do not reuse
build5 helpers, uninstall, clear app data or drive the phone UI. Continue work independently.

Hosted native run [41322946959](https://github.com/fajarxfce/hris-mobile/actions/runs/41322946959)
succeeded and downloaded evidence is audited in [this record](measurements/chat-native-ci-2026-11-01.json).
It binds Mobile `1a114442`, Platform `075e63db` and one proof APK `9a42e773` on Android API 35:
scheduled case 70.507s, SDK case 61.405s, direct worker 27.600s. Three/one/one encrypted sends
respectively for SDK/direct/scheduled, zero admission failures, full cleanup; scheduled
job retained its namespace/CONNECTED/task-only input through ordinary death and restarted
automatically with the sender closed, then WorkInfo SUCCEEDED. Radio settings were restored.
This does not include foreground paging from `2d6753ee`, deliberate retry/cancel races or
capacity acceptance. Earlier run `41319810722` still failed worker cleanup with ClosePending;
a later successful run does not erase or diagnose that failure.

**Next: continue, do not stop at this release checkpoint.** Implement/validate bounded native
fixture close draining and primary-error preservation, then actual foreground history via
the real app ViewModel/container in the native worker scenario. `.work/native-foreground-next.txt`
is supplementary design only, not implemented. Extend local browsing beyond ten pages,
then durable drafts/cancellation and all remaining P0–P8. Review explicit Android 12+ backup
extraction exclusions as a separate reusable foundation change, prove in Mobile before porting
to Starter. No live backend/provider or Starter change was made here. Push each atomic commit.


## Foreground remote history validated — 1 November 2026

Older now coordinates retained reference reconciliation, one admitted provider page and at
most two original windows on the screen-owned signal consumer. Local pages remain first;
only potential message events become native anchors or missing-content notices. Unknown
references remain durable without starving later pages. Provider exhaustion is separate
from decryption; concurrent provider growth/replacement resets the scan. Separate loading
and failure state preserves the composer/local history, and exact-anchor capacity recovery
uses explicit admitted reopening. Stop/account denial prevents late publication.

[Contract](collaboration-remote-history.md), [measurement](measurements/chat-remote-history-2026-11-01.json)
and [screenshot](images/chat-thread/chat-thread-history-large-text.png) bind 18 source inputs.
Final root/browser run: 220s; 2,733 desktop cases/427 suites, 368 domain-browser cases,
70 presentation-browser cases, 82 presentation-desktop cases and 170 Android-host cases,
all with zero failures/errors/skips. The image was visually inspected at 320dp and 200% text.
Reader checks passed in 117s; first UI checks passed behavior/DI but failed the 100-line
method rule (101 lines). Extracting the screen-owned consumer fixed that. Full root/browser
then passed in 260s before two final review tests/corrections. The initial wrong Gradle task
name failed before tests. Every attempt is recorded; source hashes match the final run.

**Continue the long run; this is a bounded foreground increment, not completion.** The
existing local window still stops at ten 50-message pages: implement bounded sliding or
bidirectional browsing. Add actual SDK/provider foreground paging evidence; the new
orchestration currently has real use-case/repository-fake tests plus Compose layout checks.
Then durable drafts, safe pending cancellation and all remaining P0–P8. Starter and live
backend/provider are unchanged. No whole PRD group is accepted.

Before broadening the next feature, prepare the updated phone release and address native
fixture cleanup: hosted run `41319810722` passed the SDK scenario but failed direct-worker
instrumentation with `ClosePending` at `HrisWorkerProofContainer.close`. Driver cleanup
succeeded; it never read the worker result after instrumentation failed, so delivery cannot
be inferred from this failure. The lease-level proof helper already allows six bounded
close-pending waits; the application container currently asserts only one. Preserve the
primary failure if cleanup also fails. Run `41322946959` at `1a114442` is still active in
native tests; audit its terminal artifacts. Do not relax product ownership/drain guarantees.

Release 5 remains prepared but uninstalled; `10.77.77.3:5555` still timed out at 16:15 UTC.
The reconnect question remains pending. Prepare a higher validated build for these UI changes,
then notify and install-only with the saved signer/data preflight when the phone returns.
No phone UI interaction, uninstall or data clear. Push each validated atomic commit.


## Actual Android scheduling validated — 1 November 2026

The normal proof Activity now queues through AndroidWorkScheduler while the owned emulator
is offline. Read-only checks bind the exact WorkSpec, production worker class, CONNECTED
constraint and task-key-only input. The driver backgrounds the Activity, kills its exact
PID without force-stopping the package, and confirms the system job remains registered.
Restoring connectivity causes Android to start a new process and run the actual registered
worker; no instrumentation or manual job-run command starts it. The sender is closed at
bootstrap, then restored by the worker. Actual WorkInfo SUCCEEDED, original text/history,
archived completion and one encrypted gateway event establish delivery.

[Contract](collaboration-workmanager.md) and [measurement](measurements/chat-workmanager-2026-11-01.json)
bind six Mobile/four Platform inputs. Scheduled run: 76s, two processes/one ordinary death,
automatic startup, one encrypted send, zero admission failures and complete cleanup/network
restoration. Both prior native scenarios pass in a combined 112s on the same APK `3b5eb0d5`:
four-process/three-death sender recovery (three sends, held room never sent), and direct
worker recovery (one send and peer decryption). All 62 Python cases pass. Initial Android
build 97s, owner-initialization review build 79s, final build/lint/quality 85s; server compile
28s; root checks 57s with 2,710 desktop cases/426 suites and no failures/errors/skips.
The first native run failed because the fixture queried the default JobScheduler namespace;
actual WorkManager jobs use a dedicated namespace on Android 14+. Namespace-aware inspection
fixed the fixture. Every attempt is retained. Emulator is stopped.

**Continue remote older history, durable drafts and safe pending cancellation now, then
all remaining P0–P8.** Scheduled execution is accepted only for this scenario. Deliberate
interruption while sending, retry/cancellation races, production login recovery, incoming
FCM/replies, Apple/web runtime and scale remain separate criteria. No complete PRD group is
accepted. No live provider or Starter change. The native CI workflow now pins Platform `075e63dbdc0271ebf00bea4fd9756c7441408328`, runs all three scenarios
and uploads only bounded checkpoint/trace/result evidence. YAML/target checks pass; audit
the matching hosted run after push before claiming its acceptance. The older sender-only hosted run `41319561027` reports success; the two-
scenario run `41319810722` is running. Audit artifacts before claiming hosted equivalence.

Release build 5 remains prepared but uninstalled because WireGuard ADB is unreachable; the
reconnect question is still pending. Use the saved preflight/install-only helpers when the
phone returns, preserving signer/data and avoiding UI interaction. Continue independently
while unavailable and push every validated atomic commit. The implemented WorkManager
proof supersedes the earlier `.work/scheduled-proof-draft` design files.


## Release build 5 prepared; phone connection pending — 1 November 2026

The user asked to install progress and continue. The selected private `devRelease` APK is
`.work/release-phone-2026-11-01/hris-mobile-dev-release-1.0.0-5-arm64.apk`, SHA-256
`ab31489690b4d311665e0005878a7e221ee4b1ebba2f2aadb2964beb5d660036`, 87,248,188 bytes.
Package `dev.fajar.hris.mobile.dev`, version 1.0.0/code 5, non-debuggable, correct WireGuard
API/Firebase configuration and the existing signing certificate are verified. Its DEX
matches the universal release; only Crashlytics VCS resource metadata differs. APK ZIP
alignment passes at 16 KiB. Product sources match validated `4213d5f5`; the embedded Git
revision is `ccc7c4fc`. Root tests: 2,710/426 suites, zero failures/errors/skips. Release
lint: zero errors/one existing warning. First packaging failure, successful retry and
44-second arm64 assembly/lint are retained in the [measurement](measurements/android-release-progress-2026-11-01.json).
The [private update recipe](android-private-device-release.md) documents the procedure.

**Not installed yet.** `10.77.77.3:5555` still times out; the reconnect question is pending.
Last confirmed phone version is 4. When available, notify before installation, run the
saved preflight (including actual arm64 ABI/current signer/version), then install-only
and verify pulled APK hash plus preserved app ID/firstInstallTime. Helpers are under
`.work/release-phone-2026-11-01/`. Do not uninstall, clear data or drive the phone UI.
The live backend/provider are unchanged; this is not full communications acceptance.
Continue WorkManager and the remaining plan while the phone is unavailable.

Native worker CI at `55d5e15e` is queued as run `41319810722` behind the prior sender-only
run. Audit its terminal result/artifacts later; local native proof is already recorded
below. Do not claim the new CI run has passed until verified.


## Actual native application worker validated — 1 November 2026

The separate proof app now resolves the real application worker and registered SyncTask
through `apps.shared`, using real encrypted Android stores, protected SDK records and
HRIS/Synapse HTTP. Queued exact text/UUID survives a confirmed process death. The second
process proves its sender remains closed until the worker restores it; the peer decrypts
the same content, original history is acquired, completion retired and another worker
pass sends nothing. The gateway confirms exactly one encrypted send and live admission.

[Contract](collaboration-native-worker.md) and [measurement](measurements/chat-native-worker-2026-11-01.json)
record seven Mobile/four Platform source hashes, all failed/corrected attempts and both
actual SDK runs. The new worker test passes in 70s (two processes/one death/one send).
The prior sender regression passes in 78s (four processes/three deaths/three sends,
held room never sent, 22/22/22 admissions, zero failures). Both use proof APK `924cdd45`.
All 51 Python driver cases pass. All root checks completed: 2,710 desktop cases across
426 suites, zero failures/errors/skips. The first combined invocation then failed in
release APK packaging; an unchanged-code release-only retry passed in 62s. Release lint
has zero errors and one existing warning. That packaging failure's underlying cause was
not present in its log; do not relabel it a diagnosed cache issue. Emulator is stopped.

**Continue actual WorkManager lifecycle evidence now, then all P0–P8.** This scenario
invokes the worker directly; it does not accept platform scheduling or automatic restart
after force-stop. `.work/workmanager-native-next.md` and `.work/scheduled-proof-draft/*.txt`
are unimplemented design/drafts for a normal proof Activity, actual Android scheduler,
offline constraints, ordinary PID death and OS job startup. Do not count those as tests.
The native HRIS workflow now pins Platform `784fc9113a4f09fd1ed9bff7541479088263aca5` and runs both
SDK/sender and application-worker scenarios. It also triggers on apps.shared changes and
uploads only bounded evidence, never fixture credentials. YAML/target/artifact checks pass;
audit the hosted run after push before claiming CI acceptance. Remote history, drafts/cancel, push/reply, media/actions, key continuity/backups,
Apple/web, calls and meetings remain. No complete PRD group is accepted; Starter unchanged.

User additionally requested progress builds installed while the long run continues.
WireGuard phone `10.77.77.3:5555` is currently unreachable; an async reconnect question is
pending. Release build 5 is prepared/verified locally; installation has not started and
the last confirmed phone version remains 4. Use the preserved signing certificate and
install-only after explicit serial/signature/version preflight. Never drive the phone UI.
Keep implementing independently and push each validated atomic commit.


## Latest-head history recovery validated — 1 November 2026

`ReopenCollaborationHistory` now accepts an optional event anchor. Without one, it reads
and validates the complete owned SDK head, re-admits the exact ready room, then performs
one existing capacity recovery. An absent head neither closes the runtime nor acknowledges
anything. Owner/company/room changes and cancellation prevent late shutdown/restoration.
Known-anchor behavior is retained. The worker can recover a lost ACK when the native focus
cache is full without another enqueue/resume; original evidence still controls completion.
Initial runtime-opening/close-pending contention now backs off instead of blocking work.
Explicit thread Refresh on focus capacity releases the old child, performs the admitted
reopening and opens a new child while retaining owned local content; stopping prevents
late restore/publication. No automatic bootstrap, enrollment or screen-owned send.

[Contract](collaboration-history-recovery.md) and [measurement](measurements/chat-history-recovery-2026-11-01.json)
record nine matching source inputs. Focused checks pass in 169s, full checks in 240s:
2,710 desktop / 426 suites, 358 domain browser, 58 presentation browser, 170 Android
host, 14 worker integration and two binding cases, with zero failures/errors/skips.
All format/architecture/detekt and relevant Android/Wasm builds passed. Android lint:
zero errors and one existing warning. APK `98cd76d4`, AAR `0bba8390`, proof APK `75a576d1`
are built, not installed. An initial fixture formatting error stopped before Gradle and
was fixed; Android's incremental DirtyData/QueueCollaborationText cache error successfully
fell back to compilation. Six new domain, two worker and two thread cases passed.
No actual SDK/server/WorkManager/emulator, Apple runtime, phone/live deployment or Starter
change is claimed by these simulated-provider integration tests.

**Continue actual native worker interoperability now, then all remaining P0–P8.**
[Next work](collaboration-delivery-next.md) preserves the earlier four-process/three-death
provider proof as separate evidence. `.work/native-worker-proof-plan.md` has supplementary
composition notes: reuse the actual worker/application container with real encrypted
Android stores and real HRIS/SDK adapters, seeded fictional owner, task-key-only scheduler,
process death after Queued persistence, and exact original/retirement evidence. Do not
restore the sender before the worker; verify the worker restores it. A direct native
worker proof still needs separate WorkManager lifecycle acceptance. Remote paging,
drafts/cancel, push/inline replies, groups/rich actions, keys/backups, Apple/web adapters,
calls and meetings remain. No complete PRD group is accepted. Keep the authorized long
run moving and push every validated atomic commit.


## Application-owned background delivery validated — 1 November 2026

Text delivery now belongs to a registered singleton application SyncTask. Exact local
queue acknowledgement survives failed/bounded scheduling; task payloads contain only
`hris.collaboration-delivery`. Startup/owner renewal wakes the task. Local selected-company
discovery validates owned commands and an encrypted restart-safe CAS cursor provides
round-robin room turns. Worker passes are bounded to 60s / 12s per room / eight staging
candidates. It replays exact room operations, requires explicit failed-room retry, acquires
original evidence before uncertain native replay, and retires only proven completion.
Confirmed-only work does not resume native sending. Blocked rooms do not halt healthy
remaining batches. Cancellation and owner loss release late child timelines.

The thread now queues/observes and wakes delivery; it no longer stages, sends, reconciles
receipts or retires messages. Explicit failed-room retry persists control intent for the
worker. [Contract](collaboration-background-delivery.md), [measurement](measurements/chat-background-delivery-2026-11-01.json)
and [next implementation](collaboration-delivery-next.md) describe the exact boundary.

All 32 source inputs match. Full validation passes in 320s: 2,700 desktop / 426 suites,
352 domain browser, 233 data browser, 56 presentation browser, 170 Android host,
12 worker integration cases and two binding cases, with zero failures/errors/skips.
Format, architecture, detekt, app Wasm, Android APK/AAR and proof APK passed; Android lint
has zero errors and one existing warning. APK `4e47ea8c`, AAR `97a1280f`, proof APK
`17ad74cc` are built, not installed. All eight attempts are retained, including corrected
fixture contract, type and function-length failures and an earlier successful compiler
cache fallback. Worker tests use real encrypted repositories/SQLite with simulated wire
and SDK; container restart is not actual Android process-death evidence. No new native
server/emulator, Apple runtime, phone/live deployment or Starter change is claimed.

**Continue latest-head capacity recovery and actual native worker interoperability now,
then all P0–P8. Do not stop here.** Known Sent anchors support one capacity reopening;
uncertain work without a known event anchor still needs an admitted latest-head recovery
path. Drafts `.work/reopen-history-latest.txt` and `.work/reopen-history-latest-tests.txt`
are not applied or tested. Also handle repeated foreground incoming-history capacity.
Then extend the owned Android proof using the actual worker, preserving earlier provider
proof assertions. Remote paging, durable drafts/cancel, incoming notifications/inline reply,
groups/rich content/actions, cross-family keys, Apple/web adapters, calls and meetings
remain. No complete PRD feature group is accepted. Keep pushing each validated atomic
commit during the authorized long run. Other running Gradle daemons belong to experiment
or CodePush work; do not kill them while managing this repository's build memory.


## Owned text thread and application routes validated — 1 November 2026

Chat now opens from the company-gated Home service into inbox, participant selection,
thread and explicit device setup. The thread reads owned cached context/history before
live setup; queueing retains exact UUID/text across uncertain storage responses. Its
projection merges original history, durable commands and native presentation only with
trusted sender/content or complete native device/transaction evidence. New timeline
source metadata binds family/device/bootstrap/room, and entry preserves shared company
denials through cancellation. Local observers clear hidden content after scope loss.

Foreground entry restores an existing runtime; pending messages can request a room with
retained control IDs, finite readiness polling, exact staging/delivery and acquired-history
receipt recovery/retirement. Confirmed-only queues do not reopen delivery. A destination
owns only the child timeline and its cancellable work; the application owns the parent.
The compact multiline composer stays below the reversed list and handles safe/IME insets.
[Contract/screenshots](collaboration-thread.md), [measurement](measurements/chat-thread-2026-11-01.json)
and [next delivery implementation](collaboration-delivery-next.md) are committed.

All 30 source inputs match. Focused checks pass in 88s; full root/browser/Android checks
in 232s: 2,660 desktop / 421 suites, 332 domain browser, 55 presentation browser,
170 Android host, zero failures/errors/skips. Lint: zero errors and one existing warning.
New tests cover 12 domain, 10 projection, 13 ViewModel and 3 desktop layout cases.
All six attempts, including the corrected confirmed-only refresh and function-length
failures, are recorded. Fictional English/Indonesian 200% screenshots were opened and
reviewed. APK `2339d0a5`, AAR `bfab470b` and proof APK `52633bd5` are built, not installed.
No new actual-server/emulator/Apple runtime, phone/live deployment or Starter change.

**Continue platform-scheduled delivery now, then all remaining P0–P8. Do not stop at
this checkpoint.** Use existing SyncTask/WorkManager/ForegroundSyncWorker; the scheduler
stores only the task key and durable encrypted commands remain authoritative. The next
increment needs queue wakeups, owned company-wide local work discovery, bounded worker
orchestration, startup/ownership recovery and cancellation/retry tests. Remove route-owned
transport once the worker owns it. `.work/collaboration-delivery-discovery.txt` is only a
pending draft, not applied or tested. Local Older currently covers acquired history only;
drafts are volatile. Pending cancellation, remote paging, groups, rich actions/media,
notifications/inline reply, cross-family crypto continuity, Apple/web runtime adapters,
calls and meetings remain. No full PRD feature group is accepted. Keep pushing each
validated atomic commit during the authorized long run.


## Trusted local sender identity validated — 1 November 2026

`ReadCollaborationIdentity` now reads owned cached identity and can explicitly acquire
it from the initialized protected SDK record. Android opens only a short-lived protected
credential handle; no client, directory, sync or network is acquired and no credential
is rewritten. Handle cleanup covers cancellation/failure. The identity cache uses a
separate authenticated payload under `collaboration-history/self/<company>` and the
existing protected history key. First-write or exact replay only; changed provider
identity requires migration. Existing key-loss protection includes identity-only records.
Local account identity survives age and native-family replacement; native crypto keys
remain family-scoped. [Contract](collaboration-sender-identity.md) and
[measurement](measurements/collaboration-sender-identity-2026-11-01.json) retain 29 matched
source inputs and all attempts. The existing local owner capture was extracted and its
control tests still pass. Application DI resolves the new repository/use case.

Focused checks pass in 162s; full checks in 203s. Reports: 2,622 desktop /
417 suites, 320 domain browser, 226 data browser and 170 Android host, zero
failures/errors/skips; Android lint has zero errors and one existing warning. Seven
new storage cases run on both real SQLite and IndexedDB, nine new domain cases and
five new Android host cases pass. Initial attempts had an incorrect task name and an
incorrect expected CAS result; both are recorded. APK `51944ab4` and proof
APK `fe5b22b5` are built, not installed. No new actual-server/emulator or
Apple runtime result is claimed. No phone/live/Starter change or PRD group acceptance.

**Next: implement the message thread, application routes and owned delivery scheduling,
then all remaining P0–P8.** Draft `.work/chat-thread-projection.txt` and
`.work/chat-thread-integration-notes.md` are not applied/tested. Compose canonical history,
durable pending intent and SDK presentation using exact sender/content identity or
persisted transaction mapping; never a message-controlled logical ID alone. Preserve
stable outgoing keys through confirmation/retirement and test spoofed IDs, hash changes,
redaction, cancellation, local pagination and late owner changes. Inbox must remain
accessible offline; setup should be a separate action from a thread that lacks a ready
device. `QueueCollaborationText` accepts a caller-retained UUID after uncertain storage.
`EnterCollaborationConversation` owns a child timeline; application owns the parent SDK.
A screen-only send loop does not complete background/offline delivery requirements.
Continue the authorized long run and push each tested atomic commit.


## Recoverable Chat setup validated — 1 November 2026

The setup screen now distinguishes activation, provisioning wait/retry, saved operation
continuation, revoked access and readiness. Entry reads only; mutations require an
explicit typed action. Uncertain results replay their retained operation, confirmed
receipts reconcile without resending, and confirmed bootstrap transfers the protected
runtime to the application owner before retiring its command. The page receives only
device identity, with no parent close capability or gateway credentials in state.
Current active devices restore through Ensure; local ownership remains observed while
hidden, and late/cancelled resource acquisition is closed by the use case.

`ReadCollaborationControl` now implements owned local setup/direct/room intent reads;
its records survive ordinary credential age and application upgrades. `LoadCollaborationSetup`
now maintains shared company admission through received denial, cancellation, failed
persistence/retry and newer denial revisions. [Contract and screenshots](collaboration-setup-presentation.md)
and [measurement](measurements/chat-setup-2026-11-01.json) retain 21 matched source hashes
and every attempt. Focused presentation passes in 227s; full root/browser/Wasm/Android/
AAR/lint passes in 330s: 2,606 desktop / 415 suites, 311 domain browser, 32 presentation
browser, zero failures/errors/skips and zero lint errors (one existing warning).
APK `7d5df852` is built, not installed. No phone/live/Starter change.

**Next: trusted sender identity, message thread, app navigation and owned scheduling,
then all remaining P0–P8.** All three Chat screens are DI-registered; no Home service
route is exposed yet. No full PRD group is accepted. Draft identity design: cache an
immutable account/company Matrix sender from the owned protected SDK record, under an
authenticated `collaboration-history/self/…` checkpoint. That prefix must participate
in existing history key-loss protection. Add a local SDK metadata read that never opens
a client, directory or transport, then compose history/pending messages using exact
sender/content evidence. Drafts in `.work` are not implemented or validated.

Bootstrap still pins build/platform; cross-build uncertainty, expired issuance,
cross-family key continuity and lost-key recovery need explicit protocol work. The
new identity cache must not be presented as cryptographic key continuity. Continue
the authorized long run and push each validated atomic commit.


## Participant discovery and durable DM acquisition — 1 November 2026

Application DI now registers the Chat presentation module. Participant search uses
owned company admission, bounded/debounced queries and explicit pagination; selection
prepares/replays the retained Open operation, directly acquires the confirmed summary,
and reconciles the exact peer/version before navigation. Lost responses reuse the
same command. Confirmed results survive a failed follow-up read without another Open.
Directory and individual-resource denial policies stay separate. Company denial is
retained through cancellation, failed persistence and failed recovery; ordinary age
does not hide owned local access. The shared list publication mutex covers point reads.

[Contract and reviewed fictional screenshots](collaboration-discovery.md) and
[measurement](measurements/chat-participants-2026-11-01.json) contain 21 verified source
hashes and all six attempts, including three corrected test compilation/assertion
failures. Final focused pass takes 235s; full root/domain+presentation browser/Wasm/
Android/AAR/lint takes 401s. Reports: 2,577 desktop / 412 suites, 295 domain browser,
21 presentation browser, zero failures/errors/skips. Android lint has zero errors and
one existing warning. APK `3a093eba` is built, not installed. No phone/live change;
Starter remains unchanged because this increment is HRIS-specific.

**Next: setup recovery, message thread, app routes and owned scheduling, then all
remaining P0–P8.** No service destination is exposed yet, and no full PRD group is
accepted. `ReadCollaborationControl` is only a local draft, not implemented. Setup UI
must inspect retained action before preparing another operation: confirmed registration
can reconcile across builds, but bootstrap installation currently pins build/platform.
The native encrypted SDK store currently includes native session family in its scope;
explicit recovery must preserve keys and never silently reset uncertain work. Actual
`RuntimeRepository.enterTimeline` already starts owned sync; do not add an unnecessary
second sync owner. Canonical history needs a trusted local Matrix sender identity for
multi-device alignment and safe pending-message composition. Continue the long run and
push each validated atomic commit.


## Chat inbox presentation validated — 1 November 2026

The new `features/collaboration/presentation` module contains typed inbox MVI,
owned local observation, finite remote refresh, separate local/remote pagination
and compact Fluent rows. [Contract and screenshots](collaboration-presentation.md)
and [measurement](measurements/chat-inbox-2026-11-01.json) retain implementation,
17 source hashes and every validation attempt. Failed refresh preserves first-page
cache; stopped routes retain only local observation; account/denial changes clear
hidden content. Admitted refresh reopens completed local subscriptions. One Next
request advances after local publication. Header Add keeps Chat on one line at
320 dp / 200% Indonesian text; avatar initials are decorative.

Final root/browser/Wasm/Android/lint/AAR validation passes in 143s after the first
754s full build. Reports: 2,547 desktop cases / 410 suites, including 8 inbox VM and
4 Compose layout cases, plus 8 presentation browser cases; zero failures/errors/
skips and zero Android lint errors (one existing warning). APK `9f429467` is built,
not installed. Preparatory failures were a smart cast, a new-module package lock,
and a Gradle DSL scope; each is recorded. CI now includes presentation desktop,
browser, Android library and Apple common compilation/tests. This is HRIS-specific;
Starter is unchanged. No phone/live change and no complete PRD group accepted.

**Next: finish participant discovery, setup recovery, thread and app navigation.**
The module is intentionally not yet connected to a Home service; no dead destination
is exposed. Add exact conversation summary acquisition under the existing list
publication mutex, and observe local ownership/admission without reading message
bodies. Directory reads must retain company denials through cancelled/failed retries.
Then wire typed participant search to durable Open command preparation/replay, cache
the selected DM directly, reconcile its receipt and navigate. Continue device setup,
canonical-history/pending-message composition, owned delivery scheduling and all P0–P8.
Preserve exact operation IDs, offline facts and explicit resource lifetimes. Continue
the authorized long run; commit and push each validated increment.


## Hosted retirement control and Apple build — 1 November 2026

[Control run 41301133469](https://github.com/fajarxfce/hris-mobile/actions/runs/41301133469)
passes all 28 steps at Mobile `070d3ab5`: 523 desktop / 58 suites, 499 browser /
54 suites and 407 iOS simulator / 46 suites, zero failures/errors/skips. The downloaded
log contains one Xcode BUILD SUCCEEDED marker. All 308 recorded Mobile source hashes
match that immutable commit. The [retirement measurement](measurements/collaboration-retirement-2026-11-01.json)
retains this evidence separately from the actual Android four-process proof. It is
not evidence of Apple message transport interoperability or of the later Chat UI.

The first Chat inbox module is now pushed at Mobile `829b2566065cc78ea289445dc416c692dc63a6e5`;
its [contract and reviewed screenshots](https://github.com/fajarxfce/hris-mobile/blob/829b2566065cc78ea289445dc416c692dc63a6e5/docs/collaboration-presentation.md)
and source-bound measurement cover local ownership, paging, cancellation and layout.
Participant discovery, setup, thread, app navigation and all remaining P0–P8 work
continue. No complete PRD feature group is accepted and no phone/live change occurred.


## Hosted HRIS retirement proof — 1 November 2026

[Run 41301133411](https://github.com/fajarxfce/hris-mobile/actions/runs/41301133411)
passes at Mobile `070d3ab5f718229ab88dcc7b0a36ad3cd1f5a346` with Platform fixture
`f80be73987224fda12d5a3a08a96ec6716538cc9`: all 17 steps, 165 Android host cases
and the actual HRIS emulator case, zero failures/errors/skips. Fixture
`7d92ab3729f544daa05111bc4df00a75` takes 50.609s (suite 94.355s), confirms completion
cleanup after the third forced death, exactly three encrypted sends, no held-room
send, 22/22/22 successful admissions and cleanup true. All 308 recorded Mobile and
33 Platform source hashes match those immutable commits; the extra Mobile input
relative to the 307-file local pre-build set is the final HRIS workflow pin.

The existing [retirement measurement](measurements/collaboration-retirement-2026-11-01.json)
now retains the independently checked hosted results. Chat presentation remains in
progress; this proof accepts no complete PRD feature group. Continue P0–P8 and push
each tested commit. No phone or live service changed.


## Validated HRIS completion and journal retirement — 1 November 2026

Encrypted completion and owned local retirement now pass common, SQLite/IndexedDB,
application and actual Android/HRIS process-death proof. [Measurement](measurements/collaboration-retirement-2026-11-01.json)
binds 307 Mobile / 33 Platform inputs. Platform fixture `f80be73987224fda12d5a3a08a96ec6716538cc9`
is pushed and pinned by the Mobile HRIS workflow. [Contract](collaboration-retirement.md)
describes retained identity, exact replay, bounded cleanup and later aliases.

Message `get`/matching `insert` recover validated archives, while pending observations
exclude them. Proof encryption binds the complete retained raw command and owned
account/company; key loss cannot generate a replacement over archives. Exact Sent
mapping, durable receipt, trusted original sender/logical ID/full-content hash and
all aliases are required before local retirement. Legacy or missing evidence stays
pending. At most 32 completions are prepared and archived per call, then their exact
journal entries are removed under the current checkpoint CAS. An authenticated empty
checkpoint preserves journal key protection. Reconcile, ACK recovery and delivery
recognize archived journal entries, including later aliases that do not repeat the
old primary event; cleanup verifies new aliases against acquired original history.

Actual run `90ac7444ccff4f258b1a78f6c076a226` passes with four distinct processes and
three forced deaths, including after completion archival and before journal cleanup.
Process four starts no sync/delivery, recognizes both archived receipts, cleans the
two entries, reopens SQLite and verifies exact replay plus changed-intent rejection.
Server sees exactly three encrypted sends, no held-room send and 22/22/22 admission
reads with zero failures. Case 43.141s / suite 65.560s / Gradle 95s; cleanup true.
Proof APK `9e9dcdbd` matches actual and final full builds; emulator stopped.

Final focused integration passes in 150s: 280 domain desktop / 280 domain browser,
241 data desktop / 219 data browser, 165 Android host and two app binding cases,
plus format/architecture/detekt and proof build. Final fixture build/quality passes
in 55s; Platform compile/quality 31s; 45 Python driver cases pass. Full root/browser/
Wasm/Android/proof/lint passes in 197s with 2,535 desktop, 165 Android host, 280 domain
browser, 219 data browser and 35 database browser cases, zero failures/errors/skips
and zero lint errors. Initial completion-only attempts hit a test assertion type
inference error (116s, then an unchanged 37s rerun after a failed scripted edit);
explicit `assertSame<Any>` passed in 53s. Those attempts remain in the measurement.
Dev APK `5c9487c1` is built, not installed. No phone or live service change.

**Next: Chat presentation and owned scheduling, then all remaining P0–P8. No PRD
group is accepted by this storage increment.** There is currently no collaboration
presentation module. Add the Fluent Chat service entry, owned inbox, setup recovery,
participant discovery and DM navigation, then compose history with durable pending
messages without duplicate/disappearing rows. Domain setup/conversation/send/history
operations already exist; application lifetime currently closes obsolete runtimes.
Keep delivery/admission/cleanup lifetimes explicit and preserve offline local facts.
Then finish Apple/web owned adapters/interoperability, backup, rich messages and every
call/meeting phase. Physical iPhone testing is waived; implementation is required.
Continue the authorized long run with atomic commits pushed and Git handoffs.

Previous hosted audits remain separate: ACK control `41295813930` passes 484 desktop /
460 browser / 389 iOS simulator cases and Xcode, 279 hashes matched. Starter archive
Apple `41298641454` passes 84 simulator cases and Xcode with all 16 Apple steps and
15 matching hashes; other jobs were explicitly skipped. Starter evidence is pushed
at `6226bc21`; HRIS completion/retirement stays out of the generic template.


## Validated command completion archive — 1 November 2026

The generic raw archive now passes Mobile and Starter validation. All **15 source,
test and schema files** match exactly. [Evidence](measurements/command-archive-2026-11-01.json)
records Mobile base `9da09b6b` and Starter base `36116b2`, final source hashes,
initial checks and the review-driven strengthening. SQLite schema **11** and
IndexedDB version **10** preserve existing account bindings and pending commands.

`CommandStore.archive(scope, expectedRecord, evidence)` atomically retains the
complete encrypted command plus at most **64 KiB** of opaque proof and removes
pending work. Account/session and every observed field are checked together:
ID, resource/sort identity, request/state ciphertext and revision. A same-ID
replacement at equal revision cannot receive an old completion. Matching archive
replay is immutable; changed proof/record is rejected. The archive fences later
insertion, so an old operation cannot become pending again. Normal reads and
observations exclude archives; `archived` is an exact owned lookup. Archived
ciphertext remains visible to key-loss protection, including inactive accounts.
Pending capacity remains 128/account and 512/database and is released on archival.

Ten new scenarios pass on both actual SQLite and IndexedDB, including competing
writes, same-revision replacement, lost responses, cancellation, account changes,
capacity, UTF-8 bounds, reopen/reauthentication, previous-schema migration and a
forced abort between archive creation and pending deletion. Corrected focused
checks pass in **118s** with **37 SQLite desktop / 35 browser** cases and quality.
Corrected full Mobile checks pass in **326s**: **2,496 desktop / 165 Android host /
262 domain browser / 198 data browser / 35 database browser**, zero failures,
errors or skips and zero Android/proof lint errors. Starter root/database/browser/
Wasm/Android/lint checks pass in **152s**, **341 desktop / 35 database browser**,
zero failures/errors/skips and zero lint errors. Its source and portable regressions
were copied only after Mobile focused proof; template-specific docs were preserved.

The first version used revision-only matching and passed focused/full checks
(**95s / 793s Mobile / 167s Starter**). Review found the delete/reinsert case before
commit; the final API compares the full record. Initial sources, reports and APK
hashes remain under `initialRevisionOnly`; they are not attributed to the final
contract. No test attempt failed. Final Mobile dev APK **`ce57d7e0`**, proof APK
**`87a28466`**, Starter dev APK **`96eb96c5`** are built, not installed. No phone,
live service or emulator change occurred in this foundation increment.

**Next: integrate encrypted HRIS completion evidence, exact archived lookup/replay,
retirement policy and restartable journal cleanup; then Chat UI and all P0–P8.**
The raw store never decides that a command succeeded and does not call HTTP/SDK.
Keep uncertain legacy work; validate original history, aliases and immutable device/
transaction mapping before retirement. Account/denial checks and interrupted cleanup
must remain explicit. No complete product feature group is accepted by this port.

The next HRIS steps are recorded in [retirement integration](collaboration-retirement.md).

## Active collaboration implementation — 1 November 2026

**ACK-before-journal recovery now passes actual Android/HRIS process death.**
[Evidence](measurements/collaboration-original-receipts-2026-11-01.json) binds
**279 Mobile / 33 Platform source hashes** to run
`39775a6b851040e19d1f4c97059025c8`: **41.453s case / 62.142s native suite /
91s Gradle**, cleanup true. The driver verifies three different processes across
staging and ACK deaths. The final process recovers the missing receipt from exact
original history, reopens the database and projects Sent once. The ACK checkpoint
contains no event ID or body. The server verifies exactly **three encrypted sends**
for the initial message, reply and separate ACK message; held-room sends remain zero.
Live admission is **22/22/22 device/conversation/room reads, zero failures**.
`ackBeforeJournalRecovered`, driver-owned `processRestartedAfterAck` and the two
admitted latest-history boundaries are mandatory evidence.

New staging persists trusted sender and canonical full-content hash before enqueue.
The native writer verifies that identity before mutation; Staging/Staged/Sent keep
it immutable. Explicit plain text replaces the SDK Markdown constructor: Markdown,
HTML, emoji and newlines remain literal content, with empty mentions and the logical
ID included in the hash. Old envelopes remain readable without invented identity.
`RecoverCollaborationMessageReceipts` is a local-only, owned/gated operation: up to
128 command candidates, at most 32 appended receipts, exact sender/logical ID/hash,
all event aliases and confirmed redactions. Missing originals stay pending; conflicts
reject the batch before append. Lost writes and late denial retain journal facts.
It never fetches history, starts SDK delivery or projects commands implicitly.

Focused domain/storage/host/DI checks pass in **132s**, followed by proof build and
quality in **55s**, Platform fixture/quality in **40s**, and **40 Python cases**.
Full root/browser/Android/Wasm/proof/lint passes in **235s**: **2,486 desktop /
165 Android host / 262 domain browser / 198 data browser**, zero failures/errors/
skips and zero lint errors. All attempts for this increment pass first time.
Dev APK **`1eb5a9a4`** is built, not installed; actual proof uses **`8f066ba0`**.
Emulator stopped; no phone or live change. Starter remains `36116b2`; this increment
is HRIS-specific and has no generic port.

**Next: safe command/journal retirement, Chat UI, then all P0–P8. None of the 54
groups is accepted.** Retirement must free the shared 128-command capacity while
preserving complete durable identity and retry deduplication; deleting Sent rows
alone would let an old logical UUID enqueue again. Account-owned raw storage must
atomically fence insertion against retained completion evidence. Command and receipt
stores currently have separate transactions. Preserve uncertain legacy work and all
aliases; validate interrupted retirement and lost responses before enabling cleanup.
Then integrate scheduling/UI, Apple/web owned adapters and interoperability, backup,
rich messages and all call/meeting phases. Physical iPhone testing is waived; Apple
implementation remains required. Continue the authorized long run, with atomic
commits pushed and this handoff updated; do not treat this checkpoint as completion.

Predecessor control report audit `41290902945` at `388d0534` reports success and
contains **451 desktop / 427 browser / 358 iOS simulator** passing cases; 266
immutable source hashes match. The API returned no job/step records and an empty
run log, so this audit does not independently claim an Xcode host build. See the
explicit limitations in the restart measurement's `hostedControlReports`.

### Previous SDK reopening checkpoint

**Coordinated SDK reopening now passes actual Android/HRIS recovery.**
[Evidence](measurements/collaboration-history-reopening-2026-11-01.json) records
run **`e395fab292fd4d088cae138b4aaebd27`**, **35.536s case / 56.375s native suite /
87s Gradle**, cleanup true. The fixture fills the 16-focus budget, proves that a
real queue writer blocks history-driven close, then restores the protected client
and verifies the same encryption keys/bootstrap, original message body, paused
sibling transaction and encrypted reference checkpoint. Live admission is
**17/17/17 device/conversation/room reads, zero failures**. The new
`historyRuntimeReopened` Boolean is mandatory across Android, Python and server.

`ReopenCollaborationHistory` admits the owner/device/direct room before preparation,
before restore and after restore. `Available` leaves a retained focus or spare
capacity untouched; `SetupRequired` never enrolls a new identity. A late denied,
replaced or cancelled restored lease closes. The native operation shares the
client-wide writer exclusion with message queues. An active/unfinished writer
stays owned; already-requested parent cleanup can finish. No original, reference,
intent or receipt is erased, and no history read, sync start or delivery is implicit.
The repository retains clients; application lifetime only closes obsolete owners.

Twelve new common-domain cases, four Android-host cases and one common focus case
pass. Initial focused attempts exposed two assertion type issues, a cleanup-wait
fixture assumption and a function-length check; all are preserved in the evidence.
Corrected focused checks pass in **50s**. The first actual run stopped at a bounded
close; the corrected fixture permits at most six explicit common requests, only
for `close_pending`. Its corrected proof build/quality passes in **51s**. This is
fixture-only retry; the product operation never loops. Full root/browser/Android/
Wasm/proof/lint passes in **231s**: **2,466 desktop / 161 Android host / 247 domain
browser / 193 data browser**, zero failures/errors/skips and zero lint errors.
Platform fixture/quality passes in **28s**; 35 Python cases pass. All **272 Mobile /
33 Platform source hashes** match. Dev APK **`b437c344`** is built, not installed;
actual proof uses **`e693f636`**. Dedicated emulator stopped; no live/phone change.

**Next: ACK-before-journal recovery, command retirement and Chat UI, then all
P0–P8. None of the 54 groups is accepted.** Original history already indexes exact
sender/logical ID and canonical full-content hashes. Do not infer ACK from a missing
native transaction or matching text alone. Current queue sending calls the SDK's
Markdown constructor, which may add formatted content: recovery needs a trusted
sender binding and the exact original payload identity before enqueue, not a body-
only hash. Inspect the pinned SDK content factory and preserve uncertain old work.
The SDK reopening scenario preserves stores but does not compare a newly synchronized
cursor after reopening; earlier process-death/429 cursor proof remains separate.
Apple/web owned adapters, interoperability, backup, rich messages and every call/
meeting phase remain. Physical iPhone testing is waived; Apple implementation is
required. Starter stays at `36116b2`; this HRIS-specific increment has no generic port.

Additional predecessor audits are recorded independently. Common acquisition control
**`41287684753`** at `0cb6509a` passes all 28 steps, **421 desktop / 397 browser /
342 iOS simulator** cases and Xcode build, with 256 immutable hashes matched.
Explicit restart HRIS **`41290902971`** at `388d0534` passes all 17 steps and run
`8f5ec0ad` (**33.788s case / 71.175s suite**), cleanup true, 11/11/11 admissions,
156 host cases and 266 Mobile/33 Platform hashes matched. Resolution control
`41289463827` was superseded while pending; it is not claimed as passed.

New predecessor audits are recorded separately. Traversal control **`41286066709`**
at `e992a053` passes all 28 steps, **407 desktop / 383 browser / 328 iOS simulator**
cases and actual Xcode build, with 253 immutable hashes matched. Offline resolution
HRIS **`41289463686`** at `61c6b516` passes 17 steps, 156 host cases and run
`21032404` (**50.626s case / 91.170s suite**), cleanup true, strict reconciliation
flag and 262/33 hashes matched. Resolution protocol **`41289463684`** reports
success. Acquisition control `41287684753` is running; resolution control
`41289463827` was pending at the last check. Audit terminal artifacts and this
increment's own CI after push; earlier proofs do not validate later code.

The HRIS workflow pins the tested Platform fixture **`3fb67304`**, now pushed.

No live/schema/phone change. Atomic commits and push after every commit remain
authorized. Continue after this checkpoint; the user requested the full plan.

[Earlier November detail](collaboration-implementation-history-2026-11-01.md)

## Previous collaboration checkpoint — 31 October 2026

**Android room/timeline children now belong to their protected runtime.**
[Contract](collaboration-timeline.md) and
[evidence](measurements/collaboration-owned-timeline-2026-10-31.json) cover bounded
child capacity, partial acquisition, late/cancelled waiters, explicit failed
cleanup retry, sibling disposal and parent drain ordering. The native listener
copies/disposes FFI values, applies ordered vector batches atomically and retains
virtual positions. Text, redaction, unavailable keys and pending/failed/sent states
have typed projections. Local echoes keep unknown logical IDs until reconciliation.

Fifteen new common cases pass. Focused checks pass in 70s; final root/browser/
Android/Wasm/lint passes in 245s: 2,160 desktop cases, 64 data browser cases and zero
lint errors. A preceding mistyped Gradle task path failed in 13s before correction.
Actual SDK run `5aea81cc` passes in 34.340s (53s Gradle, 32 driver cases), checking
both decrypted timeline replies, explicit child disposal/sibling survival and
closed snapshots after parent close. All 15 Platform/120 Mobile hashes match
before/after. One provider-busy key upload returned 503 before successful exchange.
The emulator is stopped; dev APK `941ce4de` is built, not installed.

**Next: admitted common content ports and app entry, product timeline/history
windows, owned offline message projections, bootstrap/cross-family recovery,
logical-ID reconciliation and chat UI; continue all P0–P8.** The platform handle
currently opens known rooms only and gates vectors above 512 entries. No product
group is accepted; no live/schema/phone change. No reusable Starter change is
required by these HRIS SDK adapters.

Conversation command CI `39217be2`/`41248280288` now passes all 26 steps across both
jobs: 143 desktop, 119 browser and 119 iOS simulator cases; Android/Wasm/Apple
compilation succeeds. All 26 source hashes were checked against the immutable
commit. Its actual HRIS run `41248280306` remains in progress at this recording.
Apple native chat runtime remains pending.

## Earlier collaboration checkpoints

Use the latest checkpoint above for the active queue; earlier next-step notes are historical.

**Native conversation commands now persist and reconcile through application DI.**
[Contract](collaboration-conversations.md) and
[evidence](measurements/collaboration-conversation-commands-2026-10-31.json) cover
immutable per-peer/room intent, separate authenticated request/outcome envelopes,
uncertainty before HTTP, original-operation retry and exact-record retirement only
after observing owned metadata. Preparation validates current device/retry state;
ordinary app upgrades preserve these control operations. Both conversation and
setup replay now retain known server denials through failed transport retries.
No UI submission or automatic retry is connected yet.

Twenty-four new cases pass (12 common domain, four common data, eight real SQLite
storage). Initial fixture failure reused a unique sort key (53s); corrected focused
checks pass in 76s. Root/Wasm/Android/lint passed in 193s before denial review, then
final root/browser/assembly/Android/lint passed in 4m: 2,145 desktop cases, domain
browser 70/data browser 49, zero lint errors. APK built, not installed.

**Next: product room/timeline ownership and chat UI, owned offline message projections,
bootstrap/cross-family recovery, logical-ID reconciliation, then all P0–P8.**
No product group is accepted; no live/schema/phone change. CI `767ed980` now lets
running control/HRIS proofs finish, retaining only the newest pending workflow.
`fbb2f3ae` provider CI `41246770541` passes both jobs/all steps, classic 83.930s and
native 92.938s with two browser cases each; all 14 Platform hashes per mode match
`e0dbb567`. Its control/HRIS runs `41246770522`/`41246770543` also pass every job/step:
119 desktop, 103 browser and 103 iOS simulator cases; app Android/Wasm/Apple
compilation passes. Hosted actual HRIS SDK case `001256c3` passes in 70.969s with
cleanup complete. All 94 Mobile/15 Platform source hashes match the pinned commits.
The new command implementation has its own queued/running checks.
Starter AppAuth hosted verify `41245287269` passes all 15 verify steps at `9740cb5`,
with source hashes checked; Starter records closure at `36116b2`.

**Android SDK synchronization now belongs to its protected runtime lease.**
Concurrent starts share one acquisition even after caller cancellation; late or
failed acquisition, bounded waiting and failed/cancelled termination retain clear
ownership. Cleanup stops sync before database draining and exclusive-store unlock.
Credential failure stops sync; reopening is explicit and does not erase owned data.
[Evidence](measurements/collaboration-owned-sync-2026-10-31.json) retains eight new
common cases, 15 Platform/94 Mobile source hashes and proof APK `e5c07ded`.

Focused checks passed in 70s. Root/Wasm/Android/proof-lint passed in 192s: 2,121
desktop cases; data browser has 45 cases. Actual Android/server case `54ab8f5a`
passed in 35.750s (full fixture 1m), one API and 32 driver cases. Both directions
decrypt after real process death, original transaction and keys survive, and
server observation confirms no early encrypted send. Instrumentation/cleanup
complete and the owned emulator is stopped. One `keys/upload` HTTP 503 provider
busy is retained in the trace; the SDK exchange subsequently succeeds.

**Next: durable conversation commands, product room/timeline ownership and chat UI;
then bootstrap recovery, owned offline projections/logical-ID reconciliation and
all P0–P8.** No product group is accepted; no live/schema/phone change. Conversation
control is pushed at `50964794`; predecessor `9f83dcdc` hosted control/HRIS runs
were superseded/cancelled. `50964794` runs `41246022664` and `41246022568` are still
in progress at this checkpoint. Starter AppAuth port `9740cb5` remains locally
verified; hosted verify `41245287269` is separate and still running.


**Native conversation discovery/control adapters and scoped reads are now wired.**
[Contract](collaboration-conversations.md) and
[evidence](measurements/collaboration-conversation-control-2026-10-31.json) cover
participants, DM list/detail/open and room status/setup. Bounded responses validate
owner identities, UUID page progress, receipt versions and coherent room states.
Reads reacquire native/company admission after successful or failed I/O; late
selection/permission/session changes cannot publish obsolete data. Mutation ports
preserve operation/payload through refresh but are not yet product actions: durable
preparation/replay/reconciliation must come before submission from UI.

Twenty new common cases pass: seven domain and thirteen data. Focused desktop/
browser/assembly/quality passed in 58s after correcting a test enum name (35s
initial compile failure). Final root/Wasm/Android/lint passed in 196s: 2,113 desktop
cases, 58 domain browser and 37 data browser cases; zero lint errors. App bindings
resolve the new reader/repository. APK built, not installed. Predecessor hosted
control `41245001690` and HRIS `41245001517` are still running at this checkpoint.

**Next: SDK sync/room child ownership, durable conversation commands and chat UI;
then bootstrap recovery, owned offline projections/logical-ID reconciliation and
all P0–P8.** No product group is accepted and no live/phone change. Starter AppAuth
port `9740cb5` passed independent root/Android/lint (331 desktop cases); hosted
verify `41245287269` remains separate. HRIS control adapters/policy stay here.


**Application DI and Android process ownership are now wired and locally verified.**
`MaintainCollaborationRuntimeOwnership` closes obsolete SDK leases after session/
company/permission/deadline changes, retains authoritative denials through cleanup
failure, rejects a second collector and closes all on cancellation. One timer per
captured frame replaces polling; token rotation preserves its owner. Android's
Application starts `ApplicationCollaborationLifetime` once, with a protected native
runtime configured from the HRIS root. Other hosts explicitly report unavailable
runtime support. Startup does not enroll, bootstrap or open a native client.

[Evidence](measurements/collaboration-application-runtime-2026-10-31.json) retains
eight new common lifecycle and two application binding cases. Root/Android passed
in 328s; Wasm compiled but lint exposed the manifest's missing direct AppAuth
dependency. Declaring the existing catalog version fixed it; final root/Wasm/
Android assembly/lint passed in 70s, 2,093 desktop cases and zero lint errors.
Domain browser has 51 passing cases. The initial focused fixture compilation
needed explicit nullable-list type arguments; recovery passed in 62s. CI now
checks app bindings, Android assembly/lint, Wasm and Apple app compilation.

**Next: native conversation/participant/room adapters and chat UI, with SDK sync/
room children owned through shutdown; then bootstrap uncertainty/build recovery,
offline projections/logical-ID reconciliation and all P0–P8.** No product group is
accepted. No live/schema or phone change. The generic AppAuth declaration/lint
guard is now independently verified in Starter `9740cb5`; keep HRIS runtime policy here.

Corrected provider CI `3cedb0b1`/`41242901470` passes both jobs/all steps: classic
77.293s and native 75.327s, two browser cases each, cleanup true. All 14 Platform
source hashes match `e0dbb567`; results are in the offline-signal measurement.
Earlier cancelled runs and non-atomic-signal failures remain recorded below.


**Admitted restore now reopens existing protected runtime ownership.**
`RestoreCollaborationRuntime` returns a paused matching resource or explicit empty
result, never issues tokens/installs a replacement, and leaves setup commands intact.
Late account/company/permission changes, failed credential writes and cancelled
acquisition close returned leases. [Evidence](measurements/collaboration-runtime-restoration-2026-10-31.json)
retains eight new common cases and root/browser/data-assembly/quality passing in
72s: 2,083 desktop, 43 domain browser cases. **Next: application-wide runtime/SDK
child ownership, bootstrap uncertainty/build recovery, setup/chat UI and all P0–P8.**
Owned offline message projections remain separate; this is live-admitted setup
recovery. No product group accepted, live/phone/Starter change.

UI preparation read `ui-ux-pro-max` and `compose-component-design` plus modifier/
slot references. Verified searches match Chat & Messaging App and Compose LazyColumn;
the initial design-system landing pattern was unsuitable. Keep the existing Fluent
tokens, compact top bars, 48dp targets and direct conversation rows. No new UI code
has been added yet. `9c44fc56` hosted control/HRIS runs were superseded/cancelled;
its provider workflow still used the old non-atomic signal. Inspect the corrected
`3cedb0b1` protocol run `41242901470` and the next exact control/HRIS run after push.

**Admitted bootstrap installation now owns confirmed command retirement.**
`InstallCollaborationRuntime` verifies current owner/build/platform and the original
attempted bootstrap, acquires a paused protected lease and removes only the observed
command revision after matching its owner/device/operation and credential gate.
Cancellation or late denial closes the acquired resource; lost retirement responses
preserve already installed facts for restore. Twelve common cases pass, including
competing calls, credential/cleanup failures, account/company/permission replacement
and cancelled handoff. [Evidence](measurements/collaboration-runtime-installation-2026-10-31.json)
retains focused 63s and root/assembly 45s checks: 2,075 desktop cases, 35 domain browser
cases, quality passed. The initial 17s test compilation error used `copy` on an
intentionally non-data credential class; the fixture now constructs bounded variants.
**Next: explicit restore/application session ownership, bootstrap uncertainty/build
recovery, live delivery, app DI/UI and all P0–P8.** No product group accepted.

Provider-only CI `41240634061` at `70bfbd21` passed native sync but failed classic:
Android observed the empty `offline-ready.json` while its producer was still writing
(`ProofOfflineInvite.kt:28`). Both modes cleaned up. Platform `e0dbb567` now
publishes the completed private file by rename. The corrected actual classic proof
passes in 35.498s with two browser cases and cleanup true; all 14 source hashes are
verified. [Evidence](measurements/collaboration-offline-signal-2026-10-31.json) retains
both predecessor results and the correction. Protocol CI now pins that commit;
inspect the next hosted classic/native results. This is separate from the successful
protected HRIS SDK and common-control runs recorded below.

**Actual Android process death preserves the pending SDK transaction.**
[Evidence](measurements/collaboration-sdk-process-death-2026-10-31.json) binds APK
`a14d6bf1` to 71 Mobile/15 Platform sources. The driver force-stops the owned proof
process after durable enqueue, verifies its exit, then restores both protected
identities in another PID. The original transaction remains paused until explicit
release; gateway observation catches any premature encrypted send. Both directions
decrypt and server reads verify ciphertext. SDK case 33.512s; fixture/quality 62s,
four API and 32 Python cases pass. Mobile root/debug/lint/browser passes in 66s:
2,063 desktop/24 data browser cases. Cleanup completed; owned emulator stopped.

Explicit runtime opens now retry already-closing abandoned leases once, preserving
locks on failed drains. The fallible retrieval bridge normalizes unexpected nonfatal
exceptions. **Next: admitted bootstrap installation/recovery, application session
ownership, logical-message reconciliation and DI/UI; continue all P0–P8.** This is
SDK queue persistence, not full product outbox/notification or Apple acceptance.
No live/phone/Starter change, and no product feature group accepted.

Predecessor `70bfbd21` hosted runs `41240634120` (actual SDK, 68.188s, cleanup
true) and `41240634056` (61 desktop, 47 browser, 47 Apple simulator cases) pass
all jobs/steps. All 70 Mobile/15 Platform source hashes and retained XML are
verified in the protected-store measurement.

**Protected Android SDK reopening and encrypted HRIS exchange now pass.**
[Evidence](measurements/collaboration-protected-native-store-2026-10-31.json)
retains APK `18b072bc`, 70 Mobile/15 Platform source hashes and a 22.732s actual
SDK case, cleanup true. The proof now uses the real Keystore adapter/private
runtime: competing opens, a missing record and a wrong passphrase preserve the
original device keys after restoration. SDK `pause()` drains database stores
before unlock; shared shutdown tests cover competing callers, timeout, cancellation,
failed drain retry and repeated close. Final root/debug/lint/browser passes in 78s:
2,063 desktop/24 data browser cases. Four newly resolved AndroidX metadata files
match official Google Maven bytes; the initial lint verification failure is retained.

The exchange exposed a Platform key-shape bug after retry: fallback IDs may overlap
one-time IDs in their independent maps. The fix keeps all identity/signature/marker
checks. Platform's full 454s run passes 37 domain, 201 data, 39 API plus quality;
27 Python driver cases pass. Both earlier failed runs remain recorded.
The newer checkpoint above adds abandoned-close recovery, retrieval normalization
and actual Android message queue process death. Continue bootstrap installation/
recovery, app DI/UI and all P0–P8. No app DI/routes, live service,
phone or Starter change. The predecessor `4d124732` hosted run `41238521070` also
passes both jobs/all steps: 57 desktop, 43 browser and 43 iOS simulator cases; all
52 source hashes and artifact XML are verified in its runtime measurement.

**Android runtime composition now compiles and passes the shared checks.** The
application factory supplies Android Keystore records and `noBackupFilesDir`;
runtime installation pins bootstrap identity and preserves existing SDK-rotated
credentials on the same operation. Client ownership starts before restore. Closing
retains the store lock until the SDK's E2EE initialization task has finished;
callers wait at most five seconds per close, with five outstanding leases allowed.
The void callback records bridge failures without unwinding across FFI; a concurrent
successful write cannot clear that terminal gate. [Evidence](measurements/collaboration-sdk-runtime-2026-10-31.json)
retains focused 58s and root/assembly/browser 50s checks. All 2,059 desktop and
20 collaboration data browser cases pass. **Next: actual protected native store
reopen, missing-record/wrong-key preservation and queue process death in the owned
HRIS emulator fixture.** No app route/DI or physical device change yet. Continue
through all P0–P8; no product group accepted. Hosted predecessor `b464a805`, run
`41237201395`, passes both Linux and Apple; all 47 source hashes verified and XML
evidence appended to the storage measurement.

**SDK store and credential-write components now pass.** The data module has a
JVM/Android exclusive directory lease, strict scoped SDK record codec, per-lease
pending credential writer, and an Android factory that pauses queues before its
single restore. [Lifecycle notes](collaboration-sdk-lifecycle.md) and
[evidence](measurements/collaboration-sdk-storage-2026-10-31.json) retain the exact
scope and limits. Final root/assembly/browser checks pass in 57s: 2,058 desktop
cases and 19 data browser cases; quality zero findings, 73 modules. Six file lease,
six codec and six credential-write cases include cancellation after acquisition/
committed write, missing ownership, failed persistence and late callback closure.
Android factory is compiled, not yet run through the actual SDK fixture.

**Next: compose those parts in the Android runtime repository using the existing
AndroidCredentialStore and the private noBackupFilesDir; connect its safe SDK
session delegate, then run protected store reopen and message process-death proof.**
Do not treat the codec/writer fixture as actual platform Keystore acceptance.
Session save callback returns void; record failure and retain pending tokens without
throwing across FFI. Own every SDK listener/sync/task and directory lock through
shutdown. Inspect initialization tasks before releasing a store while native work
may still hold it. No provider logout/reset, no app route/DI or phone action yet.
Continue bootstrap recovery, UI/outbox and all P0–P8; no product group accepted.

Setup `fb1b1c13` hosted run `41235576905` now passes **both Linux and Apple, all
steps**: 38 desktop, 30 browser and 30 iOS simulator cases. All 39 source hashes
match the pushed commit; retained artifact XML hashes/counts are appended to its
setup-command measurement. Prior CI failures/cancellations remain documented.

**Durable product setup commands are implemented.** Prepare persists immutable
registration/retry/bootstrap input; replay records uncertainty before I/O and uses
one owner/mutex, captured native family, client build and original operation.
AES-GCM request/outcome envelopes use the protected shared command key, separate
from disposable caches. Account/company/family changes, lost keys, stale revisions,
late denials and cancellation preserve retained facts. Gateway credentials remain
encrypted until an SDK installation owner can consume them. Only matching observed
registration/new-job receipts retire; uncertain/bootstrap results stay retained.
[Contract](collaboration-setup.md) and [evidence](measurements/collaboration-setup-commands-2026-10-31.json)
retain sources and corrections. Final root/assembly/browser checks passed in 62s:
2,040 desktop cases, collaboration domain 23/data 15; browser domain 23/data 7.
Quality has zero findings. Apple runtime checks remain pending.

The preceding control run `41234732498` passed Android setup but portable checks
stopped at missing Wasm lock entries. The retained lock update adds only four local
module workspaces; no external dependency version changed. Inspect the next exact
hosted run. Local Chromium must be selected with `CHROME_BIN` as recorded in the
measurement. Do not put draft `.kt` files under `.work`: formatCheck scans them;
use an external owned temporary directory while keeping checked sources frozen.

**Next: product SDK store/session installation and actual process-restart proof.**
Read [SDK lifecycle findings](collaboration-sdk-lifecycle.md): exclusive owner lock,
protected passphrase, one restore per client, queue pause before restore/listener,
void credential-save callback failure handling and no logout/reset. Then bootstrap
recovery, app DI/chat UI/outbox and the full P0–P8 scope. No product feature is
accepted. Phone, dedicated emulator, live Platform and Starter remain unchanged.

Control CI run `41234420892` exposed setup-android v3's obsolete default SDK
package `tools`, before any portable tests ran. The workflow now requests
`platform-tools` and SDK/build-tools 36 explicitly, matching the passing protocol
workflows. Inspect the corrected hosted run. Durable setup command storage and
prepare/replay orchestration are in progress locally; eight real database cases
already pass, with full validation still pending. Continue implementation.

The product [native setup modules](collaboration-setup.md) now provide bounded
registration, status, retry and bootstrap adapters plus a scope-safe read use case.
Eight domain and seven transport cases pass; root checks and setup assembly pass
in 334s (2,017 desktop cases). [Evidence](measurements/collaboration-mobile-setup-2026-10-31.json)
retains sources and the initial compilation/UI-fixture corrections. The module is
not yet installed into app DI or navigation. `collaboration-control.yml` checks
desktop/browser and Apple targets; inspect its exact hosted result after push.
Next: encrypted durable setup commands, pinned bootstrap client build, SDK store
ownership and credentials, then chat UI/outbox and all P0–P8. No feature accepted.

Hosted SDK evidence is now retained: Mobile `538a1827` HRIS run `41231913617`
passed all steps, 63.437s, cleanup true, APK `9900b041`; twelve Mobile and ten
Platform hashes match their exact commits. Platform control `41231798310` passed
both jobs. Dual provider regression `41231913637` also passed both modes with
cleanup true: classic 35.088s/81.130s, native 21.795s/61.379s. All fourteen driver
hashes match `d425e6d8`; results are in the native HRIS measurement. These are
protocol fixtures, not product queue-restart or physical-device acceptance.

Existing expense UI scenarios now pin the observer clock to their October claim
fixtures. The company timezone had crossed into November, correctly excluding the
October data in production and exposing a nondeterministic test. No product date
behavior or screenshot tolerance changed. Both affected cases pass; full root
checks and the in-progress collaboration adapter assembly passed in 334s.
[Evidence](measurements/expense-fixture-month-2026-10-31.json) retains the failed
screenshot hash, 351s initial failure and 54s focused recovery. Continue collaboration
implementation; this test correction does not finish the approved plan.

Actual Android SDK ↔ HRIS gateway now passes in both directions against Platform
[`45ccd56f`](https://github.com/fajarxfce/hris-platform/commit/45ccd56f29871a3474aca676be3464cc7d43bee4).
Two fictional employees own separate encrypted SDK stores. Keys/claim/to-device,
Megolm send and classic sync pass real HRIS admission; both clients decrypt the
expected content and logical ID, and gateway event reads confirm ciphertext.
[Evidence](measurements/collaboration-native-hris-sdk-2026-10-31.json) retains
12 Mobile/10 Platform hashes, APK `3b10b319`, four initial timeout failures and
final 83s Platform checks: 19 API cases, format/architecture. All 27 Python driver
cases pass. Mobile root/debug/lint passed in 69s after fixing nested builder
structure; no rules were suppressed. Initial debug/lint passed in 274s.

The pinned SDK/Ruma sends **unprefixed `use_state_after`** even to v1.12. Platform
now accepts one boolean preference and returns its standard state-before response;
it does not advertise state-after support. Actual fallback is tested. SQL fixtures
activate the device/publish the HRIS room binding; registration/bootstrap, provider
room creation, gateway transport and client crypto are real. SDK handles the
observed transient concurrent key-upload provider-busy response. This is not product
queue-restart or verification acceptance. New `collaboration-hris.yml` pins the
exact Platform fixture and compiles before starting its dedicated CI emulator.
Inspect its hosted result after push; existing browser/native protocol CI remains.

Dedicated emulator is stopped with normal app data retained. Phone/live/Starter
unchanged. **Continue product SDK session/store ownership, durable queue restart
and logical-ID reconciliation, UI and all P0–P8; no product feature is accepted.**

Earlier provider-only evidence follows.

Latest source supports both classic `Client.syncV2` and native `SyncService`, with
matching builder/session modes and one restore per client. Both actual local
Android/browser runs passed two cases using APK `d835e1c1`: classic 19.219s / owned
38.121s; native 18.814s / owned 33.649s; cleanup true. Complete SDK membership,
not optional summary counts, now gates the offline first message. Final root,
debug build and lint passed in 23s: 2,002 desktop cases, zero lint errors (47
warnings). [Evidence](measurements/collaboration-dual-sync-2026-10-31.json) retains
the three corrected provider failures, compilation/nesting corrections and exact
source hashes. Workflow pins Platform `d425e6d8`. Exact `b061d390` hosted
[41212817421](https://github.com/fajarxfce/hris-mobile/actions/runs/41212817421)
passed every job/step in both modes: classic 31.659s / owned 72.695s, native
34.002s / owned 81.267s. Both cleanups succeeded; separate hosted APK hashes
`e5053bb5` / `f65bb8e8` and all source checks are retained in the measurement.
Driver browser CI `41212746123` also passed every step (10.128s / 33.605s). Dedicated emulator is
stopped with data retained; phone unchanged. Continue scoped recipient key/query,
sync/content and product UI/outbox integration; no feature is accepted by this lab.

Latest exact native protocol CI `41207178159` at `7b57865b` passed every job/step. Driver `047897f9` hashes match the sanitized artifact: two scenarios 33.604s, owned run 77.491s, cleanup true; hosted APK `b4cdef67` is retained in `docs/measurements/collaboration-sdk-protocol-surface-2026-10-31.json`. The same driver browser CI `41207148334` also passed every step (8.995s/32.972s). No Kotlin source or product acceptance changed.

The user approved the complete chat/call/meeting plan. Branch
`feat/communications` starts from `7e12251a` with no inherited edits. The canonical
[54-group PRD](https://github.com/fajarxfce/hris-platform/blob/feat/communications/docs/collaboration/prd.md)
and [delivery ledger](https://github.com/fajarxfce/hris-platform/blob/feat/communications/docs/collaboration/delivery.json)
live in Platform. P0 begins with actual isolated Matrix/LiveKit protocol proof;
no collaboration feature is accepted yet. Preserve current Firebase/HRIS
identity and build-4 phone installation. Continue all P0–P8 phases with matching
tests, source evidence and pushed atomic commits. Prove reusable foundations
here before porting them to Starter. The completed prior scope below is history,
not closure of this new implementation request.

The latest driver records bounded method/static-servlet request counters from
its owned Synapse metrics endpoint. Browser baseline passed in 15.512s; the
Android/browser pair passed in 39.009s (18.498s scenarios), with complete cleanup.
The unchanged proof APK `d3662eb9` matches source `e6dcb5ea`; no new compilation
or root-check claim is made. [Protocol evidence](measurements/collaboration-sdk-protocol-surface-2026-10-31.json)
retains both results and fifteen parser/ownership cases. Counters contain no
account/room/device IDs, tokens or payloads and include fixture control/negative
requests; they are not an automatic gateway allowlist.

The earlier fixture explicitly used SlidingSyncVersion.NATIVE with SyncService. Eighteen
SlidingSyncRestServlet requests occur only in the combined run; pinned Synapse
implements MSC4186 Simplified Sliding Sync. Classic Kotlin syncV2/syncOnceV2 entry
points exist but are not tested by this fixture. Product gateway integration must
scope native lists/subscriptions/extensions and keep unverified rooms out of sync.
Native CI now pins Platform `047897f9f5a7b126fd2db4ed5a918db3e11e364e`; inspect
its exact hosted run after push. The emulator is stopped with data retained; no
physical phone or live HRIS action occurred. Continue all implementation phases.

The Android sender now creates a version-12 encrypted room with `invited` history
while the enrolled browser peer is offline. It observes SDK encryption/member
state, sends through the paused/resumed queue, then closes its SDK. The driver
stops only the proof process before the peer joins, loads history and decrypts.
Encrypted logical IDs, the SDK transaction mapping, standard refresh and unchanged
signing key still pass. [Invitation evidence](measurements/collaboration-native-invited-2026-10-31.json)
records two scenarios in 18.486s (33.494s owned run), with complete cleanup. Full
root/debug/lint checks passed in 48s: 2,002 desktop cases, zero lint errors and 47
warnings. An initial Detekt long-method failure was fixed by extracting result
serialization; no check was suppressed.

Native CI pins Platform `54eb3a667ebe47ff06fcb0c2a973824215f383b8`. The
hosted [41204075013](https://github.com/fajarxfce/hris-mobile/actions/runs/41204075013)
passed every job/step at `e6dcb5ea`. Both scenarios passed in 34.359s
(82.746s owned run) with complete cleanup; driver hashes match the pin. This proves Android sender to an already-enrolled
offline browser invitee, not native recipient-before-join or Swift interoperability.
Never-enrolled recipients still need a pending product outbox until eligible keys
exist. Durable restart/coalescing, product sessions/content and UI remain pending.
The dedicated emulator is stopped with regular HRIS data retained. No physical
phone, live service or employee message was touched; no Starter port is due for
the isolated fixture. Continue all P0–P8 implementation.

The native queue/extra-content experiment now passes in both directions through
actual Android 35 and browser E2EE. `org.hris.message_id` remains inside encrypted
content. The SDK's paused queue emits its local transaction, resumes, then maps
that same transaction to the server event; standard refresh preserves the signing
key. Both scenarios pass in 29.090s with complete cleanup. [Queue evidence](measurements/collaboration-native-logical-queue-2026-10-31.json)
records tested source/APK hashes, twelve driver cases, full root checks with 2,002
desktop cases and debug assembly/lint (52s; zero errors, 47 warnings).

Two earlier runs failed at synchronous `Room.enableSendQueue` with native
`InternalException`. Safe class/method/line diagnostics locate the call without
logging provider messages/credentials. The client owner's asynchronous
`enableAllSendQueues` passes and is used for connectivity gating. This experiment
does not prove process-restart queue persistence or recipient deduplication;
implement durable logical IDs, event aliases/tombstones and conflict handling
before offline acceptance. No Starter port is due for this isolated proof.
The dedicated emulator is stopped with regular HRIS data retained; no phone action
occurred. Native CI now pins Platform `d0731afe`; inspect its exact result after push.

Hosted native queue proof passed at exact `e8a5ceb3` in
[41198048864](https://github.com/fajarxfce/hris-mobile/actions/runs/41198048864).
Every job/step succeeded. Both provider scenarios passed without failures/skips
in 84.382s, with complete cleanup and an independently built APK. The
[measurement](measurements/collaboration-native-logical-queue-2026-10-31.json)
retains that result; its driver hashes match pinned Platform `d0731afe`.
Continue HRIS gateway/content integration and durable recipient reconciliation.

P0 native checkpoint: [the separate Android protocol fixture](collaboration-protocol.md)
uses Matrix Rust Kotlin SDK 26.10.23. Actual Android 35 ↔ browser encrypted text
exchange and the existing web/media scenario passed together in 24.590s, with
full owned cleanup. Platform driver/source `a9100ece` records 12 guard tests and
ciphertext evidence; [Mobile source/artifact hashes](measurements/collaboration-android-protocol-2026-10-31.json)
bind the tested APK. Full root checks and debug lint passed in 4m42s, including
2,002 desktop cases across 349 suites (no failures/errors/skips); lint has zero
errors and 45 dependency-version/fixture-packaging warnings. Native CI is defined with that exact Platform driver; record
its result separately. This is still P0: product sessions, native durable queue,
Apple bindings, MatrixRTC and native media remain pending. No Starter code port
is due yet and no phone install was performed. Android proof source is `477f9c60`.
Its first hosted run `41181935938` failed before compilation because the SDK
setup action still defaults to Google's retired `tools` package. The workflow
now requests `platform-tools` explicitly. Corrected run
[41182128950](https://github.com/fajarxfce/hris-mobile/actions/runs/41182128950)
passed at `eefc4121`: both real-provider scenarios passed without failures/skips
in 66.016s including cleanup. [Hosted evidence](measurements/collaboration-android-ci-2026-10-31.json)
records the independently built APK hash and pinned Platform driver. The first
setup failure remains historical; it was not a passed native proof.

Apple binding work now lives in `tool/collaboration-apple-proof`, pinned to Swift
SDK 26.10.27. Its hosted workflow must pass real native key-store reopening,
wrong-key rejection and the iOS device compile. Linux has not run those checks.
The SDK requires iOS 16; only this isolated package changes its minimum so far.
Hosted run [41182072489](https://github.com/fajarxfce/hris-mobile/actions/runs/41182072489)
passed at `e210508a`: one actual native key-store case and the iOS device build,
using Xcode 16.4 / Swift 6.1.2. The resolved package revision and
[measurement](measurements/collaboration-apple-binding-2026-10-31.json) are retained
in Git. This does not prove Apple provider interoperability or native media.
Continue protected HRIS integration while those remaining P0 gates stay explicit.
The local dedicated emulator has been stopped after successful Android proof;
its regular HRIS data and the physical phone were untouched.

The workspace policy now recognizes `COLLABORATION` separately from the HRIS
Inbox's `COMMUNICATIONS` key. The existing data-boundary test retains future-key
filtering and round-trip validation. All 28 workspace-domain and nine workspace-data
cases pass; dev-debug assembly passed in 3m54s. No installation was performed:
this is policy plumbing for the ongoing chat integration, not a delivered chat UI.
Platform schema 105 and its dashboard policy UI carry the matching identifier.
No Starter port is appropriate for this HRIS company-module catalog entry.

The standard native Matrix refresh mechanism now passes against real Synapse and
the browser. Four-second native access credentials expire, the SDK refreshes, the
session delegate saves both replacement tokens and encrypted chat continues with
the same device signing key. Both provider scenarios passed in 30.586s with owned
cleanup. [Refresh evidence](measurements/collaboration-native-refresh-2026-10-31.json)
records hashes, twelve driver cases and the corrected full root check: 2,002
desktop cases, zero lint errors, debug APK built (2m54s). An initial Detekt long
method failure led to the separate `ProofSessionStore`; no check was suppressed.
Native CI pins Platform `1db0f9fe`; hosted run `41187875527` passed at
`d799e5cf`, both provider scenarios in 54.657s with full cleanup.
This is private fixture storage, not a production Keystore implementation. Use
SDK standard refresh, never restore again on an initialized SDK client. Continue
protected HRIS sessions/provisioning/gateway and actual chat UI. No Starter port
is due for this isolated proof. The dedicated emulator is stopped, data retained;
no physical phone action occurred.

Updated 31 October 2026 (UTC). The release-configuration audit and independently
verified Starter port are complete. This employee application was generated from
Compose Fluent Starter and is the proving ground for reusable foundation changes.

## Completed mobile layout and compact-filter review — 31 October 2026

The requested employee-page layout/component review, fictional sample data and
follow-up compact filters are complete. Mobile is the proving ground; reusable
components and tests were then ported and independently checked in Starter.

- Mobile application `e5af27fd` includes the earlier form/detail/document fixes
  from `b73031be`, then lightweight status filters and paired period chevrons on
  the right. Full labels reflow at narrow widths or large text. Month/year/week/
  revision limits and each feature's filter scope remain unchanged.
- Starter production `58623f5`, portable test `df2906f`, closure `44d164c` include
  the identical generic foundation. No HRIS policy, routes or configuration was
  copied. No additional port is pending.
- Platform `1363abee` created 23 fictional visible records for the existing
  user-linked employee plus leave balance examples, using authorized APIs and
  retained idempotency receipts. Do not seed again with a fresh manifest. No
  identity binding, permissions or real payroll/payment action was introduced.

Configured **devRelease 1.0.0 / build 4** is installed on the WireGuard phone.
SHA-256 `20adbce11c8f727d3cf80d24d4909ebfd9dfd1f8c94d39327dd70f91bd701e71`,
24,127,587 bytes. Signed assembly passed in 142.752s. Install-only and pulled-APK
verification passed in 25.852s; app identity, first-install time and data were
preserved. No phone UI was launched/driven. **Future builds require versionCode
>= 5 and the same signer**. Test/docs-only commits after `e5af27fd` do not require
another installation. The normal Firebase configuration and private API are
verified in the actual artifact. Never deliver an unconfigured emulator fixture.

The identical configured release was inspected on the dedicated Android 35
emulator: month/year/week stepping, status selection/reset, normal/200% text,
dark appearance and landscape. Overtime content begins 72 dp earlier and Expense
content 80 dp earlier than build 3, measured on matching fictional rows. See the
[review and screenshot galleries](layout-review-2026-10-31.md) and
[complete measurements](measurements/compact-filters-2026-10-31.json). The emulator
was restored to portrait, normal text and light appearance; only its own session
was logged out, then its process was stopped. Private helpers, raw captures and
immutable APKs remain under `.work/ui-review-2026-10-31/`; no secret belongs in Git.
An old emulator auth fixture has build 3: rebuild it with a sufficient version
before using it again, and keep it on the dedicated emulator only.

Final local checks passed **2,002 Mobile desktop cases / 349 suites** (212 shared
UI cases) and **331 Starter cases / 114 suites**, without failures/errors/skips.
Hosted validation is complete and explicitly matched to unchanged production
source: Mobile Windows/Web/Apple passed in [41170243851](https://github.com/fajarxfce/hris-mobile/actions/runs/41170243851)
at `e5af27fd`; corrected Linux root/browser/Android/app-link verification passed
in [41171544190](https://github.com/fajarxfce/hris-mobile/actions/runs/41171544190)
at `f742e09d`. Starter Windows/Apple passed in `41170061606`, and corrected Linux/
Android/Web verification passed in `41170810070`. The earlier full runs retained
failed Linux tests; they are not represented as entirely green runs.

Those test corrections preserve the intended contracts: host fonts may wrap
390 dp filter labels, while 640 dp proves a single row; checkpoint publication
may briefly replace facts collectors through `flatMapLatest`, so tests wait for
a settled collector and count fake subscriptions atomically. Saved facts,
no-automatic-fetch, offline recovery, ownership and keyboard/disabled-action
assertions remain. Production code and APK bytes did not change for these fixes.
The manual CI `verify` scope keeps default all/PR/push and Apple-only behavior.
Physical iPhone testing remains explicitly waived; Apple simulator tests and the
Xcode app build passed.

Installed guidance is recorded with source pins in the review: Chris Banes
`compose-component-design` and `compose-ui-testing-patterns`, plus
`ui-ux-pro-max`, under `/home/fajar/.codex/skills/`. They become discoverable on
subsequent turns; their guidance was read and applied to this change.
No implementation, validation, delivery, sample-data write or user input remains
pending for this review. Earlier sections below are retained history.

## Previous Android installation — build 2, 31 October 2026

The user explicitly requested the latest release build on the WireGuard phone.
Source `5ffb7300` retains the accepted `d170810f` application/build/test trees.
A signed `devRelease` APK, version `1.0.0` / build **2**, is now installed on
`10.77.77.3:5555` (Samsung SM-A725F, API 36). It is non-debuggable and uses
`http://10.77.77.1:8088/api/v1/`. Release assembly/vital lint passed in 132.050s.
The existing development signing certificate was deliberately reused for the
private in-place update and existing Firebase registration; this is a release
build, not a new production distribution signing identity.

The 24,111,115-byte installed APK exactly matches SHA-256
`fa9b20a67148d323b4d8975da147871df6a1d9b576cf9202d0cd8c8cdf45919d`.
Install-only plus installed-byte verification took 24.772s. The package app ID and
first-install time are unchanged; no uninstall, data clear or UI launch occurred.
[Measurements](measurements/android-release-phone-2026-10-31.json) retain build
inputs, source continuity, certificate/package/API checks and installation proof.
The immutable APK and prior debug APK are retained locally under
`.work/release-phone-2026-10-31/`.

That installation established versionCode 2; the current build-4 delivery above
supersedes it. The repository default remains 1 and would be a downgrade.
Preserve the same certificate and install in place. No application source or
server change, Firebase distribution upload or generic Starter port was needed.
Earlier phone hashes/builds below are retained history.

## Completed workspace audit — 30 October 2026

The full Platform/Mobile/applicable Starter audit is complete. Platform commit
`64eb0d14` records all 61 initial requirements and eleven detailed module reviews
accepted, with final [CI 41094285531](https://github.com/fajarxfce/hris-platform/actions/runs/41094285531)
at `93438f99`: all ten jobs passed, including 3,090 API cases, 104 active worker
cases, 1,111 mocked browser scenarios, 56 actual workflows and schema-103 recovery.
GitHub temporarily returned stale step details; final logs, reports and artifact
digests prove completion. No cancellation, rerun or source change was required.

The private API/worker now run verified application `2c2a59e3` after a fresh
schema-103 data/key checkpoint. Health, migration history, Google/person bindings,
active native session IDs, document integrity and runtime environments are verified;
SMTP stays disabled. Dashboard `1e0522c0` is unchanged. [Current Platform handoff](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/agent-handoff.md)
and [runtime evidence](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/measurements/final-development-rollout-2026-10-30.json)
retain current ownership, rollback images and recovery locations.

Mobile's [fifteen-area review](feature-review-2026-10-30.md) and
[source/CI index](measurements/feature-review-2026-10-30.json) remain accepted at
`f4f101a1`, against unchanged full-CI source `d170810f` (`41077194465`). The normal
private APK with application `3c6db4eb` is already installed and required no new
installation for this backend-only update. Actual Android Google/Home and FCM
acceptance remain source-scoped; physical iPhone testing is explicitly waived.
Apple simulator tests and the complete Xcode app passed.

All 657 identical shared files and 60 differences were reviewed. HRIS business
policy stays downstream; no additional generic Starter port remains. Starter's
accepted source `77063862` retains its independent full CI and separately proved
artifact-upload correction. This closing update changes documentation only.
No implementation, local test or CI remains active. For new work, continue proving
reusable changes in Mobile before porting them, and use the current Platform
inventory rather than reopening historical pending paragraphs below. The review
explicitly records undefined future extensions, including post-approval overtime
fact amendments; it does not label them implemented or claim production release.

## Retained acceptance history

Earlier pending statements below are historical; use the active queue above.

Current metadata acceptance: exact source `d170810f` passed all four jobs in
[CI 41077194465](https://github.com/fajarxfce/hris-mobile/actions/runs/41077194465).
Downloaded reports verify 1,994 desktop, 1,676 browser, ten build-logic and 104
Apple simulator cases, with zero failures/skips. The full Xcode app, three Android
variants, app links and BOM passed. App source remains `3c6db4eb`; the intervening
change only adjusts the bounded metadata test deadline and records native evidence.
The normal private API APK is installed on `10.77.77.3:5555`; installed SHA-256 is
`1a114fbc388c843b45749a8475296f506563493a38bccabd146323ceb87cf791`.
Install-only took 5.777s; no UI was launched/driven. [Final evidence](measurements/document-metadata-final-ci-2026-10-30.json)
retains every job/step, report hashes, failed predecessor and installed binary.
Platform's current runtime is schema 103 with dashboard snapshot `1e0522c0`;
older schema-101/phone-72341ef6 paragraphs below are historical. SMTP stays disabled.

Platform Administration correction `3c8fb15a` is locally accepted and pushed;
full CI `41081153648` is active. Reporting review reproduced seven final-policy
MFA failures and the corrected focused run passes; broader verification continues.
These are HRIS business changes. No new Starter implementation port is due.


The full PRD review remains active. Platform's [audit](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/feature-audit-2026-10-30.md)
now retains forty-one reviewed rows across Approvals, Communications, Identity, Expenses,
Leave, Documents, People and Administration; twenty Platform rows and full Mobile review remain. Keep the original
scope; accepted increments do not establish whole-product completion.

- Platform `8014d733` adds optional announcement email, atomic publication capture,
  bounded SMTP delivery and private observations. Local acceptance, schema-102
  recovery and all ten [full-CI jobs](https://github.com/fajarxfce/hris-platform/actions/runs/41005223374) passed.
- `bbb99b48` exposes immutable shared approval history. `fcfdaf26` corrects
  read-completion MFA/delegation expiry; 60 approval API and 13 domain cases,
  quality/packages and two actual API/browser workflows passed.
- `737d39a7` corrects thirteen Communications read-expiry reproductions. All
  106 selected API, 13 domain and three repository cases passed, plus the actual
  API/worker/browser/SMTP workflow (37.1s; 1.0m with setup).
- `a45d5a9b` records Identity's five initial rows and eight detailed sections.
  Its source trees match accepted `8014d733`; downloaded reports verify 190 API,
  35 domain, 22 data, 17 worker and 95 Chromium cases, including eight real workflows.

- Platform `7a1c0251` and `8e5dd3db` complete Document read/command assurance.
  Twenty read reproductions and nineteen command/enrollment reproductions now
  pass; one inventory-replay scenario was an already-correct positive control.
  All 243 selected API, 18 domain and nine data cases, quality/packages and four
  actual API/browser workflows passed (42.5s). Four Document rows retain acceptance. The explicit category requirement
  in `documents.02` is reopened after the People audit found missing metadata.

- Platform `5b60aba3` completes the Expense assurance/input correction and its
  six-row/nine-section review. Twenty-nine genuine failed cases now pass, plus
  ordinary-scope controls and delegator-account wait regressions. Broad/final runs
  cover 335 distinct API, 20 Expense domain and 115 Reporting cases; quality/packages
  and four actual browser workflows pass (1.2m with setup). Scope includes current
  enrollment, receipt and decision delegation, and stable admitted command inputs.

- Platform `c68b721a` and `70b92e59` complete Leave read/command/delegation
  assurance and its five-row/eleven-section review. Ten reader and twenty-five
  command/scope failures now pass. Broad and final targeted runs cover 485 distinct
  API/OpenAPI cases (471 broad plus fourteen added cases), with 65 domain cases,
  quality/packages, six actual browser workflows (50.4s) and three actual worker
  workflows (53.4s). Scope includes final MFA, original receipt approval, beneficiary
  exclusion, stable policy input and current delegated access. Eight added capacity
  compatibility cases preserve ordinary/direct reads without permitting oversized
  delegated decisions; fixture setup failures are explicitly excluded from defects.

[Approval CI](https://github.com/fajarxfce/hris-platform/actions/runs/41009137720)
passed all ten jobs for `fcfdaf26`. Communications/Documents source `8e5dd3db`
passed all ten [CI 41013958569](https://github.com/fajarxfce/hris-platform/actions/runs/41013958569) jobs.
Expense [CI 41018107365](https://github.com/fajarxfce/hris-platform/actions/runs/41018107365)
passed nine jobs and all fourteen worker scenarios, but exceeded the five-minute
step budget (5.2m); later storage/export checks were skipped. Platform `84d37ad6`
records the failure and sets bounded eight-minute worker/twenty-minute job budgets.
Final Leave source `70b92e59e7b41bdafe9f9d1c4b9d5106412777f5` passed all ten jobs in
[CI 41023645590](https://github.com/fajarxfce/hris-platform/actions/runs/41023645590),
including unchanged Expenses, worker, private exports and recovery. Platform
`7dae1e26` records the exact outcomes and the failed predecessor.

People reader correction `b0b7c21c` passed 427 API/OpenAPI, 50 domain and fourteen
repository cases, quality/packages (1124s), three actual browser workflows (36.5s)
and two actual worker/browser workflows (37.3s), including setup. The later command
and Organization correction has fifty reproduced failures plus four prior controls;
all 54 focused API, 52 domain and fourteen data cases plus quality/packages passed
(142s). Final broad acceptance passed 497 API cases. Two legacy worker-import
fixture failures were corrected; all 104 ordinary worker cases then passed with
one independently accepted manual test skipped (226s). Production/package hashes
matched the broad API run. Three normal browser workflows passed in 36.1s and two
worker/browser workflows in 36.8s, including setup. Platform `158b81ac` commits the
complete correction and evidence. Exact source `158b81acce89f637dbf715bb573fffb7b99b92c9`
passed all ten jobs in [CI 41032184925](https://github.com/fajarxfce/hris-platform/actions/runs/41032184925).
Platform retains the terminal job/step evidence in `06f8dede` and its predecessor.

The audit confirmed missing distinct document category and foreign identity/document
business-validity metadata in `documents.02` and `people.03`. Upload deadlines are
operational; tax residency already belongs separately to Payroll. The previous
Documents review omitted category, so that row is reopened. See the
[gap and continuation](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/document-identity-metadata-gap-2026-10-30.md).
People command acceptance is complete. Platform `27e7f77e` implements metadata
backend/dashboard with 689 distinct accepted API cases, 104 ordinary worker cases,
27 database, 18 domain and nine data cases, plus 4,054 dashboard cases, 25 mocked
and five actual browser workflows. [CI 41038369597](https://github.com/fajarxfce/hris-platform/actions/runs/41038369597)
tests exact `27e7f77e722da9e8ac2bde47836353ef92dcc3b3`: all eight shards passed,
but recovery rejected schema 103 because the reviewed verifier still stopped at 102.
Dashboard integration also passed: the original run finished with nine successful
jobs and one recovery failure. Platform `292a30f1` fixes the verifier; 42 Python tests
and actual schema-103 restore passed (57.356s), including metadata/history/RLS and
restored constraint behavior. [Replacement CI 41043798660](https://github.com/fajarxfce/hris-platform/actions/runs/41043798660)
passed all ten jobs for exact `292a30f14498b2eee97a9255f22fe645639658e5`, including
normal/worker browser integration, private exports and schema-103 recovery. Platform Git
retains every terminal job/step and the downloaded recovery summary: 104 objects,
2,098,795 bytes, restored metadata/history/RLS/constraints and completed cleanup.
The original run remains recorded as a failure; no CI run is pending for this source.

Mobile's [owned metadata projection](document-business-metadata.md) is locally
validated. A real mapper-loss reproduction now passes. Root `:check` passes 1,869
desktop tests, Document Chromium suites pass 123 cases, and the dev APK builds.
[Measurements](measurements/document-metadata-read-model-2026-10-30.json) preserve
source/APK hashes and each earlier fixture/setup/lock correction. Format 2 refreshes
canonical records even at unchanged document versions and when the server later
advertises support. Old encrypted facts remain visible during staging; failed
cleanup never removes a confirmed read denial. Finance/Leave DocumentEvidence
stays separate from owned category, identity number and validity facts.

Initial-upload metadata `41fe79f9` is committed/pushed and locally validated: the capability-aware form, typed
confirmation, authenticated payload and encrypted intent retain category/identity/
printed dates. Legacy omission and pending original operations remain unchanged.
All 1,878 desktop tests, 131 Document Chromium cases, root quality checks and dev APK
assembly pass (163.698s). The assembled form exposed and now fixes feedback hidden
above a long form. Read [upload evidence](measurements/document-upload-metadata-2026-10-30.json)
for earlier fixture failures, the real reproduction, source and APK hashes.

First definite upload rejection recovery is also locally validated. Two genuine
baseline failures now pass; unknown codes and earlier uncertainty remain pinned.
The fresh form retains prior fields, and explicit confirmation replaces only the
exact observed rejected intent while keeping file bytes and proof. Cancellation,
competing writes, capability loss and read-denial restoration are covered. Full
root checks, dev APK and Document browser suites pass in 189.354s: 1,888 desktop and
140 browser cases. [Evidence](measurements/document-upload-rejection-2026-10-30.json)
records the baseline controls, method-length correction and source/APK hashes.

The separate metadata header/history/correction transport is locally validated.
It preserves legacy unknown facts, bounded contiguous history and the exact confirmed
correction/receipt across retry; headers no longer depend on a published binary.
Ten new cases pass, including authenticated Ktor payloads and denial/cancellation
boundaries. Root checks, dev APK and both Document browser suites pass in 184.233s:
1,898 desktop and 150 browser cases, with no failures/errors/skips. Exact source,
case names and APK hashes are in [transport evidence](measurements/document-metadata-remote-2026-10-30.json).

Metadata storage and per-document admission now also pass complete local validation.
Account-owned encrypted headers/history commit at most twenty rows and survive
restart/reauthentication; a page decrypts exactly one header plus its selected rows.
Tests retain 1,000 immutable revisions and cover stale writers, scope changes,
cancellation, lost keys, manipulated ciphertext and pending/failed admission storage.
Root checks, dev APK and both Document browser suites pass in 184.621s: 1,912 desktop
and 164 browser cases, without failures/errors/skips. See [storage evidence](measurements/document-metadata-cache-2026-10-30.json).
The existing workspace key is read-only; no database primitive or Starter port changed.

Admitted metadata reads and the actual detail/history route are now locally validated.
Thirteen domain cases and two assembled UI workflows cover ownership/capability,
finite directory/history acquisition, cancellation, local history after credential age,
pending/failed denial recovery and explicit storage-observer restart. Two genuine
failures in the new publication path exposed policy expiry during cache/admission
writes; both now pass with the earlier denial retained. Root quality, Android dev
assembly and Document Chromium suites pass in 197.437s: 1,927 desktop and 177 browser
cases, all without failures/errors/skips. Three narrow screenshots were inspected.
[Read/UI evidence](measurements/document-metadata-read-ui-2026-10-30.json) records
source/APK hashes, actual cases and the earlier compilation/method-length corrections.

Correction command contracts and encrypted storage are also locally validated.
The existing shared CommandStore retains at most 128 account commands, one per
document, with immutable input, exact record/revision checks, bounded authenticated
envelopes and the shared command key. A prior uncertain attempt cannot become a
proven rejection; original receipts and terminal outcomes remain immutable. Three
domain and six actual SQLite/IndexedDB cases cover restart, sessions, capacity,
resource slots, stale writers, lost keys, corruption, cancellation and late accounts.
Full root checks, dev APK and Document browser suites pass in 193.331s: 1,936 desktop
and 186 browser cases, all zero failures/errors/skips. Initial focused tests passed
116 domain/69 data cases in 40.635s before the final terminal assertions.
[Command storage evidence](measurements/document-metadata-command-store-2026-10-30.json)
retains exact sources, tests and the uninstalled APK hash.

Command intake, scoped observation, manual delivery and explicit review are now
locally validated. Confirmation stores immutable input without HTTP; replay checks
current policy/ownership, persists uncertainty before sending, and uses the original
operation after timeouts or later header edits. Denials fence projections even when
outcome/admission storage fails. Acknowledgement requires acquired current metadata;
it never replaces a newer header with an old receipt. No automatic delivery task was
registered. Fourteen domain scenarios cover those boundaries and release per-resource
subscriptions. The initial twelve failures were a read-only fixture, not production
defects; the corrected write fixture and separate read-only control pass. Focused
retry passed 130 cases in 29.808s, then the final overview case was added. Full root
checks, dev APK and Document Chromium suites pass in 180.515s: 1,950 desktop and 200
browser cases, all without failures/errors/skips. [Orchestration evidence](measurements/document-metadata-command-flow-2026-10-30.json)
retains sources, exact cases, setup failure history and the uninstalled APK hash.

Mutable working-copy contracts and encrypted storage are also locally validated.
A bounded draft retains incomplete input and its reviewed header; ordinary saves
cannot change that baseline. The existing ReplicaStore/workspace key holds one
16-KiB authenticated checkpoint per document, protected by exact-copy and revision
checks. Copies reopen across sessions without age expiry. Indexed overview pages
read twenty copies plus a lookahead; a full page decrypts exactly twenty envelopes.
Two domain and six actual SQLite/IndexedDB cases cover restart, stable paging,
concurrent edits/deletion, key loss, corruption, cancellation and late accounts.
Focused tests passed 133 domain/75 data cases in 41.438s; full root checks, dev APK
and Document browser suites pass in 180.730s: 1,958 desktop/208 browser cases, all
without failures/errors/skips. [Draft storage evidence](measurements/document-metadata-working-store-2026-10-30.json)
retains exact cases, sources and the uninstalled APK hash.

Working-copy access and acknowledgement cleanup are now locally validated. Ten
new domain cases cover reopening the original baseline without published binary
facts, exact save/delete recovery, concurrent edits, read-only retention, pending
cancellation/timeouts, ownership changes and denial-before-storage subscription.
Confirmed command acknowledgement removes only a matching no-later draft; failed
cleanup retains the receipt and a concurrent replacement survives. Focused domain
checks passed 143 cases in 37.376s; full root checks, dev APK and Document browsers
passed in 182.812s: 1,968 desktop/218 browser cases, all zero failures/errors/skips.
[Working-copy orchestration evidence](measurements/document-metadata-working-flow-2026-10-30.json)
retains exact cases, source and the uninstalled APK hash.

Catalog resource admission is now locally validated. An actual assembled failure at
`36ddd5ed` showed a denied document's title/file still visible after returning from
details. The catalog now observes at most twenty resource gates, hides the denied
record, preserves other rows and the original cursor, and retains a neutral recovery
action. Unrelated catalog sync cannot clear this denial. Three domain, one state and
one assembled workflow cover subscription disposal, failed admission storage,
pending downloads and offline/pending/successful recovery. The first focused domain
failure was incomplete metadata in the new fixture and is recorded separately.
Focused checks passed 146 domain/four state/one UI case in 100.848s. Full root checks,
dev APK and Document Chromium suites passed 183.001s: 1,973 desktop/221 browser,
all zero failures/errors/skips. Two narrow screenshots were visually inspected.
[Catalog admission evidence](measurements/document-metadata-catalog-admission-2026-10-30.json)
retains the genuine reproduction, fixture correction, source and uninstalled APK hash.

The typed correction editor is now locally validated. Its serial writer coalesces UI
edits into storage revisions and retains the exact pending mutation after a lost local
acknowledgement. Back/confirmation/review flush edits; incomplete drafts reopen with
the original reviewed header. Confirmation and manual retry preserve the original
command, while acknowledgement uses acquired current facts and keeps newer drafts.
Three assembled flows cover retained incomplete input, successful correction, lost
response plus a later server edit, explicit stale-baseline reset and denied/offline
recovery. Two presentation cases cover coalescing and late acknowledgements. A real
new feedback failure put the stale notice above a scrolled form; explicit refresh now
reveals it. Control readiness also waits for pending initialization and avoids redundant
local reloads. Fixture permission, selector and timing failures remain recorded separately.
Two additional domain failures on `8ca77f19` exposed single-command storage errors
masking known/later resource denial. Per-document admission now precedes storage and
remains observed after storage failure. All 148 Document domain cases pass, and final
root checks, APK and Document browsers passed in 178.280s: 1,980 desktop/223 browser,
zero failures/errors/skips. The final full run repeats all three new assembled flows;
six screenshots were visually inspected. [Editor evidence](measurements/document-metadata-editor-2026-10-30.json)
retains source/APK hashes, actual cases, earlier failures and practical limits.

The independent retained overview is now locally validated. Catalog opens **Document
corrections** even when a published binary row disappears. Local commands/drafts
retain their original input, resource admission and draft page cursor; row selection
opens the existing editor or neutral admitted recovery. Opening/reloading does not
send HTTP or replay commands. A 45-copy domain case verifies bounded 20/20/5 paging
and released subscriptions. Typed neutral restriction targets prevent private titles
from appearing during resource recovery. Two new presentation regressions exposed
stale private rows when either local reader failed; both admitted projections must
now be ready before private rows display. Five state cases cover that boundary,
denial union, reload and command/draft display deduplication.

Both actual-storage/controlled-HTTP application workflows passed, including the
retained incomplete draft after binary deletion/offline recovery, and the original
uncertain correction retried after a later administrative edit. Final root checks,
APK and Document browsers passed in 190.845s: 1,987 desktop/224 browser, zero
failures/errors/skips. The full run repeats both workflows. Three screenshots were
inspected. [Overview evidence](measurements/document-metadata-changes-2026-10-30.json)
retains exact sources, reproduced failures, actual cases and the uninstalled APK.

Account's Pending actions now includes the separate metadata summary and direct
overview link. Six domain cases cover original outcomes, exact draft deduplication,
untouched input, age-independent bounded pages, resource/company admission, failed
storage, disposal and delayed session replacement. At most 128 commands and twenty
working copies contribute; a next cursor marks a lower bound. Known resource gates
remain observed through storage errors. Unknown/denied resources hide all counts.
The actual-storage/controlled-HTTP Account flow resumes the same draft, reloads
without additional HTTP, and counts one original uncertain delivery with no duplicate
draft. The existing Leave flow and large-text/Indonesian layout controls also pass.
Full root checks, dev APK and Document browsers passed in 173.186s: 1,994 desktop/230
browser cases, zero failures/errors/skips. Two screenshots were inspected; [summary
evidence](measurements/document-metadata-pending-2026-10-30.json) retains exact
sources, cases, runs and the uninstalled APK hash.

The actual Android application `3c6db4eb` now passes owned API/PostgreSQL/private
Garage acceptance, committed in Platform `1e0522c0`. Two offline process restarts
retain incomplete input and then the read-only original uncertain correction.
Retry after an independent later admin edit confirms the same operation/body;
Done removes its matching draft and command. All seven summary groups empty;
the current version-4 admin header, three history revisions and binary revision
remain intact. Summary reloads stay 36→36 and 48→48. The fixture observed 78 native
requests and exactly two successful company writes; it passed in 432.451s with
owned cleanup and five inspected screenshots. [Native evidence](measurements/document-metadata-native-2026-10-30.json)
retains the APK/source hashes, initial fixture-lifetime failure and precise limits.

Full CI `41071828262` at `3c6db4eb` passed Web/Windows and iOS simulator compilation/
tests, but Linux failed the 1,000-revision browser storage case at Mocha's default
two-second deadline. A module-local bounded twenty-second deadline preserves all
assertions; all 75 Document data browser cases pass locally in 21.607s. The complete
Apple app also passed, with 104 tests in 28 downloaded suites and all terminal
Apple steps successful. [First CI evidence](measurements/document-metadata-first-ci-2026-10-30.json)
retains its overall failure and exact source. Replacement full CI `41077194465`
is running at `d170810f03852f46a948f9f4d5dea9ca4104bdbd`; inspect every final job
and artifacts before accepting it. Do not relabel the failed predecessor.

Next: complete hosted Mobile/Apple checks, then coordinate the controlled schema-103
API/static-dashboard/Mobile rollout. Finish the detailed People review and continue
remaining Platform/Mobile/Starter scope. Keep shared finance/leave evidence free
of private identity numbers and preserve legacy omitted payloads. The browser
configuration covers an HRIS-only metadata module; Starter's generic database
capacity suite already has the same bounded I/O deadline. No implementation port
is due. The full goal remains active.

The private API remains schema 101 with SMTP disabled; preserve the accepted Google
binding and installed phone. The dashboard serves an isolated baseline directory
documented in Platform's handoff, so local builds cannot replace it. The new APK
has not been installed. Coordinate the controlled schema-103 API/static-dashboard/
Mobile rollout after the complete metadata feature is validated. Phone updates are
install-only with advance notice; physical iPhone testing stays waived.

## Distribution configuration — accepted

The audit fixes selected release API precedence over per-environment project
properties, optional app-link configuration through signing and packaging, and
31 missing SBOM POM verification entries. The original artifact hashes remain
unchanged. Android checks the normal test configuration before compiling the
selected release; Xcode forwards the selected backend/API explicitly. The 15
portable files retain Starter's application IDs, demo/remote choice and three-job
CI. HRIS's remote-only policy, build-version UI fixtures and explicit dev-network
XML attributes remain in Mobile.

Mobile source `3dc81d00` passed all 4 jobs in full
[CI 40909156570](https://github.com/fajarxfce/hris-mobile/actions/runs/40909156570). Downloaded Apple reports
confirm 104 tests in 28 suites, zero failures/errors/skips;
compilation/tests/Xcode took 791s/122s/1295s. The complete Xcode app, native/domain
artifacts and explicit selected distribution API/backend assertions passed.

Starter port `77063862` passed all 3 jobs in full
[CI 40916187442](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40916187442). Downloaded Apple reports
confirm 84 tests in 24 suites, zero failures/errors/skips;
compilation/tests/Xcode took 724s/81s/744s. The complete Xcode app, native/domain
artifacts and explicit selected distribution API/backend assertions passed.

Actual local signed Android APK/AAB/SBOM exercises passed in 215.572s on Mobile
and 136.647s on Starter. Both verify the selected release API and native manifest
in APK and AAB, actual signatures, retained domain files and checksums. Mobile
passed 1,860 desktop cases; Starter passed 326 desktop cases in 112 suites. Both
passed 17 distribution regressions. Normal dev builds and production SBOMs were
restored afterward (39s Mobile, 23s Starter), with fixture API/host values absent;
Starter's normal backend remains demo. Every disposable signing key was removed.

A workflow-only follow-up at Mobile `d7e445a8` and Starter `4c794e7` retains
`.well-known` files in the manual artifact upload. The pinned action's actual
`@actions/glob` 0.5.0 selection reproduced five files with both domain statements
missing on each signed fixture; the correction selects all seven and every
checksum matches. Both workflows include hidden files only in the packaged
artifact directory. This separate proof does not invoke upload; application/native
code and ordinary CI definitions remain at the full-CI sources above.

No CI, local check or reusable port remains pending for this audit. No distribution
workflow, upload or phone installation was performed; the installed Mobile APK
remains `72341ef6`. The private server and accepted Google binding are preserved.
Real signed Apple IPA, APNs, public domain/DNS/TLS activation, physical GPS and
production operation retain their separate target requirements. Physical iPhone testing was
explicitly waived; simulator and unsigned Xcode proof does not imply device delivery.

See [distribution evidence](measurements/distribution-2026-10-29.json).

## Previous implementation checkpoint — HTTPS links

Final Mobile source `72341ef6` passed all four jobs in full
[40895095518](https://github.com/fajarxfce/hris-mobile/actions/runs/40895095518).
Downloaded Apple reports confirm 104 cases in 28 suites, zero failures/errors/skips
and the complete Xcode app; compilation/tests/Xcode took 701s/77s/1040s.
Starter port `738db36` independently passed all three jobs in full
[40895798634](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40895798634),
with 84 Apple cases in 24 downloaded suites, no failures/errors/skips and Xcode;
compilation/tests/Xcode took 561s/84s/703s. Generated Android/Apple association
checks passed, and the Xcode/Apple artifact success markers were inspected.
No CI or owned local acceptance remains active from this increment.

The authorized phone has application `72341ef6`, installed with `--install-only`.
APK SHA-256 `5b3754d5097eb0a535d1a98cff5bc37e508ea12a008eec897895d9289797fe4d`,
32,872,857 bytes. Its registered certificate/private WireGuard API match, and
fixture values are absent from runtime code and the binary manifest. First-install
time was unchanged; no phone UI was driven. Actual Google/Home acceptance remains
attributed to the earlier `678783b3` user-operated APK. The private API/dashboard
still returned HTTP 200; preserve the Google binding, keys, volumes and running stack.

The implementation queue is complete for this development pass. Physical iPhone
testing was explicitly waived. Public domain/DNS/TLS association, actual APNs,
physical GPS and production deployment remain separate configuration/target
observations; no result for those is inferred from builds or fixtures. Default
native builds do not enable an unconfigured HTTPS host. Reopen implementation for
an observed failure or a documented requirement; do not recreate accepted modules
from old next-step paragraphs.

The source foundation `d36a1bc9` first passed full CI `40887772486` (103 Apple
cases/Xcode) and actual Android HTTPS cold/login/company/scope/offline/archive
acceptance: 28 requests, no company writes, unchanged read/ack state, 3.2m/3.6m.
Only then was the reusable foundation ported. The later Unicode correction first
reproduced a host alias, then passed seven Mobile JVM/seven Chromium cases before
copying the same two files to Starter. Its own root/Android/Chromium checks passed
326 desktop, seven common/four app Chromium and six generator cases in 88s.
[Measurements](measurements/app-links-2026-10-29.json) retain the source boundaries,
initial failures, corrected checks, final full CI and both phone deliveries.

## Current user direction — 29 October 2026

The user explicitly waived physical iPhone testing for this completion pass
("iphone gkpp gk ditest juga") and requested continued implementation through
completion, with each atomic commit pushed. Do not ask for an iPhone again or
keep that test as a completion blocker. Simulator/contract tests and Xcode builds
remain required; do not claim actual iOS sign-in or APNs delivery from them.
Configurable HTTPS admission/native associations and the proven Starter port
are now complete, with the source and destination evidence above.
The controlled domain remains a deployment setting, not a reason to leave the
configurable implementation unfinished. Preserve the accepted Google binding,
phone data and running private development stack.

The HTTPS implementation is now in Mobile: exact environment-host path admission,
optional ignored public registrations, generated domain/Apple files, variant Android
manifest transforms, Xcode preparation and Universal Link handoff. Root/Android
and Chromium passed in 7m25s with 1,859 desktop cases and 26 common/app Chromium
cases; build-logic tests cover removal and malformed inputs. Configured artifacts
and full hosted CI passed before the foundation port.
Platform's public `/app/...` handoff passed 4002 unit and 14 related browser cases
without session or business requests.
See [app links](app-links.md); no real domain is enabled by the default build.

## Start here

1. Read `AGENTS.md`, inspect `git status`, the branch, recent commits and upstream.
   The working branch at this checkpoint is `feat/native-push`.
2. Read [validation](validation.md), [local-first coverage](local-first.md), the
   affected module contract and [template updates](template-updates.md).
3. Read the [Platform handoff](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/agent-handoff.md)
   and [remaining PRD delivery](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/completion.md#remaining-delivery-sequence).
   Backend/dashboard work is on Platform `feat/firebase-auth`, with application
   integration `b7f42070` / schema 101 and subsequent native acceptance fixtures.
   Full CI at `9345c19b` passed all ten jobs after correcting recovery report upload
   and a closing-report browser assertion; its exact run is in the Platform handoff.
   Starter work is on `fix/push-token-lifecycle`.

The three local checkouts are siblings under `/home/fajar/hris`. A fresh clone
must be able to continue from tracked evidence; local `.work` logs, screenshots
and `/home/fajar/hris/PRD-PROGRESS.md` are supplementary. The user authorized ongoing
implementation and a push after every atomic commit. This does not authorize a
release. Preserve unrelated edits and keep implementation, tests and docs together.

## Current phone delivery: installed after authorized key replacement

Current implementation adds iOS Firebase email/password configuration loading,
Apple bundle restrictions and an owned Google system-browser PKCE/code exchange.
All three matching public plists are installed locally and ignored. Mobile first
passed local password checks at `64039536`; Apple run `40853359016` compiled and
passed the four reader cases but failed one existing cancellation fixture (81/82
native cases passed). `196c1171` replaces both Firebase transport fixtures' racy
Boolean assertion with explicit bounded cleanup acknowledgement. Its Apple
follow-up [40855843460](https://github.com/fajarxfce/hris-mobile/actions/runs/40855843460)
passed 82 simulator cases and full Xcode before the password/configuration port
to Starter `8a75de5`. Starter root/Android, 308 desktop and 31 Chromium cases passed.
Its independent Apple run `40861505322` also passed 62 native cases in 18 downloaded
suites and the full Xcode app, with no failures/errors/skips.

The Google increment passes 65 focused desktop identity cases and public dev OAuth
configuration preflight. Root checks, Android assembly and 82 identity/application
Chromium cases passed in 3m23s, with 1,854 cases in the desktop report set. Full run
[40858176601](https://github.com/fajarxfce/hris-mobile/actions/runs/40858176601) at
`43cd8ec6` passed all four jobs. Downloaded Apple reports confirm 97 native cases
in 26 suites, zero failures/errors/skips, and the complete Xcode app. This closes
the Mobile verification gate for the generic Google port. Starter `e0c6071`
passed root/Android, 320 desktop and 46 Chromium cases; its full
[run 40864341588](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40864341588)
passed all three jobs. Downloaded Apple reports confirm 77 native cases in 22 suites,
zero failures/errors/skips and the complete Xcode app. Compilation took 526s,
tests 87s and Xcode 589s. The generic Google port is accepted. Direct
iOS-configured HTTPS password preflight also
returned Firebase proof for the exact owned fictional fixture, without HRIS
exchange or binding changes. Source/run details are in
[measurements](measurements/apple-firebase-2026-10-29.json).

Actual iOS sign-in/APNs still require device/provider acceptance; injected-browser
native tests and an unsigned simulator build do not claim that result. Physical iPhone
testing is waived for this completion pass; the [optional iOS procedure](ios-acceptance.md)
records future setup, observations and cleanup. The provider/owned-browser/crypto changes are
reusable; HRIS session policy and configuration stay here. See the
[Firebase contract](firebase-auth.md) and [validation](validation.md).

The user subsequently tried Google on the phone. Firebase confirmed the exact
verified Google identity; Platform `533472ca` records the successful explicit
binding after retiring the fictional password UID. The user retried and confirmed
company/Home, with one new native session independently visible in authorized
server metadata. Actual Google → Firebase → HRIS sign-in is accepted on the phone.
The old emulator session is revoked, with cached data retained; do not reactivate
its old Firebase binding over the real user's account. Private paths and recovery
details remain in Platform's current runtime record.

The user requested installation on the WireGuard-connected Samsung SM-A725F and
subsequently authorized uninstall/reinstall after the signing-key mismatch was
explained, including loss of local HRIS login/drafts/data. Both uninstall and the
install-only command returned **Success** on `10.77.77.3:5555`. The signing-key
question is resolved; do not ask for that approval again.

The installed normal dev APK is `dev.fajar.hris.mobile.dev`, version 1.0.0 / code 1,
32,853,128 bytes, SHA-256
`1883d1da41fdeabe77165de9fc75f1d106ff7583ae93aa92878940c7a2addd0e`.
The phone's installed APK hash matches the prepared artifact. Package metadata
reports the new installation and `stopped=true`, `notLaunched=true`; no app launch
or phone UI interaction was performed. This build uses application `678783b3`,
built at `f740d658` in 26s, with the normal `http://10.77.77.1:8088/api/v1/`
endpoint. See [initial rejection and successful delivery](validation.md#phone-update-signing-key-mismatch).
The local HRIS app data was removed with the user's authorization.

The API was unavailable during installation. The user then authorized continuing
development startup: Platform `703af975` records a new persistent private `hris-dev`
stack at schema 101, with actual HTTP 200 API health from this phone and a dashboard
user service at `http://10.77.77.1:5173`. Firebase Management confirmed the exact dev
app and registered this APK's SHA-1; its web client still matches the installed
configuration. No replacement APK is needed for that provider registration.
See [current development runtime](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/current-development-runtime.md)
for ownership and private configuration/access paths. Fictional company/employee
provisioning is complete: operator MFA, worker-delivered local invitation, standard
employee access, independent person link, weekday schedule, demo policies and
independent approvals. The same normal APK passed actual Firebase email/password
login, company selection and Home on the owned Android 35 emulator. Its document
catalog synchronized a real worker-inspected PDF and the detail displayed
**Downloaded**, 734 B. Platform's private database/content/key checkpoint passed
isolated restoration, followed by successful host/phone network and browser checks.
See [runtime evidence](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/measurements/development-runtime-2026-10-29.json).
The emulator is stopped with this persistent demo account's data retained.
There is no application change or generic Starter port in this increment.
Human Google sign-in is accepted above; physical FCM/location acceptance remains
separate from successful package installation and network health. The prior
loopback APK remains ignored at `.work/fcm-native-validated.apk`; do not distribute
it to this phone.

## Accepted result: actual Android FCM passed

The sender credential question is resolved. Platform `55288bc4` installed the supplied service
account privately outside Git and accepted two actual FCM publications on the
owned Android 35 emulator, using unchanged Mobile `678783b3`. Both foreground and
background notifications displayed generic copy; tapping the latter opened the
correct company/Inbox item without changing either item's unread/unacknowledged
version-zero state. The 55 native requests made no company mutation. Disable/logout
removed the server token/hash. Fixture 6.2m/6.6m with setup; all owned resources
are cleaned and emulator data retained. See [exact evidence and timing limits](validation.md#actual-fcm-foreground-and-background-delivery).
The initial manual-readiness and two-minute observer timeouts remain recorded;
the final 350-second observer passed without changing application behavior.

There is no Mobile implementation change or generic Starter port in this increment.
The existing SDK/queue/navigation foundation passed this actual provider case.
Android Google exchange was subsequently accepted above. iOS/APNs, verified HTTPS associations and physical-location
acceptance still need their separate inputs. Do not ask for the supplied FCM
credential again; its private host path is in Platform's current handoff.

## Previous result: Apple CI correction passed

The earlier local application change `678783b3` corrected the English Expense count label to
`Expense items: %1$s`, after inspecting the accepted native screenshots. Root
checks and Android dev assembly passed in 2m31s; the initial SDK-path setup failure
and source/result boundary are recorded in [validation](validation.md#expense-item-count-copy).
Earlier native fixtures and hosted CI below still refer to `02743a9f` application
sources. No Starter port applies to this HRIS copy edit.

Full run `40815794384` finished: Windows, Web and Linux/Android passed.
Its overall conclusion remains failure because Apple timed out.
Apple timed out during cold test compilation, not an observed compilation error.
See [CI correction](validation.md#apple-ci-cold-compilation-budget). The workflow
now separates compile/test/Xcode time limits, preserves all three test targets,
and supports manual `scope=apple` with independent concurrency and retained reports.
Apple follow-up [40817592306](https://github.com/fajarxfce/hris-mobile/actions/runs/40817592306)
passed at `451a2c4d`: 27 simulator tests in ten downloaded JUnit suites, no
failures/errors/skips, complete unsigned Xcode build and retained artifact. Compile
took 8m3s, simulator execution 52s and the Xcode step 18m54s. The generic workflow
correction is accepted in Starter `1817e45`: independent Apple run
[40820482699](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40820482699)
passed 27 simulator tests, the full Xcode build and artifact upload. No port is
pending. [Measurements](measurements/ci-2026-10-29.json) retain both Mobile runs,
the later copy-only checks and Starter proof.
Those local native fixtures are all stopped/cleaned. iOS/physical inputs
remain external; the FCM credential and actual Android delivery are now accepted above.

## Accepted evidence: native Attendance capture recovery

Platform `a837320f` verifies actual Android/API/PostgreSQL/worker response loss,
offline cold restart and exact replay of an explicit capture without location.
Stable summary reload stayed 21 → 21; one version-zero event retained its original
capture/receipt time. All six local pending rows cleared while Home/server still
required independent review with zero accepted minutes. There were 30 requests/two
intended writes; fixture 4.6m/5.2m with setup. All owned local resources are cleaned,
location restored and emulator data retained. See
[validation](validation.md#attendance-capture-response-loss-on-android).

Cross-platform CI `40815794384` was dispatched at `b0a95391`, with the same
`02743a9f` application sources used by the native fixture. Its three non-Apple jobs
passed; the separately corrected Apple job and validated Starter port are recorded
above. Platform full [40820912608](https://github.com/fajarxfce/hris-platform/actions/runs/40820912608)
then passed all ten jobs at `9345c19b`, including real API/worker/export integration
and schema-101 restore with a downloaded sanitized artifact. Those CI runs are
complete; no generic port arose from the native HRIS business fixtures. The newer
Starter Google port has its separate accepted full run above. Physical/iOS acceptance
still needs its actual external inputs. Android Google, the supplied FCM credential
and Android delivery are accepted above.

## Accepted coverage: read replicas and commands

The [local-first coverage](local-first.md#current-coverage-and-continuation) now
matches all nine `ObserveCompanyData`/`SyncCompanyData` registrations and the six
pending-action feature observers. Its old next-work list incorrectly still called
document feeds and the shared pending summary unfinished. Directory, private
profile and document catalog storage were checked against their account-storage
mappers and company partitions. Expense/Leave/Overtime working-copy and file/
attendance command contracts already cover the current employee editors.
This is a source/documentation reconciliation, with no new runtime acceptance or
application change; previous measured outcomes remain in their feature ledger.

Platform's payroll period comparison passed 119 API, 94 worker and 12 data tests,
3,997 dashboard tests, 23 distinct Chromium scenarios and an actual five-actor
January/February workflow with retained tax continuity and live access withdrawal.
Push delivery observations also passed schema-101 recovery. Neither administrative
feature needs a Mobile/Starter port. Continue missing acceptance and concrete PRD
gaps without repeating completed implementation or the resolved FCM credential question.

## Accepted evidence: native upload and inspection review

The actual Android/API/PostgreSQL/worker/Garage fixture passed in 8.7m/9.2m with
setup. One committed chunk lost its response; offline restart retained one transfer
and retry preserved the original command. A stopped scanner-unavailable job became
one review item, surviving a second offline restart. Explicit inspection retry,
cancellation, local deletion and logout passed. All six summary rows became empty
before local deletion. Independent server checks observed 57 requests, nine intended
company writes, one unpublished cancelled revision and four single-attempt chunks.
Summary counters stayed 32 → 32 and 42 → 42. See
[complete evidence](validation.md#interrupted-upload-and-inspection-review-on-android)
and [Platform's fixture](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-upload-acceptance.md).

The unchanged `02743a9f` loopback APK was reused; no implementation/Starter port is
due. All owned resources were cleaned and emulator app data retained. Platform
subsequently accepted actual elapsed export retirement, including the unchanged
one-hour physical grace and lost deletion receipt retry, at `1abdbce9`. iOS/physical
cases still need their separate setup. Actual Android Google exchange and
foreground/background FCM hints were subsequently accepted above.

## Previous checkpoint: native Firebase registration recovery

Actual Android Firebase Messaging token acquisition and HRIS registration now
pass response loss, offline restart, original-command replay, explicit disable and
logout. The final fixture observed 51 requests, one registration, two identical
PUTs, one disable, version 1 and no retained server token. No company writes were
made. Full acceptance took 9.7m/10.3m with setup. The final emulator APK SHA-256 is
`2b603fff2e40e31ee9cf5a45e694ea6aae8c96ae6c72cd4168d920c2e239c0c9`.

Two HRIS-specific fixes were required: clamp submillisecond server expiry to the
session's retained millisecond boundary; and complete retirement when a previously
owned family returns the exact absent-session result. Other expiry overruns,
errors and accounts remain rejected/retained. The first upgrade recovered an
already retained registration command; the second cleared an absent family before
new registration. See [full evidence and corrections](validation.md#firebase-registration-and-offline-recovery-on-android)
and [Platform's tracked driver](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-push-acceptance.md).

Root checks passed 1,840 desktop/UI tests plus tool/static checks; identity browser
suites passed 64 domain and 49 data cases. Android assembly passed. Local setup
requires `ANDROID_HOME=/home/fajar/Android/Sdk`; browser tests use
`CHROME_BIN=/home/fajar/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome`.
The first command omitted these environment settings and is not a passing browser
result; the corrected full command passed in 1m59s. Services, control files and
forwarding were cleaned, connectivity restored, and emulator data retained. No
phone was connected. Generic SDK sources did not change; no Starter port applies.

At this earlier registration checkpoint, server FCM credentials were unavailable.
The user subsequently supplied the file and actual Android FCM delivery passed
above. Continue Google token exchange and iOS/physical acceptance when their
inputs are available; do not repeat the resolved credential question.

## Last accepted increments

- Partial-download recovery now passes actual API/PostgreSQL/worker/Garage/Android:
  one retained MiB and transfer count 1 through offline process restart, summary
  no-op HTTP (31 → 31), explicit resume from byte 1,048,576, native save matching
  3,145,841 bytes/SHA-256 and six empty summary rows afterward. The server observed
  49 requests and zero company mutations. Fixture 9.7m/10.2m with setup; unchanged
  loopback APK reused. All owned services, control files, forwarding and public
  copy were removed; emulator app data retained. See
  [executed evidence](validation.md#partial-download-recovery-on-android) and
  [Platform fixture](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-download-acceptance.md).

- `c650e0d7` adds an actual unconfirmed Inbox command: server commit
  followed by response loss, Pending actions count 1 through offline cold start,
  local summary reload with no HTTP (30 → 30), exact-operation Retry and zero
  pending afterward. The independent server observed two identical read commands,
  version 1 and no acknowledgment across 42 requests. Fixture 6.0m/6.4m with setup;
  APK assembly 45 seconds. All owned resources were removed and the emulator
  stopped without wiping data. See [executed evidence](validation.md#inbox-command-response-loss-on-android)
  and [Platform's tracked procedure](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-acceptance.md).
- `60456c48`: real Android 35/API/PostgreSQL/worker URL and Inbox archival
  acceptance. Cold-login/company gating, repeated/resumed navigation, scope/query
  rejection and offline restoration passed. Independent server reads proved no
  implicit read/acknowledgement or company writes. Archival stayed enforced after
  offline restart. See [exact URL evidence](deep-links.md#android-url-acceptance).
- `1c1f9104`: actual Android/API Pending actions acceptance for pristine and edited
  Leave drafts, offline process restart, local summary refresh, reconnect and
  explicit deletion. Subsequent cases now cover all six feature observers; consult
  their individual evidence above without generalizing to every possible state.
- `9a557dcc` records cross-platform Firebase CI. Provider implementation `d191c61d`
  passed 1,837 desktop/UI tests, 119 related Chromium cases and Android assembly.
  Actual Firebase email/password completed HRIS exchange, MFA, company selection
  and logout; the temporary provider account was removed. Google chooser/Back
  passed, but actual Google token exchange did not run.

Reusable Firebase adapters are already ported to Starter `9a13791` and documented
at `a16325e`. Notification resource paths, token retirement and incoming-link
ownership were also ported; consult the file/commit ledger in template updates
before repeating a port. The latest native registration increment also fixes the HRIS metadata/cleanup
policies described above.
Platform worker observations/global audit are HRIS administration work, and the
native transfer fixture changes no application implementation; no Starter port is due.

## Next work and actual external inputs

All six Pending actions feature observers now have actual Android/API recovery
examples: Inbox, Attendance, Overtime, submitted Leave, saved Expenses and document
transfers/inspection review. See the specific validation sections for unchanged
summary counters, exact replay, final server invariants and cleanup; they do not
prove every state combination or physical target. Nine company data-sync registrations
and existing employee working copies are reconciled in [local-first coverage](local-first.md).
Do not repeat these features or native cases because an older checkpoint called
them next work. Continue concrete uncovered requirements or observed failures.

Platform full CI `40820912608` passed all ten jobs. The earlier Mobile and Starter
Apple correction runs also passed. New Mobile provider CI `40858176601` is accepted;
Starter Google port run `40864341588` also passed all three jobs as recorded above.
Those provider runs are complete. The newer HTTPS run `40887772486` also passed all four jobs and 103 Apple
cases/Xcode. Its Starter port and the subsequent Unicode correction both passed their final
full CI runs above. Do not repeat completed provider-foundation
work or use the older provider CI as proof of the HTTPS increment.

Firebase project `cmp-fajar` has Email/password and Google enabled. Android configs
exist locally in ignored `apps/android/google-services.json`; do not commit provider
credentials. Actual FCM foreground/background delivery and notification navigation
and Android Google/Home are accepted above. Matching public iOS plists are installed.
Configurable native/route association is now implemented. Public domain
publication, APNs setup and physical location remain deployment observations.
Physical iPhone testing is waived; only the domain question remains pending.
Do not repeat it while waiting or request an iPhone again. The supplied sender credential is installed privately
in Platform's host configuration. Public Android config is a separate file; do
not repeat the resolved credential question.

## Validation and device ownership

Use JDK 21 and `ANDROID_HOME=/home/fajar/Android/Sdk`. On headless Linux, root
checks use `xvfb-run -a ./gradlew --no-daemon :check`; Android development assembly
is `:apps:android:assembleDevDebug`. Select related Chromium tasks from the owning
module and inspect [development](development.md) and [distribution](distribution.md).
Do not infer device/provider acceptance from compilation or fixture responses.

For this workspace, supply tool locations explicitly (adjust paths on another host):

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
ANDROID_HOME=/home/fajar/Android/Sdk \
ANDROID_SDK_ROOT=/home/fajar/Android/Sdk \
CHROME_BIN=/home/fajar/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
xvfb-run -a ./gradlew --no-daemon :check
```

Append the affected browser/build targets to that command. Keep fixture-specific
API overrides local and explicit; this command preserves normal app configuration.

Use AVD `hris-mobile-dev`, serial `emulator-5560`; never wipe its retained data.
The last acceptance used a build-specific loopback API override. Preserve the
normal environment configuration and inspect any fixture before starting another.
The accepted fixtures were stopped and private connection files removed. Phone
delivery is install-only under `AGENTS.md`: notify before installation, select its
serial explicitly and do not launch or operate its UI while it is in use.

Update this handoff and validation after each accepted increment. Include the
exact remaining work, known failed attempts and corrected causes, resource cleanup
and whether a reusable change still needs its validated Starter port.
