# Agent continuation — communications long run

Updated 14 November 2026, 22:52 UTC. Continue beyond checkpoints; push every commit.
All 54 PRD acceptance groups remain OPEN. No subagents. One local compiler OR emulator.

## Recipient thread consumer accepted

RecipientChatThreadViewModel composes local source, actual destination, exact progress and real
history under one bounded request lifetime. Requests join old readers and carry identities that
reject late publications; history/progress also name the real canonical destination. One serial
editor captures Send synchronously, preserves later typing and binds the latest retained time.
Screen rows are published before represented durable actor originals can be released. No source
absence is treated as delivery, and no transport, setup or retry timer belongs to this consumer.

Any read/write denial invalidates the entire request and clears private state; another reader's
buffered success cannot reopen it. Explicit entry/new admitted root lifetime can acquire again.
Hidden preparation reads locally but cannot Send. Disposal clears captured originals, progress,
source/thread/rows and editor state, with a generation fence against late callbacks.

Validation: 18,351 cases (2219 fresh / 16132 exact unchanged), preserving all 18,331 prior identities plus 20. Focused 34/session49829 and full/session30870 reaped 0. Source aaad9db957498dfd586c3460a01b6a2569076be75c8fb4b9c7bc09382df0c92a; reports 849cd6dd499aba5377da5335bbe774a346b18cba349448f7c2978e0a26be46f7.
Read [recipient thread consumer](collaboration-recipient-thread.md). Ten new common lifetime/state
cases plus the unchanged 9 composer-only / 13 serial-editor cases and actual application DI passed.
This is local state/lifetime validation, not rendered UI or measured Android first-frame latency.
Apple source compilation remains distinct from Apple runtime acceptance. Recipient rows 3e7c9f5d,
display fd041623, progress 4f6676f1, destination 2c93bc95 and source d4134a90 remain accepted/pushed.

No active product route uses this consumer yet. The old composer-only ViewModel remains a regression
control while actual thread/root behavior is integrated; do not bind both editors to one route.
Preserve its hidden-admission regression when moving that control to the completed root contract.
The `.work/recipient-thread-preparation-2026-11-14` prototype is now APPLIED and superseded by actual
source; do not replay its apply script or stale copies (which precede disposal cleanup/import fix).

A three-file departure prototype with six tests is UNAPPLIED/UNTESTED in
`.work/recipient-departure-preparation-2026-11-14`; read review.md and verify source hashes before
application. It flushes only local editor work before a confirmed Back effect, cancels stale
navigation on stop/denial, and still lets a newly requested Back leave a restricted page. It has
a prepared apply.py requiring the accepted/pushed consumer and exact source hashes. It creates
focused18/full18,363 gate scripts only when applied; review and run them before acceptance.

Next implement back flushing, selection/actions and the real page with existing ChatMessageBubble,
ChatDayDivider and ChatComposerInput. Wire actual root/contact/thread routes with the existing
bounded navigation ownership and verify rendered/root lifetime behavior, first layout and focus.
The current recipient draft is plain text: outgoing reply/forward controls need their own durable
capability before exposure. Independent canonical/recipient drafts and bounded global pending/draft
inbox identity still need product integration. PrepareCollaborationSpaceConversation needs finite
application ownership after retained Send/current discovery; passive opening/typing/renewal cannot
initiate setup. Follow collaboration-recipient-projection-review.md and
collaboration-recipient-product-review.md, complete company filters and independent rollout gates.

Starter c7323ce remains independently accepted with 1,060 cases. HRIS recipient policy stays Mobile-only.
Phone 93 is installed from 2eb833f4 and predates this change. Group OFF; V117–140 undeployed;
all 54 PRD groups OPEN. Continue beyond checkpoints and preserve blocked SQLite preparation,
failed Apple evidence, protected stores and phone install-only authorization below.

## Delivered build and loop diagnosis

Release 93 is installed in place on `10.77.77.3:5555`, from Mobile source
`2eb833f460cf46fa2f1170bbb1656b5cd9144f27`. Its ARM APK is 125491323 bytes/SHA256
`1097c710ff0296282f5a9113b7a4b7412017758e233d42d9b189db4ced14f07c`.
Install session 26007 reaped 0 on 14 November 16:44 UTC; signer, UID and first-install identity remain.
Both real release startup/reopen screenshots were inspected after fixing missing Compose locale
assets. Do not install the first failing 93 candidate or repeat accepted gates. Full evidence is
in Mobile `docs/collaboration-release93.md`; correct workspace is
`/home/fajar/hris/hris-mobile/.work/release-phone-2026-11-14-build93-resources`. Later source is not part of that APK.

Loop fixes are already in 93: e82abe168924d6586abd7a0651cb6f2d732e681c separates passive reopening
and admission renewal from delivery wakes; 0f4f0039ef30a3db8413a547ca8349dca328982c retains a healthy
Matrix runtime through ordinary access renewal. `m.secret_storage.default_key` is secret-storage
metadata, not proof that a recipient lacks message keys. Native quiet tests passed, including two
current-release 30-second windows with 3 normal long polls and zero non-sync requests each.
The later 45-second passive phone 93 logcat had only 2 lines; it is NOT complete HTTP evidence.
Its session 47918 reaped 0 and the process stayed running. No fresh phone chat-latency claim.

