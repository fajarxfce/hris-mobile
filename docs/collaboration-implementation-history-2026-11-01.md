# Collaboration checkpoint history — 1 November 2026

## Active collaboration implementation — 1 November 2026

Control-denial checkpoint Mobile `92d91275` is now verified in hosted CI:
control `41258471231` passes all 26 steps (201 desktop, 177 browser, 177 iOS
simulator); HRIS `41258471223` passes all 17 steps with an actual SDK run in
64.046s, process restart, retained-cursor recovery and completed cleanup.
The recorded 131 Mobile/24 Platform hashes match the immutable checkouts;
[evidence](measurements/collaboration-control-denial-2026-11-01.json) retains the
reports. This is separate from the newer owned-summary/cache work.

Mobile now consumes the owned summary page/detail contract at Platform
`8813ea9eee28cafc274eafcd41456340f132ae2f`; CI pins that backend.
[Evidence](measurements/collaboration-mobile-summaries-2026-11-01.json): one new
domain/four data cases, focused 61s, full root/browser/Android/Wasm/lint 232s,
2,208 desktop cases, 97 domain/85 data browser, zero lint errors. Both summary
paths share current-owner/denial guards, bounded decoding and exact counterpart
validation. No UI, live service or phone change; encrypted owned projections are next.

Transport checkpoint `4d90c6e0` is fully verified in CI: control `41257419472`
passes all 26 steps (187 desktop, 163 browser, 163 iOS simulator); protocol
`41257419463` passes all 40 steps (classic 73.011s, native 86.187s); HRIS
`41257419507` passes all 17 steps and actual SDK recovery in 72.015s. All recorded
24 Platform/131 Mobile source hashes match the pinned checkouts; cleanup completes.
See [recovery evidence](measurements/collaboration-sync-recovery-2026-11-01.json).
Apple native chat interoperability remains pending despite the common Apple checks.

Control preparation, replay, receipt reconciliation and runtime install/restore
now preserve denial through failed post-request owner reads. Explicit guards take
the preceding result. A failed uncertainty write cannot erase the retained denial
or start another HTTP attempt. Owned credential denial also survives failed local
admission and failed resource cleanup; partial/late leases still close.
[Verification](measurements/collaboration-control-denial-2026-11-01.json): ten new
common cases; preliminary focused checks 45s, final root/browser/Android/Wasm/lint
223s, 2,203 desktop cases, 96 domain browser, zero lint errors. No native transport
change or local SDK rerun; earlier SDK proof remains tied to `4d90c6e0`/`013f8092`.

Setup and all four conversation discovery reads now retain received server
authorization denials if the following session read fails. A newer authorization
denial still wins. [Evidence](measurements/collaboration-read-denial-2026-11-01.json)
records four new common cases, focused verification in 43s and full root/browser/
Android/Wasm/lint in 202s (2,193 desktop, 86 domain browser, zero lint errors).
No provider transport changed; native SDK evidence below remains at `4d90c6e0`.

[Evidence](measurements/collaboration-sync-recovery-2026-11-01.json) · [Contract](collaboration-sdk-lifecycle.md)

The actual HRIS CI fixture now pins Platform `013f8092114eef7a603930bdf242aed859ccba7c`,
which includes the required direct-metadata contract.

Common admitted conversation entry and retained-cursor sync recovery are implemented.
Entry verifies the active device, owned DM, READY room and current owner after each
request; a late result closes only its child. Structured SDK denials survive failed
post-request reads and disposal. The pinned FFI loop stops on its first error;
the replacement owns one `syncOnceV2` request, bounded backoff and the SDK's cursor.
Final denied responses remain visible even when cleanup fails. The gateway also
confirms the SDK's post-join `m.direct` only when it matches the existing HRIS
DIRECT scope. No provider metadata writes or arbitrary account-data are enabled. The server fixture
injects a sync 429 only after actual process death and requires a successful retry
with that same cursor, both encrypted replies and the original queued transaction.

