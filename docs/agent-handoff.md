# Agent continuation — HRIS Mobile

Read this checkpoint, [architecture](architecture.md), and the linked contracts before
continuing. Previous checkpoints remain verbatim in [history through conversation
storage](agent-handoff-history-through-conversation-storage-2026-11-07.md), which links
the earlier histories. Git and these contracts are authoritative; `.work` is evidence
and staging, never permission to reapply old candidates.

## Current accepted work — 7 November 2026

Owning Open preparation1d9493df is accepted/pushed to recovery and primary:
seven Kotlin files, bounded original/canonical recovery, current discovery for new
intent and exact-ID lost response reconciliation. Focused50437 passed15 cases204.552s;
full36411 passed3,626 fresh cases/449 suites491.548s on4,385 unchanged inputs, zero
failures/errors/skips/lint. Root/Android host/quality/13 actual Browser cases/shared
Wasm passed. All local jobs reaped. [Contract/results](collaboration-space-conversation-preparation.md).
No HTTP/SDK/archive/product caller or new APK is added.

Canonical conversation metadata aa700e37 is accepted/pushed to recovery: ten Kotlin files, native-parent-free facts, atomic encrypted conversation/
peer indexes and actual application binding. Focused15306 passed16 cases183.384s;
full33894 passed3,614 fresh cases/448 suites476.855s on4,380 unchanged inputs, zero
failures/errors/skips/lint. Root/Android host/quality/14 actual Browser cases/shared
Wasm passed. All local jobs reaped. [Contract/results](collaboration-space-conversation-metadata.md).
No new APK, product caller, provider operation or phone latency acceptance.

Owning local command observation3b3a5d81 is accepted/pushed to both branches: four files (domain flow, data factory, seven lifetime/ownership cases,
actual application binding resolution). Focused75614 passed7 cases104.606s; full49430
passed3,593 fresh cases466.030s on4,371 unchanged inputs, zero failures/errors/skips or
Android release lint issues. Root/Android host/format/architecture/Detekt/actual Browser
observation/shared Wasm passed. First73246 failed a nested generic test assertion;
its two app binding cases passed and the failure is retained. All local jobs reaped.
[Contract/results](collaboration-space-conversation-observation.md).

Prior durable storagec8c952af is accepted/pushed: ten source/test files, full3,598
cases/447 suites813.971s on4,369 stable inputs, including actual SQLite/IndexedDB
storage and shared Wasm; zero failures/errors/skips/lint. First fixture-name collision
is retained. [Storage contract](collaboration-space-conversation-commands.md).
Stable room transport79b41269 is accepted:3,560 cases366.186s, stable v2 native room
writer and current-owner receipt recovery. [Transport contract](collaboration-space-room-stable-intent.md).
Groupd37459fd implements those endpoints; its current branch checkpoint is839c3ae2.
No product caller, new APK or organization routing activation exists for these pieces.

## Next work

1. First1d9493df control42372520573/native42372520624 are PENDING behind running
   first3b3 control42369792173/native42369792184. Preserve these first pending runs
   before another primary promotion; continue source work on recovery.
2. TWELVE original Open replay files are applied/FROZEN. Sole heavy local job69888
   runs .work/space-conversation-open-replay-2026-11-07/full-1: root check, Android
   host/release lint, actual Browser domain/storage, shared Wasm and quality checks.
   Focused35951 passed34 fresh cases134.702s on4,393 unchanged inputs, zero failures/
   errors/skips; all focused jobs are reaped. [Contract](collaboration-space-conversation-open-replay.md).
   Focused15001 passed30 fake-domain/binding cases195.304s. Focused3935 then
   REPRODUCED a real codec normalization failure:3 fresh storage cases,1 failure,
   104.619s,4,393 unchanged inputs; both jobs reaped. Directory and command codecs
   deliberately normalize human error text differently, making an in-memory expected
   mark differ from the stored record. Fixed dispatch exact-reads the successful mark
   before HTTP, validates original command/revision/attempts and stored kind/code/retry,
   rechecks ownership/native admission, then uses the actual stored record for outcome
   CAS. Changed/missing marks cannot dispatch. Do not weaken raw CAS or persist private
   error presentation. This new organization flow is NOT installed phone53's latency
   diagnosis. Full root/Android/lint/actual Browser checks remain required. No SDK,
   message ACK, product caller or new APK. Preserve the failing run as regression proof.
3. Implement finite owning prepare/replay and projection-before-archive. Recover the
   same original resource/operation after response loss; CAS to Unconfirmed BEFORE HTTP.
   Separate native account/MFA/client denial from conversation/peer denial; do not close
   every contact because one peer is unavailable. A setup receipt grants no SDK/content
   authority or message ACK. Truly new offline chats also need an explicit provisional
   peer/open-command mapping to the eventual canonical conversation without replacing
   original message transaction IDs.
