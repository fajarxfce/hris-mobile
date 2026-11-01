# Collaboration checkpoint history — 1 November 2026

## Latest-event discovery checkpoint — 1 November 2026

**Latest-event discovery now feeds common original-history acquisition, proved
through the actual Android SDK and HRIS gateway.** [Evidence](measurements/collaboration-history-head-2026-11-01.json)
records run **`de144a6572dd4705b6fd608f0cb2a229`**, **24.794s test case /
45.579s native suite / 75s Gradle**, cleanup complete. The fixture omits the
anchor, requires the head to equal the newest encrypted reply, restores both
originals from encrypted storage, and proves exactly one unpositioned
`/messages?dir=b&limit=1` request with **5/5/5 HRIS admissions, zero failures**.

The runtime borrows the application's public HTTP pool and reads the current SDK
session only after its request child is registered. User/device/homeserver match
the retained identity; the configured gateway determines the destination. Caller
or parent cancellation stops the request without closing the shared pool. Bodies
are bounded to 256 KiB (errors 16 KiB), strict UTF-8 and depth 16; only one valid
opaque event ID escapes. Empty/unsupported heads do not clear history or certify
its end. No extra SDK, crypto store, cursor, credential renewal or message retry
owner is added. Seven common admission cases, six portable transport/mapping
cases and four Android lifecycle cases cover the change.

Matrix token rejection cannot distinguish ordinary expiry from HRIS revocation.
SDK Unauthorized still fails live operations, but no longer persists a company
read restriction. HRIS control denials continue to gate acquired history and
pending commands. Only admitted company-summary acquisition reopens that gate.

Corrected full checks pass in **103s**: **2,388 desktop / 140 Android host /
205 domain-browser / 157 data-browser** cases, zero failures/errors/skips and
zero application/proof lint errors. Server fixture/quality checks pass in **38s**;
all **35 driver cases** pass. All **235 Mobile / 33 Platform source hashes** match
before/after. Dev APK **`506b96ba`** is built, not installed; proof APK **`a26ebcf5`**
is the one actually tested. The dedicated emulator is stopped.

The first focused command passed new behavior tests and APK creation but failed
Detekt's 100-line fixture limit (93s); history evidence composition was extracted.
The first full command passed desktop/browser/quality checks but Android host
compilation found the public `HttpClient` dependency missing from the host classpath
(238s); app composition now exports `core.network`. A first Python invocation used
the wrong module import path; corrected discovery passed. All attempts are retained,
not presented as successful commands. Native diagnostic extraction measured 9.038ms;
network/decoding/hash/database latency and native-cache allocation are outside that
metric. There is no large-room memory or capacity acceptance.

Hosted predecessor audits are now recorded: original-window control **`41277971067`**
at **`5b5cbc73`** passed all 28 steps, **359 desktop / 335 browser / 291 iOS simulator**
cases and actual Xcode build, with **223 immutable source hashes** matched. Common
acquisition HRIS **`41279620656`** at **`79028f26`** passed all 17 steps, 130 Android
host cases and actual native suite 63.208s; all 227 Mobile/33 Platform hashes match.
Its encrypted run `06cf785c` cleaned up. These predecessors do not validate this
new head adapter; audit this increment's own hosted CI after push.

**Continue P0–P8; none of 54 complete groups is accepted. Next implement bounded
provider history progress, ACK-before-journal reconciliation and command retirement,
then Chat list/setup/conversation UI.** Preserve unresolved events, original-content
fingerprints, aliases, original native transactions, SDK crypto stores and cursors.
Do not infer success from absent queue IDs. Apple/web owned content adapters,
bootstrap/cross-family recovery, backup, rich messaging and all call/meeting phases
remain. Physical iPhone testing is waived; Apple implementation/interoperability
is still required. Starter remains clean at `36116b2`; this HRIS composition,
provider adapter and policy change requires no reusable foundation port.

Platform **`7626d684181af320d5d26a02e0afaeb74625bd65`** is pushed; the HRIS workflow
pins its strict latest-head and before/after admission assertions.

No live/schema/phone change. Atomic commits and a push after every commit remain
authorized. Continue after this checkpoint; the user requested the whole plan.

## Common live-admitted history checkpoint — 1 November 2026

**Common live-admitted original-history acquisition is implemented and proved
through real HRIS APIs and the Android SDK.** [Evidence](measurements/collaboration-history-acquisition-2026-11-01.json)
records final run **`28029aeb24b04618a8c88a023161eab4`**, **35.719s test case /
57.460s native suite / 92s Gradle**, cleanup complete. The server records
**5 device / 5 conversation / 5 room admissions, zero failures**, with counters
captured at the context request proving fresh admission afterward too. The common
use case must store both originals before verification; the fixture no longer
performs the initial merge itself. Exact bodies/event/logical indexes reopen from
actual encrypted storage and later replay leaves the same page.

