# Agent continuation — HRIS Mobile

Updated 9 November 2026, 15:25 UTC. Historical detail is retained in
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
| Mobile original | feat/chat-send-timing-20261107 /78076d4e | Binding preparation accepted; no product activation |
| Mobile isolated CI | feat/pinned-inspector-ci-20261109 /951cdbcf | Public consumer evidence accepted and integrated |
| Inspector original | feat/matrix-rust-bindings-20261107 /f8ca3d6 | Verified public SDK6 integrated |
| Inspector isolated | feat/sdk6-distribution-20261109 /a6b8236 | SDK6 distribution accepted; preserve unrelated gradlew.bat delta |
| Starter | feat/network-inspector-20261107 /ffd0ac5 | Generic pinned workflow/checksum port accepted |
| Platform original | feat/communications /691f3fc5 | Recovery verifier port accepted/pushed; runtime unchanged |
| Platform group | feat/communication-space /bf6e176a | Merge of4d4df31f in progress; resolved source adaptations, not yet validated or committed |

Isolated directories are under `/home/fajar/hris/.worktrees/`: `communication-space`,
`mobile-inspector-ci-2026-11-09`, `inspector-sdk6-2026-11-09` and
`inspector-pinned-consumer-2026-11-09`. The last checkout exactly matches742a2d1 bytes;
its apparent gradlew.bat change is text/eol normalization, not a byte change.

Live API/worker remains e558a6868399058a2dcdee97acfd0e7fa7f1ef07, schema116.4,
readiness control ON. Group V117–138 and organization routing are not deployed.

## Active direct-transport candidate

Thirteen Mobile Kotlin files are uncommitted: finite native direct-transport API,
datasource/repository/DTO, public domain facts and pure validation; published-room
mapping extraction; eight transport test methods and the application DI assertion.
[Contract](collaboration-space-direct-transport.md) records current scope and limits.
Direct ktfmt0.59 parsing/formatting passed0.954s,0.866s and0.968s after stricter
ID/time/encoded-identity validation;256MiB heap, no Kotlin/test acceptance yet.
`.work/space-direct-transport-2026-11-09/check.py` is prepared, NOT RUN while Group33511
owns the local heavy slot. Its full/browser phases must select the new eight methods
plus the existing room transport suite; do not inherit the previous binding filters.

Review corrected one draft assumption before compilation: device SPACE does not mean
user SPACE. A new HRISG_ crypto device may retain a LEGACY selected user/original room.
Tests include all valid combinations and reject a mismatched physical device/user.
The backend `.work/space-direct-transport-2026-11-09/candidate` now has11 files including
API, DI and tests (seven actual SQL methods, three pure policy methods, HTTP/OpenAPI).
Formatter2.017s covers all11 files; it remains private, unapplied and uncompiled while33511 owns sources.
Its base hashes must match before applying after the accepted reconciliation merge.

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

## Prepared original matching follow-up

`.work/space-original-delivery-2026-11-09/candidate` contains seven PRIVATE UNVALIDATED
files: pure matching/acknowledgement/transport policies, fixtures and ten common test methods. The active domain source
is unchanged. Existing full-identity inspection APIs keep their validation/output;
`matchCollaborationOriginalMessages` accepts the exact canonical original sender facts
and retains the existing whole-inventory/command/completion checks, avoiding a made-up
identity-selection timestamp. Formatter passes0.680s initially,0.718s for four files and0.917s for all seven;
no compiler/test/SDK proof.
Do not apply before both direct-transport contracts are accepted. base.json pins the
copied policy source. Existing original match/read regressions remain required, plus
owning current admission, actual writer acquisition, durable receipt/archive recovery,
late callback journaling and pause/drain. This is not a delivery implementation.

The acknowledgement policy derives exact completion facts from a persisted Sent command
and durable journal before archive retirement; actual archive aliases remain supported.
It does not write an archive or infer an ACK from SDK absence. The transport policy matches
retained room and complete original queue to current logical/physical device coordinates.
A false recipientReady is not send authority and does not erase previously retained ACKs.

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

## Platform reconciliation in progress

Group bf6e176a and Root4d4df31f diverged171/85 commits since8bad394e. Eight conflict
paths were resolved, retaining both runtime-transfer guards and current company
readiness/secret-storage behavior. Group shared MatrixSyncReader now explicitly
receives company readiness framing; a new test rejects old company controls even
when an adopted group runtime keeps the same physical Matrix user/device.

The merged default-key HTTP endpoint originally still dispatched only company
credentials. A fixed group read now uses its existing native/provider request lifetime;
missing, empty and present data stay distinct. Three new actual SQL/admission cases
and one HTTP routing case cover this. The partial/focused results below are not yet
full merge acceptance. Shared API and FULL worker
contexts must run because DI changed. Do not deploy or commit the merge before its gate.

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
- Full33511 is RUNNING on8,686 fixed inputs: database/collaboration/required API and
 FULL worker, plus quality/migration/recovery checks. It is the only heavy local job;
 Group source inputs remain frozen until reaped. No merge commit/deployment yet.

Eleven private, uncompiled Group direct-transport drafts are prepared under
`.work/space-direct-transport-2026-11-09/candidate`, including API/DI/SQL/HTTP tests
and exact base hashes; the guarded apply/check scripts remain unexecuted. They are not applied to the frozen source or
accepted. Original message.get already returns archived immutable commands, verified
by its actual encrypted storage contract; do not add an unnecessary archive fallback.

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

1. Hosted42628449460 is accepted/reaped; preserve its proof and continue the pending direct-transport candidate.
2. Validate/finish the Platform source merge with shared API/FULL worker and actual
   SQL/RLS/migration/projection checks. Keep live runtime unchanged during source work.
3. Continue owning original SDK delivery/history, current recipient readiness, worker
   lifetimes and group UI routing. Existing Room Prepare/Resolve/Replay use cases and
   immutable stores are accepted; do not recreate them or replace uncertain originals.
4. Continue rich message actions/media/stickers/voice, Drive/iCloud encrypted recovery,
   MatrixRTC native call lifecycle, LiveKit meetings and all remaining PRD work. Scope
   is Platform docs/collaboration/prd.md and delivery.json; no acceptance group is done.
