# Agent continuation — HRIS Mobile

Updated 9 November 2026, 16:38 UTC. Historical detail is retained in
[02e61220](https://github.com/fajarxfce/hris-mobile/blob/02e61220/docs/agent-handoff.md).
This file owns current work; do not restart old jobs from historical notes.

## Authorization and boundaries

The user authorized the entire communications PRD, continued implementation, atomic
commits with pushes and validated phone updates. All54 acceptance groups remain open.
Mobile proves generic foundations before Starter. Follow AGENTS.md: use cases own
policy/repository orchestration, repositories map datasource I/O, ViewModels own screen
state. Preserve exact accounts, native/SDK identities, keys, original queues and messages.

One heavy local compiler OR emulator. Do not kill live API/worker JVMs. The owned
emulator uses2560MiB/two cores and is stopped. Announce each phone installation to
10.77.77.3:5555 and use install-only; never launch, drive, uninstall or clear the phone.
No repeat IAM request: Crashlytics access is verified. No physical iPhone interaction
is required; Apple source/runtime, push and calling acceptance remain separate.

## Current checkouts and runtime

| Checkout | Branch/source | State |
| --- | --- | --- |
| Mobile original | feat/chat-send-timing-20261107 /this commit | Original-only owning delivery accepted; no product activation |
| Mobile isolated CI | feat/pinned-inspector-ci-20261109 /951cdbcf | Public consumer evidence accepted and integrated |
| Inspector original | feat/matrix-rust-bindings-20261107 /f8ca3d6 | Verified public SDK6 integrated |
| Inspector isolated | feat/sdk6-distribution-20261109 /a6b8236 | SDK6 distribution accepted; preserve unrelated gradlew.bat delta |
| Starter | feat/network-inspector-20261107 /ffd0ac5 | Generic pinned workflow/checksum port accepted |
| Platform original | feat/communications /691f3fc5 | Recovery verifier port accepted/pushed; runtime unchanged |
| Platform group | feat/communication-space /96d2d238 | Reconciliation and current direct transport accepted/pushed |

Isolated directories are under `/home/fajar/hris/.worktrees/`: `communication-space`,
`mobile-inspector-ci-2026-11-09`, `inspector-sdk6-2026-11-09` and
`inspector-pinned-consumer-2026-11-09`. The last checkout exactly matches742a2d1 bytes;
its apparent gradlew.bat change is text/eol normalization, not a byte change.

Live API/worker remains e558a6868399058a2dcdee97acfd0e7fa7f1ef07, schema116.4,
readiness control ON. Group V117–138 and organization routing are not deployed.

## Accepted direct transport

Thirteen Mobile Kotlin files implement the finite native direct-transport API,
datasource/repository/DTO, public domain facts and pure validation; published-room
mapping extraction; eight transport test methods and application DI resolution.
[Contract](collaboration-space-direct-transport.md) and
[measurement](measurements/collaboration-space-direct-transport-2026-11-09.json)
record source scope, exact report hashes and limits. No SDK/UI/worker caller is added.
Device SPACE does not imply user SPACE: a new HRISG_ crypto device may retain its
LEGACY selected user/original room. Logical and physical device IDs stay distinct.

- Focused25221 is REAPED PASS203.352s:23 fresh cases (8 direct,13 existing room,2 app DI).
- Full90875 FAILED22.617s before testing: unsupported private browser filter closure.
  Its script/result are preserved. No application source changed for the correction.
- Full94359 (`full-2`) is REAPED PASS548.834s:4,237 fresh cases (3,595 desktop,
  621 Android host,21 browser), zero failures/errors/skips. Both exact browser classes
  contain8+13 cases. All4,562 application inputs and Inspector sources stayed fixed.
  Root, release compilation/lint, Wasm, Linux iOS source/configuration and quality pass.
  Exact archived init SHAbe393ff16f62e2e6a48c533eab388c2de95d05afb76a223ed8ea5371dcc87fef.
- Strict publisher verified reports/manifests and accepted the source contract.
  This does not claim native Apple execution, a new APK or product activation.

Backend96d2d238 is accepted/pushed with452 audited domain/SQL/API/FULL-worker cases;
its nine final corrected cases pass148.630s. Live runtime/schema remain unchanged.
All local heavy jobs are reaped. Do not repeat accepted source reconciliation or gates.

Hosted42628449460 is complete SUCCESS on exact78076d4e. Both artifacts match GitHub
digests and ZIP CRC. [Hosted evidence](measurements/collaboration-space-binding-hosted-2026-11-09.json)
records9,003 cases:3,035 desktop,613 Android host,2,898 browser and2,457 Apple simulator,
zero failures/errors/skips. The21 domain+3 actual storage binding-preparation cases
are present on desktop, IndexedDB and Apple SQLite. Xcode's unsigned simulator host
build passes. No physical iPhone/APNs/calling or SDK6 native Matrix delivery acceptance.
Portable artifact13682061569 is3,273,813 bytes/SHA2560998f1decd370cfc32c08ed8806c1f104ab1ae57458aeaefe25a56a7ef9085b9;
Apple13684362715 is479,237 bytes/SHA25609f40e77dffdb4db8b777ee38fb0169d2946164333fddde80a9fec88a281e53f.
Audit67988 is reaped PASS; private evidence is the prior binding preparation's
`hosted-42628449460` directory. Do not redispatch or reaudit this accepted source.

## Accepted owning original delivery

Mobile direct transport ab17eadd is accepted/pushed;56681 is reaped. Both source
contracts and clean source manifests passed the guarded original-policy apply. Do not
repeat `.work/space-original-delivery-2026-11-09/apply.py`: the seven files are applied.

This commit contains12 validated Kotlin files: three pure policies (including the
narrow original-sender overload), their ten common test methods/fixture, the owning
`DeliverCollaborationSpaceOriginalMessages`,24 new owning-flow tests/fixture and DI/app
resolution. [Contract](collaboration-space-original-delivery.md) documents actual scope.
The new use case directly coordinates repositories, owns one exclusive invocation and
original-only writer, reconciles journal/archive evidence and uncertain CAS, settles
only proven originals, preserves missing SDK work as unresolved, and journals late ACKs
before separately gated projection. It starts no setup or transport/readiness loop.

Direct ktfmt0.59 passes0.999s for all12 Kotlin files. Focused39005 is REAPED PASS226.041s:49 fresh cases (47 domain+2 app DI),
zero failures/errors/skips;4,571 fixed application inputs and unchanged Inspector.
Formatting/architecture/Detekt pass. This is not full acceptance or product activation.
Review then added seven owning-flow regressions (24 methods total) without changing
production. Red10514 is REAPED FAILED62.397s:24 tests, exactly two failures, zero errors/skips
and fixed application/library sources. It tests reordered complete
inventory, post-ACK storage deadline, denial during callback, unrelated archived
receipts, missing ACK, native clock limits and SDK rejection scope. Preserve this
expected-failure proof. Both expected failures are corrected in the owning use case
only: compare exact transaction/content maps independently of list order, and wrap
SDK delivery plus projection in its own30s deadline. Focused82764 is REAPED PASS151.954s:56 fresh cases (54 domain+2 app DI),
zero failures/errors/skips,4,571 unchanged inputs and unchanged Inspector; quality
passes. Full88367 (`full-4`) is REAPED PASS561.986s:4,304 fresh cases (3,629 desktop,
621 Android host,54 browser), zero failures/errors/skips;4,571 unchanged application
inputs and unchanged Inspector. Root/release compile-lint/Wasm/Linux iOS source/
format/architecture/Detekt pass. Publisher29124 is REAPED PASS: exact current/focused/
full manifests, every archived report, all six browser classes and both red assertions
verified. [Accepted evidence](measurements/collaboration-space-original-delivery-2026-11-09.json).
All local heavy jobs are reaped. No new APK, actual SDK delivery or native Apple proof. No new APK/phone installation.
The SDK's25s delivery ownership is separate from15s preparation; current hard native
expiry and captured denial remain gated. Final close must pause/drain before release.

Actual SDK/SQL/HTTP/restart proof and group readiness wake/background/UI ownership are
still required. `.work/space-original-delivery-native-2026-11-09/README.md` identifies
existing real Synapse/SQL/HTTP/proof-app facilities and the new two-original, offline
recipient, interrupted post-ACK projection/restart scenario. It is a plan, not code/proof. Preserve the stopped sender store and all eight originals below.

## Current Mobile gate

Encrypted published-room binding storage0f6c843f is accepted/pushed. Its14 shared
storage cases use exact immutable canonical/provider/original facts and encrypted
atomic conversation/room indexes. Full45444 passed4,187 fresh cases; its inherited
browser filter selected the wrong class. That selection failure remains recorded;
corrected browser45197 passed28 actual IndexedDB cases on identical sources.
[Storage evidence](measurements/collaboration-space-room-binding-2026-11-09.json).

The owning `PrepareCollaborationSpaceRoomBinding` candidate first reads complete owned
local facts without native expiry/network/admission writes. Only absence performs a
bounded current acquisition: one published room, exact LEGACY original if needed,
canonical retention and exact binding readback. No SDK, queue, UI or worker is opened.
Nine source files include21 domain cases, three shared actual database cases with
Desktop/browser/Apple wrappers and actual application DI resolution.
[Contract and current verification](collaboration-space-room-binding-preparation.md).

- Initial focused71916 passed20 domain cases but FAILED Detekt LongMethod. A cohesive
  local read phase replaced that oversized operation; no suppression was added.
- Focused92982 is REAPED PASS239.944s:25 fresh cases=20 domain+3 actual SQLite+2 app DI.
- Full36351 is REAPED PASS586.604s:4,222 fresh=3,586 desktop+613 Android host+23 browser,
  498 fresh/441 preexisting suites, zero failures/errors/skips. Actual browser XML is
  verified as20 preparation+3 IndexedDB cases. Root/release/Wasm/iOS-source/quality pass;
  all4,551 app/library inputs stayed fixed and equal the focused source manifest.
- Review found a coincident-expiry race: a valid directory denial was read but not
  retained before the native session check failed. New regression10100 is REAPED with
  one expected failure (AccessDenied replaced by client.session_changed); production
  source exactly matches the earlier full gate. The correction retains the captured
  denial before checking native expiry, leaving local ownership separate.
- First correction focused61497 FAILED144.234s:26 cases/one failure. The shared
  response policy still preferred the later local expiry; preserve this failure.
- Final correction keeps captured global denial specifically at local native checking;
  a prior network error still yields current native rejection. No global policy changed.
  Formatter12973 PASS36.991s; final focused50366 REAPED PASS142.545s,26 cases.
- Final full81742 REAPED PASS511.375s:4,224 fresh=3,587 desktop+613 Android host+24
  browser. Browser XML verifies21 domain+3 IndexedDB cases. Zero failures/errors/skips;
  root/release/Wasm/iOS-source/quality pass and4,551 application/library sources match
  the final focused manifest. All Mobile local compiler jobs are reaped.

[Accepted evidence](measurements/collaboration-space-room-binding-preparation-2026-11-09.json)
retains source hashes, first failures and corrected proof. Private files are in
`.work/space-room-binding-preparation-2026-11-09`. Hosted Apple storage/domain execution
and the Xcode simulator host are now accepted above. No new APK, SDK/worker/UI activation
or signed iOS distribution is claimed by that proof.

## Accepted Platform reconciliation and current transport gate

Group bf6e176a and Root4d4df31f diverged171/85 commits since8bad394e. Eight conflict
paths were resolved, retaining both runtime-transfer guards and current company
readiness/secret-storage behavior. Group shared MatrixSyncReader now explicitly
receives company readiness framing; a new test rejects old company controls even
when an adopted group runtime keeps the same physical Matrix user/device.

The merged default-key HTTP endpoint originally still dispatched only company
credentials. A fixed group read now uses its existing native/provider request lifetime;
missing, empty and present data stay distinct. Three new actual SQL/admission cases
and one HTTP routing case cover this. The full gate below accepts this source merge, including shared API and FULL worker
contexts because DI changed. It does not deploy the organization schema or routing.

The recovery verifier also rejected reviewed116.2–4 schemas. One new regression fails
before the explicit-version correction;27 Python migration/recovery cases now pass.
Only reviewed versions are allowed; encrypted envelope projection is unchanged. This
is verifier proof, not a live recovery. Original Platform691f3fc5 independently accepts/pushes the minimal port, with40
passing database-recovery/migration/recovery-set Python cases and its own116.4
maximum. Group keeps138; do not copy either branch's schema maximum to the other. Details/scripts live under the group checkout's
`.work/space-root-reconciliation-2026-11-09`:

- 49700 failed226.851s after207 passing domain/data cases: five Group-only fixture
 calls retained the old directMetadata name after Root renamed it accountData.
- 67950 failed83.59s:two HTTP cases/one failure. The new default-key test froze the
 application clock before PostgreSQL created its lease; request_lost was correct.
 Only that live-operation fixture now follows system time, keeping all assertions.
- 24065 REAPED PASS75.409s:both HTTP cases pass, zero failures/errors/skips; cached
 domain/data results are not recounted.
- Full33511 is REAPED PASS5,607.828s:1,444 fresh cases, zero failures/errors and one
 existing elapsed-retention opt-in skip,8,686 unchanged inputs. Database138/domain189/
 data762/delivery25/API180/FULL worker150 plus quality/migration/recovery pass. Exact
 archived XML and all five new regressions/both readiness contexts were audited.
- Reconciliation fa90ddcc is committed and pushed;34015 is reaped. No live deployment.
- Direct-transport full38552 is REAPED FAILED459.946s:342 fresh domain/worker cases,
 zero test failures/errors and one existing opt-in skip. SQL/API compilation failed on
 two fixture mistakes; production/DI and the complete150-case worker task passed.
- Focused34852 is REAPED FAILED591.639s:302 cases/eight failures. Seven new SQL cases
 used an active pre-V122 fixture with no provisioning head; the new HTTP fixture queried
 room_id instead of matrix_room_id. The30 existing room cases, actual retained LEGACY
 publication/direct transport,70 OpenAPI cases and existing group HTTP case passed.
- Test-only corrections now create valid provisioning history through actual enqueue/
 worker claim/finish and retain explicit missing-head rejection. The HTTP query uses
 the actual schema column. Formatter1.467s; production/schema/DI are unchanged.
- Targeted43271 (`targeted-3`) is REAPED PASS148.630s for nine fresh SQL/HTTP cases
 plus quality. Exact source/XML audit accepts452 cases:192 domain,38 SQL,72 API/OpenAPI
 and150 complete worker, zero failures/errors and one existing opt-in skip. Broader
 unchanged reports remain identified by their original gate. Only three SQL/API fixture
 files changed; production/DI/build/worker inputs stayed identical. Backend96d2d238 is
 committed/pushed;85313 is reaped. Reconciliation33511/fa90ddcc and hosted42628449460
 must not be rerun. Mobile full94359 also passed and is reaped; no heavy job remains.

The Group increment now contains twelve Kotlin files, including the actual LEGACY
publication regression. Its guarded initial apply must not be repeated. All private
attempts/corrections remain under `.work/space-direct-transport-2026-11-09`. Original
message.get already returns archived immutable commands; no archive fallback is needed.

## Accepted phone and native proof

APK87 is ALREADY INSTALLED. Product source d8383438; evidence ff1adeca.
[Release evidence](measurements/collaboration-release87-accepted-2026-11-09.json).
Nondebug devRelease ARM64, same signer, version87,123481487bytes, SHA256
43373c6c4e6335149138630e7c2c9d6daa05edc1f76729ff01dae1023916ce8b.
Install33957 is REAPED PASS210.86s, exact installed digest/first-install identity;
no UI launch, uninstall or clearing. No APK88 exists. Do not reinstall87.

Actual retained86→87 native proof passes670.401s. Home/room/resume/cold phases each
provide110 complete quiet seconds with active sync, zero setup/rejected admissions,
and no room_join across the whole run. Paired refresh200×3 retains returned credentials;
otherAccess0 and native parent26→27 renews before expiry. Original strict gate passes
without exemptions. New Joined87 send took2,066.684ms; previous86 sample2,983.114ms.
Room acquisition1ms/entry154ms; setup520ms/delivery825ms remain. These are individual
emulator samples, not phone latency or p95. Final screenshot is not first-frame proof.

Latest COMPLETE STOPPED sender store: `.work/joined-room-entry-native-2026-11-09/sender`.
Preserve the whole AVD and all userdata/encryption files, plus the stopped Bima74 store.
Eight originals must NEVER be resent: Control77 235408, Warm77 235728, Recovery78 005634,
Registry82 055959, Parent83 071123, History84 083059, Selected86 102110, Joined87 115837.
Draft66 092041 must NEVER be sent. All native/emulator jobs are reaped and stopped.

## Accepted source and public distribution

- Original room dispatch4e7be081: one exact bounded attempt, durable uncertainty and
  receipts, no replacement/poll loop.76 focused,5,963 full,74 browser cases accepted.
- Published original reference: backend8d5724e2 passes266 cases; Mobile5b63b498 passes
  49 focused/4,162 full. Exact LEGACY company/conversation/room/time is retained; SPACE
  has no original reference. Group backend is not deployed.
- Public inspector consumer CI42611795210 at21101489 passes7,950 cases; artifact
  13672363171 digest/XML/APK/SBOM audited. Root integration57ebc77a passes66 focused
  cases. Earlier failures remain recorded; this hosted source predates newer Room work.
  [Integration evidence](measurements/pinned-inspector-root-integration-2026-11-09.json).
- Starterffd0ac5: pinned public inspector workflows/checksums independently pass root,
  Android/Web/iOS-source/quality/SBOM and62 fresh browser cases; no hosted distribution.
- Immutable Matrix SDK26.10.23-hris.6 release438835525 is published. Never overwrite
  assets. AAR SHA a4aa1e2cb3136c0531bb35f7f06df10582c84c1974ce4e5fda7099d0ab533bb3.
  Inspector742a2d1 CI passes226 portable/Android-host+69 Apple simulator cases. Separate
  consumer Apple CI passes1,777 cases/Xcode, Windows passes, Web42599865437 passes
  production distribution. Native Apple Matrix binding remains open.

## Next work

1. Commit/push accepted original delivery with evidence, then dispatch the existing
   Collaboration control workflow for exact-source portable/Apple checks. Continue the
   actual SDK/SQL/HTTP/restart scenario documented above; do not rerun accepted local gates.
2. Direct contracts ab17eadd/96d2d238 and reconciliationfa90ddcc are accepted/pushed;
   do not reapply or rerun those unchanged gates. No heavy job is active.
3. Continue owning original SDK delivery/history, current recipient readiness, worker
   lifetimes and group UI routing. Existing Room Prepare/Resolve/Replay use cases and
   immutable stores are accepted; do not recreate them or replace uncertain originals.
4. Continue rich message actions/media/stickers/voice, Drive/iCloud encrypted recovery,
   MatrixRTC native call lifecycle, LiveKit meetings and all remaining PRD work. Scope
   is Platform docs/collaboration/prd.md and delivery.json; no acceptance group is done.