`AcquireCollaborationHistory` requires a known event anchor and an installed
runtime/known encrypted room. It binds returned owner/device/room/anchor, detaches
collections before suspension and validates all events before writing. At most
128 combined entries enter 1–16-event atomic batches. Later conflicts, cancellation
or uncertain responses preserve earlier acquired facts; no implicit retry or
provider cursor advancement occurs. Known company/session denial survives failed
restriction persistence. Recipient failure does not close all company history.
The report counts acquired event facts; its unresolved entries can include state
events and must not be labelled unreadable messages in UI.

Fourteen common acquisition tests cover pre/late denial, owner replacement,
changed room, cancelled SDK work, partial commits, lost responses, immutable
input, malformed windows and unsupported anchors. Android decoding/hashing now
runs on `Dispatchers.Default` within the caller's structured operation. An eighth
Android window case verifies cleanup while decoding waits in a held dispatcher.
No extra SDK client, restore, join, sync producer or send-queue owner is added.

Full application checks pass in **255s**: **2,375 desktop / 130 Android host /
198 domain-browser / 151 data-browser** cases, zero failures/errors/skips
and zero lint errors. All **227 Mobile / 33 Platform source hashes** match.
Server fixture/quality checks pass in 39s and all 35 Python driver cases pass.
Dev APK **`d88fd1d5`** is built, not installed.

Platform **`24e893777e4e4cb9f0ffd7b4c0e2d607f105256f`** is pushed; the HRIS CI
workflow pins its common-acquisition and before/after admission assertions.

Initial common proof **`a268b22bd45c41ec9a96bdee857987ab`** passed in 107s before
background decoding. Initial focused/domain checks passed in 87s and APK checks
in 92s. One worker-check command was deliberately stopped to replace an invalid
two-test-scheduler fixture; it is not a passing result. Corrected worker/host/APK/
quality checks passed in 120s. Both actual runs and all source revisions are
retained. The final fixture measured 10.017 ms for native diagnostic extraction,
not network/decode/hash/database latency. Native diagnostic/cache allocation still
precedes app bounds; there is no large-room memory or capacity acceptance.

**Continue P0–P8; none of 54 complete groups is accepted. Next implement head
discovery and bounded provider progress, ACK-before-journal reconciliation and
command retirement, then Chat list/setup/conversation UI.** Preserve original
native transactions, unresolved events, original-content fingerprints and aliases.
Do not infer success from absent queue IDs or lose SDK crypto stores/cursors.
Apple/web owned content adapters, backup, rich messaging and all call/meeting
phases remain. The physical iPhone test is waived; Apple implementation and
interoperability are still required.

Canonical-storage CI **`41274726910`** at **`ad0848e6`** is now fully audited:
all 28 steps, 352 portable desktop / 328 browser / **284 iOS simulator** cases,
actual **Xcode app-host build**, and 207 recorded source hashes matched immutable
Git blobs. See its history measurement. This predecessor does not validate the
later window or this acquisition increment; audit their own hosted CI separately.

The dedicated emulator is stopped. No live/schema/phone change. Starter is clean
at `36116b2`; these HRIS policies/adapters require no generic port. Atomic commits
and a push after every commit remain authorized. Continue after this checkpoint;
the chat/calls/meetings implementation is not complete.

## Active collaboration implementation — 1 November 2026

**An owned Android original-event window now passes through real HRIS and Matrix
SDKs into canonical encrypted history.** [Evidence](measurements/collaboration-original-window-2026-11-01.json)
records actual run **`125c8c38739645df9e5cd445fe6f90c2`**, **46.115s native suite /
77s Gradle**, cleanup complete. Both exact text originals and their event/logical
indexes survive database reopen; replay leaves the same page. The SDK's focused
context request returns 200 with its typed membership filter. The prior common
sender's 3/3/3 live HTTP admissions, paused restart and isolated held room remain
asserted.

This is a finite known-anchor snapshot, not complete acquisition or head discovery.
The shared decoder authenticates original event identity and hashes complete
canonical original content, retaining unknown fields. Replacements and unavailable
content cannot establish originals. Seven decoder and seven Android ownership
cases cover original-versus-edited data, bounds, partial acquisition, late denial
and retained cleanup. The real fixture contains two unedited text originals plus
10 unsupported entries. Diagnostic extraction measured **21.967 ms**, **4,732 model
characters / 937 original JSON characters**. The pinned SDK allocates its diagnostic
model and internal cache snapshot before application bounds; no large-room memory
or capacity claim follows from this measurement.

