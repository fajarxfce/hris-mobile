# Agent continuation — HRIS Mobile

Read this checkpoint first, then [architecture](architecture.md) and the contracts
linked below. Earlier checkpoints remain verbatim in
[history through original queue matching](agent-handoff-history-through-original-queue-matching-2026-11-07.md)
and [history through build 50](agent-handoff-history-through-build50.md).

## Current work — 7 November 2026

Native original room receipt transport is accepted:11 Kotlin files, current owner/
actual build distinct from immutable original proof, exact full echo/account checks,
4KiB response bound, read-only authentication replay and retained uncertainty.
Focused93348 passed30 cases/4 suites183.845s; full78045 passed3,548 fresh cases365.793s,
4,354 stable inputs, no failures/errors/skips or Android release lint issues. Root,
Android host, format/architecture and Detekt passed. All Mobile sessions are reaped.
[Contract/evidence](collaboration-space-room-receipts.md). No product caller or APK.

Group `4e1b5b90` is accepted/pushed: native original receipt HTTP+shared DI,238 API/
FULL worker cases504.080s, one existing elapsed-retention skip,8,619 unchanged inputs.
ONLY heavy local job51650 is Group room-proof-capture/characterize-1: one test file
is frozen to characterize real initial HTTP write after native refresh. Current
native credential DTOs/session storage do NOT expose native version, while the old
writer fingerprints the actual bearer version/build. Never guess a durable proof.

NEXT accept that characterization, then implement the stable versioned room intent
namespace, preserving original fingerprints/receipts and current native/device/peer
admission. Eight candidate domain/test files are PREPARED ONLY outside source in
Group .work/room-stable-intent-2026-11-07, not applied or compiled; verify their before
hashes before use. No new HTTP route for stable intent yet. Stable native intent and
its explicit receipt acquisition must precede durable Mobile commands and owning
original-message delivery. Missing receipt is not a fence; retain uncertainty before
any explicit replacement. Then publication/history, parent recovery, product routing
and all54 PRD groups. V117-137 remains disabled/unshipped.

Original72a native42358423298 passed both ABIs509/82 with exact immutableSDK4, original
keys/queue preserved through process death and offline held-writer recovery. Its
first control42358423232 FAILED the old app-test fake, fixed in e8fc7844; original
artifacts/failure are audited in777d6215. e8fc first control42360096500/native42360096584
are IN_PROGRESS. Preserve later first pending runs before another primary promotion.
Phone53 latency stays OPEN; last bounded reconnect timed out10:10-10:11UTC. Existing
optional feedback is pending, no new question or phone UI driving. Live services,
SDK4, original Bima data/keys/IDs and Starterb11437a are unchanged.

## Prior native room transport

Native group conversation/open and room request/status transport is now accepted:
22 source/test files; full87652 passed 3,530 fresh cases/439 suites in416.326s with
4,349 unchanged inputs and zero failures/errors/skips/Android release lint issues.
Focused33116 passed20 cases/106.323s. First71194 passed18 transport cases but failed
fresh app-test compilation: an old runtime fake omitted the new original-writer method.
The fake now rejects implicit writer acquisition; application bindings pass. All Mobile
sessions are reaped. [Contract/results](collaboration-space-conversations-and-rooms.md).