4. Implement [explicit server non-execution fencing](https://github.com/fajarxfce/hris-platform/blob/e131495e/docs/collaboration/native-room-request-fencing-plan.md)
   before an uncertain original is replaced. Null receipt is not a fence. Do not use fake
   MutationReceipt values, impersonate an old native parent or reset original SDK data.
5. Complete owning original SDK delivery/history, native-parent recovery, product routing
   and all54 PRD groups:3 in progress/51 planned/none complete. Physical iPhone testing
   is waived, but relevant Apple CI/compilation remains required.

Metadata/preparation candidates are historical applied staging. Open replay candidates
are also applied now, but unaccepted and frozen for69888. Its candidate folder is
historical before the real codec fix; do not reapply it. Metadata stays inside the
existing guarded collaboration-history/ prefix with metadata-only/inactive key-loss
coverage. No server fence or room request successor implementation exists yet.

## Original SDK and phone invariants

Preserve `.work/demo-chat-device-2026-11-05/bima/userdata.img`, every sidecar/private key,
original pending transaction ID and failed native fixture. No reset, re-enrollment,
new physical store or fabricated ACK. The current logical organization device and the
physical device recorded in an original message are different identities.
[Original writer](collaboration-space-original-writer.md) borrows one exclusive paused
writer, validates the complete bounded inventory (max128), and never implicitly joins,
syncs or enqueues new work. Current canonical room/recipient admission remains required.

Immutable SDK4: `matrix-sdk-26.10.23-hris.4`, release437093395,
target4228599ba659cf68cfea1138e6f8017b0b77c222. Never rebuild/republish that tag.
AAR SHA256 `9b6806de02b7d40b8487b54a51b192c114a38c68c6dfe35c68faf50d3b6bfd21`.
Stripped x86 `.so`: `55bde5be2080c3e42a2ece6053f94231d27165cc39698c89c50e36ecbdb2909a`;
ARM: `5df0419b7f1a5dd5d934b0cce3846a439812581e35f86defe3c2098f29260d34`.
NDK28.2.13676358. Frozen SDK worktrees and original artifact provenance are in history.

Phone `10.77.77.3:5555`, package `dev.fajar.hris.mobile.dev`, UID10962 (also1010962):
release53 remains installed, source08545820c0b58f46d784971e71ceb207dd628c0f,
ARM64 devRelease1.0.0/nondebuggable/noR8,121022760 bytes,
APK SHA256 `994d161ade3455745157d0793475b5091d67e968af909bda7a282dce163152c6`.
Installed02:52:45.968748UTC with original UID retained. No54 exists. Announce any
validated feature install, choose the serial explicitly and use --install-only;
never launch or drive the user's phone UI while it is in use.

Phone latency is OPEN. Latest bounded10s reconnect at11:41UTC timed out; no new phone
logs. Earlier08:38 passive UID audit had0 postinstall lines: silence is not acceptance.
Existing optional feedback is pending; do not repeat the question. Bima's06:42:18UTC
read-only audit had1 active device,1 device key,50 signed one-time keys and1 fallback.
He does not need to stay online for sender server acceptance. This does not establish
the cause or resolution of the user's20s–2min pending delay.

## CI and live state

First1d9493df control42372520573/native42372520624 are PENDING; preserve them.
First3b3 control42369792173/native42369792184 have BOTH STARTED. First79b control42366357406/native42366357400 are SUCCESS and fully
audited: Apple1,975/225, portable4,817/536, each native ABI543/86, zero failures/errors/
skips. Original queue continuity31.255s x86/77.166s ARM preserved keys/IDs and cleanup,
zero provider sends; separate native HRIS scenarios also passed with cleanup.
[Original evidence](measurements/collaboration-room-stable-intent-control-native-ci-2026-11-07.json).
ImmutableSDK4 artifact hashes match. Audit61573 is reaped; no provider scenario rerun.
First fb514281 control42363807332/native42363807289 are SUCCESS and fully audited:
portable4,805/534; Apple1,969/224; each native ABI537/85, all zero failures/errors/skips.
[Control evidence](measurements/collaboration-room-receipts-control-ci-2026-11-07.json),
[native evidence](measurements/collaboration-room-receipts-native-ci-2026-11-07.json).
Original queue continuity24.827s x86/80.993s ARM preserved keys/IDs through process death,
zero provider sends and full cleanup. Older e8fc and72a original evidence is in history;
72a's app fake compilation failure was corrected and accepted ine8fc. Storage/observation await first3b3 Apple/native CI; newer metadata will require
its own primary CI after pending runs have started.

Live API2e75fe1ee88b5766259fc3efd3ab7f21e70d621a, worker10485916, schema116.1 remain
unchanged. Group V117–137 remain disabled/unshipped. Starterb11437a is clean/pushed;
current HRIS-specific work requires no generic Starter port. Main branch checkpoint
1a1dba11 and Group2160044c point here for build ownership; their earlier running-job
statements are superseded by accepted full36411 above.

## Work rules

User authorized long run, atomic commits with every commit pushed, and validated
feature installations. Keep progress updates within60s. No subagents unless explicitly
requested by the user or applicable instructions. One heavy local build/emulator at a
time; freeze/hash sources during checks, reap jobs, and release only a frozen pushed
HEAD. Domain policy is pure; use cases coordinate repositories, repositories raw
sources, datasources I/O. Never hide business retry/lifetime in generic helpers or UI.