Phone authorization remains install-only: announce each validated installation, select the
explicit serial, preserve app data and do not launch/drive its UI. Next delivered version must
advance beyond93. Use the dedicated emulator for interaction. Physical iPhone interaction is waived.

## Apple evidence and blocked preparation

Hosted control 43498640097 at b4a3a89d105f4f79032ced3c912c1d1f6a4a15d7 is accepted with 20,459
(14359 portable/6100 Apple). Later control 43517312645 at 7aad7abc is FAILED overall: its portable
14,505 passed, but Apple 162 had one SQLite lock failure in
AppleCommandArchiveMutationsTest.failureDeletingArchivedCommandRollsBackEarlierReplicaWrites;
later Apple/iOS-host jobs did not run. Preserve the successful and failed artifacts as distinct
source-specific results; do not relabel or redispatch the same source.

Automatic content review blocked preparation of the SQLite fault-fixture gate payload.
The developer prohibited repeating, reformatting, splitting, encoding or rerouting that payload
and its later gate runs. `/home/fajar/hris/hris-mobile/.work/sqlite-fault-fixture-ownership-2026-11-13` remains UNAPPLIED;
no proposed fixture edits or gates were executed. This restriction is separate from the current
recipient/domain/database checks. Preserve it for the next agent.

Isolated SDK8 Apple control 43370071243 at ad96a35a8d4ea2f931476a906a4183fb6e1f3c5e accepted
five Swift tests, five architectures/exports and device/simulator links. Isolated consumer
d21fdf34 is in `/home/fajar/hris/.worktrees/hris-mobile-apple-budget-2026-11-12`. Runtime archive SHA256
`966c198f042a092423ca7e5c55ca3dc69bf7d448ef8caf991c1b4e44d7e5b687` is retained. Main Swift/Kotlin
bridge, application link and actual SDK8 Apple activation remain separate work. Do not copy
isolated builders/workflows into main or trigger a new native publication implicitly.

## Repositories, stores and runtime to preserve

Mobile: `/home/fajar/hris/hris-mobile`, branch `feat/chat-send-timing-20261107`.
Group: `/home/fajar/hris/.worktrees/communication-space`, branch `feat/communication-space`.
Starter: `/home/fajar/hris/compose-fluent-starter`, branch `feat/network-inspector-20261107`;
e9b4ff500baf62f2642871c6705d64b35bd1d2e1 is independently accepted/pushed with 1,052 cases,
three Android variants, packaged locale resources and actual production startup/reopen.
HRIS recipient policy is not a Starter port. Preserve main Platform `feat/communications` at 37a74bb9.
Inspector main is f8ca3d6535a875ce8a832326746026f8b86e2602. Pinned consumer 742a2d1c at
`/home/fajar/hris/.worktrees/inspector-pinned-consumer-2026-11-09` has its pre-existing gradlew.bat line-ending
difference; never report that tree clean or overwrite it.

SDK 26.10.23-hris.8 AAR SHA256 is
`6eebe8dab124541783d2b1d746cf30ea4b8dd2a599980cccb823927780678717`.
Preserve the protected matrix-sdk-incoming-hints-2026-11-10, matrix-kotlin-incoming-hints-2026-11-11
and hris-mobile-incoming-hints-consumer-2026-11-11 worktrees and their accepted local patches.
Legacy collaboration-hris.yml/check_space_queue_adoption.py use SDK4, not SDK8 proof.

Live source 90163c32500ff2fa005013eaf83b026d4bdcf5ff/schema116.4 has readiness ON, Group OFF
and 11 key publications. V117–140 are UNDEPLOYED. Never replace live services from the Group
worktree. Preserve Bima74, stopped Mobile87, their stores/sidecars/keys and all failed userdata.
Never send Draft66 or replay Control77/Warm77/Recovery78/Registry82/Parent83/History84/Selected86/Joined87.
Never print Firebase credentials or kill the protected server Java services.

Run Platform `.work/readiness-wake-2026-11-08/owned-heavy-processes.py` before heavy work.
One compiler OR emulator. Emulator 2560MiB/2 cores, serial emulator-5562. Java21 and Android SDK
`/home/fajar/Android/Sdk`; TMPDIR `/home/fajar/hris/.work/build-tmp`. Gradle no-daemon, no-parallel,
one worker, Kotlin in-process. Reap sessions and retain failed evidence. Focus/full runs must use
matching environment/version properties. New checks must preserve accepted test identities;
do not rerun already accepted gates without a changed source or unresolved concern.

[Archived checkpoints through recipient proof](agent-handoff-history-through-recipient-proof-2026-11-14.md) preserve all older source-specific evidence verbatim. Historical pending instructions are superseded by this page.
