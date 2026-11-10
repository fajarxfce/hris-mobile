# Agent continuation — HRIS communications

Updated 10 November 2026, 13:03 UTC. Follow AGENTS.md. Detailed prior evidence is retained
in [65850051](https://github.com/fajarxfce/hris-mobile/blob/65850051/docs/agent-handoff.md).
This file owns current work; private .work notes supplement it. Do not restart old gates.

## Authorization and boundaries

Continue the full communications PRD, atomic commits with pushes and validated phone updates.
All54 acceptance groups remain open. Mobile proves reusable foundations before Starter;
HRIS policy stays in Mobile. No subagents. Usecases coordinate repositories and pure policy,
never other usecases; repositories coordinate their own datasource I/O, and ViewModels own
screen state. Preserve original messages, transactions, keys, stores and physical identities.

One heavy compiler OR emulator. Native event-read focused1/7075 is RUNNING, source frozen.
Private runtime-event-reads-2026-11-10 owns runner,14-file list and acceptance scripts.
Expected77focused/10203full cases,4762source inputs; no new acceptance yet.
The emulator is stopped. Preserve the one-compiler OR one-emulator boundary.
Do not kill live API/worker JVMs. Native proofs use2560MiB/two-core isolated emulator plus
768MiB precompiled harness; local Mobile compiler3GiB, Group2GiB, one worker.
Phone10.77.77.3:5555 is INSTALL-ONLY: announce each install, never launch/drive/uninstall/
clear its UI or data. ADB82255 timed out15s near12:14 and is reaped; existing availability
question stays pending. Phone87 last known;88 uninstalled. No new phone build yet.
Crashlytics IAM is verified; no repeated question. Physical iPhone testing is waived,
not Apple compilation/runtime/notification/call acceptance. Apple Matrix runtime is missing.

## Accepted acquired-event classification

Three new Kotlin files add typed acquired facts, a common raw-event mapper and21common
cases. Original/replacement hashes reuse unchanged existing mappers. SDK decryption is
independent of room state; redaction actors only trigger target reads, while matching target
metadata preserves original sender/event identity. Invalid identity/metadata/content and
ambiguous targets cannot publish a partial original. Unresolved/unsupported are never text
or exhaustion. [Contract](collaboration-raw-event-classification.md),
[measurement](measurements/collaboration-raw-event-classification-2026-11-10.json).

Focused1/49881 REAPED PASS105/302.286s=(21new+7old original+7old edit)*Desktop/Androidhost/
actualChromium,plus iOS source/quality. Strict auditor preserved all six old mapper report/case
identities. Full1/34957 REAPED PASS695.005s,10172verified=8153fresh+2019exact accepted reused,
zero failures/errors/skips. Root check,Android host/release compile/lint,Wasm/iOS app source,
actualChromium,proofAPK/unit/quality pass. Strict full auditor/publisher passed.4755source/library
inputs fixed,manifestf56484e626ceb0d012e9cca86f628150b7863dca87a3dc20ab6ed4ea7e69ff3c.
Current proofAPK150605511B SHA24e8cef1c2319a00c2378495f9552f8a4c0f127cecdb86ff24edd64745ca685e.
Private raw-event-classification-2026-11-10 contains source/results/auditors. No native binding,
retention usecase, incoming activation, phone/live/SDK change or native redaction equivalence
claim. Source5c276a2fcc472260245ea56ba6db7b50c537bd59 is committed/pushed39983(REAPED).
Hosted42819170095 RUNNING exact5c276a2f,refvalidation/raw-event-classification-20261110,
8170portable+3125Apple=11295+16Swift/host. Ref67054/dispatch33180 REAPED; configuration
recorded. Baseline raw source42815332867 is now fully audited. Do not claim pending checks.

## Accepted raw event I/O

Mobile99c7be198ecdab8dd9f6d8185de160dee184bdff is committed/pushed93173(REAPED).
Four new Kotlin files add an internal raw-event DTO/contract/Android datasource plus15host
cases. It reads one exact event through the supplied SDK6 client and closes its own room
handle on success/failure/cancellation. Detached JSON retains unknown fields and actual SDK
NOT_DECRYPTED/DECRYPTED/UNABLE_TO_DECRYPT independently of membership/encryption facts.
Bound96KBUTF8, cleared mutable SDK result, redacted diagnostics, exact exception preservation
and independent overlapping reads. No new client/join/sync/retry/send, runtime binding,
native execution, incoming activation or phone claim. [Contract](collaboration-raw-event-source.md),
[measurement](measurements/collaboration-raw-event-source-2026-11-10.json).

Focused1/2142 REAPED PASS26/191.306s:15new and11unchanged history-source cases, Desktop/Wasm/
iOS data compilation,format/architecture/detekt. Strict auditor checks all4new source files and
previous case identities. Full1/92524 REAPED PASS597.577s,10109verified=3562fresh+6547exact
accepted reused,zero failures/errors/skips. Root check,Android host/release compile/lint,Wasm/
iOS app source,actualChromium,proofAPK/unit/quality pass. Strict full auditor/publisher passed.
4752source/library inputs fixed,manifest19fc3bd37896f5618a11df1a542e1fe78b75f8acff6a21d364d8a3d2bb22f036.
Historical raw-I/O proofAPK150602468B SHA5d0d280316aa07cb97644e28746bc6b8cec9a7274d39a28bb3d78c82d4e04cac.
Private raw-event-source-2026-11-10 contains exact files/runners/auditors/results. Hosted
42815332867 COMPLETE exact99c7be19,11211=8107portable+3104Apple,16Swift/unsignedhost.
Source93173/ref87991/dispatch78335 REAPED. Portable85411 and Apple96085 REAPED, strict source/
GitHub digest/ZIP CRC/previous case identities pass. Appleartifact13765474866,570249B,
SHA8092f10d0c1fc370b2961455ec43a87fc14f93efa2c750dcdf6f04cc5d5dfb0e,
manifest1ff25793ac0f7294494a9fc3da35c2d1b92eac9909b1c2c7a0df0907fafa4421.
Complete measurement published alongside this checkpoint. No native Apple Matrix claim.
Never rerun accepted gates.

## Accepted Space message edits

Accepted Mobile5da6cb95678f8e1493e245d42ac2a76f6f4d00ee is committed/pushed96465(REAPED).
This7-file increment adds typed Space replacement storage to the same owned
history repository instance. Original aliases/order and send-acknowledgement identity stay
separate from edits. Exact replay changes no revision; redactions fall back through eligible
revisions. Company source/namespace unchanged; no incoming acquisition or UI activated.
[Contract](collaboration-space-history-edits.md), [measurement](measurements/collaboration-space-history-edits-2026-11-10.json).

Focused1/72288 REAPED exit1/335.481s:94SQLite/Chromium storage cases passed, then DI test
compilation failed on inferred assertSame between two interfaces. All7candidate files archived.
Only that assertion gained explicit<Any>; production/storage source unchanged. Focused2/8330
REAPED PASS2fresh/115.724s plus iOS source/format/architecture/detekt. Strict repair auditor
verifies exact one-test-file delta and all94completed unchanged cases:96total,not96fresh.
17new Space edits+14existing Company edits+16existing Space history run on both actual stores;
2DI checks preserve one bound repository instance. Full1/31487 REAPED PASS660.201s:
10094verified=8075fresh+2019exact accepted reused,zero failures/errors/skips. Root check,Android
host/release compile/lint,Wasm/iOS app source,actualChromium,proofAPK/unit/quality pass. Strict
full auditor/publisher passed.4748source/library inputs fixed,manifest
d7cf45325f9349e1fefbb8e0bbf7277ba865370b7501de33176ebb943759e45d.
Historical edit-gate proofAPK150602468bytes SHA5ae67e42e3d0b6fc468d9a495e8395229178a85d428af5ce6fea9d87ef33b5c1.
Private .work/space-history-edits-2026-11-10 contains exact source/archive/runners/auditors.

Hosted edit control42811675228 COMPLETE11196=8092portable+3104Apple,16Swift/unsignedhost
on exact5da6cb95. Source96465/ref10060/dispatch12225 REAPED. Portable59554 and Apple69605
REAPED; strict source/GitHub digest/ZIP CRC/previous case identity checks pass. Appleartifact
13765577688,570922bytes SHAd0a4ca69f122424af4287bdaaa73ec74f41152ad4794fd27182e26a041d30475,
manifesteb0e1b77ddc9bd8f5259f982b47b87afc616c7761a193fd59590cf119288746a. Complete measurement
is published. No native Apple Matrix runtime claim; do not rerun accepted controls.

## Accepted owned Space history

Accepted Mobile0b84f0dcc90969b36fb82b2cc7bb9416b6ed2c6d is committed/pushed72460(REAPED).
This14-file increment adds typed account/Space/conversation history storage and
locally admitted observation. No native incoming stream or product route is activated.
[Contract](collaboration-space-history-storage.md), [measurement](measurements/collaboration-space-history-storage-2026-11-10.json).
Shared codec/key/batch mechanics preserve the original Company authenticated byte frames;
Space has explicit distinct coordinates. Same UUIDs cannot cross-read/decrypt Company/Space
facts. Replay leaves revisions/ciphertext unchanged; key loss never regenerates over owned data.

Focused1/41593 REAPED PASS112/466.361s:9new local-admission +8existing Space-message cases on
Desktop/Chromium,16new Space history +22existing Company history on both real databases,2DI.
Fixed HMAC/AAD vectors, >500-message traversal/restart, alias/tombstone replay, competing writers,
uncertain commit/cancellation, account changes, missing keys and tampering are covered. iOS
source/format/architecture/detekt pass. Full1/63357 REAPED PASS603.865s:10060verified=8041fresh+
2019exact accepted reused,zero failures/errors/skips. Root check,Android host/release compile/
lint,Wasm/iOS app source,actualChromium,proofAPK/unit and quality pass. Both strict auditors and
publisher passed.4743source/library inputs fixed,manifest
eb43c7e4cbf14e211a050ea90dd545cd0eff70a25b290e4b159fb5b470a3e26a.
Historical storage-gate proofAPK150602743bytes SHA9d0e7ddafdd92fbb784db8d9fb4478724cae21576d0d9eff15bc2e977f574996.
No native repeat or phone latency claim; the raw-source proofAPK above now supersedes it.
Private .work/space-history-storage-2026-11-10 holds source/results/auditors and exact file list.

Hosted storage control42808577683 COMPLETE11145=8058portable+3087Apple,16Swift/unsignedhost
on exact0b84f0dc. Source72460/ref82114/dispatch40835 REAPED. Portable60998 and Apple27630
REAPED; strict source/GitHub digest/ZIP CRC/previous case identity checks passed. Appleartifact
13764254868,570354bytes SHA bbd2454f07a8d26c396c2365db4eb6b6019e46ffe6dc921d8119db1e4c22dfe6,
manifest91d10e95e0d87995f112b911027e60e038874aabc228daa05321dd6d99df78fa. Complete hosted
measurement is published. Do not redispatch/re-audit; no native Apple Matrix runtime claim.

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

Worker control42801929551 COMPLETE11070=8008portable+3062Apple,16Swift/unsignedhost on
exact8c76f92c. Portable74895/Apple10663 REAPED and strictly audited (source,GitHub digest,ZIP CRC,
previous case identities). Appleartifact13761991889,
SHA94bd39afe5319bd67ecef746e202857848f40f2e86920af33acaeccbec25b8af,
manifest92a73e3175da70c9afcb22751dac57e069a19d37851e5a5e57a5d1ca1ab36f53. Complete
measurement accompanies this checkpoint. Native Apple Matrix implementation still missing.

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

[Incoming/history plan](collaboration-space-incoming-plan.md) now continues from accepted
original AND replacement storage and the accepted internal raw-event I/O primitive above.
The raw classifier is accepted above. Current UNCOMMITTED14-file native integration adds
physical and logical typed event contracts/snapshots, parent-owned finite reads, exact runtime
lookup before/after I/O, unavailable-host bindings,15child-lifetime +4parent-integration host
cases and4portable availability cases. Existing DI/absent-runtime cases cover same-instance
bindings and no implicit acquisition. No second client/registry or repository delegation.
Formatted; focused1/7075 RUNNING, not accepted. Prepare strict focused/full auditors and
accept source before committing product. No live/phone/source-compatibility claim. Then prove
actual original/edit/redaction/missing-key behavior through public repository capabilities in
the isolated SDK6 native fixture. Do not make an internal datasource public for tests or create
a second client/registry. Raw I/O alone does not admit content or advance progress.
Private space-original-acquisition-2026-11-10 retains the
classification/parent-ownership review and inspected immutable
SDK6 AAR/Javap API; preserve the dirty SDK source worktree and exact immutable library.

Provider Head requests Matrix/messages dir=b limit1; the current original reader requires that
anchor in an event-focused UI timeline. Rust treats edits as aggregations on originals, so a
latest-edit head needs a real SDK regression before reusing that contract. Do not weaken its
checks or pretend the edit ID is an original. Room.loadOrFetchRawEvent exists in actual SDK6,
returns original JSON plus NOT_DECRYPTED/DECRYPTED/UNABLE_TO_DECRYPT, and preserves HRIS fields.
Prefer explicitly owned raw event acquisition/classification and one bounded replacement-target
read for incoming. Availability is verified,not current native execution. Historical November6
raw consumer patch/evidence is a different candidate; never blindly reapply it or claim coverage.

The owning usecase must verify current logical runtime and immutable physical registration/
setup/room provenance across every I/O, retain bounded original/edit captures before progress,
and distinguish SDK decryption from sender trust. Require actual decryption for encrypted
message content; room state alone is insufficient. Redaction/control metadata needs separate
policy. Never acquire a queue writer merely to reuse ACK recovery validation. Local owned
history remains independent of live transport/recipient readiness. Company IDs and Space IDs
remain distinct; do not call usecases from usecases. No generic Starter delta.

The existing Company/Space acknowledgement reader also needs a native regression for an edit
head absent from the presentation timeline. Prove and address that boundary using exact acquired
anchor evidence; never merely weaken its checks or relabel an edit as an original. Private
runtime-integration-review.md records the next finite parent-owned read and separate logical/
physical snapshots. No second client/registry or repository-to-repository delegation.

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