Twenty-nine new common cases pass. Final full root/browser/Android/Wasm/lint passes
in 240s: 2,189 desktop cases, 82 domain/81 data browser,
zero lint errors. Later proof-only corrections each rerun root/proof assembly/lint
(66s diagnostic, 64s current owner, 65s bounded explicit entry attempts). Actual
SDK run `c17b1b10` passes in 39.126s (66s Gradle/quality,
one API and 32 driver cases), with cleanup complete. Backend regression passes
in 411s with 40 domain, 204 data and 20 HTTP
cases; focused verification passes in 54s. Format and architecture checks pass. All 24 Platform/131 Mobile
hashes match before/after. Proof APK `8a212769`; dev APK `87631d85` is built,
not installed. Emulator stopped; no live/schema/phone changes.

The earlier missing-room failures were a fixture ownership bug: restoration changed
the local session, but entry retained its old owner. RuntimeNotOpen correctly rejected
it before SDK join. The initial room-ID hypothesis was incorrect; all attempts are
retained. A later 503 provider-busy join is also retained. The fixture permits at most
four explicit idempotent entry attempts; product repositories still issue each join
once, with no automatic enrollment. The deterministic sync fault remains independent. Two subsequent failed runs
exposed the SDK's post-join metadata confirmation. Both failures are retained;
three new domain, three database and two HTTP cases verify the correction,
including malformed requests, identity isolation and permission loss.

**Continue P0–P8; none of the 54 groups is accepted.** Build encrypted owned
conversation-list projections and app entry with explicit shared runtime ownership,
then product history windows, offline message projections, bootstrap/cross-family
recovery, logical-ID reconciliation and chat UI. Treat summary UUID continuation
as live page navigation; it gives neither snapshot completeness nor change order.
Keep native SDK keys/stores intact. The current 512-item observer cap is a guard,
not a product history window. Keep HRIS adapters in Mobile/Platform and port only
verified reusable foundations to Starter.

Predecessor `0ef8018c` control CI `41250076314` passes all 26 steps:
158 desktop, 134 browser, 134 iOS simulator cases; 120 source hashes match the
immutable commit. Provider CI `41250076315` passes both modes/all steps, classic
90.228s/native 85.289s, with 14 matching Platform hashes per mode. HRIS CI
`41250076331` failed after provider-capacity sync 429s; its timeout/cleanup evidence
and the pinned SDK diagnosis are retained. Earlier command CI `39217be2` HRIS
`41248280306` also passes all 17 steps: 68.983s, cleanup complete, 108 Mobile/15
Platform hashes verified. Apple native chat interoperability remains pending.


## Offline intents and runtime reuse checkpoint

Owned offline text intents are now implemented through `QueueCollaborationText`
and `ObserveCollaborationMessages`, using the encrypted account command store.
Retried logical IDs retain exact text/timestamp; native attempt states cannot reset
uncertainty or retarget a mapped transaction. Local preparation reads an acquired
owned conversation, preserves facts through ordinary expiry, and respects confirmed
company denial. It never enrolls, joins, refreshes credentials or sends HTTP.
[Contract](collaboration-message-intents.md) and
[evidence](measurements/collaboration-message-intents-2026-11-01.json): 14 new common
domain cases, nine real SQLite/IndexedDB message cases and one exact-list lookup
case. Focused checks pass in 64s; full root/browser/Android/Wasm/lint passes in 263s:
**2,267 desktop / 382 suites**, **135 domain / 106 data browser**, all zero failures,
errors/skips and zero Android lint errors. All 19 source hashes match; dev APK
`abd3ff29` is built, not installed.

**Next: implement the exclusive native queue writer and its cancellation/drain
ownership, persist native transaction mapping before delivery, and prove actual
process death between enqueue and mapping. Then connect Chat list/setup/conversation
UI and durable history.** The SDK explicitly exposes no JSON/logical ID on unsent
local echoes; the earlier hydration proposal was incorrect and is superseded by
complete paused queue snapshots under one exclusive enqueue owner. The pure set-
difference recovery policy is implemented; its native owner is not connected yet.
No second transport retry owner or automatic cross-device resubmission is allowed.