The Platform gateway now forwards only validated boolean lazy-member flags for
messages/context. Unknown/event/sender filters, duplicate keys, trailing input,
nonboolean or oversized values fail before provider access. The actual runner also
publishes app signals by completed rename under its exclusive fixture owner;
partial files can no longer satisfy the APK's ready-file check. Evidence retains
the original filter rejection, renderer crash, partial-signal race and Android's
rejection of the first hard-link publisher. Every attempt completed cleanup.

Full application checks pass in **290s**: **2,361 desktop / 129 Android host /
184 domain-browser / 151 data-browser** cases, zero failures/errors/skips
and zero Android lint errors. All **223 Mobile / 33 Platform source hashes** match.
Focused backend checks pass in 123s (40 domain / five history data / 21 API);
35 Python driver cases pass. Dev APK **`6cf0338f`** is built, not installed.

Platform **`61303ade50aac74124be6fb59ef10d880875f594`** is pushed; the HRIS workflow
pins that verified gateway/filter and atomic-signal fixture source.

**Continue P0–P8; none of 54 complete groups is accepted. Next connect the finite
history window to the common live-admitted acquisition port, then head discovery,
provider progress, ACK-before-journal reconciliation, command retirement and Chat
list/setup/conversation UI.** Do not infer send success from missing native queue
IDs or drop unresolved history. Keep original native transactions and SDK crypto
stores/cursors. Apple/web owned content runtimes, edit/attachment projection,
backup and all call/meeting phases remain. Physical iPhone testing is waived;
Apple implementation/interoperability is still required.

Prior hosted sender run `41273236979` (`20995a8c`) failed with a safe RuntimeError;
its trace contains provider-busy join and no encrypted send, cleanup complete.
Later canonical-storage source `ad0848e6` passed existing HRIS proof `41274726916`
(all 17 steps, 55.798s native suite, 115 Android host cases). Both outcomes are
retained in their measurement files. They do not validate this new window.
Current-source Apple/portable evidence must still be audited from hosted CI.

The dedicated emulator is stopped. No live/schema/phone change. Starter remains
`36116b2`; no reusable foundation change is required by these HRIS adapters.
Atomic commits and a push after every commit remain authorized. Continue after
this checkpoint; the collaboration implementation is not complete.

[Earlier November detail](collaboration-implementation-history-2026-11-01.md)

## Active collaboration implementation — 1 November 2026

**Canonical original-message storage and owned offline observation are implemented.**
[History evidence](measurements/collaboration-history-2026-11-01.json) records the
full **301s** root/browser/Android/Wasm run: **2,354 desktop / 115 Android host /
184 domain-browser / 144 data-browser** cases, zero failures/errors/skips and zero
Android lint errors. All **207 source hashes** match. Six new common observer and
12 shared SQLite/IndexedDB cases cover ownership/admission and real encrypted
storage. Initial storage checks passed in 68s; the expanded build found a nullable
fixture argument (48s). One root attempt was stopped when its scripted correction
had not applied; the explicit correction precedes the passing full run. Dev APK
**`c757faec`** is built, not installed.

Original identity is scoped by account/company/conversation/room plus authenticated
sender and logical UUID, with provider-event fallback for legacy content. Exact
retries share one canonical message; all observed provider aliases remain indexed.
Another sender copying the ID stays separate. Conflicting original content or
retargeted provider IDs fail the complete batch. Redaction before original content
retains a tombstone; confirmed deletion of an alias hides the logical message and
later original/retry input cannot resurrect it. The first observed primary event
and ordering timestamp remain stable.

History uses the existing atomic ReplicaStore with independent AES-GCM and HMAC
keys in a separate OS credential record. It does not share the outbox's 128-message
capacity. Queries page at most 50 messages plus one lookahead; the 137-message test
checks paging beyond that outbox bound, not production scale. Atomic batches hold
1–16 changes and each canonical message at most 128 aliases, without eviction.
Missing keys cannot regenerate over inactive-account records. Indexed event and
sender/logical-ID lookups support future ACK recovery. Offline observation learns
its authenticated room binding from the checkpoint; no SDK or HTTP room discovery
is needed. Known company denial closes pending observation, and final page checks
capture account ownership after admission I/O. Ordinary credential age preserves
owned content.

**Continue P0–P8; none of 54 complete groups is accepted. Next implement bounded
native history windows and original-event decoding/acquisition, close the
ACK-before-journal gap, then command retirement and Chat list/setup/conversation
UI.** No native adapter currently supplies the new history port. Its content
fingerprint must be computed from complete canonical original content, including
unknown fields; the SDK's `latestJson` may represent an edit. `debugInfo` exposes
original JSON but also formats the full model, so measure/bound extraction before
using it. NotificationClient raw JSON is filtered by notification rules and is not
a general history API. Event-focused timeline windows exist in the pinned SDK;
creating a default live timeline and rejecting >512 items is not a product window.
Retain conflicts/uncertainty; do not replace native transactions, clear SDK keys or
cursors, or introduce a second transport retry owner. Provider acquisition cursors,
edit projection, quota/overflow recovery and SDK-cache/backup deletion remain.

