# Agent continuation — HRIS Mobile

Updated 9 November 2026, 13:49 UTC. Historical detail is retained in
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
| Mobile original | feat/chat-send-timing-20261107 /this commit | Binding preparation accepted; no product activation |
| Mobile isolated CI | feat/pinned-inspector-ci-20261109 /951cdbcf | Public consumer evidence accepted and integrated |
| Inspector original | feat/matrix-rust-bindings-20261107 /f8ca3d6 | Verified public SDK6 integrated |
| Inspector isolated | feat/sdk6-distribution-20261109 /a6b8236 | SDK6 distribution accepted; preserve unrelated gradlew.bat delta |
| Starter | feat/network-inspector-20261107 /ffd0ac5 | Generic pinned workflow/checksum port accepted |
| Platform original | feat/communications /4d4df31f | Runtime unchanged |
| Platform group | feat/communication-space /bf6e176a | Merge of4d4df31f in progress; resolved source adaptations, not yet validated or committed |

Isolated directories are under `/home/fajar/hris/.worktrees/`: `communication-space`,
`mobile-inspector-ci-2026-11-09`, `inspector-sdk6-2026-11-09` and
`inspector-pinned-consumer-2026-11-09`. The last checkout exactly matches742a2d1 bytes;
its apparent gradlew.bat change is text/eol normalization, not a byte change.

Live API/worker remains e558a6868399058a2dcdee97acfd0e7fa7f1ef07, schema116.4,
readiness control ON. Group V117–138 and organization routing are not deployed.

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
`.work/space-room-binding-preparation-2026-11-09`. Actual Apple execution remains
separate; no new APK or SDK/worker/UI activation is claimed. The existing hosted
collaboration-control workflow includes the new Apple database contract; dispatch
once on this accepted source after push, checking for an already active run first.

## Platform reconciliation in progress

Group bf6e176a and Root4d4df31f diverged171/85 commits since8bad394e. Eight conflict
paths were resolved, retaining both runtime-transfer guards and current company
readiness/secret-storage behavior. Group shared MatrixSyncReader now explicitly
receives company readiness framing; a new test rejects old company controls even
when an adopted group runtime keeps the same physical Matrix user/device.

The merged default-key HTTP endpoint originally still dispatched only company
credentials. A fixed group read now uses its existing native/provider request lifetime;
missing, empty and present data stay distinct. Three new actual SQL/admission cases
and one HTTP routing case cover this; all remain UNVALIDATED. Shared API and FULL worker
contexts must run because DI changed. Do not deploy or commit the merge before its gate.

The recovery verifier also rejected reviewed116.2–4 schemas. One new regression fails
before the explicit-version correction;27 Python migration/recovery cases now pass.
Only reviewed versions are allowed; encrypted envelope projection is unchanged. This
is verifier proof, not a live recovery. Its later minimal port to original Platform is
still required. Details/scripts live under the group checkout's
`.work/space-root-reconciliation-2026-11-09`. Focused49700 is RUNNING; this is the
only heavy local job. Group source inputs are frozen until it is reaped.

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

1. Dispatch/audit actual hosted Apple preparation coverage on this accepted source.
2. Validate/finish the Platform source merge with shared API/FULL worker and actual
   SQL/RLS/migration/projection checks. Keep live runtime unchanged during source work.
3. Continue owning original SDK delivery/history, current recipient readiness, worker
   lifetimes and group UI routing. Existing Room Prepare/Resolve/Replay use cases and
   immutable stores are accepted; do not recreate them or replace uncertain originals.
4. Continue rich message actions/media/stickers/voice, Drive/iCloud encrypted recovery,
   MatrixRTC native call lifecycle, LiveKit meetings and all remaining PRD work. Scope
   is Platform docs/collaboration/prd.md and delivery.json; no acceptance group is done.