Mobile `4980ea00` hosted checks are now verified: control `41261819143` passes all
26 steps (230 desktop, 206 browser, 195 iOS simulator); HRIS `41261819051` passes
all 17 steps and SDK recovery `c2cd4309` in **72.366s**, with all verification and
cleanup flags true. All 22 cache source hashes match its immutable checkout;
Platform pin remains `8813ea9e`. The measurement retains exact artifacts/digests.
These hosted runs predate the message-intent code. No product group is accepted.

Admitted application entry now reuses an initialized SDK runtime through
`EnsureCollaborationRuntime`; it returns scoped readiness without lending a parent
close handle to a route. Concurrent entries share one restore, incomplete native
initialization reports opening, and late/cancelled new acquisitions clean up only
their own lease. [Evidence](measurements/collaboration-runtime-reuse-2026-11-01.json):
11 new common cases; focused proof build/checks 84s, final full root/browser/Android/
Wasm/proof/lint 251s (**2,243 desktop / 379 suites**, **121 domain / 96 data browser**,
zero failures/errors/skips and zero app/proof lint errors). Actual HRIS SDK run
`f0cfaed6` passes in **61.058s** (95s Gradle), including open/closed owner probes,
process death, both encrypted directions, original queued transaction and same-cursor
sync recovery. All **153 Mobile / 24 Platform hashes** match before/after; proof APK
`a7a39c54`. Dedicated emulator stopped after cleanup; phone/live remain unchanged.

The owned direct-conversation list now persists encrypted account/company facts
across database restart and reauthentication. One explicit live page merges at
most 50 summaries atomically; local browsing reads at most 51 indexed records.
Mutable counterpart labels use a local publication revision. Missing or empty
live pages do not delete saved conversations or claim snapshot completeness.
Ordinary expiry preserves local reads. Confirmed access/maintenance denial has
an independent encrypted gate plus a bounded process buffer; failed writes and
rejected CAS cannot discard it. Only fresh acquisition of the observed denial
revision can reopen reads. No SDK store reset, device enrollment or implicit send.

[Evidence](measurements/collaboration-local-list-2026-11-01.json): 13 new domain
cases and an 11-case real SQLite/IndexedDB contract. Final root/browser/Android/
Wasm/lint passes in 254s: **2,232 desktop / 378 suites**, **110 domain browser**,
**96 data browser**, zero failures/errors/skips and zero lint errors. The earlier
255s full pass, four initial build/quality corrections and intentional browser
negative control are retained. All 22 recorded source hashes match. The negative
control proves async storage assertions are awaited; its positive case was
restored before final verification. New dev APK `c21bbe13` is built, not installed.

Owned-summary Mobile `820e3c3c` also has verified hosted evidence: control
`41259805423` passes all 26 steps (206 desktop, 182 browser, 182 iOS simulator);
HRIS `41259805464` passes all 17 steps and actual SDK recovery in **69.753s**.
All 132 Mobile/24 Platform source hashes match the pinned checkouts. CI pins
Platform `8813ea9eee28cafc274eafcd41456340f132ae2f`. [Summary evidence](measurements/collaboration-mobile-summaries-2026-11-01.json)
retains process restart, both encrypted directions, same-cursor recovery and
cleanup. Earlier `92d91275` hosted control/HRIS evidence (201/177/177 cases,
64.046s SDK) is in [control denial](measurements/collaboration-control-denial-2026-11-01.json).
These SDK runs do not validate the later cache source; its native transport is unchanged.

**Continue P0–P8; none of the 54 feature groups is accepted.** The next native
writer/delivery and product UI work is listed above. Bounded history, offline
message projections, bootstrap/cross-family recovery, richer chat/calls/meetings
and Apple native interoperability remain pending. Keep SDK keys/cursors intact;
the 512-item observer guard is not a product history window. Common Apple checks
and the physical-device waiver do not replace native interoperability.

No live/schema/phone change; the dedicated emulator remains stopped. Starter
stays at `36116b2`: no new generic foundation change requires a port. Keep HRIS
policy/adapters in Mobile and Platform. Atomic commits and every-commit pushes
are authorized; do not treat a checkpoint as completion.

[Local list contract](collaboration-local-list.md) · [Earlier November detail](collaboration-implementation-history-2026-11-01.md)



