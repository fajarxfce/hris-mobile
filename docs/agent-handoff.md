# Agent continuation — HRIS Mobile

Read this checkpoint first, then [architecture](architecture.md) and the contracts
linked below. Earlier checkpoints remain verbatim in
[history through original queue matching](agent-handoff-history-through-original-queue-matching-2026-11-07.md)
and [history through build 50](agent-handoff-history-through-build50.md).

## Current work — 7 November 2026

No heavy local job or emulator remains. Packaging gate15900 passed 5,373 fresh
cases in 754 suites (1,364.389 seconds), with 4,324 unchanged inputs and zero
failures/errors/skips. Proof lint: zero errors, 49 existing warnings. Actual workflow
preflight accepted APK `fca8e884a7f0f48af5baf5aa4dd339421d7060368721342f7c57d0dc37955c27`
and rejected a separate local APK containing the exact published unstripped library.
The SDK4 hashes and original APK/source stayed unchanged. [Packaging contract](collaboration-sdk4-packaging-ci.md).
Packaging program `9deb55bf93314622967231c9de8ab695c775faeb` is committed and pushed
to both temporary and primary branches. First control42354718734 is PENDING behind
05de's Apple job; native42354718728 and protocol42354718729 have started. Preserve
that first pending control before the next primary source promotion. Short-SHA run
lookup returned no results; the full SHA returned these three actual runs.

NEXT run the narrowly scoped [mixed-room diagnosis](collaboration-mixed-room-delivery-diagnosis.md)
using controlled recipients with different device/key readiness. No production policy
change or new message has been made for this experiment. Supplementary plans are in
`.work/mixed-room-delivery-2026-11-07` and `.work/space-original-queue-delivery-2026-11-07`.
The user was told this experiment follows packaging acceptance; continue it before
returning to original organization delivery/publication. Phone latency remains open.

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
of the corrected source remains to observe; no SDK update or provider send was needed.

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
not prove prompt UI acknowledgement. The last passive phone53 audit at 07:38:44
UTC found zero post-install lines, fatals or chat diagnostics; unchanged private
log SHA `518c42ad6b85ace21148a5ba2750bdfc338828db4c59c8372d8e95485297dd66`.
Silence is not delivery evidence. An optional release53 feedback question is already
pending; do not repeat it. No phone UI/new messages were driven.
[Key evidence](measurements/collaboration-bima-key-availability-2026-11-07.json).

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
- First `05de0bf6` control 42348626104 has STARTED. Native 42348626124 is fully
  audited FAILED at SDK packaging before any runtime proof; 493 host cases per ABI
  passed. [Failure evidence](measurements/collaboration-original-metadata-native-ci-2026-11-07.json).
  Protocol 42348626066 is accepted: 14 checks each mode and 14 source hashes match
  pinned Platform `e0dbb567acc4c5571b82324c515ed8ddc145007d`.
- Primary `feat/communications` is `9deb55bf`; current work is on temporary
  `feat/chat-recovery-20261107`. Push every atomic commit. Preserve every first pending control/native
  run before later primary promotions; even `cancel-in-progress: false` replaces
  an older pending run. Do not rerun accepted provider sends just for evidence.

## Repositories and invariants

Main Platform `feat/communications` checkpoint `e162d0a5` and Group worktree
`feat/communication-space` checkpoint `ab5fed5c` are pushed. Group source remains
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
