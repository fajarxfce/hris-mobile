# Agent continuation — HRIS Mobile

Read this checkpoint, [architecture](architecture.md), and the linked contracts before
continuing. Previous checkpoints remain verbatim in [history through conversation
storage](agent-handoff-history-through-conversation-storage-2026-11-07.md), which links
the earlier histories. Git and these contracts are authoritative; `.work` is evidence
and staging, never permission to reapply old candidates.

## Current accepted work — 7 November 2026

Room-only durable Fenced evidence is locally accepted: six Kotlin source/test files,
unchanged v1 immutable requests/keys and old outcome envelopes, schema2 only for exact
new fences, terminal CAS and no Fenced archive/successor permission. Focused99362
passed65 cases/217.124s; full22532 passed3,697 fresh cases/455 suites/513.19s on4,398
unchanged inputs, zero failures/errors/skips/lint. Audit caught a Browser domain filter
with zero selected tests; corrected supplemental82617 ran all6 policy cases/26.512s
on identical source. Actual Desktop/Browser each ran21 storage,3 Open-storage,8 fence
transport and6 policy cases. Root/Android host/quality/shared Wasm passed. All local
jobs are reaped. [Contract and exact results](collaboration-space-room-fence-storage.md).
No owning capture, successor, product caller, SDK operation or new APK is added.

NINE next owning-resolution source/test files are applied/FROZEN and NOT accepted.
Focused21909 passed91 cases/219.787s on4,405 unchanged inputs, zero failures/errors/
skips and format/architecture/Detekt. All22 domain and4 actual-storage new cases and
application DI passed. Sole heavy job28463 now runs
`.work/space-room-request-resolution-2026-11-07/full-1`: root/Android host/release lint,
64 actual selected Browser cases/shared Wasm. Its script asserts the selected case
counts explicitly. Do not edit frozen inputs or overlap another compiler/emulator.
The finite owner reads terminal facts locally, rejects Pending, marks the same send
attempt before one current-native original fence call and reads normalized stored
state for exact CAS. Observed evidence has a2s cancellation capture budget; peer
failure does not close all contacts. No successor/archive/provider/product caller.
After acceptance implement atomic original/successor storage using archiveAndInsert,
authenticated forward/back references and unchanged original ciphertext. The next
local design is `.work/space-room-successor-2026-11-07/plan.md`; no successor source
is applied. The nine resolution candidates are now historical applied copies.

First Open source31e control42376025279 is fully audited SUCCESS: Apple2,056/232,
portable4,979/550, zero failures/errors/skips; actual25 Open replay and3 storage
cases on each Apple/Desktop/Browser verified. Original artifact hashes retained,
no provider rerun. [Evidence](measurements/collaboration-space-conversation-open-control-ci-2026-11-07.json).
Native42376025306 still awaits ARM completion and original-artifact audit. First
fe3992f1 control42380244144 has STARTED; native42380243983 remains PENDING. Preserve
that first native run before another primary promotion. Recovery source/storage
fc0034ed is pushed; primary staysfe3992f1.

Typed room fence transport is locally accepted:11 Kotlin files, exact current-native
original mutation key/body and full response echo, distinct Recorded/Fenced domain
results, bounded4KiB response and preserved cancellation. Focused12497 passed44 cases
187.108s; full77406 passed3,671 fresh cases/454 suites472.962s on4,398 unchanged inputs,
zero failures/errors/skips/lint. Root/Android host/quality/shared Wasm and14 selected
Browser cases passed;8 new fence cases ran Desktop/Android host/Browser. All local
jobs are reaped. [Contract and measurements](collaboration-space-room-request-fence.md).
Durable evidence is now accepted above; successor, product caller and SDK delivery remain separate.

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

1. Primary and recovery now contain accepted sourcefe3992f1426712bf23964bf5aea68ad382f1b624.
   First control42380244144 has STARTED; native42380243983 remains PENDING behind
   first31e native42376025306. First31e control42376025279 is now audited SUCCESS. Preserve both new first runs before another
   primary promotion; continue source work on recovery. First1d9493df control/native
   are fully audited SUCCESS, including14 metadata-storage and13 preparation cases
   on Apple/Desktop/Browser, SDK4 native keys/IDs and cleanup. [Evidence](measurements/collaboration-space-conversation-preparation-control-native-ci-2026-11-07.json).
2. Group native dcdaf220 and HTTP cf885a1c are accepted/pushed. Focused195/319.052s and
   API/FULL worker241/540.839s pass with one existing opt-in retention skip. Corrected
   HTTP42852 passes99/202.31s on8,637 stable inputs,0failure/error/skip/quality. First57183
   fixture expectation failure is retained: unselected provider identity rejects the
   writer with peer_unavailable. Current admission is unchanged. All Group jobs reaped.
   [Server contract](https://github.com/fajarxfce/hris-platform/blob/cf885a1c/docs/collaboration/native-room-request-fence-http.md).
   Mobile11-file typed fence transport fe3992f1 is accepted/pushed to both branches,
   with44 focused and3,671 full cases as above. Durable Fenced storage is now accepted
   above. Next finish the nine-file owning-resolution full gate, then implement atomic
   original/successor linkage with retained exact evidence.
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
The real codec fix supersedes Open's initial candidate directory. Group native14-file, HTTP6-file and Mobile11-file fence transport are accepted locally.
Their candidate directories must not be reapplied. The six-file durable slice is now
accepted; the separate nine resolution files are applied and frozen for full28463.
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
Existing optional feedback is pending; do not repeat the question. Fresh14:00:00UTC
read-only Bima audit confirms1 active device/provider binding,1 device key,50 signed
one-time keys and1 fallback, without login/send/private-key/phone access. [Server evidence](https://github.com/fajarxfce/hris-platform/blob/ddf214f1/docs/measurements/collaboration-bima-recipient-keys-2026-11-07.json).
At14:04:40UTC the exact retained peer view still admitted1 publication-confirmed
recipient device despite expired interactive access and with a live hard parent.
[Eligibility evidence](https://github.com/fajarxfce/hris-platform/blob/4fad6bb3/docs/measurements/collaboration-bima-recipient-keys-2026-11-07.json).
He does not need to stay online for sender server acceptance. This does not establish
the cause or resolution of the user's20s–2min pending delay.

## CI and live state

Both first1d9493df control42372520573/native42372520624 are SUCCESS and fully audited;
first31e618cc control42376025279/native42376025306 have STARTED.
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
first1d CI is fully accepted; Open335e3914 first31e control is accepted and native remains running.

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