Group86925 passed all6 native room database cases in118.329s,8,613 stable inputs,
with format/architecture and no failures/errors/skips. It confirms the existing
original-proof fingerprint restriction without modifying provider/production behavior.
Group `c80432eb` stores the test and
[next recovery contract](https://github.com/fajarxfce/hris-platform/blob/c80432eb/docs/collaboration/native-room-receipt-recovery.md).
All sessions are reaped; no heavy job/emulator remains.

NEXT retain durable open/room commands, including original native parent/version and
actual platform/build, and implement explicit original-room receipt recovery. Source
inspection found that Group room idempotency hashes those proof fields, so unchanged
JSON can conflict after ordinary native rotation or app upgrade. Preserve that failure
and uncertainty; never invent a receipt or replace the old operation on timeout. A
missing receipt alone does not fence a delayed original. Then owning original message
delivery, receipt/history publication, native-parent recovery and product routing.
Transport tests verify wire preservation; they do not accept server recovery across
an earlier committed fingerprint. Group V117–137 remains disabled and no new APK exists.

Program `e8fc784489e32136a0b47d0df5778af8fee562e5` is pushed to recovery and primary.
Its first control `42360096500` and native `42360096584` are IN_PROGRESS.
Prior72a control/native outcomes are recorded above. Protocol `42358423295` is fully audited SUCCESS:14 checks each, all14
Platform-pinned sources verified; native91.638s/artifact13568937867 and classic69.285s/
artifact13568743001. [Original protocol evidence](measurements/collaboration-original-writer-protocol-ci-2026-11-07.json).
Do not rerun accepted provider scenarios to audit them. Continue on the recovery branch.

The original `9deb55bf` control and native runs are now fully audited SUCCESS.
Apple: 1,941 cases/221 suites; portable: 4,749/528; each Android ABI: 499/81,
all without failures/errors/skips. Both native APKs contain the immutable accepted
SDK4 hashes with NDK 28.2.13676358. Original artifact digests were verified.
[Control/native evidence](measurements/collaboration-sdk4-packaging-control-native-ci-2026-11-07.json).
Protocol evidence below is also accepted. No provider test was rerun for this audit.

The previously accepted restricted original-writer
increment is accepted locally:17source/test/probe files. Full8613 passed3,512fresh cases
in441suites/438.471s on4,328 unchanged inputs, zero failures/errors/skips/lint errors;
49 existing proof lint warnings. Native16901 passed154.702s for x86_64 and ARM64, with
original keys/two pending messages intact through interrupted adoption, process death,
offline restoration and replays. Held-writer exclusion, partial/changed resume rejection,
close/reacquisition passed. Both fixtures, emulator and transport are stopped. All
sessions are reaped. [Contract](collaboration-space-original-writer.md),
[measurement](measurements/collaboration-space-original-writer-2026-11-07.json).

Actual native adoption preserves a DIFFERENT original physical device ID from the
current logical organization device. The earlier matcher incorrectly equated them.
Red83172 reproduced5failures/10cases in75.293s; focused39902 passed41cases in131.880s
following correction. Original device/family/bootstrap and immutable commands remain
exact; current logical owner/device is guarded independently. Never rewrite old IDs.
The Android handle exposes no new enqueue, store, join or sync capability. A cancelled
waiter cannot lose its late captured receipt, and close drains before freeing the writer.

The group direct-conversation/open and room request/status transports are accepted above;
durable commands and owning original-delivery remain absent. Group native-space-room.md/space-conversations.md
and V132 original-pair-routing.md define the contract. Open keeps operation/space/peer
fixed and returns a GROUP conversation ID; retain original company/conversation IDs
separately. Terminal room retry uses observed account+revision together. A LEGACY room
must equal the original room. Delivery still needs current recipient admission and a
published group binding; a local queue match/history read alone grants neither.
Then original receipt/history publication, native-parent recovery, product routing and
all54 PRD groups. Supplementary trace: `.work/space-original-queue-delivery-2026-11-07/plan.md`.

Mobileca76e87d/Main1de67d71 mixed-room fixture is pushed and accepted: one real missing-
key-room403 then one encrypted200 to a ready recipient whose SDK was closed. Queue
return to Sent1,316ms/journal1,321ms/completion1,815ms. This warm-runtime case did NOT
reproduce the reported phone delay. No production retry/timeout/writer change follows.
Phone53 latency remains OPEN and its optional question is already pending.
[Diagnosis/result](collaboration-mixed-room-delivery-diagnosis.md).

Primary is nowe8fc7844. Prior9deb55bf control42354718734 and native
42354718728 are accepted; preserve each later first pending run before further primary
promotions. Protocol42354718729 is fully audited:14checks each, all14source hashes match
Platformpine0dbb567. Classic89.411s artifact13567970547; native89.862s artifact13567611736.
[Protocol evidence](measurements/collaboration-sdk4-packaging-protocol-ci-2026-11-07.json).
First05de control42348626104 is fully audited SUCCESS: Apple1,912/218 and portable
4,691/522, zero failures/errors/skips, original artifact digests verified.
[Control evidence](measurements/collaboration-original-metadata-control-ci-2026-11-07.json).
Packaging9deb's pinned NDK and unchanged SDK4 hashes remain accepted; no SDK rebuild.

Original SDK/application queue matching and its owning admission read have passed
local acceptance. Full1361 passed 5,538 fresh cases in 649 suites (512.73 seconds), with 4,320
unchanged inputs and no failures/errors/skips or Android lint findings. Program
`032697ea` is committed and pushed on the temporary recovery branch.
Eight source/test/DI files implement the complete bounded queue match and owning
read. The focused final run passed 22 fresh tests in 3 suites (201.624 seconds).
[Contract](collaboration-space-original-queue-reconciliation.md),
[measurement](measurements/collaboration-space-original-queue-reconciliation-2026-11-07.json).
No queue delivery, settlement, new acknowledgement or product caller was added.
This increment retains original physical/native/bootstrap/transaction identities.

The native packaging correction is accepted locally. First 05de
native run 42348626124 failed on both ABIs before installing the fixture:
`check_space_queue_adoption.validate_apk` rejected the native library digest.
Both original logs say Matrix could not be stripped and was packaged as-is. The
failed APK bytes were not uploaded, so their exact library hashes are unknown.
Local reproduction from the immutable published AAR with NDK 28.2.13676358 produces
both already accepted stripped hashes exactly. Keep the strict SDK check. Pin the
proof NDK in the version catalog/proof module, install it before CI assembly, and
run the same actual APK validator before starting the emulator. No SDK rebuild or
new acceptable hashes. Plan/reproduction: `.work/native-sdk4-packaging-ci-2026-11-07`.
The accepted correction pins the proof NDK, installs it from the catalog before CI
assembly and records native hashes before strict APK validation. Source, local checks
and both preflight outcomes are in the linked packaging measurement. Remote acceptance
of the corrected source is accepted above; no SDK update or extra provider send was needed.

After that, continue actual original-message delivery under current organization
admission, immutable receipt/history publication, hard/revoked native-parent
recovery, product/root routing, and all 54 communications PRD groups. Group program
`e45a4313` is accepted but V117–137 remain disabled/unshipped. No PRD group is complete
(3 in progress, 51 planned). Do not stop the broader work because of phone feedback.

## Phone and Bima

Release 53 is installed on `10.77.77.3:5555`, with original UID 10962 and first-install
identity retained. Source `08545820c0b58f46d784971e71ceb207dd628c0f`; APK SHA-256
`994d161ade3455745157d0793475b5091d67e968af909bda7a282dce163152c6`.
Its foreground wake no longer appends new chat work behind a retrying WorkManager
job. Synthetic Android scheduling measured 436 ms; actual phone delivery latency
remains OPEN. [Release evidence](measurements/collaboration-release53-2026-11-07.json),
[foreground contract](collaboration-foreground-delivery.md).

The user's latest question is whether Bima has no keys because he has not logged
in. Read-only server audit at 06:42:18 UTC confirmed one active Bima Demo device,
50 signed one-time keys and one fallback key; no keys were read or consumed.
Bima need not remain online. Earlier phone52 traces matched one HTTP200 to Bima's
room and repeated403s to another owned room with no recipient device. This does
not prove prompt UI acknowledgement. The last passive phone53 audit at 08:38:32
UTC found zero post-install lines, fatals or chat diagnostics; unchanged private
log SHA `518c42ad6b85ace21148a5ba2750bdfc338828db4c59c8372d8e95485297dd66`.
Silence is not delivery evidence. An optional release53 feedback question is already
pending; do not repeat it. No phone UI/new messages were driven.
[Key evidence](measurements/collaboration-bima-key-availability-2026-11-07.json).
The next passive read around09:18UTC failed because ADB was offline. One bounded
reconnect timed out (session11456, exit124, reaped). There is no newer phone evidence.

## Accepted foundations and first CI

- `b7f39b38`: finite owning original-conversation reads; 5,498 full fresh cases.
- `11e2b36b`: explicit Android notification cancellation after neutral reply grace;
  delayed-OS-expiry red/green proof, 18 raw cases, 4 runtime cases, 3 ordinary process
  deaths, 5 actions including real SystemUI Reply. Three screenshots inspected.
  [Notification evidence](measurements/native-notification-retirement-2026-11-07.json).
  Starter `b11437a` ports the exact Android adapter; independent root/Android/lint
  passed. Common/Apple unchanged, no unrelated Apple rerun.
- First `1bb572eb` control 42344998885 is fully audited SUCCESS: Apple 1,900/216,
  portable 4,667/518, zero failures/errors/skips. Digests verified.
  [Control evidence](measurements/collaboration-original-queue-control-ci-2026-11-07.json).
  Its native 42344999033 failed ARM notification expiry after 486 host cases; x86
  passed 492 cases and native proofs. That failure predates accepted `11e2b36b`.
- First `05de0bf6` control 42348626104 is fully audited SUCCESS (Apple1,912/218; portable4,691/522). Native 42348626124 is fully
  audited FAILED at SDK packaging before any runtime proof; 493 host cases per ABI
  passed. [Failure evidence](measurements/collaboration-original-metadata-native-ci-2026-11-07.json).
  Protocol 42348626066 is accepted: 14 checks each mode and 14 source hashes match
  pinned Platform `e0dbb567acc4c5571b82324c515ed8ddc145007d`.
- Primary `feat/communications` is `e8fc7844`; current work is on temporary
  `feat/chat-recovery-20261107`. Push every atomic commit. Preserve every first pending control/native
  run before later primary promotions; even `cancel-in-progress: false` replaces
  an older pending run. Do not rerun accepted provider sends just for evidence.

## Repositories and invariants

Main Platform `feat/communications` checkpoint `d1dbbc3a` and Group worktree
`feat/communication-space` checkpoint `c80432eb` are pushed. Group production source remains
`e45a4313`. Live API `2e75fe1ee88b5766259fc3efd3ab7f21e70d621a`, worker `10485916`,
schema 116.1 and V117–137 disabled status are unchanged. Starter is clean/pushed
at `b11437a`. Detailed program state is in each repository's handoff.

SDK4 is immutable: release 437093395, tag `matrix-sdk-26.10.23-hris.4`, target
`4228599ba659cf68cfea1138e6f8017b0b77c222`, AAR SHA-256
`9b6806de02b7d40b8487b54a51b192c114a38c68c6dfe35c68faf50d3b6bfd21`.
Never rebuild/republish it. Keep the frozen Rust/wrapper trees and original failed
native fixtures. Original Bima image `.work/demo-chat-device-2026-11-05/bima/userdata.img`
and ALL sidecars, private keys and pending identifiers must be retained.

One heavy local job, never Gradle alongside an emulator. No subagents or goal.
Announce each phone install, use the explicit serial and `--install-only`; never
launch/drive the user's phone UI. Physical iPhone testing is waived, not relevant
Apple compile/runtime gates. No fake ACK, replacement pending transaction or silent
revival of an obsolete native parent. Keep domain policy/use cases separate from
repository/datasource I/O and presentation. Generic fixes return to Starter after
Mobile acceptance; HRIS policy/configuration stays here.