The common sender's actual HRIS/SDK proof remains **`25eb1e1f29fb4bd18643e2536bffce07`**
(Mobile `20995a8c`, Platform **`6e50bbd05a557ecc7fb82c0ab0a44da99558cdb9`**, still the
HRIS workflow fixture pin). It checks 3/3/3 HTTP admissions, actual encrypted ACK,
durable receipt, interrupted `Sent` projection, database reopen and one repair then
zero on replay. This history increment changes no native adapter/fixture, and no
new native-device execution is claimed.

Separately audited receipt CI **`41270939965`** at **`8d8fb233`** passed all 28 steps:
316 portable desktop / 292 browser / **260 iOS simulator** cases, actual **Xcode
application-host build**, and all **191 immutable source hashes** matched. See the
receipt measurement's hosted audit. That result does not validate later source.
Physical iPhone tests are waived; current Apple implementation/build evidence
continues through CI, and native Apple interoperability remains outstanding.

The emulator is stopped. No live/schema/phone change. Starter remains `36116b2`
and clean; this HRIS feature/host composition requires no generic port. Atomic
commits and every-commit pushes remain authorized. Continue after this checkpoint;
the full chat/calls/meetings implementation is not complete.

[Storage and observation contract](collaboration-history.md).

[Earlier November detail](collaboration-implementation-history-2026-11-01.md)

## Active collaboration implementation — 1 November 2026

**The common sender and receipt recovery now pass through real HRIS APIs and the
Android SDK.** [Integration evidence](measurements/collaboration-sender-integration-2026-11-01.json):
actual run **`25eb1e1f29fb4bd18643e2536bffce07`**, **25.386s test case / 46.694s suite**,
**83s Gradle including Platform quality**. Server-side counters verify **3 device /
3 conversation / 3 room HTTP admissions, zero failures**, with two successful
rounds before the first encrypted send and the third before `Sent` projection.
Product API classes/remote repositories, `DeliverCollaborationMessages`, the native
writer and actual encrypted receipt store participate. Local login/workspace state
is seeded fixture input; this does not exercise login UI.

The fixture deliberately rejects `Sent` projection after the real ACK is journaled.
It checks the original `Staged` record, closes/reopens the actual app database,
checks the retained receipt and runs product `ReconcileCollaborationMessages`:
one repair, then zero on replay. The independent native observer agrees on the
original event/transaction. Encrypted exchange in both directions, previous actual
staging process death, native key/cursor recovery, held sibling room and cleanup
all remain passing. New strict flags `commonSenderOwned`, `receiptJournalRecovered`
and `projectionInterrupted` supplement server-owned `applicationAdmittedBeforeSend`
and bounded admission counters. Only the private sender fixture receives an HRIS
API credential; published artifacts have safe flags/hashes/counters.

All **198 Mobile / 24 Platform source hashes** match. Proof build/lint/format/detekt/
architecture passes in **67s**, with zero lint errors; **13 driver tests** pass,
including rejected variants of the new flags. Initial Platform compilation failed
on nullable-Boolean atomic CAS warnings (17s; no SDK execution); an integer marker
resolved it before the passing actual run. Proof APK **`a7b6bb8b`**. No production
source changed after Mobile **`b5834eb84e6f2f89fdd3d63976e86e8d6112f1f4`**, whose
full **2,336 desktop / 115 host / 178 domain-browser / 132 data-browser** checks
pass in 270s. Its Dev APK `f06f2d5a` remains built, not installed.

**Continue P0–P8; none of 54 complete groups is accepted. Next implement incoming
canonical history and bounded SDK history windows, then command retirement and
Chat list/setup/conversation UI.** This integration reopens the database after
projection failure; it does not add process death after ACK. History must close
native-ACK-before-journal loss, coalesce by conversation/authenticated sender/
logical ID, reject conflicting reuse and retain all aliases/tombstones. Original
content must stay distinct from edits. SDK `latestJson` may contain the latest
edit; `loadOrFetchEvent` exposes typed content without the custom logical-ID JSON.
Inspect/measure the pinned SDK original-event and bounded-window APIs before
choosing the adapter; a 512-item overflow guard is not a product history window.
No replacement transactions, SDK key/cursor resets or second transport retry owner.

Current journal bounds remain 128 messages / 512 aliases per conversation,
128 aliases per message and 1–32 per append. The shared 128-command account limit
still has no retirement. These initial bounds are not capacity acceptance.
Application/background invocation ownership and notification/call/meeting work
remain. Physical iPhone testing is waived; Apple implementation/interoperability
and the hosted Xcode app build still need evidence. Prior native/control run IDs
and exact earlier proofs are in the archive and measurement files.

