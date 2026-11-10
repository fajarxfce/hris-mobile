# Agent continuation — HRIS Mobile

Updated 10 November 2026, 04:34 UTC. Historical checkpoints, failures and accepted measurements remain in
[78145d78](https://github.com/fajarxfce/hris-mobile/blob/78145d78/docs/agent-handoff.md).
Use this file for active jobs; do not restart historical runs.

## Authorization and boundaries

The user authorized the entire communications PRD, long-running implementation,
atomic commits with pushes, and validated phone updates. All 54 acceptance groups
remain open. Mobile proves reusable foundations before Starter. Preserve accounts,
original native/SDK identities, keys, stores, transactions and retained messages.
Use cases own policy/repository orchestration; repositories coordinate datasource I/O;
ViewModels own screen state. Follow AGENTS.md. No sub-agent delegation is authorized.

One heavy local compiler OR emulator. Never kill live API/worker JVMs. The owned
emulator uses2,560MiB/two cores and is stopped. Phone10.77.77.3:5555 is install-only:
announce each installation; never launch, drive, uninstall or clear its app.
Crashlytics IAM is verified; do not ask again. Physical iPhone interaction is waived,
not Apple source/runtime, push or calling acceptance. Continue work after checkpoints.

## Active: command storage accepted locally; SPACE message producer next

The command foundation follows pushed7b032633/a86ae506. `ReplicaWakeRequest` is renamed
`WorkWakeRequest` for both raw mutation types. New explicit
`CommandStore.insertBatchAndWake` retains new commands and fresh work in the owning
SQLite/IndexedDB transaction. Old insertion APIs and result enums stay unchanged.
Null means wake capacity/current-generation conflict; all-retained active/archive
replay returns AlreadyStored without changing any wake. No new domain producer yet.
Thirteen shared cases per platform cover batch/archive replay, generation/capacity,
ownership/identity/resource conflicts, actual late unique-constraint rollback,
consumer/capacity races, lost response, cancellation, bounds, compatibility and reopen.

Private `.work/command-commit-wake-2026-11-10/runner.py focused-2` passed126.140s;
session99243 was reaped and accept-focused.py verified202 fresh cases (102 SQLite,
100 real Chromium), zero failures/errors/skips, Apple source and quality checks.
All4,660 source inputs and the library match; source manifest SHA
ed60ebcb63b731cfbe898ae0f8e08f22edbc735352e0b7af3895ad3232cc74a1.
Full-1/session44596 REAPED PASS939.564s on the same frozen source, with non-incremental
Kotlin compilation to avoid the previously proven stale delegate issue. Strict
accept-full.py verifies9,320 results:3,864 fresh+5,456 exact accepted reused, no
failures/errors/skips. All90 AppFlow cases pass unchanged. Required root, Android
host/release/lint,Wasm/iOS source,browsers,proof APK/unit tests and quality pass.
New proof APK150470250bytes SHAa71da4218d67520f9184f84b6e98c2bbbe919697801ff73bb9905516c3338e6f;
compiled only, no new native execution or phone installation claimed. No local heavy
job remains. [Contract](command-work-wakes.md),
[evidence](measurements/command-work-wakes-2026-11-10.json).
Initial focused-1/session20937 was reaped with zero tests:
its private init script incorrectly used Gradle.project in an included build. Only
that runner configuration was corrected; retain the failed logs. No source/test
workaround. Pinned Apple execution and downstream port remain. Do not port this
version before its Mobile platform proof. hosted-audit.py expects7,318 portable+
2,730 Apple=10,048 tests, plus16 Swift checks and unsigned host; no new command job
dispatched while producer42753380451 remains pending.

Next producer preparation is in private `.work/space-message-intent-design-2026-11-10`.
Three uncompiled drafts cover explicit account/space intent/record, pure ownership/
state policy and a local repository contract. They are outside production source;
review before applying. No next-capability datasource,usecase,DI or tests exist yet.
New nonqueued SPACE states require exact physical origin; never use the legacy
nullable-origin exception or encode spaceId in companyId. Add encrypted local
retention/observation, then complete mixed-ledger reconciliation and explicit new
enqueue capability before product routing. Original-only leases remain unchanged.

Generic42749839268 is running; producer42753380451 is pending. Do not supersede it.
Receipt42745403043 is fully accepted:9,798 tests plus16 Swift checks/unsigned Apple
host, recorded in7b032633. Continue the PRD after this foundation and keep handoffs
in Git. Phone remains87; pending availability question need not be repeated.

Mobile d89e6b5d is committed/pushed (79200 reaped) and contains atomic recovery work for newly retained immutable room bindings.
The repository writes both encrypted indexes and fresh work in the same transaction.
The owning preparation use case schedules after exact readback; lost write responses
are reconciled without another wake write. Scheduling failure/timeouts preserve local
data and its marker; cancellation and late ownership/denial retain their prior behavior.
Existing owned bindings and identical retention create no work or scheduler requests.
[Contract](collaboration-space-binding-producer-wake.md),
[evidence](measurements/collaboration-space-binding-producer-wake-2026-11-10.json).

Red43490 proves the original missing wake (one exact null assertion,66.155s). Focused
62815 passes87 fresh cases/372.923s (25 domain,30 SQLite,30 browser,2 app DI). Full77826
passes682.233s:9,294 validated=7,301 fresh+1,993 exact accepted reused; zero failures,
errors/skips. All4,656 source/library inputs and current/archive XML match. Root,
Android host/release compilation/lint,Wasm/iOS source,browsers,proof APK/tests and
quality pass. Only one native fixture file was added after focused; production remained
identical. New proof APK150470250bytes SHAde897799fc7e0eb4c8751915aa4a02a7a1a7fd14b83447426dd8a0556322a686.

Native46744 REAPED PASS193.275s,two cases,zero failures/errors/skips. Actual new binding
verifies a fresh wake and one schedule; retained reads verify unchanged wake/schedule.
Both SDK callbacks/process-death/original transaction scenarios remain. Original has
2 provider events,10 native/11 Matrix requests; group has2 events,25 native/17 Matrix,
one pusher,zero rejects/server errors and zero extra idle direct requests. Sources/APK
match; no compilation during the 2,560MiB/two-core emulator,now stopped. No local heavy
job remains. Exact unchanged Group source-2 compilation was reused,not rerun. No backend
source/schema/DI change. Private `.work/space-binding-producer-wake-2026-11-10` in Mobile
and Group retains runners,red/focused/full/native acceptance and source manifests.

Generic foundation7a6fe534 is committed/pushed (50848 reaped). Its independent
control42749839268 pins7a6fe534fc2e86064be9f60509f7befcc6afbf31,now running after
receipt42745403043 completed. Do not redispatch or cancel this proof. Expected generic
7,276 portable+2,709 Apple=9,985 cases,16 Swift checks,unsigned host. Auditors/config are
in `.work/replica-commit-wake-2026-11-10`. This generic CI excludes the producer above.
Producer control42753380451 pinsd89e6b5dcb0c0463916628ecd10a9bae946e5a1a (dispatch14839
reaped), now pending behind generic. Expected7,292 portable+2,717 Apple=10,009,plus
Swift/host; private producer hosted.json/audit.py compare exact generic predecessor
archives. Do not supersede this pending run. Current branch has no open PR.

Generic local evidence:22 focused cases; full core89 desktop/87 browser. Final60400
passes303.454s with7,468 validated results,including4,012 same-source full/repair and
3,456 exact predecessor results. Earlier AppFlow failure was a proven stale generated
Kotlin delegate; non-incremental repair2593 passes714 app cases with unchanged90AppFlow
identities. No UI/timeout/assertion workaround. Existing failed class/XML are retained.
[Generic contract](replica-work-wakes.md),[evidence](measurements/replica-work-wakes-2026-11-10.json).

Starter remains clean HEADffd0ac5. AGENTS/README/architecture/handoff/downstream guide
were read. Nine-file core-only patch from76a143b6..7a6fe534 passes `git apply --check`;
exact input/output hashes are in starter-port-preparation.json beside starter-core.patch.
No Starter source/build changed. Apply/verify/commit/push after actual generic Apple
acceptance; no HRIS policy belongs in the template.

Next new SPACE messages require explicit account/space ownership; never put a spaceId
into legacy companyId or rewrite existing command envelopes. CommandStore owns durable
commands separately from disposable replica caches and still needs atomic command+wake
insertion for a new producer. An adopted physical SDK queue may contain old company and
new organization commands together. Reconcile the complete mixed ledger before resume;
never ignore unmatched IDs or treat a partial queue as complete. Original-only leases
must not silently gain new enqueue capability. Continue local observation,receipts,
incoming/older history,notifications/replies,rich media,backup,calls and meetings.
All54 PRD groups remain open; no product release,phone or live group activation here.

## Accepted: original callback-loss recovery; continue producer wakes

This commit contains the owning receipt recovery and deterministic native proof.
[Root cause and contract](collaboration-original-lost-callback.md),
[acceptance](measurements/collaboration-space-original-receipt-recovery-2026-11-10.json).
The preserved earlier failure has2 app commands,1 receipt and0 SDK originals: SDK
dequeue precedes the async app callback. Exact local facts or one finite original
window repair only disappeared pending originals. Same runtime/physical keys/device,
sender/logical ID/content hash/text; no new enqueue, transaction, key or inferred ACK.
Ordinary queued sends never acquire history. Complete group/older history remains open.

Red46986 fails2 assertions as intended. Focused89708 passes70 fresh cases/318.638s.
Full50072 passes6,338 validated=4,521 fresh+1,817 exact reused/578.408s;4,651 stable
inputs and all current/archive/library checks agree. Required root/Android release,
host/lint/shared Wasm+iOS/browser/proof/quality checks pass. Group source22157 passes
25.556s with8,734 inputs;15 driver cases pass0.006s. No production backend/DI/schema change.
Native93669 is REAPED PASS208.523s,two cases,zero failures/errors/skips. Original:
exactly1 app receipt after both SDK rows disappear,hard death,then2 receipts/Sent from
exactly2 provider events; recovery544ms in fixture only. Later offline reads succeed.
Group:25 native/17 Matrix requests,one pusher,no rejects/server errors,idle adds0direct.
Source/APK unchanged,no compilation during emulator;emulator stopped.
Proof APK150469403bytes SHAa1a89e3c1e228fdb82349599c48471767c4c6c2bdfe43071d1cf4e97f8b1e351.

First native-1 FAIL265.004s: original case passed,but shared driver waited first-ACK
before group listening/control handshake. Scenario-owned interruption now preserves
both exact assertions; same Mobile APK. Failed data retained. Its launcher3535717
exited; exec session ID was lost in compaction. Do not rerun or erase either old
pusher/native-1 or receipt/native-1 stores. Source/native acceptance is complete;
private .work/space-original-receipt-recovery-2026-11-10 retains frozen inputs/audits.

Mobile76a143b6 and Group401c6d56 are committed/pushed (85826/7722 reaped). Hosted
42745403043 pins76a143b6c8fc81c6920658797f127dcf4a92e7b5 and is running after
pusher42739907129 completed; dispatch69781 reaped. Portable85032 accepted7,100 cases,
artifact13732619273 SHA bb615bd38b9c6ce45d51aaf6f3e8b036671278e9f2279c07efd519026f603976.
Apple18663 accepted2,698 cases,artifact13733719359 SHA1497d79c277a5dc0ae781fd15332b8d9e90a9b0b3837d9f5f726bad60b5c0af3.
Total9,798 plus16Swift/unsignedhost; both archives/previous case identities verified.
[Hosted evidence](measurements/collaboration-space-original-receipt-hosted-2026-11-10.json). Do not rerun. Private hosted.json/audit.py retain
the pin and predecessor archive comparison. Next: atomic replica mutation+producer wake.
Do not claim predecessor CI covers this source. The reusable database contract must
prove Mobile SQLite/browser/Apple before Starter. Read-only design and133 raw override
inventory are in .work/replica-commit-wake-2026-11-10; generic code is accepted locally above. Same-current wake
generation must be rejected even when pending. No consumer reread can close the writer
race. Then bind new immutable room writes to a durable wake and schedule after commit;
identical retained bindings remain no-op. Continue new SPACE commands/incoming routing,
notifications/replies,media,backup,calls,meetings; all54 PRD groups remain open.

## Managed pusher / device / live status

Pusher source95195319,Group9c3014e3 are accepted/pushed. Native55319 passed206.26s,
with one actual managed pusher and process recovery; the earlier unexplained original
failure is now traced and repaired above. [Contract](collaboration-space-pusher.md).
Hosted42739907129 pins951953193c73d555d57218d1bf20a202067ec03c: portable7,063
accepted (audit3789,artifact13728979615); Apple2,681 accepted(audit30631,artifact
13731975537),plus16 Swift checks and unsigned iOS host. Total9,744,zero failures,
errors or skips. Digests/ZIP CRC/previous testcase identities verified. [Hosted evidence](measurements/collaboration-space-pusher-hosted-2026-11-10.json). Do not rerun. Auditor/config are in
.work/space-pusher-2026-11-10. Its success excludes the current receipt recovery source.

Phone remains87. ADB6953 timed out15s around03:37; no install/UI/data change.
Accepted release88 (6a8b4f61,before receiver/pusher/recovery) remains uninstalled;
one availability question remains pending,do not repeat. Use its accepted install.py
--install-only after announcing when reachable. Live remains e558a686/schema116.4,
groupOFF; GroupV140 not deployed. SDK6 immutable; no Starter port for HRIS-specific repair.

## Accepted: organization push receiver

Strict schema1 group signal -> local account/native family/root -> exact registered
logical device (`Registered.receipt.id`,not command/original SDK ID) -> durable group
wake -> bounded fixed-key scheduling. Shared Android/Apple router has explicit group
lane; Swift adapter includes group fields. No SDK,API or display in receiver; no
company/Inbox fallback. Expired transport credentials do not erase local ownership.
Cancellation/late owner changes prevent success, retaining any committed wake.

Focused30082 reaped PASS100.7s:45 fresh cases; full5687 reaped PASS954.348s:
6,301 validated,6,301 fresh+0 exact reused,zero failures/errors/skips.
All4,641 inputs stable; current/archive XML and Inspector checks accepted.
Initial99688:45 tests passed but detekt rejected106-line worker DI; moved complete
push composition into owning ApplicationPushReceiverModule, no suppression. Only
workflow artifact location changed after focused2; full validates final source.
Swift unavailable locally;16-case checker and app push11 cases added to pinned CI.
Hosted42733595170 pins cd4fb78b and is fully accepted:9,690 cases,16 Swift envelope
checks and unsigned iOS host. Portable audit6770 and Apple14390 are reaped. Artifact
hashes/ZIP CRCs and previous testcase identities verified. [Hosted evidence](measurements/collaboration-space-push-receiver-hosted-2026-11-10.json).

[Contract](collaboration-space-push-receiver.md), [measurement](measurements/collaboration-space-push-receiver-2026-11-10.json).
Private `.work/space-push-receiver-2026-11-10` retains runners,failed/accepted gates and
source/report audits. Source cd4fb78b is committed/pushed;51430 reaped. The managed
group pusher is validated in the current checkpoint above. No product activation until incoming group
routing/dedup acceptance. Producer wakes,new SPACE enqueue,rich media/calls/meetings
and all54 PRD groups remain open. No compiler/emulator active after full reap.

## Accepted: skip idle original-room transport

This increment adds a bounded read-only domain demand check before per-room delivery.
SDK inventory,active commands,ACK journal and immutable completions must agree before
the worker skips transport. ACK-before-Sent,missing proof,unsettled SDK originals and
changed inventories remain work. Current local family/directory denial/runtime guards
remain. No enqueue,resume,settlement,message projection or retirement occurs in this read.
Global adopted sync is unchanged;producer wakes after final observations remain next.

Focused89418 REAPED PASS170.44s:123 cases. Full48682 REAPED PASS483.891s:4,437 fresh
plus20 exact reused proof cases,4,457 validated,zero failures/errors/skips. Required
root/Android host+release/lint/shared Wasm+iOS/browser/proof/quality pass. Both4,633-input
manifests and all current source/library/XML checks agree. Source gate34803 passes
30.759s,8,733 stable backend inputs,format/architecture. Native77040 REAPED PASS
203.279s:2 actual cases,zero failures/errors/skips. Group has2 provider
events,24 native/16 Matrix requests,
zero401/403/server errors;exactly2 required direct transport reads,and no additional
direct transport for the explicit fresh idle sweep. Original scenario retains2 events/
10 native requests. Manual scheduler,actual registered task;later offline Sent reads
follow online recovery. No OS timing or phone/p95 claim.

First focused99095 failed only the new8-page fixture assertion (worker limit4/turn).
The corrected test verifies retained wake after turn one and completion after turn two;
production was unchanged. Native gate passed first attempt for this increment. Failed
prior native userdata remains retained. No compiler/emulator remains active.
Private `.work/space-recovery-demand-2026-11-10` holds exact audits and runners.
Proof APK150450270 bytes,SHA2d3e323b055123cbbfc3e17bbf1b375d59291be31994089fa19270434129115a.
No product88,phone or live rollout. All54 PRD acceptance groups remain open.

[Contract](collaboration-space-recovery-demand.md) and[measurement](measurements/collaboration-space-recovery-demand-2026-11-10.json).

## Accepted dependencies

- [Native proof](collaboration-space-recovery-native.md): Mobile78145d78 and
  backend450fd51d accepted/pushed;push15951/83711 reaped. Actual Matrix readiness ->
  encrypted receipt/durable wake -> actual registered group task -> original ACK/claim
  -> process death before Sent -> recovery without new hint/FCM. Two native cases pass
  196.262s,each exactly2 provider events. Group22 native/16 Matrix requests,zero401/403
  or server errors. Captured-signal replay explicitly tests consumer dedup. Manual
  scheduler and later offline Sent reads are separate from real OS/offline-ACK timing.
  Source/APK/library hashes match; emulator stopped. Do not rerun unchanged proof.
- [Worker](collaboration-space-recovery-worker.md): efa700bb,97 focused cases and
  4,439 validated full cases (2,489 fresh +1,950 exact reused). One processor/one
  waiter,four raw pages/bounded turn;durable claim before SDK,finite backoff,exact wake
  ACK. Domain binding + app lifetime subscriptions and independent registered SyncTask.
- [Progress](collaboration-space-recovery-progress.md):8bc952fe,encrypted native-family/
  account/space cursor and one pending claim. New wakes cannot erase an old sweep.
- [Sync](collaboration-space-device-sync.md):b51e70d8,same original SDK/parent/pump/cursor.
- [Inventory](collaboration-space-room-inventory.md):eba05a9c,complete HMAC indexes,
  explicit raw continuation including empty projected pages,concurrent append detection.
- [Wake](collaboration-space-readiness-wake.md):01544c26,receipt -> durable wake ->
  OS schedule -> exact publication ACK;bounded native hints alone are not recovery.

Hosted [42721024887](https://github.com/fajarxfce/hris-mobile/actions/runs/42721024887)
pins efa700bbb5a2b7a75af392afb5add8b642eb2ba0. Portable accepted6,940 cases=3,242
Desktop+629 Android host+3,069 browser,zero failures/errors/skips. Audit71793 reaped;
GitHub digest/ZIP CRC/full prior testcase identity and new cases verified. Artifact
13720089441,3,385,094 bytes,SHA2ea99a2369cd5ff6a6af84dc10a68d103900f464750d83b3b75385614f34ee7b.
Private `.work/space-recovery-worker-2026-11-09/hosted-42721024887/portable-audit.json`.
Apple accepted2,628 cases,zero failures/errors/skips; unsigned iOS application host built.
Audit38111 reaped;artifact13722169419/SHA dc61fea6c530fc8e1f540e13adede75baaee2f66ce088da752b97e94f08962e3.
Total9,568 accepted; [hosted evidence](measurements/collaboration-space-recovery-worker-hosted-2026-11-10.json).
It does not cover later native proof or current demand. Successor42728686367 on6a8b4f61: portable accepted6,975 cases; audit94614 reaped,
artifact13724609514/3,391,825bytes/SHA8aaa646239cc22cf09ff6bfd606ec1c8df27a19c8e3cda338b9a529316cbafd9.
[Portable evidence](measurements/collaboration-space-recovery-demand-portable-2026-11-10.json).
Apple audit54325 is reaped and accepted:2,644 cases plus unsigned iOS host. Total9,619 cases are accepted; [hosted evidence](measurements/collaboration-space-recovery-demand-hosted-2026-11-10.json). Do not repeat.
Earlier42707758241 is fully accepted9,292 cases+unsigned Xcode host;do not repeat.

## Next implementation

The managed group pusher is implemented and validated above; investigate the unexplained first native original-recovery failure next. Producer wakes after final inventory
reads remain necessary; the legacy QueueText/Edit/Forward scheduler alone does not
wake organization recovery. Group FCM/product routing and new SPACE enqueue remain
before rollout. Company-only runtime access deliberately cannot reach adopted group
entries. Keep logical group and original physical sender identities distinct. Never
look up the runtime registry while holding the native callback monitor (reverse lock
order during retirement). Then rich media/stickers/voice,encrypted Drive/iCloud recovery,
MatrixRTC calling,LiveKit meetings and the remaining PRD/delivery acceptance.

Original key publication is already owned by provisioning in Group61e8c2f5; it verifies
and commits existing signed original identity atomically with LEGACY transfer. No
extra Mobile confirmation endpoint,private-key change or SDK7. The discarded vendor
key-confirmation candidates are retired;never restore them.

## Checkouts

| Checkout | Branch / source | State |
| --- | --- | --- |
| Mobile | feat/chat-send-timing-20261107 /this commit | Guarded group pusher and native diagnostics; release88 precedes them |
| Platform original | feat/communications /9ff6bd90 | Cross-handoff pushed;schema116.4 verifier |
| Platform group | feat/communication-space /2c2e7db9 + current test increment | Idle/native proof pushed;V140 |
| Starter | feat/network-inspector-20261107 /ffd0ac5 | Generic pinned workflow/checksum port accepted |
| Inspector | feat/matrix-rust-bindings-20261107 /f8ca3d6 | Public SDK6 integrated |
| Inspector isolated | feat/sdk6-distribution-20261109 /a6b8236 | Accepted;preserve gradlew.bat normalization delta |
| Mobile isolated CI | feat/pinned-inspector-ci-20261109 /951cdbcf | Consumer evidence integrated |

Worktrees live under `/home/fajar/hris/.worktrees/`. Preserve the dirty isolated SDK
refresh worktree. SDK 26.10.23-hris.6 is immutable; never overwrite release assets.
AAR SHA256: a4aa1e2cb3136c0531bb35f7f06df10582c84c1974ce4e5fda7099d0ab533bb3.

Live API/worker remains e558a6868399058a2dcdee97acfd0e7fa7f1ef07, schema116.4,
readiness control ON. Group V117–140 and organization routing are not deployed.
Never copy the group schema bound into the original platform recovery verifier until
its complete source/migration integration is actually performed and validated.

## Accepted phone and native proof

APK87 is ALREADY INSTALLED. Product source d8383438; evidence ff1adeca.
[Release evidence](measurements/collaboration-release87-accepted-2026-11-09.json).
Nondebug devRelease ARM64, same signer, version87,123481487bytes, SHA256
43373c6c4e6335149138630e7c2c9d6daa05edc1f76729ff01dae1023916ce8b.
Install33957 is REAPED PASS210.86s, exact installed digest/first-install identity;
no UI launch, uninstall or clearing. Do not reinstall87.

Release88 is packaged and accepted for a progress update; phone is STILL87.
[Artifact/source evidence](measurements/collaboration-release88-accepted-2026-11-10.json).
Source6a8b4f61,build51256 reaped PASS573.355s,ARM64 nondebug devRelease,123596175bytes,
SHA142082381ed2eff87d50dbbeb01538229ac7c1491ccf70d46ef0ce73519b5c51.
Signer/16KiB/backup/Firebase/privateAPI/lint/SDK6 bytes verified;audits14571/27599 reaped.
No native product88 startup or phone runtime claim: dedicated recovery proof is separate.
Private `.work/release-phone-2026-11-10-build88` preserves all inputs and accepted APK.
Phone ADB connects52260/4060/82811/84775 reaped timeout00:37/01:08/01:24/01:48UTC; availability question pending. No installation
attempt yet. Announce then use `python3 .../install.py --install-only` when reachable.
Application source and HEAD freeze for packaging has ended; next implementation may proceed.

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
