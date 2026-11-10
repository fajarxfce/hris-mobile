# Agent continuation — HRIS Mobile

Updated 10 November 2026, 00:38 UTC. Historical checkpoints, failures and accepted measurements remain in
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
It does not cover later native proof or current demand. Successor42728686367 on6a8b4f61 is running;
expected6,975portable+2,644Apple. Audit only after successful jobs; do not redispatch.
Earlier42707758241 is fully accepted9,292 cases+unsigned Xcode host;do not repeat.

## Next implementation

Current idle integration is accepted and pushed as6a8b4f61/7ae66f89. Implement the
organization push receiver next (strict group packet, matching local account/family/root
and logical device, durable wake before group scheduling). Producer wakes after final inventory
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
| Mobile | feat/chat-send-timing-20261107 /6a8b4f61 | Demand/native accepted and pushed;release88 packaged |
| Platform original | feat/communications /4750d6c4 | Cross-handoff pushed;schema116.4 verifier |
| Platform group | feat/communication-space /7ae66f89 | Idle/native proof pushed;V140 |
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
Phone ADB connect52260 reaped timeout00:37UTC;availability question pending. No installation
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