The dedicated emulator is stopped. No live/schema/phone change. Starter stays
`36116b2`; this HRIS fixture/adapter work needs no generic port. HRIS CI now pins verified Platform fixture
**`6e50bbd05a557ecc7fb82c0ab0a44da99558cdb9`**. Atomic commits and every-commit pushes remain authorized. Continue after
this checkpoint; the long-run implementation is not complete.

[Earlier November detail](collaboration-implementation-history-2026-11-01.md)

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



## Common staging checkpoint — 1 November 2026

`StageCollaborationText` now coordinates live owner/company/device/conversation/
room admission, the immutable local intent and exclusive paused native writer.
It saves `Staging` before enqueue, reconciles one original native transaction and
CAS-publishes `Staged`. Lost responses, cancellation, failed snapshots/writes,
unknown transactions and another device family preserve uncertainty. Already
staged/sent results retain their original binding. No delivery is enabled.

Fixed an account-switch race in both local queueing and staging: capture the
current owner after the final admission I/O. Two deliberate negative-control
tests failed with the old ordering, then passed after correction. A native
operation interrupted independently of its caller now retires the protected
runtime through full child/task/database-pool drain, even if no waiter remains.
Ordinary caller cancellation/timeout keeps that native operation owned.

[Staging evidence](measurements/collaboration-message-staging-2026-11-01.json):
16 new common cases. Initial focused checks pass in 62s; the two negative controls
fail as expected in 25s; corrected focused/proof/quality checks pass in 92s.
Full root/browser/Android/Wasm/lint passes in **295s**: **2,288 desktop / 384 suites**,
**150 domain / 112 data browser**, zero failures/errors/skips and app/proof lint
errors. All **171 Mobile / 24 Platform source hashes** match before/after actual
execution. Dev APK `ed7d2318` is built, not installed; proof APK `44f9b05d`.

Actual HRIS SDK run **`fa782638a1e94a1a88309c9829c745e1`** passes in **40.127s**
(69s Gradle/quality). Protected stores, identity, original transaction and the
encrypted app intent survive real process death before app mapping; exclusive
writer/closed-handle recovery, both E2EE directions, no early send, sync fault and
retained-cursor recovery all pass. Every proof/cleanup flag is true. This validates
the current native primitive; the common staging policy is covered separately by
portable tests, not production UI. The dedicated emulator is stopped.

**Continue P0–P8; none of the 54 feature groups is accepted. Next prove and implement
per-room delivery, then durable recipient logical-ID/event-alias reconciliation
and Chat list/setup/conversation UI.** Pinned Rust source shows that first access
to a room queue can spawn tasks. The historical synchronous room-enable failure
may be initialization outside its async runtime. The product writer must initialize
through an async API before room controls. Run an actual two-room experiment:
enable the admitted room while another queued room stays paused. Do not use global
enablement as a substitute. Synapse dedup mappings expire after 24h; recipient
coalescing remains required. Preserve unknown/cross-family uncertainty and SDK
keys/cursors. The 512-item observer guard is not a product history window.

Hosted checks for intent commit **`518575387be8ed1622b4c08f020076b09ff9e1df`** are now
audited: control **`41263763417`**, all 26 steps, **265 desktop / 241 browser / 220
iOS simulator**; HRIS **`41263763420`**, all 17 steps, SDK **`81d990e0` / 72.312s**,
all proof/cleanup flags true. All 19 intent source hashes match the immutable commit.
[Intent evidence](measurements/collaboration-message-intents-2026-11-01.json) includes
these artifacts. Its older SDK fixture does not exercise the later app mapping.
Runtime `67d23041` now has three successful CI runs (41262754682 / 41262754762 /
41262754840), but those artifacts remain unaudited. Native writer `117a5794` protocol
41264784290 succeeds; HRIS 41264784292 and control 41264784282 were still running.

Latest preceding implementation: Mobile **`117a5794f2d178b273c3834b53012f0660771c80`**,
Platform **`7e95bffdae322e16c9b904d21461b42c19b20012`** (the current HRIS CI pin).
No live/schema/phone change. Starter remains `36116b2`; these HRIS SDK/policy
changes need no generic port. Atomic commits and every-commit pushes remain
authorized. Continue implementation; a checkpoint is not completion.

## Per-room initialization checkpoint — 1 November 2026

**Per-room queue enablement is proven; global enable is not room admission.**
The Android product writer now initializes `Room.timeline()` through async FFI
before synchronous queue controls. Pinned Rust first access may spawn tasks.
Cleanup tracks completed initialization, so failed encryption/timeline acquisition
cannot accidentally create a queue from synchronous FFI. Product leases still
expose paused snapshot/enqueue/close; the fixture alone enables delivery.