## Native writer checkpoint — 1 November 2026

The protected runtime now owns one exclusive paused native queue writer across
all its rooms. `SdkQueueOperations` keeps an in-flight native enqueue alive after
waiter cancellation/timeout; competing operations cannot overtake it. Child cleanup
drains native work and listeners before releasing exclusivity. Per-room subscription
captures the full bounded transaction set without relying on local-echo JSON.
The domain handle exposes snapshot/enqueue/close and never enables delivery.

[Native-writer evidence](measurements/collaboration-native-writer-2026-11-01.json):
five new common ownership cases; initial focused compile needed explicit generic
types in two test lambdas (45s), then corrected proof/quality checks pass in 82s.
Full root/browser/Android/Wasm/lint passes in **228s**: **2,272 desktop / 383 suites**,
**135 domain / 111 data browser**, zero failures/errors/skips and app/proof lint
errors. All **168 Mobile / 24 Platform source hashes** match actual execution.
Dev APK `ec43002c` is built, not installed; proof APK `ad767cf1`.

Actual HRIS SDK run **`6e761470778d411db4ddee83bf9fea2e`** passes in **39.509s**
(79s Gradle/quality). The Android fixture persists an encrypted app intent and
`Staging` snapshot, enqueues through the product native writer, then the driver
force-stops the real process **before app transaction mapping**. Restoration
recovers the exact original native transaction, CAS-publishes `Staged`, and only
then resumes the fixture exchange. It checks exclusive-writer refusal, closed
handles, reacquisition and the final `Sent` mapping. Both encrypted directions,
original identity/transaction, no early send, injected sync failure and retained-
cursor recovery still pass. All new proof flags and cleanup are true. The updated
Python evidence validator passes its 13 cases. HRIS CI now pins the verified
Platform `7e95bffdae322e16c9b904d21461b42c19b20012`. The dedicated emulator is stopped.

Owned offline intents were pushed at Mobile **`518575387be8ed1622b4c08f020076b09ff9e1df`**:
14 common domain cases, nine SQLite/IndexedDB message cases and exact-list lookup.
Its full check passes in 263s (2,267 desktop, 135/106 browser); all 19 source hashes
match. [Intent contract](collaboration-message-intents.md) separates stable account
facts, local denial gates, native-family uncertainty and live delivery. Logical IDs
retain exact text/timestamp across lost responses. The earlier local-echo hydration
proposal was incorrect: unsent echoes have no JSON. Paused snapshots plus one
exclusive enqueue are the implemented recovery primitive.

**Continue P0–P8; none of the 54 feature groups is accepted. Next implement the
common live-admitted sender and per-room delivery lifetime.** Coordinate owned
intents, device/conversation/room admission, uncertain-attempt reconciliation and
CAS mapping before enabling delivery. Unknown native transactions and cross-family
uncertainty must remain held; no second transport retry owner. Then connect Chat
list/setup/conversation UI, durable message history/windows and bootstrap recovery.
The actual fixture proves storage/native primitives; it does not replace product
sender policy, UI, richer chat/calls/meetings or Apple native interoperability.
Keep SDK keys/cursors intact; the 512-item observer guard is not a history window.

Verified hosted cache checks for Mobile `4980ea00`: control `41261819143` passes
all 26 steps (230 desktop, 206 browser, 195 iOS simulator); HRIS `41261819051`
passes all 17 steps and SDK `c2cd4309` in 72.366s. All 22 cache source hashes match.
[Local-list evidence](measurements/collaboration-local-list-2026-11-01.json) records
the artifacts. Newer `67d23041` protocol `41262754682` and HRIS `41262754762` report
success but their artifacts are not yet audited; control `41262754840` was still
running. Intent `51857538` HRIS `41263763420` was running and control `41263763417`
was pending. Do not replace running CI or claim its unaudited results as proof.

No live/schema/phone change. Starter remains `36116b2`; these HRIS-specific adapters
need no generic port. Atomic commits and every-commit pushes remain authorized.
Do not treat a checkpoint as completion. [Earlier November detail](collaboration-implementation-history-2026-11-01.md)

