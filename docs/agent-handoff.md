# Agent continuation — HRIS Mobile

Read this checkpoint, [architecture](architecture.md), and the linked contracts before
continuing. Previous checkpoints remain verbatim in [history through conversation
storage](agent-handoff-history-through-conversation-storage-2026-11-07.md), which links
the earlier histories. Git and these contracts are authoritative; `.work` is evidence
and staging, never permission to reapply old candidates.

## Current accepted work — 7 November 2026

Original Open replay335e3914 is now locally accepted/pushed on recovery:12 Kotlin source/test files. It retains
original intent, marks uncertainty before HTTP, reads the normalized persisted record
for exact CAS, captures observed outcomes through bounded cancellation, and publishes
canonical metadata before archive. Full69888 passed3,669 fresh cases/452 suites505.461s
on4,393 unchanged inputs, zero failures/errors/skips/lint. Root, Android host, quality,
25 actual Browser domain cases,3 actual Browser real-storage cases/shared Wasm passed.
Focused35951 passed34 cases134.702s. All Mobile jobs are reaped. The first real-storage
run3935 REPRODUCED the normalized-error CAS failure (1 of3 cases); it and the earlier
fake-only30-case pass15001 are preserved. [Contract/results](collaboration-space-conversation-open-replay.md).
No SDK send, message ACK, product caller or new APK. First source Apple/native CI remains.

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

1. Primary now31e618cc89e2ca0cce38bcb62ccd95b8533c88c0 (Open335e3914 included). First
   control42376025279/native42376025306 are PENDING behind running first1d9493df
   control42372520573/native42372520624. Preserve before another primary promotion.
   First3b3 control/native are SUCCESS and fully audited; no provider reruns.
2. Group V138 storagec6937843 is accepted/pushed:7 migration138 cases plus17 actual
   writer cases after fixture upgrade133→138, same-lock stable receipt/fence exclusion,
   immutable evidence and archive/restore;26 recovery checks retain unchanged envelopes.
   [Storage contract](https://github.com/fajarxfce/hris-platform/blob/c6937843/docs/collaboration/room-request-fence-storage.md).
   Sole heavy job49421 now runs Group .work/room-request-fence-native-2026-11-07/focused-1.
   FOURTEEN Kotlin files are applied/FROZEN, not accepted: typed repo/raw source/mapping,
   native owning fence, current-admitted stable writer rejection and shared DI;27 native
   transaction/4 mapping cases plus domain/quality. After focused, API + FULL worker
   context-check.py is required. No HTTP endpoint or Mobile successor/rollout yet.
   Do not edit frozen Group source or start a second heavy job.
3. Open preparation/replay and canonical projection-before-archive are locally accepted.
   Continue equivalent owning Room request projection/recovery and original SDK routing.
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

Metadata/preparation/Open candidates are historical applied staging; do not reapply.
The real codec fix supersedes Open's initial candidate directory. Group's5 V138 storage files are accepted;14 new owning
fence files are frozen for49421 and have no HTTP caller yet.
Metadata remains inside the guarded collaboration-history/ prefix.

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

Both first1d9493df control42372520573/native42372520624 have STARTED.
First3b3 native42369792184 is SUCCESS and original artifacts audited:543/86 perABI,
zero failures/errors/skips, original queue33.483s x86/80.188s ARM,0 provider sends,
keys/IDs and cleanup preserved. Separate HRIS fixture sends/cleanup remain exact.
[Native evidence](measurements/collaboration-space-conversation-commands-native-ci-2026-11-07.json).
Audits4322/74274 are reaped; no provider rerun. First3b3 control42369792173 is SUCCESS:
Apple2,001/228, portable4,869/542, zero failures/errors/skips and original artifact digests
verified. [Control evidence](measurements/collaboration-space-conversation-commands-control-ci-2026-11-07.json). First79b control42366357406/native42366357400 are SUCCESS and fully
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
72a's app fake compilation failure was corrected and accepted ine8fc. Storage/observation first3b3 Apple/native CI is fully accepted. Metadata/preparation
first1d CI is running; Open335e3914 requires its own subsequent first CI.

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