[Two-room evidence](measurements/collaboration-room-queue-2026-11-01.json):
final actual HRIS SDK **`837dfb7f8f154058a06d65faa46229a6` / 52.330s** (92s Gradle /
quality), all proof and cleanup flags true. A second room is staged before any
main timeline, then both original transactions survive real process death. The
admitted room exchanges encrypted messages in both directions while the other
room stays disabled with its exact transaction. HRIS counts **zero attempted
sends** for the held room. Existing store/identity/intent-mapping/exclusivity/sync-
fault/retained-cursor checks continue to pass.

An intentional global-enable APK **fails as expected** at
`HrisProofHeldQueue.checkPaused`: SDK **`03148dbf` / 43.455s**, 60s Gradle, cleanup
true. The first positive **`ad5d25ae` / 47.760s** and restored positive
**`f685907d`** use a byte-identical APK. Subsequent cleanup review added the
initialization guard; final actual execution matches all **172 Mobile / 24
Platform hashes**. The measurement records the earlier one-file source delta.

Initial quality failed the fixture's 100-line function bound (109 lines, 32s).
Extracted fixture parsing; corrected root/Android/proof checks pass in 86s.
Final root/Android/proof checks after the cleanup adjustment pass in **95s**:
**2,288 desktop / 384 suites**, zero failures/errors/skips and app/proof lint errors.
The 13 Python driver tests pass, including two new rejected proof-flag variants.
Common source is unchanged; browser tests were not repeated in this increment.
Final dev APK `6648df90` is built, not installed; proof APK `a6e73c0a`.
The dedicated emulator is stopped. No live/schema/phone change.
HRIS CI pins the verified Platform **`4944b34f0946d449bc4b7ff4651737db2255ce93`**.

**Continue P0–P8; none of the 54 feature groups is accepted. Next implement the
admitted per-room delivery lifetime and durable result reconciliation.** Preserve
ACKs and unresolved native transactions; bound delivery/listener lifetimes and
pause before releasing ownership or allowing another writer. A caller disappearing
cannot cancel a native commit. Do not introduce another transport retry owner or
clear keys/cursors. Recipient logical-ID/event-alias coalescing, bounded durable
history, bootstrap/cross-family recovery and Chat list/setup/conversation UI still
remain. Synapse transaction mappings expire after 24h; stable SDK transaction IDs
alone do not prevent duplicate recipient presentation.

Common staging is pushed at Mobile **`1a8b6283`**, its cross-repository handoff at
Platform **`e4800f12`**. Sixteen new common cases include two account-switch negative
controls; 295s root/browser/Android/Wasm checks pass (2,288 desktop, 150/112 browser).
Actual native primitive `fa782638` passes in 40.127s. See
[staging evidence](measurements/collaboration-message-staging-2026-11-01.json).
Hosted intent `51857538` remains audited: control 41263763417 (265 desktop / 241
browser / 220 iOS simulator), HRIS 41263763420 (72.312s, all flags true), all 19
immutable intent hashes verified. Later unaudited CI must not be claimed as proof.

Starter remains `36116b2`; these HRIS SDK/proof changes need no generic port.
Atomic commits and every-commit pushes remain authorized. Continue implementation;
a checkpoint is not completion.

## Native delivery checkpoint — 1 November 2026

**The product native queue lease now owns bounded per-room delivery.** `resume`
validates a complete initial native snapshot against immutable mapped transactions,
then permits only that room to send for at most 15 seconds. Results retain observed
receipts/event aliases separately from pending IDs. Missing native IDs never
invent acknowledgements. Unknown mutations, malformed/conflicting receipts and
bounded overflow remain unresolved. Native SDK transport keeps its retry ownership.

Child disposal pauses the room **before** joining the native operation and blocks
late enablement after a pending listener acquisition. Ordinary caller cancellation
keeps native work owned. Delivery allows a 25-second caller wait, with an internal
30-second ceiling; snapshot/enqueue defaults stay five seconds. Finalization
pauses, cancels/joins listeners and only then releases writer resources. Ignored/
late callbacks dispose native payloads; slow cleanup retains handles for retry.

[Delivery evidence](measurements/collaboration-delivery-2026-11-01.json): **15 new
Android host cases** use the SDK's public `NoHandle` constructors, and **9 new common
cases** exercise receipt bounds/conflicts, partial results and the longer waiter.
The data module enables host tests; root `check` and HRIS CI include them, with XML
artifacts retained. All focused checks pass (56s host foundation, 103s initial
native delivery, 154s updated product proof). Full root/browser/Android/Wasm/lint
passes in **252s**: **2,297 desktop / 385 suites**, **115 Android host**, **150 domain /
121 data browser**, zero failures/errors/skips and app/proof lint errors.

