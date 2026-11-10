# Agent continuation — HRIS communications

Updated 10 November 2026, 11:14 UTC. Follow AGENTS.md. Detailed prior evidence is retained
in [65850051](https://github.com/fajarxfce/hris-mobile/blob/65850051/docs/agent-handoff.md).
This file owns current work; private .work notes supplement it. Do not restart old gates.

## Authorization and boundaries

Continue the full communications PRD, atomic commits with pushes and validated phone updates.
All54 acceptance groups remain open. Mobile proves reusable foundations before Starter;
HRIS policy stays in Mobile. No subagents. Usecases coordinate repositories and pure policy,
never other usecases; repositories coordinate their own datasource I/O, and ViewModels own
screen state. Preserve original messages, transactions, keys, stores and physical identities.

One heavy compiler OR emulator. Current focused history gate41593 is RUNNING; freeze compiled
source until reaped. Earlier native/core runs below are finished/reaped; emulator stopped.
Do not kill live API/worker JVMs. Native proofs use2560MiB/two-core isolated emulator plus
768MiB precompiled harness; local Mobile compiler3GiB, Group2GiB, one worker.
Phone10.77.77.3:5555 is INSTALL-ONLY: announce each install, never launch/drive/uninstall/
clear its UI or data. ADB34894 timed out15s at10:39 and is reaped; existing availability
question stays pending. Phone87 last known;88 uninstalled. No new phone build yet.
Crashlytics IAM is verified; no repeated question. Physical iPhone testing is waived,
not Apple compilation/runtime/notification/call acceptance. Apple Matrix runtime is missing.

## Accepted registered pending-worker native proof

The accompanying Mobile/Group test increment verifies four actual SDK6 scenarios in
269.304s. Native1/45886 is REAPED PASS4,zero failures/errors/skips. Source/APK/library bytes
stayed fixed; no compilation during emulator; emulator is stopped. Strict auditor/publisher
passed. [Contract](collaboration-space-pending-native.md),
[measurement](measurements/collaboration-space-pending-native-2026-11-10.json).

New case retains actual canonical metadata without a local binding, queues a new Space
intent, then lets the REGISTERED worker persist its message claim, prepare the binding,
stage and deliver beside two unchanged Company originals. Real recipient readiness control
wakes the task. Process death after the first Company receipt and before projection retains
exact message ID/creation Instant/claim/transaction; restart recovers3 Sent and exactly3
provider events. Separate offline restart reads all3. Recipient remains offline. App storage
staging evidence is distinct from direct SDK inventory; no SDK lookup while writer held.

Pending worker26native/19Matrix requests,3direct transport,1managed pusher,0additional idle
transport,0rejected/server-error responses. Original-only2 and direct-mixed3 controls retain
actual SDK-dequeue/lost-app-callback recovery; their measured recovery728/1135ms is fictional
fixture timing, not phone latency. Original group control25native/17Matrix,2transport,
1pusher and0extra idle transport. All three predecessor native case identities remain.

Mobile changes3 proof-only Kotlin files; product8c76f92c/full10010 stays unchanged. Proof2/
34855 is REAPED PASS20/107.923s with format/architecture/detekt,4732 inputs,manifest
5dc57501601ab9c083ab61d822c600b12d7b583fad0edffe5a7cf5037caffcc6.
APK150599939bytes SHA61ae98f267ce3b53cf95d4667b66ae1d78fc0427839161383264e954fbd0f3cb.
SDK6 x86 library SHAecffbab72fd5dd29d656bc4757ea423bb4bccf0444140af0480684bc32761278
and new checkpoints verified in APK. Proof1 stopped at obsolete private preflight before
compilation; corrected exact3-file allowance, no product workaround.

Group changes1 Kotlin test +3 Python driver/test files. Source1/72318 REAPED PASS3/83.808s,
8735 inputs,manifestd37166610c7b8300220d33d98514f950d01c9e38f51e813f1811711d54a4819d.
All28 Python cases pass (18 original +4 prior recovery +6 new). A merged draft missed two
imports, producing10 subtest errors; corrected before compilation and prior identities retained.
No backend product/schema/DI/live change. Private .work/space-pending-native-2026-11-10 in
Mobile/Group has exact sources, results, runners and strict auditors. Never rerun without cause.

## Accepted pending-worker source

Mobile8c76f92c5c92cdf88d6827f3678e005c581fa8ab is committed/pushed17014 (reaped).22 Kotlin
inputs add schema2 message phase under the existing authenticated schema1-compatible recovery
partition, exact Instant/ID order and one durable claim. Old pending bindings resume first;
failed attempts advance before backoff, at most4actual attempts/turn. Unready recipients are
Unavailable, preserving the original command. Concurrent cancellation skips delivery.
Dedicated Finish checks full pending inventory; generic updater rejects unchecked Finish.
[Contract](collaboration-space-pending-recovery.md),
[measurement](measurements/collaboration-space-pending-recovery-2026-11-10.json).

Focused1/40918 failed test compilation after67 domain cases (cross-module nullable smart
cast),156.483s,reaped. Corrected focused2 PASS251/435.576s; runner2452365 exited, wrapper
session lost across compaction, strict audit passed. Full1/19057 REAPED PASS581.55s:
10010 verified=7991fresh+2019exact accepted reused,zero failures/errors/skips. Root,Android
host/release compile/lint,Wasm/iOS source,actualChromium,proofAPK/unit/quality pass. All4732
source/library inputs fixed,manifestfad9f11cebc53e7e60444da4341dc9b18fca16d688872ecf2aceef1d0f21d920.
The current native proof APK is newer than this core build; do not claim old artifact hashes.

## Hosted platform checks

Current worker control42801929551 RUNNING on exact8c76f92c,refvalidation/space-pending-recovery-
20261110;ref61718/dispatch14028 reaped. Expected8008portable+3062Apple=11070,16Swift/unsignedhost.
Private .work/space-pending-recovery-2026-11-10/hosted.json and auditor are complete.

Mixed-demand42796816133 COMPLETE10958=7930portable+3028Apple,16Swift/unsignedhost on exact
1d18e232. Portable90228 and Apple91937 are REAPED/audited; GitHub digest/ZIP CRC/exact source/
previous case identities pass. Appleartifact13757163037,
SHA0fce8ccaa4bbb6b4dabb1c5397b4220d1087cf367827812ba8cd0890cf790d6b,
manifestca8a83ea5718c8e008197b492b9eea0e45f7fc834e913aec3f8864c815eed2c2.
Complete measurement accompanies this checkpoint. Do not redispatch accepted runs.

Pending-inventory42792315555 COMPLETE10862=7866portable+2996Apple,16Swift/host. Audits31108/
87521 reaped. Appleartifact13756512123,
SHA0aaa2c1e87f6aaa1e891e8f6ff5199f9bdee5ab1ec8d95431adcaec1077dd31e,
manifest8d3b847f0d6992894ff983bbf0bbddedf7b7d5f50f27f8b550469cd6052ebba4. Complete evidence
published65850051. Mixed-core42785697360 COMPLETE10808,staging42781554618 COMPLETE10676,
new-room42777934754 COMPLETE10574,capability42775719615 COMPLETE10535,projection42772661276
COMPLETE10513,matching42769825037 COMPLETE10402. Earlier accepted controls remain in prior Git
handoff/measurements. Shared Apple tests/unsigned hosts do not implement its native Matrix runtime.

## Next work — continue implementation

[Incoming/history plan](collaboration-space-incoming-plan.md) now has an UNACCEPTED14-file
storage/local-observation candidate in the Mobile worktree. Focused1/41593 RUNNING, expected
112cases:9new admission+8old Space message on Desktop/Chromium;16new Space history+22old
Company storage on both real databases;2existing DI cases. iOS source/format/architecture/
detekt included. Private .work/space-history-storage-2026-11-10 has exact file list, runner,
expected cases and strict auditor. Source14 includes11new files; no product incoming route.
Do not call it accepted before gates. Next reap/fix focused, strict audit, required full check;
then atomic source/evidence commit+push. No native SDK change in this candidate.

The explicit Space history namespace reuses original message/alias/tombstone codec and atomic
batch primitives; typed Company/Space coordinates preserve old authenticated byte frames.
Fixed HMAC/AAD vectors, >500-message traversal/restart, replay/no-op revisions, competing writers,
key loss, ciphertext tampering and cross-owner isolation are covered. Locally admitted history
observation is independent of credential age; confirmed denial stays gated through failures.
Usecases coordinate repositories, not usecases. Company history stays originally scoped and
must only join Space through an admitted immutable legacy binding. SpaceOriginalHistoryRepository
is still a bounded original SDK/provider snapshot, not the future owned live incoming stream.

Then own bounded native incoming collection, deduplication/edits/redaction, local persistence,
older-cursor exhaustion, notifications/replies and product routing. Preserve instant cached
rendering/optimistic sends, stable ordering and no no-op list blinking. No user-facing network
status, refresh buttons or technical recovery copy. Fresh SPACE-origin native rooms remain
separate from this adopted-legacy-room proof. Media/stickers/voice, encrypted Drive/iCloud
backup, MatrixRTC calls, LiveKit meetings and Apple native runtime remain in the larger PRD.

Current core/native work has no generic Starter delta. Generic sourcea7ff417c is accepted358
fresh/87suites,three Android builds,production web,iOS source,SBOM/quality,following Mobile
Apple proof. Earlier generic replica port30c4d72 is included; do not reapply domain features.

## Preserved workspace, server and device state

Mobile branchfeat/chat-send-timing-20261107; Group.worktrees/communication-space branchfeat/
communication-space; Originalhris-platform branchfeat/communications; Startercompose-fluent-
starter branchfeat/network-inspector-20261107. All accompanying commits must be pushed.
Inspector f8ca3d6,isolated Inspectora6b8236,isolated MobileCI951cdbcf unchanged. Preserve
Inspector gradlew.bat normalization delta and dirty SDK refresh worktree8de215a.
Immutable SDK26.10.23-hris.6 AAR SHAa4aa1e2cb3136c0531bb35f7f06df10582c84c1974ce4e5fda7099d0ab533bb3.
Never restore retired confirmation endpoint/private-key changes/SDK7 candidates, or look up
runtime registry while holding a native callback monitor (reverse lock order).

Livee558a6868399058a2dcdee97acfd0e7fa7f1ef07/schema116.4/readinessON/groupOFF unchanged.
GroupV117–140 not deployed; original verifier maximum116.4 must not be blindly raised.
API172.18.0.7,Matrix172.25.0.4,WireGuard10.77.77.1:8088 forwards API8080. Preserve live JVMs.
Firebasecmp-fajar and server credential remain local/private; never print or commit them.

Phone87 source d8383438/evidenceff1adeca installed,123481487bytes,
SHA43373c6c4e6335149138630e7c2c9d6daa05edc1f76729ff01dae1023916ce8b. Do not reinstall87.
Release88 uninstalled: private .work/release-phone-2026-11-10-build88,source6a8b4f61,
123596175bytes SHA142082381ed2eff87d50dbbeb01538229ac7c1491ccf70d46ef0ce73519b5c51.
Future latest build89 not created. Same signer; install-only after validation, never downgrade.
Retained sender .work/joined-room-entry-native-2026-11-09/sender is stopped; Bima74 store retained.
Never resend Control77 235408,Warm77 235728,Recovery78 005634,Registry82 055959,Parent83 071123,
History84 083059,Selected86 102110,Joined87 115837. Draft66 092041 MUST NEVER BE SENT.
