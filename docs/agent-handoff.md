# Agent continuation — HRIS Mobile

Read this checkpoint, [architecture](architecture.md), and the linked contracts before
continuing. Previous checkpoints remain verbatim in [history through conversation
storage](agent-handoff-history-through-conversation-storage-2026-11-07.md), which links
the earlier histories. Git and these contracts are authoritative; `.work` is evidence
and staging, never permission to reapply old candidates.

## Current accepted work — 7 November 2026

Owning local command observation is accepted in the source commit containing this
checkpoint: four files (domain flow, data factory, seven lifetime/ownership cases,
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

1. Push this accepted source to recovery and primary after confirming both first79b
   runs have started (last observed BOTH in progress). Preserve any newly queued first
   run before another primary promotion. Continue code on recovery while CI runs.
2. Implement the owned canonical conversation projection needed before Recorded
   command archival. Strip mutable login/native parent from persisted conversation
   metadata. Atomically retain conversation and peer indexes in one replica transaction;
   identical data must preserve ciphertext/revision. Use bounded reads and explicit key
   custody; existing directory/history key guards only cover their own prefixes.
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

The reviewed detailed source notes remain under `.work/space-conversation-commands-
2026-11-07/next-owning-flow.md`. All observation/storage candidates are now historical
applied staging, not pending patches. No new projection or fence implementation exists.

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

First79b control42366357406/native42366357400 have both STARTED. Audit their original
artifacts when complete; do not rerun accepted provider scenarios just for evidence.
First fb514281 control42363807332/native42363807289 are SUCCESS and fully audited:
portable4,805/534; Apple1,969/224; each native ABI537/85, all zero failures/errors/skips.
[Control evidence](measurements/collaboration-room-receipts-control-ci-2026-11-07.json),
[native evidence](measurements/collaboration-room-receipts-native-ci-2026-11-07.json).
Original queue continuity24.827s x86/80.993s ARM preserved keys/IDs through process death,
zero provider sends and full cleanup. Older e8fc and72a original evidence is in history;
72a's app fake compilation failure was corrected and accepted ine8fc. Newer storage/
observation source still needs its own Apple/native CI after primary promotion.

Live API2e75fe1ee88b5766259fc3efd3ab7f21e70d621a, worker10485916, schema116.1 remain
unchanged. Group V117–137 remain disabled/unshipped. Starterb11437a is clean/pushed;
current HRIS-specific work requires no generic Starter port. Main branch checkpoint
37b3f324 and Group839c3ae2 point here for build ownership; update them after promotion.

## Work rules

User authorized long run, atomic commits with every commit pushed, and validated
feature installations. Keep progress updates within60s. No subagents unless explicitly
requested by the user or applicable instructions. One heavy local build/emulator at a
time; freeze/hash sources during checks, reap jobs, and release only a frozen pushed
HEAD. Domain policy is pure; use cases coordinate repositories, repositories raw
sources, datasources I/O. Never hide business retry/lifetime in generic helpers or UI.