Actual HRIS SDK **`4cab930521cc4446a95ab19c8eb89491` / 47.608s** passes (88s Gradle /
quality). The fixture now calls the product `resume` port after real process death,
compares its receipt to an independent native observer, commits the original app
`Sent` mapping and leaves the sibling room paused. Every proof/cleanup flag,
including **`nativeDeliveryOwned`**, is true. All **179 Mobile / 24 Platform source
hashes** match. Dev APK `d0eda413` is built, not installed; proof APK `ae26173c`.
The dedicated emulator is stopped. No live/schema/phone change.
HRIS CI pins the verified Platform **`a5ec785cc03c9aebd3adc989031ca3bc5310140c`**.

**Continue P0–P8; none of the 54 feature groups is accepted. Next implement durable
application receipt/history reconciliation and the common live-admitted sender.**
The native window is a primitive: application admission and durable mappings must
precede it; all observed aliases need durable handling before accepting product
sending/history. Preserve original native-family uncertainty. Synapse dedup mappings
expire after 24h, so recipient ingestion must coalesce identical logical IDs scoped
by conversation/authenticated sender, reject conflicting reuse and retain event
aliases/tombstones. Then bounded history windows, bootstrap/cross-family recovery
and Chat list/setup/conversation UI. No second transport retry owner or key/cursor
reset. The existing 512-item SDK observer guard is not a product history window.

Newly audited hosted evidence: staging `1a8b6283` control **`41266433679`**, all 26
steps, **286 desktop / 262 browser / 241 iOS simulator**, all 171 immutable source
hashes match. Room controls `90c43ed7` HRIS **`41267815552`**, all 17 steps, SDK
**`5cc68e30` / 68.256s**, all flags/cleanup true, all 172 Mobile / 24 Platform hashes
match the immutable commit and its `4944b34f` fixture pin. The corresponding
[staging](measurements/collaboration-message-staging-2026-11-01.json) and
[room-control](measurements/collaboration-room-queue-2026-11-01.json) measurements
include these artifacts. The native protocol run `41267815497` reports success but
has not been audited; control `41267815485` was still running when last checked.

Preceding room-control implementation: Mobile `90c43ed776556199b5f7319c94b6c0773036b7a3`,
Platform fixture `4944b34f0946d449bc4b7ff4651737db2255ce93`; follow-up handoff `d1a743fe`.
Starter remains `36116b2`; the SDK adapter/test configuration is HRIS-specific and
needs no generic port. Atomic commits and every-commit pushes remain authorized.
Continue implementation; a checkpoint is not completion.

## Receipt journal checkpoint — 1 November 2026

**Encrypted outbound receipts and offline `Sent` reconciliation are implemented.**
[Contract](collaboration-receipts.md): the journal stores the original complete
intent/device/transaction and every event alias before command projection. Atomic
checkpoint/document CAS prevents lost concurrent aliases; immutable mapping and
cross-message collisions are rejected. Separate receipt keys are wired through
Android, desktop, browser and Apple Keychain hosts. Missing keys cannot regenerate
over another inactive account's records. No prior receipt is evicted on capacity.

`ReconcileCollaborationMessages` repairs a crash between journal and command writes
without network, SDK acquisition or live credential renewal. Exact staged mappings
advance; existing sent records retain their original primary event. Missing or
conflicting evidence stays unresolved. Company gates and account ownership are
rechecked after every I/O; ordinary expiry keeps facts available, and late denial/
account change prevents success while preserving committed state.

[Evidence](measurements/collaboration-receipt-2026-11-01.json): **11 new actual
SQLite/IndexedDB contract cases and 10 common reconciliation cases**. Focused
checks pass in **69s**; full root/browser/Android/Wasm/lint passes in **274s**:
**2,318 desktop / 387 suites, 115 Android host, 160 domain / 132 data browser**, zero
failures/errors/skips and Android lint errors. All **191 source hashes** match.
Dev APK `a3e62aa9` is built, not installed. The first command selected a nonexistent
module detekt task (15s); the corrected run passed 11 SQLite cases but exposed the
app-container length rule (57s). Moving static registrations into the existing
feature-module list resolved it, and the full application binding tests pass.

Collaboration control CI now compiles the **actual iOS application host** as well
as Kotlin/Apple tests; its Apple job has a bounded 70-minute lifetime and a
30-minute app build step. That Xcode build is pending hosted execution. Physical
iPhone acceptance remains waived. No native SDK source changed in this increment;
previous actual delivery evidence belongs to Mobile `622d2540` / Platform
`a5ec785c`, not a new SDK run. HRIS CI retains that immutable fixture pin.

**Continue P0–P8; none of 54 complete groups is accepted. Next connect the common
live-admitted sender to native `resume` and durable receipt append, then incoming
history reconciliation and Chat list/setup/conversation UI.** The journal alone
cannot recover a crash before native ACK reaches it: history must coalesce by
conversation/authenticated sender/logical ID, reject conflicting reuse and retain
all event aliases/tombstones. Preserve original native-family uncertainty; never
replace a transaction, infer ACK from queue absence, reset SDK keys/cursors or add
a second transport retry owner. Current journal bounds are 128 messages / 512
aliases per conversation, 128 aliases per message and 1–32 messages per append.
The shared 128-command account bound and no-retirement rule remain until durable
history supports retirement. These initial bounds are not capacity acceptance.

No live/schema/phone change; emulator remains stopped. Starter stays `36116b2`:
this HRIS receipt policy/adapter/host graph needs no generic foundation port.
Atomic commits and every-commit pushes remain authorized. Continue after this
checkpoint; the long-run implementation is not complete.

## Common sender checkpoint — 1 November 2026

**The common live-admitted sender now connects original native mappings to the
receipt journal and command projection.** [Contract](collaboration-sender.md):
`DeliverCollaborationMessages` checks active device, owned DM/ready room, company
gate and exact native owner; acquires the paused writer; repairs existing receipt
projections; rejects uncertain/foreign/unmapped work; re-admits before `resume`.
It never enqueues, replaces a transaction, creates/restores a runtime, or owns a
second transport retry loop. Application/background invocation ownership and UI
are still pending.

Received valid aliases are journaled in 1–32-message batches under the captured
raw account fence and a **five-second noncancellable bound** before `Sent` changes.
This retains already received facts after late expiry, cancellation or company
closure without authorizing another send or private publication. Replacement
accounts fail the store fence. Gate/owner and fresh remote admission follow capture
before projection. Partial receipts/writes survive; absence never invents ACK.
Existing `Sent` primary events remain unchanged. All terminal paths close the
writer through existing runtime-owned cleanup.

[Sender evidence](measurements/collaboration-sender-2026-11-01.json): **18 new common
cases**, including partial 33-message journal capture/reconciliation, late denial,
account changes, expiry, cancellation, five-second storage timeout, conflicts and
primary preservation. Focused checks pass in **66s**; full root/browser/Android/
Wasm/lint passes in **270s**: **2,336 desktop / 388 suites, 115 Android host,
178 domain / 132 data browser**, zero failures/errors/skips and Android lint errors.
All **196 source hashes** match. Initial compile failed on a report/enum name
collision (21s); the report was renamed before the passing checks. Dev APK
`f06f2d5a` is built, not installed. Native SDK implementation is unchanged.

Newly audited prior [native delivery CI](measurements/collaboration-delivery-2026-11-01.json):
Mobile `622d2540` run **`41269736098`**, all **17 steps / 115 Android host cases** pass.
Actual HRIS SDK run **`6aee6870e76d4fd9b50cfd85701329e2` / 22.689s case / 62.419s
suite**, all proof/cleanup flags including `nativeDeliveryOwned` true. All **179
Mobile / 24 Platform** hashes match immutable `622d2540` / `a5ec785c`. This proves
the preceding native primitive, not the new common sender. Protocol `41269736107`
reports success but is unaudited. Control `41269736119` was running. Receipt
`8d8fb233feee480e6e7dc7bc0430195ccf906005` control **`41270939965`** was pending;
HRIS **`41270940059`** was running. That control adds actual Xcode app-host compile;
physical iPhone acceptance remains waived. No new actual SDK run is claimed here.

**Continue P0–P8; none of 54 complete groups is accepted. Next exercise the common
sender and journal together through the real HRIS APIs and Android SDK fixture.**
Use the existing `HrisProductQueueProof` after actual staging process death; add
fixture-native HRIS API credentials privately from `NativeGatewaySdkFixture.Native.access`
to drive the product setup/conversation API adapters. Preserve the independent
native receipt observer and held sibling-room checks. Inject failed `Sent` projection,
reopen the actual app database, and reconcile the durable original receipt; do not
claim local fixture session/workspace setup exercises login UI. Then incoming
history must close ACK-before-journal loss with conversation/authenticated-sender/
logical-ID coalescing, conflict rejection and retained aliases/tombstones. Add
bounded windows, command retirement and Chat list/setup/conversation routes.

No key/cursor resets or replacement transactions. Initial receipt bounds remain
128 messages / 512 aliases per conversation, 128 aliases per message and 1–32 per
append; the shared 128-command account bound still has no retirement. These are
not capacity acceptance. HRIS CI retains Platform fixture pin `a5ec785c` until
actual fixture code changes. No live/schema/phone change; emulator is stopped.
Starter remains `36116b2`; current HRIS code needs no generic foundation port.
Atomic commits and every-commit pushes remain authorized. Continue after this
checkpoint; the long-run implementation is not complete.
