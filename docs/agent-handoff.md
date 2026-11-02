# Agent continuation — HRIS Mobile

## Settings/projection Apple build accepted — 2 November 2026

Apple41492596519 at exact222bd44147081089e4f22fbe1828e619916554f9 is fully audited:
801 cases/91 suites, zero failures/errors/skips;72 accepted source hashes verified
against that commit. Artifact13168136589 has verified GitHub ZIP digest
fc400e60a5934858c91c2661b7c1bfab9b4a030574752685a396be35bb7cd4e9.
Simulator compile795s/tests81s, full Xcode app1225s. This covers local settings and
notification projection, EXCLUDES action envelopes5fb8611b, command dispatchb8703cbe
and newer input retention. [Evidence](measurements/chat-notification-projection-apple-2026-11-02.json).
No physical iPhone/APNs/CallKit/provider/display acceptance. Physical tests waived.
This replaces the earlier pending status; all Apple jobs are now finished. Dispatch
next Apple workflow on the next accepted source after reply-input validation/push.

Ongoing uncommitted work:13 Kotlin inputs for encrypted reply-input storage and a
current-family private-input retention use case, under .work/chat-notification-reply-inputs-2026-11-02.
Authenticated checkpoint lists at most64 active input documents, independently of
composer/employee-command slots. HMAC identity makes repeated text/descriptor recover
the original token; changed text retains a separate input. Exact revisions protect
review/removal and later re-created input. No OS receiver or dispatcher wrapper yet.
First focused23 cases failed one test in100.107s: it expected a failure for inactive
account reads, while the existing raw replica contract returns absence. The test now
explicitly asserts null and verifies old input remains recoverable under its owner;
production code unchanged. Focused attempt2 is running; full/browser still REQUIRED.
Do not claim this input work accepted before actual reports. Raw storage absence is
not current-session authority: use cases must guard ownership around it.

Current implementation headb8703cbe is pushed. Phone18/source42f5d157 and live304be027/
schema116 remain; all chat push OFF. No emulator or phone interaction. Continue input
validation, then exact retained-command proof before input retirement, bounded review/
read/recovery, durable receiver wrapper, display journal/issuer/serialization and all54
open PRD groups. .work/.../completion-next.md records detailed follow-through. Native
reply caller must persist input before command dispatch and preserve it after failures.
No generic Starter port. Next product phone build >18: notify first, explicit
10.77.77.3:5555, --install-only, preserve signing/data, never drive phone UI. Keep
working after this documentation commit and push every atomic commit.

## Admitted notification command dispatch accepted — 2 November 2026

Current-session token opening now checks acquired company/direct-conversation and
exact reply/read event facts, including valid tombstones. Mute/dismiss do not need
original body retention. Captured native scopes pin the remote family throughout
pending message/read/settings work and observations, even when local session/account
IDs are unchanged. Ordinary captured reads retain their local account/session checks;
access-token rotation keeps a pinned observer open. No fresh credential or selected
company is required for these local operations.

The app singleton dispatches authenticated reply/read/mute/dismiss kinds through
existing durable use cases, within eight seconds and without a waiting queue. Reply
retains its original operation/body and actual command state. Native read covers only
the target using private metadata; unread-only policy avoids requeue after a later
ACK replaces the old receipt, while known operation conflicts still reject. Mute uses
observed settings version/preview and cannot overwrite a later unmute. Dismiss returns
an exact original-render request only. [Contract](collaboration-notification-invocations.md),
[evidence](measurements/chat-notification-invocations-2026-11-02.json).

Initial admission-focused18 cases pass151.053s. Expanded focused30 cases/five suites
pass160.896s:19 domain, nine actual shared Koin/crypto/SQLite integration cases and two
DI cases. Includes duplicate/reopen/changed reply input, cancellation after committed
reply while scheduling, superseded receipt ACK, stale mute, retained denial, bounded
callback overlap/cancellation/deadline and native-family replacement. SDK is explicitly
simulated, HTTP is fictional. Full root/proof/Wasm/browser passes363.004s:3383 desktop/
499 suites,230 Android host/37, ten build-logic/two, six proof-host/one and19 actual
browser/three, including explicit task reuse.1823 cases/235 suites fresh; zero failures,
errors or skips. Sixteen source/test hashes unchanged. No failed attempts in this
increment. DB reopen retains test credentials/tokens; this is not OS process death.

Apple41492596519 at222bd441 is building the full Xcode app after simulator tests.
It covers settings/projection, EXCLUDES action envelopes5fb8611b and this dispatcher.
Prepared artifact/source/JUnit audit: .work/chat-notification-projection-apple-2026-11-02/
audit.py, run only after success. Do not cancel this job with another same-scope
workflow. Latest accepted Apple remains42f5d157/run41480512291. Phone18/source42f5d157,
live304be027/schema116, Starter0d4432e remain; all chat push OFF. No generic port.

Next: commit/push, then encrypted native reply-input retention BEFORE wiring an Android
receiver. The command dispatcher requires its caller to persist input first; currently
no OS callback invokes it. Same token/body must recover; changed text on an old token
must remain a separate review input without overwriting the composer or first command.
Retaining family-authenticated private input is distinct from company send admission;
a later company denial must not silently lose typed text. Old-account input must never
be stored under its replacement. Retire input only against an exact retained-command
proof. Preserve uncertainty and cancellation-after-commit. A separate bounded encrypted
input replica should not consume global128 employee-command slots for bookkeeping.
Then implement exact-token issuance/display journal, serialized publication/cancellation,
privacy controls and native inline replies. Results here are not OS display leases;
match current owner and exact render before changing native cards, and allow separate
privacy cleanup after content admission is denied. Input text is never logged or put
in generic plaintext notification history. All54 PRD groups remain open; continue
long run after each milestone and push every atomic commit.

Design notes .work/chat-notification-delivery-2026-11-02/next-input-retention.md include
storage bounds and unresolved producer/capacity policy. Replica checkpoint limit32768
characters cannot hold many full token sets; use bounded encrypted document records
with authenticated checkpoint/CAS metadata. Do not notify every backfill event or
mistake scheduling hints/timestamps for message authorization/order. Prepare -> Attempted
before notify -> SubmittedToOS/Retired preserves uncertainty, not exactly-once sound.
Current priority worker completes hints after original acquisition, so display integration
must preserve its own durable work before that completion. Native privacy changes need
owned serialization/cancellation; current local use cases have no OS publication owner.

Local check-3/audit.py/record.py completed under .work/chat-notification-invocations-2026-11-02;
no repeat of accepted broad checks absent changes/failures. Next product phone build >18:
notify first, explicit10.77.77.3:5555, --install-only, preserve signing/data, no UI driving.

## Notification action envelopes accepted — 2 November 2026

Bounded native action metadata now seals under the retained history content key.
Reply/read/mute/dismiss carry stable operation UUIDs and company/conversation/room/
original/native-card/render identity. AAD binds API/account/local and native session;
old-family or changed-key tokens reject. No body or bearer credential is embedded.
Mute carries the observed settings revision and preview. Missing keys never regenerate.
[Contract](collaboration-notification-actions.md),
[evidence](measurements/chat-notification-actions-2026-11-02.json).

Focused 11 cases/two suites pass in 150.412s, including nine real-cipher storage
contract cases and two assembled DI cases. Full root/proof/Wasm/browser passes
358.711s: 3355 desktop/495 suites, 230 Android host/37, ten build-logic/two,
six proof-host/one and nine actual browser/one, including explicit task reuse.
1785 cases/229 suites are fresh; zero failures/errors/skips. Nine source/test
inputs stayed unchanged. DB reopen retains test credentials and an in-memory token;
it does not prove token journal persistence or OS process death. No Starter port.

Apple 41487045428 at 7d35e975 failed before any build step because GitHub could not
acquire a hosted macOS ARM runner; annotations identify capacity constraints.
Replacement 41492596519 at exact 222bd44147081089e4f22fbe1828e619916554f9 is running
simulator compilation. It covers settings/projection, EXCLUDES these newer actions.
Audit actual final artifact/source/JUnit/full Xcode when complete; do not cancel it
by dispatching another same-scope workflow blindly. Latest accepted Apple remains
42f5d157/run41480512291. Phone18 and live304be027/schema116 remain; chat push OFF.

Next: commit/push this increment, then admitted native action issuance/consumption,
encrypted display journal, serialized publication/cancellation, privacy controls and
native inline replies; continue all54 open PRD groups. Crypto opening is NOT current
admission. Capture the parent from SessionRepository, never Intent extras; gate local
company/retained denial/exact history around pending operations. Persist exact sealed
tokens before publishing and reuse after uncertainty; re-sealing makes new ciphertext.
Use existing durable reply/read/settings operations and exact render cancellation.
RemoteInput receiver must be explicit/internal, bounded and application-owned.
Display bookkeeping must not consume CommandStore's global128 employee command slots.
No decrypted chat content in generic plaintext notification history. Source stamps do
not form an atomic OS display lease, and candidate suppression must not clear an
unrelated card. Delivery notes under .work are supplemental; these constraints are
required regardless of their older one-use wording. No native display is active yet.

Local .work/chat-notification-actions-2026-11-02/check-2 and audit.py passed; do not
repeat accepted broad checks absent changes/failures. All .txt projection drafts were
already applied and superseded by reviewed Kotlin. Next product phone build >18:
notify first, explicit10.77.77.3:5555, --install-only, preserve data/signing, no UI driving.
Continue long run after this checkpoint and push every atomic commit.

## Local notification projection accepted — 2 November 2026

A captured-account exact event can now be prepared from encrypted original history,
retained direct-room labels, protected self identity, mute/preview settings and read
coverage. Muted/self/read/redacted sources suppress; missing data remains pending;
a confirmed tombstone suppresses even before its original is available. Defaults
expose no names/body, previews are bounded and avoid splitting surrogate pairs.
Source alias lists are detached; final original and settings comparisons detect
pending changes, and retained denial/current ownership surround each suspension.
No SDK/network, implicit retry, read mark, receipt ACK, UI or OS display is added.
[Contract](collaboration-notification-projection.md),
[evidence](measurements/chat-notification-projection-2026-11-02.json).

Focused10 cases initially passed141.782s. Review then added exact missing-original
tombstone lookup and moved final settings comparison after read-state/original reads;
three regressions were added. Final focused13 cases pass137.768s (11 domain/two
assembled DI). Full root/proof/Wasm/browser passes333.788s:3346 desktop/494 suites,
230 Android host/37, ten build-logic/two, six proof-host/one,11 browser/one, including
explicit task reuse;1778 cases/228 suites fresh, zero failures/errors/skips. Seven
source/test inputs unchanged during final validation. No new native/provider or
phone interaction test. No Starter port for this HRIS policy.

Settings7d35e975 is pushed. Apple41487045428 at exact
7d35e975d32bd04fcfdebb0487de23a03dff6174 remains QUEUED; audit eventual full Xcode
and simulator artifacts. It excludes this newer projection; dispatch Apple against
a later accepted head after it finishes, avoiding same-scope cancellation. Latest
accepted Apple remains42f5d157/run41480512291,54 hashes and780 cases plus full Xcode.
Phone18/source42f5d157 and live304be027/schema116 remain, all chat push OFF.

Next: commit/push this increment, then implement encrypted notification delivery/
action state, owned display/cancellation, UI controls and native inline reply. All54
groups stay open; continue long run. Source stamps are not an atomic OS display lease;
coordinator must serialize/revalidate and retire stale cards. Suppression is not a
blanket cancel for an unrelated card: reproject that card's canonical original.

.work/chat-notification-projection-2026-11-02 .txt drafts were ALREADY APPLIED and
then corrected in actual Kotlin. Do not reapply stale drafts. check-3/audit.py passed;
accepted full checks need no repeat absent changes/failures. Next design notes are
.work/chat-notification-delivery-2026-11-02/notes.md: do not consume the generic
CommandStore's global128 employee-action capacity for display bookkeeping; use
scoped encrypted notification state, bounded operations and exact acknowledgments.
Avoid decrypted chat text in generic plaintext notification history. Define initial
backlog/source handling rather than treating timestamp order as processing authority.
Reply tokens must survive duplicate callbacks and rotate after consumption; preserve
text on conflicts without overwriting a composer. iOS extension/store concurrency
and current-owner native cancellation remain separate required work. Next product
phone build must exceed18, notify first, explicit10.77.77.3:5555, --install-only,
preserve signing/data and never drive its UI.

## Notification settings accepted; release18 Apple audit — 2 November 2026

Encrypted per-conversation mute/preview settings and bounded local read/update use
cases are implemented. Defaults hide names/content; mute supports expiry or forever.
Stable operation plus observed generation/revision recovers lost replies and rejects
old actions after newer changes. Same-account reauthentication retains settings but
invalidates old captured OS-action ownership. Current local direct binding and
retained denial surround pending I/O; no fresh token/selected company required.
Existing history key/AAD protects one checkpoint; inactive missing-key guards remain.
No schema/key reset, hidden retry, read-receipt preference change or provider I/O.
[Contract](collaboration-notification-settings.md),
[evidence](measurements/chat-notification-settings-2026-11-02.json).

Focused21 cases pass232.667s: ten domain, nine SQLite, two actual DI. Full
root/proof/Wasm/browser passes400.043s:3335 desktop/493 suites,230 Android host/37,
ten build-logic/two, six proof-host/one,19 browser/two, including explicit task reuse.
1775 cases/228 suites fresh; zero failures/errors/skips. First focused55.332s had
two fixture failures only; captured-scope and denial sequencing fixed before all
checks passed. Database reopen uses a retained test credential store, not a new OS
process-death acceptance. No UI controls/display/cancellation/native action consumer
exists yet. This increment has no generic Starter port.

Release18/source42f5d157 Apple41480512291 is fully audited:780 cases/89 suites, zero
failures/errors/skips,54 accepted source hashes, artifact13162038114 digest
f48d985d395c78e855cab177782b9084b8f024b5142ef21d160efbbfccd49e0f.
Simulator compile758s/tests75s, full Xcode app1315s. Covers worker/viewport, excludes
these newer notification settings. [Apple evidence](measurements/chat-visible-read-apple-2026-11-02.json).
Phone remains verified18, live API/worker304be027/schema116, all chat push OFF.

Next: commit/push settings, then dispatch Apple on that exact head (previous Apple
finished; workflow concurrency would cancel a running same-scope run). Continue
notification projection, durable encrypted display/action ledger, mute/read cancellation,
UI controls and native inline reply, then all54 PRD groups. Three production and two
test .txt drafts under .work/chat-notification-projection-2026-11-02 are UNAPPLIED,
UNVALIDATED. Review source stamps/alias snapshots/Unicode bounds and sender-label
binding, then wire owning DI/tests before applying. notes.md documents renderer
races and RemoteInput duplication; a local projection alone cannot atomically order
OS display against concurrent preference writes. Settings .work/check-3 already
passed; don't rerun accepted broad tests without changes/failures. All54 groups stay
open; continue long run and push every commit. Next product phone build must exceed18,
notify first, explicit10.77.77.3:5555, --install-only, preserve data/signing, no UI driving.

## Release18 installed; receipt gateway live — 2 November 2026

Clean42f5d157 built signed devRelease18/1.0.0 (arm64, non-debuggable) and lint in
477.226s. API/Firebase target, signature,16KiB alignment and compiled backup
exclusions verified. APK87544452 bytes, SHA256
9216942d0acb4dbdc1145138bafa6cac7e62331ab5e837b3e718c2fb218cb957.
Two anonymous emulator cold starts pass with resumed readable UI and no crash;
second screenshot visually inspected. Owned emulator5562/PID871456 stopped.

Installed after notice with --install-only on10.77.77.3:5555 at16:39UTC in57.480s
including pulled-APK exact verification. Same signer, appUID10962 and original
first-install timestamp retained; no uninstall/data clear/UI launch. This is
installation/startup evidence, not human chat or calling acceptance.
[Evidence](measurements/android-release-phone-2026-11-02-build18.json).

Platform304be027 is live at unchanged schema116 after all14 full-CI jobs and fresh
verified recovery. Google/person/native/document fingerprints and existing Matrix
identity/config/provider containers preserved, dashboard2124c6fc unchanged, all
chat push flags OFF. Platform72447fb8 records the rollout. First checkpoint attempt
used inheritedJava17 and stopped before replacement; explicitJava21 corrected it,
with old runtime restored and verified before successful retry.

Apple41480512291 at42f5d157 has passed simulator tests and is compiling full Xcode;
audit final reports/artifact before acceptance. Earlier04e5cf7e Apple success excludes
worker/viewport. Continue privacy/mute, notifications/actions/inline replies and
all54 PRD groups. No generic Starter port. Chat inbox/thread UI is present; voice/
video/meeting UI still absent. Next validated phone version must exceed18, notify
first, explicit serial, --install-only, preserve data/signing and never drive UI.
Continue long run and push every atomic commit. Older pending sections below are
historical; .work release18 scripts already ran and must not blindly be repeated.

## Active viewport read marks — 2 November 2026

Chat now records exact local read coverage from a resumed, focused conversation.
The route observes visible stable keys; a current owner token and acquired original
history determine eligible colleague events. Dialogs, backgrounding, focus loss,
leaving and confirmed access loss interrupt marking. The authenticated history page
supplies its retained room binding, so an offline restart needs no live room lookup.
No offscreen gap or pending echo is marked. A long message needs at least half of
the viewport visible; smaller messages need at least half of their own height.

The ViewModel's tracker serializes cancelled storage work, retains uncertain intent,
skips already-read events and requests bounded background recovery after local
persistence. Initial viewport receipts are private to the user's own Matrix identity.
Public-sharing preferences and peer read ticks are not implemented by this increment.
No UI/Compose provider I/O, hidden polling or recipient delivery ACK is added.

Focused28 cases pass133.840s: eight ViewModel, two actual Compose
lifecycle/viewport,16 SQLite history and two assembled-container cases. Portrait375dp
with2x text and landscape720×375dp cover scroll, focus, dialog, pause/resume/disposal.
Full root/proof/Wasm compile passes389.235s: 3316 desktop, 10 buildLogic, 230 androidHost, 6 proofHost, including
explicit reuse; 1737 cases/224 suites fresh, zero failures/errors/skips.
The first browser filters selected zero cases. Corrected trailing-wildcard filters
pass31.689s separately:16 IndexedDB history and eight browser ViewModel cases,
zero failures/errors/skips, with actual fresh JUnit reports; source unchanged.
Initial two Compose failures were a missing test-only desktop Main dispatcher; the
existing Swing dispatcher dependency fixes their host without changing production.
[Evidence](measurements/chat-visible-read-2026-11-02.json).

Worker c361aa1f is pushed. Earlier Apple41475111107 at04e5cf7e PASSED full Xcode
(947s), simulator compilation613s and native tests59s:780 cases/89 suites, no
failures/errors/skips. Archive13158956537 digest500aee72a25ef2378491d42f9a900b3beb81006d7800118ed6445f4de412444d
and37 accepted source hashes verified. [Apple evidence](measurements/chat-read-receipt-apple-2026-11-02.json).
This excludes the newer worker/viewport; dispatch Apple again against this eventual
commit. Platform full Verify41475356301 at304be027: all8 backend/4 dashboard shards
and recovery passed; dashboard integration remains running. Backend artifacts audit
3159 API cases/436 suites (five existing optional skips),117 worker/24 per shard
(one existing optional skip), no failures/errors. Gateway receipt rollout remains
separate: live runtime dac6fb0d/schema116, chat push OFF, phone17.

Next: commit/push this increment, dispatch Apple on that head, build validated
release18 and install on10.77.77.3:5555 after notice, --install-only, same signer/data,
no phone UI interaction. Prepared .work/release-phone-2026-11-02-build18 scripts are
not yet run. Continue privacy/mute/notification actions/inline replies and all54
groups. No Starter port for HRIS policy. Continue long run; this is not completion.
.work/chat-visible-read-2026-11-02 stage.py/.txt drafts were already applied: do not
rerun them over accepted code. Reports/audit scripts remain supplementary to Git.

## Receipt recovery worker accepted — 2 November 2026

The existing incoming room turn now invokes admitted receipt replay: limit2 with
3-second operation sub-budget, preserving incoming history progress on ordinary
receipt failure and closing publication on confirmed denial. Remaining intents
continue a fair sweep; cancellation joins through the existing child/runtime owner.
No selected workspace/outbox needed. [Evidence](measurements/chat-read-receipt-worker-2026-11-02.json).

Focused23 cases pass66.328s; full root/proof/Wasm pass236.840s with3306 desktop,
230 Android host,10 build-logic,6 proof host cases including reused tasks;339 cases/
65 suites fresh, no failures/errors/skips. Seven new shared-container/SQLite/HTTP
cases use an SDK double. Initial focused62.360s failed only a provider-message versus
safe-gate-copy assertion; corrected classification and retained ciphertext assertions
pass. No new native provider or physical phone proof is claimed. Actual native
sender/process recovery remains the accepted ae4a1b27 proof below.

Hosted Apple41475111107 at04e5cf7e has passed simulator tests and is building the
full Xcode app; audit its final reports (it does not cover this worker increment).
Platform full Verify41475356301 at304be027 remains running. Gateway receipts still
not deployed: live API/worker dac6fb0d/schema116, chat push OFF; phone release17.

Next: apply/review staged UI work in .work/chat-visible-read-2026-11-02 AFTER this
commit. staged.json has nine source baselines and .txt drafts; additional ReadFixture/
ReadTest .txt drafts still need fixture wiring and lifecycle Compose tests. Drafts
are NOT validated or accepted. They expose the authenticated history room binding,
observe resumed/focused viewport, retain exact local read intent and request worker
recovery. Review owner replacement, cancellation and visibility carefully. Then
privacy/mute/notification actions and all54 groups; continue long run. Next validated
product build goes to10.77.77.3:5555 after notice, --install-only, preserve data and
signing, no phone UI interaction. No generic Starter port applies to HRIS policy.

## Apple validation and next integration — 2 November 2026

Mobile ae4a1b27 and Platform304be027 are pushed, with actual admitted read-receipt
replay/process recovery and all local checks described below. The Apple CI workflow
now also links/runs the collaboration domain's common tests alongside the existing
native core cases and full Xcode app build. This workflow-only increment does not
change the accepted runtime/proof sources. Dispatch `ci.yml` with scope=apple on
feat/communications after pushing; track its exact head/run and audit its reports.
No Apple success is claimed yet. Continue account-bound background/foreground
receipt integration while hosted Apple runs. Next validated product build must also
be installed on the authorized phone after notice; current release17 is unchanged.


## Admitted read-receipt replay and actual recovery accepted — 2 November 2026

[Contract](collaboration-read-receipts.md) / [evidence](measurements/chat-read-receipt-replay-2026-11-02.json):
common runtime receipt entry and a singleton finite sender now consume the encrypted
queue. Exact retained coverage/originals, live HRIS device/platform/conversation/room
admission, another pending-intent read before SDK entry and fresh admission after
SDK success precede identical local ACK.20-second budget, default4/max8 intents,
no hidden retry/ordering/coalescing; concurrent points and uncertain outcomes remain.
Confirmed remote denial is retained even when writing its gate fails. No worker,
foreground/UI, notification or scheduler call yet.

Focused133.705s passes12 domain/two assembled-container cases. Full root/proof plus
12 browser cases PASSES292.360s:3299 desktop/230 Android host/10 build-logic/six proof
host cases including explicit reuse;1732 cases/222 suites fresh, zero failures/errors/
skips. Actual native113.216s passes one HRIS/PostgreSQL/Synapse/Android scenario on
133 Mobile/25 Platform hashes. Local mark survives database reopen; provider success
followed by interrupted app ACK leaves the intent through real OS death. The next
process replays, acknowledges, reopens and repeats without another send. Exact local
coverage remains. Seven receipt POSTs succeed200 (five private/two public); prior
private isolation, three encrypted sends and all three process-death stages pass.
27 driver cases pass0.104s. Proof APK343275325B SHA256
6dbe4aceb281528b02f97eef9b519fce78ce28638f253eab2e72ec4e8040cd01.

Local dirs .work/chat-read-receipt-replay-2026-11-02 and Platform
.work/chat-read-receipt-replay-native-2026-11-02 retain reports/scripts. Staged .txt
files have been applied; DO NOT reapply old baselines. Actual fixture
.work/collaboration/37854341d48f45a0b43dc56ca73b985b has verified.json, not acceptance.json.
Owned emulator5562/PID790095 boot26.497s, proof removed by driver, stopped1.905s.
No emulator or heavy job remains. Phone remains17; private runtime remains
dac6fb0d/schema116, chat push OFF. No Apple or phone acceptance is inferred.

Commit/push this Mobile increment, then the three validated Platform proof/driver
inputs with counterpart evidence. Previous local mark increment a349c84e is pushed.
Next: dispatch full Apple/Xcode against this common integration; wire read receipt
replay into the existing account-bound background worker and foreground chat, then
privacy/mute and notification inline reply/actions, and continue all54 groups.
Explicit privacy cancellation must not masquerade as server ACK or silently drop
pending public receipts. No generic Starter port applies to these HRIS policies.
User requests the next validated product build on the phone too: notice first,
explicit10.77.77.3:5555 --install-only, retain signing/data, never drive phone UI.
Chat UI is in17; voice/video/meeting UI remains absent. Continue long run; not done.


## Local read/mark admission accepted — 2 November 2026

[Contract](collaboration-read-state.md) / [evidence](measurements/chat-read-state-policy-2026-11-02.json):
finite local read and singleton mark use cases now guard current/captured ownership,
retained content denial, the direct-conversation pair and every exact acquired event.
No fresh credentials required for owned local work; no unseen-gap/timestamp inference.
Mark has10-second cancellation and try-lock exclusion, observed-version CAS, stable
caller intent recovery, explicit capacity rejection and no hidden retry. Failed/late
responses never erase committed intent/coverage. No scheduling/provider/UI call yet.

Focused check2 PASSES128.050s with14 domain/two real-container cases. Full check3
PASSES270.921s:3287 desktop/230 Android host/10 build-logic/14 browser cases including
explicit reused tasks;1716 cases/220 suites fresh, zero failures/errors/skips. Initial
check1 stops42.074s at incompatible generic types in a new test assertion, corrected
before tests executed. All nine source/test inputs and report/log hashes recorded.

Next: runtime receipt contract/Android adapter/fake additions are PREPARED ONLY in
.work/chat-read-receipt-replay-2026-11-02/staged.json (14 hashed source baselines plus
.txt files). Additional .txt drafts contain DeliverCollaborationReadReceipts, result,
fixture and12 tests. They are not applied/validated/accepted. Apply only after this
commit and verify baselines; review the draft before compiling. Replay must recheck
live owner/device/exact room before writes and after provider success, retain the
original intent on uncertainty and ACK only its identical queued entry. Then wire
worker, foreground reads, notification/privacy/mute/inline replies and all54 groups.
New common changes need full Apple/Xcode coverage. No generic Starter port here.

Phone remains release17, live API/worker dac6fb0d/schema116, chat push OFF. User now
explicitly requests installing the next validated build too: notify immediately
before every install, explicit10.77.77.3:5555 --install-only, preserve data/signing,
never drive the phone UI. Chat UI exists in17; voice/video/meeting UI remains absent.
Latest user confirmation was release11's crash fix, not a17 chat/phone UI acceptance.
Push this commit and continue long run; this is not task completion.


## Encrypted local read state accepted — 2 November 2026

[Contract](collaboration-read-state.md) / [evidence](measurements/chat-read-state-2026-11-02.json):
exact read markers and a128-intent queue now share an authenticated atomic checkpoint.
One mark adds at most128 exact acquired references; it does not infer unseen coverage
or provider ordering. Pending points remain until their identical acknowledgement;
late ACK preserves other queued points. Active/last-acknowledged replay deduplicates,
known ID rebinding and queue overflow reject without evicting facts. Shared history
key guard includes the separate read namespace and inactive accounts. No schema/key
replacement, timestamps, data reset or age eviction. New repository resolves in the
real shared container; common local admission/replay/UI integration is still absent.

Initial single-pending draft passed125.002s (nine SQLite/two DI cases), then review
required retaining all unsent points instead of assuming monotonic supersession.
Full check2 failed247.689s on an existing RoomLookupTransport test's immediate read
of a Boolean written by MockEngine after caller cancellation; new11 storage cases
passed. Fixture now awaits a separate completion signal on Default with a5-second
bound and verifies no result publication; HTTP production unchanged.

Check3 PASSES161.148s:3273 desktop/230 Android host/10 build-logic/11 IndexedDB cases,
with explicit cached/up-to-date statuses;1373 cases/156 suites freshly executed.
Check4 separately passes the corrected five browser transport cases25.118s. Nine
source/test input hashes and all attempts/reports retained. No emulator/phone work.
Native receipt counterpart Mobile c0ad592f and Platform f87a4562 are pushed; real
SDK109.111s proves five HRIS receipt POSTs, private isolation and store recovery.
Phone17 and live dac6fb0d/schema116 remain unchanged, chat push OFF.

Next: expose receipt methods through common runtime ownership; add local read-state
observation/mark use cases with captured-account/content-denial guards and exact
owned-history validation, then live-admitted bounded queued replay. Wire the worker
and UI/notification actions afterward. Retain all unsent points without timestamp
coalescing. Privacy changes need explicit suppression/cancellation of queued public
receipts; do not label an unsent cancellation as a server acknowledgement. Follow
with mute/privacy/actions/inline replies and all54 groups. New common changes need
full Apple/Xcode coverage; physical iPhone waiver still applies only to device tests.
No generic Starter port. Continue long run and push every commit; not task completion.


## Native public/private read receipts accepted — 2 November 2026

[Contract](collaboration-read-receipts.md) / [evidence](measurements/chat-read-receipt-client-2026-11-02.json):
Android owns finite receipt reads/writes through existing parent-child cleanup.
A separate lazy native request starts only after current caller acquisition;
cancellation/parent closure joins it before closing the room. Failed cleanup stays
owned. Eight host lifecycle/validation cases pass. This adapter is not yet exposed
through the common runtime repository or invoked by product read/notification actions.

Actual SDK→HRIS→Synapse proof PASSES109.111s on114 Mobile/25 Platform inputs.
Five empty receipt POSTs (three private/two public) return200. Private metadata is
invisible to the peer; the SDK retains own metadata through actual process death and
reads it in the next process before sync. Existing encrypted exchange, queue/held-room
isolation, acknowledgement/completion recovery and three process restarts also pass.
Proof APK343,275,325 bytes, SHA2564bd9200487608c1efb258d29ddb3762e92bfe32079934307010fb690254cd035.
No FCM/OS notification/phone or Apple provider claim. Owned emulator5562/PID705941
is stopped and proof package absent; cleanup retained in measurement.

Full root/proof check2 passes275.337s:3262 desktop/230 Android host/10 build-logic/
six proof-host cases with explicit cache/up-to-date task statuses;1683 cases in218
suites freshly executed. Focused check1 passed eight cases but failed detekt's
101-line proof function; extracted completed-exchange evidence before the full pass.
No production failure or repeated native attempt. Python driver26 cases pass0.159s.

Platform receipt gateway8c87839a is pushed with focused99.155s/broad809.053s and real
Synapse7.216s evidence. Four counterpart native proof/driver changes are validated
locally and must be committed/pushed with their evidence immediately after this commit.
Live API/worker remain dac6fb0d/schema116, all chat push OFF; installed phone17 unchanged.
Keep private Matrix identity/keys/checkpoints and Google/native sessions intact.

Next: expose these operations through the common runtime owner; implement encrypted
local read intent/state and admitted replay, then mute/privacy/account-bound notification
actions and inline replies. Do not infer read ordering from timestamps or unknown gaps.
Continue all54 PRD groups; no whole group closes here. Apple shared/complete Xcode
validation must cover subsequent common changes. No generic Starter port applies to
this Matrix/HRIS increment. Notify before each phone update, explicit10.77.77.3:5555
--install-only, never drive the user's UI. Push every commit and continue long run.


## Native priority recovery accepted — 2 November 2026

[Evidence](measurements/chat-priority-native-2026-11-02.json) binds 108 Mobile and
seven Platform inputs to proof APK SHA256
a54d65517c55a4a94bd22303dd5ba1380aab0bcf8544dd96e1096e3a3b113a5b (343,275,325 bytes).
Actual HRIS/PostgreSQL/Synapse/Android SDK passes all three lifecycle variants:
cancelled draft in 289.577s, queued and frozen draft in 569.699s. Each recovers the
oldest of 175 offline peer events in one priority turn, retains 12 originals, preserves
the global wake and all company/room/Browse/Incoming positions across container reopens,
then recovers 176 originals in 11 fair incoming turns. Foreground independently pages
11 times over 186 references. One worker send and 175 peer ciphertexts, no duplicate
worker send; cancelled intent absent. Owned runtime and fixture cleanup pass.

Full check/proof build 3 passes 97.046s: 3,262 desktop, 222 Android host, 10 build-logic
and six proof-host cases, zero failures/errors/skips. Initial checks failed on a
101-line method and an outdated company-fixture constructor; phase ownership was
extracted and the synthetic company fixture explicitly supplies an empty priority
lane. No production source changed in this commit. Company WorkManager/process repair
also passes 45.199s, with synthetic work and no provider claim. Initial native run
correctly refused the company fixture's remaining owned APK before native SDK execution;
its installed hash was verified before removing only that package. Guard unchanged.
A missing collector input manifest was corrected before starting the second invocation.
All attempts and input/report hashes remain in the measurement.

The receiver is invoked with strict synthetic metadata; FCM/OS delivery, notification
privacy/actions, inline replies and capacity remain unaccepted. Counterpart Platform
proof is committed/pushed as bc9774eb. Owned emulator 5562/PID 553348 is stopped and its
proof package removed; phone untouched. No HRIS policy is portable to Starter here.

Release17 at clean c6347c75 is now INSTALLED on explicit10.77.77.3:5555 after notice,
using --install-only. Update/pull-back verification takes62.534s; installed SHA256
b94aedf2b1eb81949d8eeacc82fd2906f0c85b27641e3bcc65fd8884e5fb34d2 exactly matches the
87,495,300-byte accepted APK. UID10962/first-install time retained, non-debuggable,
no uninstall/data clear/UI launch. [Build/install evidence](measurements/android-release-phone-2026-11-02-build17.json).
Two actual-artifact emulator cold starts passed earlier via libndk_translation,
showing onboarding; no new phone login/chat/FCM claim. Future delivery must use a
higher versionCode, notify first and retain explicitserial install-only.

Platform full Verify41454857226 COMPLETE at dac6fb0d: all14 jobs pass, 3,153 active
API/116 active worker cases,1,111 mocked and56 actual browser workflows, schema116
recovery. Verified private API/worker/dashboard rollout and fresh103→116 backup/clone
checks pass. Existing Google/person/native/document state retained. Employee/operator
chat grants and read-only contact/device/directory checks pass, with applied roles
preserved. Temporary operator sessions revoked. API/worker now schema116/dac6fb0d,
dashboard immutable2124c6fc; stable private Matrix provider and keys retained.
All chat push flags remain OFF; no real-account messages or provider enrollment by
the operator checks. Platform f0929362 records exact source/images/checkpoints.

Apple41446374520 atcc4c11f1 is fully audited:112 native core cases/30 suites,
106 combined source hashes and full Xcode. Physical iPhone testing waived. Starter
backup port0d4432e passed and is pushed; no HRIS receipt policy port.

Next: Platform receipt work is staged as17 .txt inputs under .work/chat-receipts-
2026-11-02. It will add exact public/private receipt delivery and scoped sync
projection before Mobile read/mute/privacy/action integration. No new receipt code
is accepted yet. Continue encrypted inline replies and all54 open groups; this
phone/server delivery is not task completion. User's latest startup confirmation
was for release11; do not invent a release17 UI confirmation or drive their phone.

## Release17 accepted candidate and generic port — 2 November 2026

[Evidence](measurements/android-release-phone-2026-11-02-build17.json): clean c6347c75
arm64 devRelease17 build/lint passes433.769s (Gradle7m10s), zero lint issues. APK
87,495,300 bytes, SHA256b94aedf2b1eb81949d8eeacc82fd2906f0c85b27641e3bcc65fd8884e5fb34d2,
same installed development signer, non-debuggable, exact Firebase/private API and
revision metadata,16KiB alignment. Actual resource table resolves shortened backup
XML path; both nine-domain exclusions and false backup attributes are inspected.
Two local verifier assumptions (unshortened path/old aapt boolean text) were corrected
without rebuilding or changing application source. Prior release16 remains intact.

Starter0d4432e is pushed with identical XML/attributes after independent root/resources/
lint70.128s:356 desktop/six build-logic, zero failures/errors/skips,44 existing warnings.
No shared Kotlin/Apple or HRIS policy port. Mobile Apple41446374520 remains active at
cc4c11f1 in Xcode; audit once complete. Platform full Verify41448616746 at2334e266 still
runs; do not cancel/duplicate. Phone remains15, no installation. Before deploying17,
finish compatible server packages/images/checkpoint/clone/rollout/grants and refresh
phone preflight; notify and use explicitserial install-only, never phone UI driving.

Next native-priority proof is staged only in .work/native-priority-2026-11-02:
oldest of175 offline encrypted peer events, actual receiver/queue/HRIS/SDK, container
reopens, retained global wake and unchanged traversals. Apply Mobile fixture after
this build; Platform fixture changes must not invalidate rollout source guards.
No claim of actual FCM/OS priority delivery. Continue notifications/actions/replies
and all54 open groups after this candidate; this is not task completion.

## Android backup exclusions validated — 2 November 2026

[Contract](android-data-extraction.md) / [evidence](measurements/android-data-extraction-2026-11-02.json):
Android12+ explicitly excludes all nine local storage domains from cloud backup and
device transfer, while legacy allowBackup/fullBackupContent are false. No migration,
deleting stores, key transfer or user-managed encrypted backup feature. Root/release
resources/lint passes148.202s on the two unchanged manifest/XML inputs, with zero
Android lint issues. Actual linked binary XML and merged attributes inspected; the
measurement distinguishes executed/cache/up-to-date reports. No physical transfer.

Push this configuration, then port identical XML/attributes to Starter and run its
own root/Android checks. Shared/Apple Kotlin and Gradle sources are unchanged, so
active Apple41446374520 atcc4c11f1 still covers those exact inputs; do not duplicate.
Existing clean release16 artifact remains intact but excludes these rules. Prepare
release17+ from a clean accepted commit with the same signer/private API after the
port checks, then install only after compatible server rollout and prior notice.
Phone currently15, explicit10.77.77.3:5555 --install-only, no UI driving.

Platform2334e266 fixes only the outage fixture's immediate-retry assumption;116 active
worker cases pass298.222s. Replacement full Verify41448616746 is ACTIVE at that exact
source; the prior41442782804 failure remains recorded. Prepared rollout scripts now
target replacement source/run, but no HRIS checkpoint/migration/deploy/grant yet.
Private Matrix/pre-enrollment recovery and immutable dashboard candidate are ready;
HRIS schema103/API-worker2c2a59e3/dashboard1e0522c0 still run. Continue after this port;
notifications/actions/replies, actual priority SDK and all54 PRD groups remain open.

## Release16 staged; compatible server rollout pending — 2 November 2026

[Build/evidence](measurements/android-release-phone-2026-11-02-build16.json): exact
cc4c11f1b538d48680ee892f6709925bbe5e18ec arm64 devRelease16 build and lint pass464.885s,
1,969 Gradle tasks (614 executed/1,355 up-to-date). APK87,494,648 bytes,
SHA256291fbe05606191876cec1dae8eb02c0b1872210a338d92e8b3a4f125f820c07c. Signature matches
installed certificate aca5ce6a…721f3, non-debuggable, exact Firebase dev registration and
private API verified in binary, fixture endpoints absent,16KiB alignment. Source clean
before/after. Read-only phone preflight confirms15, connected10.77.77.3:5555 and same
signer. NO INSTALL yet. Notify first, use explicitserial --install-only, never drive UI.
Prepared scripts/artifact are .work/release-phone-2026-11-02-build16; do not overwrite
that artifact with a different build. Recheck package before install if state changes.

Apple41441078558 at d93e8cd5 COMPLETE:112 core cases/30 suites, zero failures/errors/skips,
96 combined incoming-wave/receiver/lookup/queue source hashes verified. Swift11s,
simulator compile672s/tests150s, full Xcode1320s and associations1s all pass. Archived
XML/source hashes are in the build16 measurement. Current Apple41446374520 at EXACT
cc4c11f1 is ACTIVE and covers the subsequent priority-processing source. Do not cancel
or duplicate. Physical iPhone acceptance remains waived; full app compile required.

Platform isolated Synapse/PostgreSQL runs privately and pre-enrollment recovery now
passes with archived keys only. Full Verify41442782804 at2124c6fc is still active (four
dashboard/three backend shards pass, backend1/downstream pending). Compatible dashboard
built into immutable private assets. No HRIS migration/grant/deployment yet; schema103/
API-worker2c2a59e3/dashboard1e0522c0 still run. Continue guarded accepted packages/images,
fresh coordinated checkpoint, isolated103→116 upgrade and explicit API/worker/dashboard
rollout; then employee/operator collaboration.chat grants preserving role snapshots,
and authorized phone16 installation. Platform's operator plan/measurement/handoff
contain exact protected paths and state. All chat push flags remain off.

Continue native SDK priority proof and notification read/mute/privacy projection,
durable display/actions and encrypted inline replies. All54 groups remain open, no
Starter port or owned emulator. This staged delivery is not completion; keep working.

## Push priority recovery validated — 2 November 2026

[Contract](collaboration-priority-recovery.md) / [evidence](measurements/chat-priority-processing-2026-11-02.json):
The worker gives one encrypted hint a20s turn with fresh hinted company/device admission,
admitted room lookup and independently checked exact binding. It acquires one original
anchor, then verifies a retained original alias/tombstone under local owner/denial gates.
No directory version is invented from push revision; normal company/room/Browse/Incoming
seek remains independent. Runtime/timeline cleanup is joined, incoming15s/room12s unchanged.
An exact snapshot CAS retires only acquired facts or ineligible scheduling metadata.
Every unsuccessful acquisition retains priority, including expired native credentials.
Normal fair company recovery still runs and the global wake remains until both lanes finish.

Focused1 passed200.440s:642 domain/57 suites,361 data/47,104 app collaboration/13,
Android release/static. Inspection then found premature completion of Blocked results.
A new real DI/SQLite expired-credential regression failed as intended45.795s against
unchanged production (expected Blocked, actual Complete). Corrected unsuccessful-result
retention and added full-worker fairness coverage. Full3 passes344.646s on16 unchanged
Kotlin inputs:3,262 desktop485 suites,222 Android host36,981 affected browser101 and
10 build-logic2; zero failures/errors/skips. The measurement archives all three attempts,
source/report hashes and task statuses. Seven actual app-container/SQLite priority cases
cover no cached list/workspace, mismatched binding, confirmed denial, unresolved original,
capacity, newer revision, cancellation/reopen and expired local credentials. SDK is a double.

Apple41441078558 at exact d93e8cd5eef294d3488a7ccb7f64cac6222ed241 remains ACTIVE:
Swift extraction11s, simulator compile672s/tests150s passed; full Xcode in progress.
Do not cancel or dispatch another Apple run until complete. Audit final artifact counts
and source hashes, then dispatch this next pushed head. Current16 inputs are excluded.
Earlier receiver dd1a449b Apple41436278627 is completely audited in queue evidence below.

Platform source2124c6fc passes both Collaboration control jobs41437984098. Full ten-job
Verify41442782804 is now active at that exact source for packages/recovery/private rollout.
Do not infer success or cancel it. Private rollout preparation .work/chat-private-rollout-
2026-11-02/plan.txt in Platform is READ ONLY so far: persistent internal Matrix/PostgreSQL,
protected JWT/config overlay, fresh coordinated checkpoint and isolated103→116 upgrade
before live changes. Existing Google/native/document state must be retained. Chat push stays
off until read/mute/privacy notification projection and durable actions/inline replies exist.
Continue that implementation and native SDK priority proof alongside rollout preparation.

Private schema103/API-worker2c2a59e3, phone15 and Starter5679c51 unchanged. Next authorized
phone build16+ needs notice first, explicit10.77.77.3:5555 and --install-only, no UI drive.
All54 groups remain open. No owned emulator, active goal or subagent. Continue after push;
this is not PRD completion.

## Encrypted push priority queue validated — 2 November 2026

[Contract](collaboration-push-priority.md) / [evidence](measurements/chat-push-priority-2026-11-02.json):
Validated envelopes now retain bounded room/event scheduling hints in a16-entry
account/native-family queue. Shared protected history key, distinct AEAD/partition,
24,000 plaintext/32,768 encoded limits; no generic storage bound enlargement. Local
session remains transactional ownership and native family binds ciphertext. Newer
company/device revisions coalesce in place; expired/future signals still request the
full sweep. Independent global wake precedes optional750ms/three-CAS priority work,
then fixed1.5s scheduling inside the3s receiver. Priority failure does not suppress
full recovery. Reads/completion guard local ownership independently of credential age;
exact snapshot CAS and fresh empty generations prevent stale acknowledgment.

Initial focused1 fails only a cross-module nullable-property smart cast in the app
integration test,127.280s. All631 domain/56 suites and361 data/47 already pass. Test
assertion corrected with requireNotNull; production unchanged. Focused2 passes the
same domain/data reports plus15 app push/6 suites and Android/static105.705s. Full3
root/Android release/affected browsers/app Wasm passes327.155s on20 unchanged Kotlin
inputs:3,240 desktop483 suites,222 Android host36,970 browser100,10 build-logic2;
zero failures/errors/skips. Task status map distinguishes executed and accepted
UP-TO-DATE reports. Ten SQLite/IndexedDB cases include maximum escaped payload,
reopen, same-family renewal, separate families, tampering, lost key, concurrency,
late account change and cancellation;20 new domain cases cover policy/access/budgets.
Real app DI/SQLite receives without selection/SDK, reopens, replaces revision and
rejects an older snapshot's completion.

Apple41436278627 atdd1a449b9a00a7323d453a48584b179c8e007416 is COMPLETE and audited:
112 core cases/30 suites, ten standalone Swift cases8s, simulator compilation431s/
tests77s, full Xcode947s and associations1s. All68 combined incoming-wave/receiver
source hashes match that exact commit. It excludes room lookup71c7f80c and this queue.
No active Apple run now: dispatch the next exact pushed head, audit its final artifacts.
Physical iPhone acceptance remains waived; core Apple tasks exclude collaboration/app
desktop tests.

Next .work/chat-priority-processing-2026-11-02 contains UNAPPLIED .txt candidates:
fresh hinted company/device admission without inventing directory versions;
ReadCollaborationHistoryEvent for exact retained original alias/tombstone under local
gates; incoming priority shares runtime/timeline/20s parent-priority budget, checks
actual room binding and never advances normal company/room/history seek. Main worker
must still give fair recovery its turn and retain the global wake for pending priority.
Candidates need application review, DI/tests, focused/root/browser checks and push.
Then implement read/mute/privacy notification projection, durable actions/inline reply,
compatible private backend/Matrix rollout and authorized release16+ installation.

Private schema103/API-worker2c2a59e3 and phone15 unchanged. Chat push stays disabled.
Notify before phone install; explicit10.77.77.3:5555, --install-only, no UI driving.
All54 groups remain open. No Starter port, owned emulator, active goal or subagent.
Continue immediately after push; this is not PRD completion.

## Owned room lookup validated — 2 November 2026

[Contract](collaboration-room-lookup.md) / [evidence](measurements/chat-room-lookup-2026-11-02.json):
RefreshCollaborationList.byRoom resolves an admitted direct summary from an opaque
room hint without directory scan, workspace selection, room creation or SDK startup.
It shares current scope, publication mutex, encrypted list merge and retained-denial
gates. Native transport has one encoded roomId query parameter and 4,096-byte response
bound; repository validates request/company/participants. Platform companion
`2124c6fc` is pushed and passes current native/RLS/HTTP/worker checks with no migration.

Focused check1 passes611 domain/53 suites,351 data/46 and one real app-container
lookup/reopen case, plus Android release/static checks,179.945s. Full root/release/
collaboration browsers/app Wasm passes310.687s on the same15 Kotlin inputs:3,210
desktop479 suites,222 Android host36,940 browser96 and10 build-logic2, zero failures/
errors/skips. The measurement distinguishes newly executed tasks from valid Gradle
UP-TO-DATE reports; domain/data desktop were already executed by focused1. No source
correction or failed check in this increment.

Apple41436278627 at exact pushed receiver headdd1a449b9a00a7323d453a48584b179c8e007416
is ACTIVE. Ten standalone Swift extraction cases pass8s; simulator compilation431s
and tests77s pass; full Xcode app is in progress. Do not cancel or dispatch another
same-scope run until completion. Audit final XML/artifact/source hashes. This run
excludes the current room lookup sources. Older41433354322 failed before runner
allocation, as documented below, not application code.

Next: apply the bounded encrypted push-priority queue, current hinted company/device
admission and independently checked room binding, then privacy/read/mute projection,
durable notification actions and encrypted inline replies. Candidate .txt files and
concrete design are supplementary in .work/chat-push-priority-2026-11-02; none has yet
been applied or tested. Keep full fair sweeps and generation fencing. Private schema103/
API-worker2c2a59e3 and installed phone15 remain unchanged; compatible rollout plus
release16+ can expose validated foreground/offline chat while chat push stays off.
Notify before install, explicit10.77.77.3:5555 and --install-only. All54 groups remain
open. No Starter port, owned emulator, active goal or subagent. Continue after push.

## Native push receive routing validated — 2 November 2026

[Contract](collaboration-push-receive.md) / [evidence](measurements/chat-push-receive-2026-11-02.json):
Android data-only and Apple remote callbacks share ApplicationPushReceiver. Chat's
strict10/12-field parser accepts canonical positive Long revisions as strings;
old valid hints can request recovery. Local account/audience/native-family ownership
is independent of credential age. QueueCollaborationPushSignal commits a fresh
durable global wake before fixed scheduling, guards late identity changes/cancellation,
and never reads workspace, starts SDK, acquires content or displays packet data.
Inbox routing remains separate. Apple caller owns cancellation/completion and25s
processing budget; receive3s/schedule1.5s. Real app DI/SQLite checks no-selection/no-SDK
and fresh wake persistence across container reopen, including stale-ACK rejection.

Focused1 passes601 domain/52 suites and15 app push/6 suites plus Android/static checks
216.813s. Review found a too-small JS-safe-integer revision cap; corrected to positive
Long and added producer Long.MAX_VALUE-1/maximum-envelope case. Full2 passes3,195
desktop476 suites,217 Android host35,602 domain browser52,10 build-logic2 plus root/
Android release/Wasm in407.797s. Its app-browser filter selected zero actual cases;
one skipped task placeholder retained. Unfiltered browser3 passes 26/7 suites,
including six new receiver cases, in34.754s on the same18 inputs. No actual final
case failed/errored/skipped. Source/report hashes and both corrections are recorded.

Prior incoming-wave Apple41433354322 at8972dc962833526a4b0d3b67c376fc9ce04496a6 failed
before runner allocation after15min queued. Annotation: hosted runner not acquired;
macOS arm64 capacity constrained. No test ran. New workflow adds ten standalone Swift
extraction cases before full app compile. Dispatch the new exact head after push;
there is no active Apple run now. Previous cacae157 audit remains valid for that source.

Platform companion room-lookup work is IN PROGRESS in .work/chat-room-lookup-2026-11-02:
summary-by-room uses current native admission and existing participant-owned binding,
no directory scan/migration. Five inputs. Check1 failed test compilation only (private
fixture DSL); fixed with fixture-owned roomScopes factory. Check2 running; inspect
its saved check2 log/JSON before any further heavy local check. Shared DI changed, so
API HTTP and :apps:worker:test are required in that command. Do not claim it accepted.

Continue bounded encrypted push priority hints and current-admission room resolution,
read/mute/privacy notification projection, durable actions and encrypted replies.
Global full sweep alone is not notification latency/scale acceptance. Consider compatible
private backend/Matrix rollout plus release16+ to expose validated chat progress while
keeping chat push off until usable. Notice before phone install; explicit serial and
--install-only. Phone15/private schema103/API-worker2c2a59e3 remain unchanged.
All54 PRD groups remain open. No Starter port, owned emulator, goal or subagent.
Do not stop at this checkpoint.


## Bounded incoming waves and real Android catchup validated — 2 November 2026

[Contract](collaboration-incoming-history.md) / [evidence](measurements/chat-incoming-history-waves-2026-11-02.json):
incoming recovery now retains provider `from`/`to` boundaries, a completed wave head,
and a fair fixed-high-water pending-reference pass. Browse retains its exact old
partition/AAD/schema. Incoming schema2 canonically encodes opaque ASCII positions
inside AEAD, preserving the existing32,768-character database checkpoint cap. Original
facts remain shared, and seek advancement never acknowledges unresolved plaintext.
Room12s/lane15s budgets, owned/captured access and joined resource cleanup remain.

Focused5 passes584 domain,346 data and94 selected app cases plus Android release/
proof Kotlin/static checks120.551s on47 inputs. Full7 passes425s Gradle /428.941s wall
on51 inputs:3,169 desktop472 suites,217 Android host35,908 collaboration browser92,
6 proof host1,10 build-logic2; zero failures/errors/skips. Earlier failures retain two
compiler fixes, worst-case JSON checkpoint overflow/canonical encoding correction,
two renamed fixture references and one proof-method detekt extraction. Subsequent
changes are confined to three proof files: APK/host/static8 passes96.152s on52 inputs,
6 host cases. Production inputs remain those accepted by full7; no second root run
on altered proof-only code is claimed.

Real SDK/HRIS/PostgreSQL/Synapse native runs2+3 pass all3 variants on the SAME APK
cb5830040a023fbf4a2ab040800f120521b0286da1d4949f3448966d7b525e50 (343,275,325 bytes),
with52 Mobile and7 Platform inputs unchanged. CANCELLED_DRAFT passes394.103s;
QUEUED/FROZEN_DRAFT pass671.255s together. Each recovers175 peer originals plus the
prior outgoing original, exhausts186 references in11 turns and survives11 container
reopens. Outbox empty, no workspace selection, fresh native admission, Browse still
untouched and runtime released are asserted each turn. Foreground ViewModel completes
11 independent Browse pages;10 state references remain. Server verifies all ciphertext
and exactly one outgoing worker send. Cleanup succeeds for every final case.

Native1 preserves3/5 failures in716.614s: SDK death/actual OS scheduling regressions
pass; cancelled/queued expanded cases hit old210s instrumentation timeout; frozen
fails before installation on an empty AVD reply/IndexError. New bounded phase/turn
counters show successful complete fixture times245.182–260.457s, including175 queue
pause checks and container reopen work. Worker-resume fixture allows420s inside480s
Python/10min JUnit bounds; production deadlines unchanged. Empty AVD replies now fail
as controlled RuntimeError before device/artifact mutation, with no fallback/retry.
84 driver cases pass0.363s. Owned191463 unexpectedly exited; cause not established
and no kernel termination record found. Later owned225320 and240903 stopped1.711s
and1.355s. No emulator remains owned. Unrelated5560/phone15 untouched.

Apple41421351125 at cacae157a83c0653428156fc47e53e97bbe1db9d is COMPLETE/audited:
112 core cases30 suites,6 wake cases0.070s, compile452s/tests82s/Xcode861s/associations
0s;16 source hashes verified. It excludes these new waves. Dispatch the next exact
committed head after push. Core CI excludes app desktop cases; physical iPhone waived.
Platform companion proof/evidence is in the same-date measurement file in that repo.
Publish both atomic increments before continuing. No generic Starter port is due.

**Continue immediately:** FCM receive routing, bounded authenticated priority hints
plus fair full sweeps, notification privacy/read/mute/actions and durable encrypted
replies; compatible private rollout and release16+ installation with notice and
`--install-only`. Android currently sends every data-only push to the inbox parser;
iOS also extracts only inbox fields. New receiver must record a fresh domain recovery
wake before scheduling, preserving an in-flight sweep's generation fencing. No packet
company/room is authority. Local receive ownership must not depend on credential age.
`.work/chat-push-receiver-2026-11-02` has unexecuted .txt candidates/test notes only.
Fixed all-company wake alone is not latency/scale or notification acceptance.

All54 PRD groups remain open. Last verified private schema103/API-worker2c2a59e3,
branch116, disabled chat push and phone15 remain. No rollout/phone operation here.
No active goal or subagents. Do not stop after this checkpoint.

## Incoming traversal isolation and concurrent key creation validated — 2 November 2026

[Contract](collaboration-incoming-history.md) / [evidence](measurements/chat-incoming-history-purpose-2026-11-02.json)
add explicit Browse/Incoming purposes to provider acquisition and encrypted traversal.
Browse retains its exact partition/AAD/schema; Incoming has separate authenticated
payload/index context and seek/reference state. Original content remains shared.
Existing captured reference operations carry the key unchanged. No worker catchup
caller is added yet. Four domain, four cross-purpose storage and eleven inherited
incoming storage cases cover admission, cancellation, legacy bytes, transplant
rejection, interleaved restart/resolution, independent writes and all existing
CAS/key-loss/account/reopen/bounded-query behaviors on SQLite and IndexedDB.

The first full check failed one existing concurrent company-progress case among336
data cases in244.747s; all15 new storage cases passed. That run did not reach new
domain/Android/browser tasks (retained567 domain XML is not new execution evidence).
Inspection found the shared history-key guard can observe no key, then a different
repository's newly committed ciphertext. A deterministic barrier test reproduces
Failed instead of CAS loser Success(null) before the correction (39.46s).
The original random failure did not retain its technical cause; the new scenario
explicitly establishes the guard race. The guard now rereads a retained key once;
missing/malformed credentials still fail without creating keys over old records.
Final full root/Android release/affected browser passes333s /336.845s with16
unchanged Kotlin inputs:3,144 desktop468 suites,216 host35,886 browser89 and10
build-logic2; zero failures/errors/skips. Both failed attempts remain in evidence.

Apple [41414832120](https://github.com/fajarxfce/hris-mobile/actions/runs/41414832120)
at3062bff25c678639204bcf4a5e2e95be85c155d4 is COMPLETE and audited:112 core cases/30
suites, six wake cases0.124s, compile610s/tests123s/Xcode1323s/associations1s,12 source
hashes verified. It excludes later ee888802 and this isolation/key change; core tests
do not execute app worker desktop cases. Dispatch the next exact committed head now.

Platform [04b0b6c8](https://github.com/fajarxfce/hris-platform/commit/04b0b6c8) is pushed:
actual incoming provider/empty-outbox/no-selection proof and all81 driver cases.
A NEW UNCOMMITTED MatrixRoomHistoryInteropTest extension is running locally in
`.work/incoming-history-boundary-2026-11-02` (one heavy local check at a time).
The gateway already supports encrypted `from` AND `to` positions, bound to company/
account/device/server/room. Prefer these provider boundaries over overlap heuristics.
The new test attempts47 additional metadata events, five more during partial paging,
adapter recreation, exact wave separation and an empty caught-up wave. It is not yet
accepted. It uses synthetic encrypted payloads, not SDK plaintext/E2EE acceptance.

**Continue immediately:** verify that provider boundary test, add optional untilPosition
through Mobile's API/runtime/page contracts, and persist each incoming wave's first
start, previous completed boundary, next seek and fair pending-reference cursor.
Do not reset unfinished scans or Browse on each wake. Original/tombstone reconciliation
remains distinct; state-only/missing-key references cannot starve later work. Native
SDK caps16 retained focuses per client, so respect foreground/borrowed ownership when
recovering capacity. Then prove an actual incoming gap beyond128, FCM privacy/actions/
encrypted replies and compatible private server rollout. Do not stop after this push.
All54 groups remain open; phone15, private schema103/API-worker2c2a59e3, branch116 and
chat push disabled remain. No Starter port is due for these HRIS-specific changes.
No emulator or user phone operation by this increment; next employee release16+.

## Actual incoming SDK/provider proof passed; generic Matrix retry corrected — 2 November 2026

[Evidence](measurements/chat-incoming-provider-2026-11-02.json) records five real
Android/HRIS/PostgreSQL/Synapse scenarios in384.631s, zero failures/errors/skips,
plus Platform format/architecture. In QUEUED/FROZEN_DRAFT/CANCELLED_DRAFT cases the
sender runtime closes before32 encrypted peer seeds. Fresh real native directory,
admission and one summary page then drive the actual incoming worker with an empty
outbox and a workspace repository that rejects selection reads/observers. Each takes
one pass;12 originals (including the prior outgoing original) remain after container
reopen, and the final borrowed runtime is released. Foreground paging then verifies
all32 peer originals plus the outgoing original. SDK process-death and actual scheduled
offline/ordinary-death regressions also pass. Incoming itself is explicitly invoked;
this is not incoming FCM/Doze/terminated-app or all-company provider acceptance.

Actual first native run failed4/5 in356.106s. Raw held-room join received503 provider_busy;
fixture now bounds idempotent M_UNKNOWN retries to3 with1s gaps. Three incoming cases
reached foreground, then Unavailable/Unclassified: inspection found fixture did not
republish foreground retention after reopening a background-only host. Activate now
publishes that seeded decision; a validated incoming checkpoint is retained separately.
First diagnostics lack the exact failure code/final incoming metrics; do not invent them.
Production mapper now classifies Matrix M_UNKNOWN as Service/retryable so transient
server failures cannot masquerade as user-action conditions. Explicit denials and
unrecognized payload sanitization remain. One new shared mapper case passes.

Initial root/proof check1 passed75.3s on4 proof inputs. Final root/proof/Android release/
affected data browser check2 passes297s /301.21s on7 inputs:3,124 desktop465 suites,
216 Android host35,6 proof host1,299 data browser39,10 build-logic2; zero failures/
errors/skips. Platform81 Python driver tests pass0.167s;4 native inputs frozen.
Exact logs/reports/source hashes and APK5f17598f (343,275,325 bytes) bind both runs.
All native driver cleanup succeeds. Owned5562/PID99409 stopped1.003s; prior79619
stopped1.404s. Unrelated5560 and phone15 untouched. No generic Starter port is due.

[Apple41414832120](https://github.com/fajarxfce/hris-mobile/actions/runs/41414832120)
is ACTIVE at3062bff25c678639204bcf4a5e2e95be85c155d4: simulator tests passed, Xcode
building. It covers the incoming app coordinator but excludes these later proof/mapper
changes. Do not dispatch a cancelling run. Audit XML/source/full Xcode on completion,
then dispatch the next implementation head. Core Apple tests exclude app desktop cases.

**Continue immediately:** independent durable recent-history traversal/frontier and
pending-reference progress, then FCM privacy/actions/encrypted replies and compatible
backend rollout. Latest window alone is not a long-offline catchup. Do not reset the
foreground Older traversal on every wake. Existing Read/ReconcileHistoryReferences
accept captured scope; native readProviderHistory pages20 and original windows128.
Review separate traversal purpose/partition/AAD with legacy Browse unchanged, durable
scan bounds/overlap and pending-reference fairness before wiring incoming. Preserve
missing-key content and distinguish metadata coverage from decrypted originals/receipts.
A prototype discussion is local only, not implementation. All54 PRD groups remain open.
Phone15/private schema103/API-worker2c2a59e3/branch116/push disabled remain; next employee
release16+ requires compatible server rollout and validated feature behavior. Continue.

## Incoming worker and fair company turns validated — 2 November 2026

[Contract](collaboration-incoming-recovery.md) / [evidence](measurements/chat-incoming-worker-2026-11-02.json):
actual app binding now runs incoming before outgoing with separate15-second lanes
and the same original admission. Root quantum is one company,50s including admission,
within90s processing/180s wait+processing. Incoming checks fresh setup, seeks one room,
persists Attempt before external work, acquires an owned latest original window with
no outbox/selection, and closes timelines/runtime/monitors on completion or cancellation.
Room retry/continuation survives restart; provisioning never creates rooms implicitly.

Fourteen actual DI/domain/SQLite app cases plus seven virtual-time coordinator cases
cover no-outbox restart, both lanes together, failure fairness, empty/provisioning,
readmission, contention, deadlines, cancellation, late runtime/account/denial and joined
cleanup. HTTP/SDK are fictional here. Focused2 passes567 domain,320 data,91 app plus
Android release Kotlin/proof APK/static97s /101.311s on12 unchanged Kotlin inputs.
Focused1 failed two incorrect null-position assertions; three assertions now expect
the intentionally retained empty generation-bearing position. Production unchanged.

Actual WorkManager/domain/Room/Keystore regression passes all four scenarios46.417s:
burst3.502s,stage0.876s,resume31.545s,backlog1.700s. New one-company quantum covers
21 healthy callbacks in21 OS completions with zero Retry; uncertainty still backs off.
Twelve changed inputs plus three unchanged committed proof sources and APKd026d7ff
are bound in evidence. SDK/directory/admission/company callbacks remain synthetic.
Proof process and owned5562/PID65117 stopped (1.404s); phone15/unrelated5560 untouched.
Full3 root, Android/proof, affected browser/app-Wasm passes184s /188.013s:
3,123 desktop,215 host,865 browser,10 build-logic; zero failures/errors/skips.

Apple41409790492 at5a39b53f is complete/audited:112 core cases/30 suites, six wake
cases0.083s, compile672s/tests74s/Xcode1269s/associations1s. Ten exact source hashes
and XML verified. It excludes later room af5cb4dc and this incoming increment; dispatch
new exact-head Apple after push, without cancelling an active run. Core Apple does
not execute these app desktop cases. Physical iPhone testing remains waived.

**Continue immediately:** prove incoming via actual SDK/HRIS/Synapse independently of
outbox/selected workspace, then independent durable recent catchup, opaque FCM hints,
notification privacy/actions and encrypted replies. Current latest window<=128 does
NOT cover a long offline gap and must not reset foreground Older traversal. Existing
HrisWorkerMatrixProbe seeds32 encrypted peer events before foreground acquisition;
close the sender app/runtime before seeding and prove captured incoming restoration,
local history across reopen, and original identity before foreground paging. Use fresh
real recovery directory/admission, not fabricated content grants. Keep seeded login and
provider binding limitations explicit. Current Platform drivers are being updated to
accept HRIS_COLLAB_NATIVE_SERIAL=emulator-5562 while checking the exact HRIS AVD.
Never use unrelated5560. Preserve all54 PRD groups. Backend private103/branch116 and
chat push disabled remain; compatible rollout required before employee release.
No generic Starter port is due. Continue after every commit/push.

## Room recovery and captured reference scope validated — 2 November 2026

[Contract](collaboration-room-recovery.md) / [evidence](measurements/chat-room-recovery-2026-11-02.json):
account/company-owned room progress now retains seek, unfinished attempt and independent
retry/continuation flags. Attempt persists before external work; Complete preserves
prior outcomes; validated-directory Finish atomically resets and returns Covered,
Continue or Retry. Fresh generations fence competing writers and repeated positions.
Protected history keys, bounded authenticated payloads and account fences are shared
with existing storage. Age/reauthentication retain facts; known denial, cancellation
and late account replacement cannot publish false coverage. No incoming caller yet.

Read/ReconcileCollaborationHistoryReferences now accept Captured local scope through
their existing implementation. String overloads still delegate to Selected. All raw
reference/fact/resolution calls carry the captured account/company, even with no or a
different selected workspace. Provider browse position is unchanged. Exact original/
tombstone matching, bounded metadata, denial priority, late-write facts and subscription
cleanup remain. Already-cancelled entry stops before admission/reference storage.

Room-only focused1 passes559 domain,320 data,two actual DI cases plus Android release/
proof Kotlin and static checks154s /157.623s on13 unchanged inputs. Then scope support
added two use-case overloads, fixture access tracking and eight regressions. Final17-
input full2 root, affected browser, Android release/proof Kotlin and app-Wasm passes
371s /374.686s:3,102 desktop,215 Android host,865 affected browser,10 build-logic;
zero failures/errors/skips. New cases:12 room domain,9 shared storage,8 captured scope.
Both report/source boundaries are recorded; no failed check occurred in this increment.
SQLite/IndexedDB are real; no new WorkManager/SDK/HRIS/FCM proof is implied here.

**Continue immediately:** app-owned incoming coordinator using durable room progress,
existing Background LoadSetup/RefreshList/AcquireRuntime/Enter/AcquireHistory and
monitorCollaborationBackgroundDelivery. It must acquire incoming content even when
there is no outbox, release each timeline/runtime, preserve the native-parent monitor,
never switch workspace, and keep outgoing and incoming finite/fair. Current outer
worker includes admission in its30s company budget and pages two companies in90s;
do not give two independent30s lanes that starve the second. Review/test an explicit
combined budget or one-company quantum before wiring both lanes. Existing synthetic
native proof and app worker tests must be updated and rerun if those limits change.

RefreshList server pagination is stable UUID ascending. Persist Attempt before each
room operation so one failure/interruption cannot permanently exclude later rooms.
A latest original window is bounded128 events and is NOT full long-offline recovery.
Incoming recent catchup needs its own durable provider traversal; do not repeatedly
reset the foreground Older cursor with Latest. Existing AcquireProviderHistory uses
that shared browse cursor. Opaque FCM priority hints may reduce scanning latency but
never grant content access; collapse recovery still must cover all admitted companies.
Then actual provider/captured incoming, FCM/privacy/actions/encrypted replies, compatible
server rollout and employee release. Preserve all remaining P0-P8/54 groups.

Apple41409790492 is active at5a39b53f0ece41d314738090e330bd7004e528db; do not cancel it
with another dispatch. It excludes this room increment. Prior41405822268 is audited
112 core cases/full Xcode at338fea17. No new employee install: phone15 remains;
private Platform schema103/API-worker2c2a59e3 and disabled chat push remain. Branch116
is not live. Owned5562/proof process stopped; preserve unrelated5560. No generic
Starter port is due for this HRIS policy. Old seven .txt candidates are obsolete;
source is authoritative. Continue after each push while hosted checks execute.

## Healthy company backlog fixed and proved — 2 November 2026

[Evidence](measurements/chat-company-continuation-2026-11-02.json): successful bounded
company batches now persist continuation separately from failure/uncertain attempts.
At directory end, durable restart precedes same-wake scheduling; healthy backlog
avoids OS failure backoff, while real failures still require it. Payload schema2
reads schema1 without losing its wake/seek/failure; unsupported schemas cannot clear
progress. Two new domain, three shared storage and four app cases cover the change.

Focused2 passes547 domain,311 data,22 worker/DI plus Android release Kotlin/proof APK
and static checks in137s /140.844s. Focused1 had two invalid raw migration fixtures
that reused a generation; corrected fixture uses fresh IDs and matching AAD, with
production unchanged. Native1 passed burst/stage but failed resumed callback order.
Inspection found fictional admission used two clock reads and could exceed60s;
that invalid fixture was corrected to one instant, with bounded diagnostics added.
The failed run did not retain skipped callback IDs, so do not invent them.

Final native build3/static passes68s /71.904s. Native2 passes all four scenarios in
37.916s: burst2.238s,stage0.830s,resume31.340s,healthy backlog1.593s. Three healthy
batches cover all seven companies in order (21 callbacks/12 task completions) with
zero Retry results; interrupted work retains its real backoff. Actual WorkManager,
consumer/domain/Room/Keystore are used with synthetic directory/admission/company
callbacks. No SDK/provider/FCM acceptance. Proof process and owned emulator5562/PID15507
are stopped; phone15/employee package and unrelated5560 untouched.

Final full4 root/affected browser/app-Wasm passes292s /295.868s with10 unchanged
inputs:3,073 desktop,215 Android host,836 affected browser,10 build-logic; zero
failures/errors/skips. Exact reports/source/APK hashes and failed attempts are
retained. No new employee APK/live server activation is implied. HRIS policy stays
in Mobile; no generic Starter port is due.

Apple41405822268 at338fea17b5738698f45c824cd1d6a06142050946 is NOW COMPLETE/AUDITED:
112 cases/30 suites, six wake cases0.122s, compile608s/tests67s/Xcode1303s/associations1s.
Fourteen source hashes and all XML match. Worker desktop tests are not in its core
simulator suite; it excludes this later continuation correction. No hosted run is
active now; dispatch a new exact implementation head after pushing this increment.

**Continue immediately:** durable per-company room recovery, then incoming acquisition
independent of outgoing backlog, actual provider and opaque FCM/notification/reply.
Seven UNAPPLIED candidates exist under `.work/incoming-recovery-2026-11-02/`:
entities/policy/contract/read/update/codec/repository.txt. They are a draft, not tested.
Review, add domain/storage/DI tests and apply only after this increment is committed.
The proposed row uses account/company ownership, generation CAS, saved room seek,
in-flight attempt and independent retry/continuation flags. Attempt precedes external
work; Complete preserves flags; Finish requires validated directory end and resets
only after a durable CAS. Ordinary expiry/reauth retain local facts, known denial and
replacement account gate access. Do not reuse/reset foreground Older history position
for every background wake; long offline gaps need independent catchup progress.
Read/ReconcileCollaborationHistoryReferences still lack Captured local overloads.
Current callbacks still invoke outgoing delivery only. Backend schema116 remains
undeployed (private103); retain disabled chat push through actual receive acceptance.
All54 PRD groups remain open. Continue after every push, including while CI executes.

## Native all-company consumer proof passed — 2 November 2026

[Evidence](measurements/android-company-recovery-2026-11-02.json) binds17 unchanged
source/test inputs and proof APK26f8c50c. Build3 APK/static passes67s /70.996s.
Actual WorkManager/worker/domain/Room/Keystore scenarios pass35.839s: main1.905s,
stage0.862s, resume31.338s. Forty rejected schedules retain the newest wake; max2
OS tasks and1 company processor; two complete company sweeps ACK their own wakes
without healthy company-page retries. After process death, later companies advance
before an uncertain first attempt is retried on the original wake. All proof jobs,
observers, database/key owners, proof process and owned emulator5562 are stopped.
Phone/employee package and unrelated emulator5560 were untouched.

Preserved failures: build1 Detekt LongMethod, fixed in fixture only; native1 asserted
successful ACK before worker completion while pending=false was already persisted.
Build3 moves that assertion after quiescence and adds bounded diagnostics. Production
consumer338fea17 is unchanged. Synthetic directory/admission/company callbacks do
not establish SDK, HRIS or FCM acceptance. Scratch .txt candidates are obsolete.

**Continue now:** distinguish healthy per-company `SyncResult.Retry(null)` backlog
from failures/uncertain attempts in durable company progress. Current consumer adds
backoff to both, although SyncResult null explicitly means successful bounded work.
Use a separate continuation flag, preserve real failure priority across pages and
restarts, test storage migration and actual WorkManager healthy multiple batches.
Then implement independent incoming room discovery/acquisition with durable fair
room progress, FCM, privacy/actions and encrypted replies. Do not stop at this proof.

[Apple CI41405822268](https://github.com/fajarxfce/hris-mobile/actions/runs/41405822268)
at338fea17b5738698f45c824cd1d6a06142050946 remains active: simulator tests passed,
Xcode app building. Do not dispatch a cancelling same-branch run. Audit downloaded
XML/source hashes and complete Xcode; hosted core tests do not run worker desktop
cases. Phone15/private backend/push-disabled state unchanged; all54 PRD groups open.
Starter5679c51 generic port complete; this HRIS fixture requires no Starter port.

## All-company consumer registered and locally validated — 2 November 2026

[Contract](collaboration-company-worker.md) / [evidence](measurements/chat-company-worker-2026-11-02.json):
the fresh recovery key now uses bounded Android scheduling and a singleton app
worker. One processor/one resource-free waiter capture current processed-parent
ownership; parent replacement cancels/joins old work, while selected-company changes
do not. Startup persists a wake for a changed native parent; same-parent renewal
preserves it. Periodic execution can create an absent hint without recursive enqueue.
Each bounded page covers at most two companies with durable Attempt/Complete/Finish,
fresh admission and exact old/new wake acknowledgment. Healthy pagination schedules
same-generation continuation instead of deliberately increasing platform backoff.

The current company callback is the existing captured outgoing delivery worker.
It still exits with no outgoing work. Independent incoming discovery/acquisition,
fair room progress, FCM, notification privacy/actions and encrypted replies remain.
No backend activation, employee APK or provider acceptance is implied by registration.

Focused validation passes545 domain and36 application cases plus release/proof
Android Kotlin/static checks in148s /152.151s on13 unchanged inputs. Full2 found
one old startup-test expectation among278 app cases (172s /176.151s): it awaited
any one request and expected only selected delivery. The corrected test awaits that
selected key, checks exactly recovery plus delivery keys, verifies a durable wake
and zero SDK deliveries/background acquisitions. Production source did not change.
Full3 root/affected browser/app-Wasm now passes in305s /309.378s on14 unchanged
inputs:3,064 desktop,215 Android host,831 browser and10 build-logic, zero failures,
errors or skips. New coverage includes16 worker,one lifetime and three domain cases.
Failed and successful report/source hashes remain in the measurement.

**Continue immediately with native proof.** Two UNAPPLIED fixture candidates are in
`.work/company-worker-native-2026-11-02/CompanyRecoveryProofOwner.txt` and
`CompanyRecoveryProbe.txt`. They use the actual app worker, domain operations,
AndroidWorkScheduler/WorkManager, encrypted Room progress and Android Keystore with
fictional directory/admission/company callbacks. Add three instrumentation branches:
company-recovery, company-recovery-stage and company-recovery-resume. Build the proof
APK/static checks, freeze source/artifact hashes, then run on a verified owned
emulator only. Main checks two OS tasks/one company processor,40 failed enqueues
retaining newer wakes, healthy continuation and exact-generation completion. Stage
persists an unfinished first-company attempt; force-stop before Resume, then verify
later companies advance before retry and the original wake is acknowledged. This
is consumer/OS/storage proof, not SDK/HRIS/FCM acceptance. Review candidates before
applying; their cleanup closes admission before draining actual workers.

Next run actual SDK/captured delivery and incoming receive independently, then
notification/reply work. Use current directory/admission; do not change the selected
workspace. Physical iPhone was waived, implementation/hosted Apple checks were not.
No new Apple run is dispatched for this worker yet. Starter5679c51 generic port is
complete (92 Apple cases/26 suites at136d2e5a). HRIS policy stays in Mobile.

Phone remains15. Private schema103/API-worker2c2a59e3 remain, push disabled, all54
PRD groups open. Owned5562 remains stopped;5560 is unrelated CodePush. Recheck all
emulator ownership before reuse. Temporary8GiB swap was safely disabled/removed
in47.997s after local builds finished; original /swap/swapfile was preserved.
Continue after each push; do not stop at this checkpoint.

## Account-owned company recovery progress validated — 2 November 2026

[Contract](collaboration-company-recovery.md) / [evidence](measurements/chat-company-recovery-2026-11-02.json):
account-scoped encrypted seek/in-flight/retry state now survives restart and same-
account reauthentication. New wakes do not reset an active finite sweep. Attempt
persists before external work; uncertain attempts and transient failures require
another sweep. A clean Finish stores inactive progress before returning only the
original wake for separate exact-generation acknowledgment. Late account changes,
competing writes, tampering and lost protected keys cannot publish a false finish.

The first focused run failed test compilation (IdentityServer fixture omitted
required deviceName, 40s /43.528s); no production correction was needed. Corrected
focused checks pass 542 domain,308 data,two assembled-DI cases plus Android proof
compilation/static in152s /155.704s. Full root/affected browser/app-Wasm checks
pass in296s /299.533s on all13 unchanged source/test inputs:3,044 desktop,215
Android host,828 browser and10 build-logic, zero failures/errors/skips. Twelve new
domain and eight storage cases cover SQLite/IndexedDB reopen and cancellation.
No all-company consumer or FCM entry caller is wired by this increment.

**Continue immediately:** implement the app-owned company worker and its actual
entry. Use processed parent state, current directory/admission and captured work;
never change selected workspace. RefreshCollaborationList already accepts Background.
The old DeliveryWorker exits when no outgoing work exists, so independent incoming
room discovery/history acquisition is still necessary before notification acceptance.
Keep fair room progress and actual provider proof separate from company coverage.

Two OS jobs can run. Admit at most one processor and one resource-free waiter;
capture authority after acquiring the processing mutex. Track waiting separately
from admitted count to avoid mistaking an exiting task for future work. Cancel/join
on parent replacement; workspace changes do not replace a native parent. Healthy
pagination should schedule a same-generation continuation and complete, rather than
building exponential backoff. Waiting/backoff/finishing races require tests and
actual Android proof. Current supplementary design is in
`.work/company-worker-2026-11-02/design.txt`; no next-increment source is applied yet.
Do not revive the old no-wait consumer proposal from historical scratch notes.

Starter [5679c51](https://github.com/fajarxfce/compose-fluent-starter/commit/5679c51)
records independent Apple41400133440 at exact136d2e5a:92 cases/26 suites, six wake
cases0.178s, full Xcode586s, zero failures/errors/skips. That port is complete;
this HRIS policy stays in Mobile. No new employee APK/server rollout: phone15,
private schema103/API-worker2c2a59e3, push disabled, all54 PRD groups open.
Owned5562 remains stopped;5560 is unrelated CodePush. Temporary8GiB swap remains
active pending safe headroom. Continue after each commit/push.

## Starter port pushed and hosted validation running — 2 November 2026

Mobile e00ab4ee is pushed. Starter [136d2e5a](https://github.com/fajarxfce/compose-fluent-starter/commit/136d2e5a607fb20b6f5ccd07bd88ed14b0a6b668)
now contains three identical generic scheduler/test files and the previously
Mobile-proven Apple wake fixture. Independent checks pass356 desktop,13 worker-
browser,six build-logic,Android/lint/Web in148s /152.12s. Its [Apple CI41400133440](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/41400133440)
is in progress at exact 136d2e5a607fb20b6f5ccd07bd88ed14b0a6b668; do not cancel it with another dispatch. Audit
six AppleWorkWakeStoreTest cases, all downloaded XML and Xcode before accepting.
[Port ledger](template-updates.md) and measurements retain exact boundaries.

Continue implementing HRIS account-owned company recovery progress and the actual
all-company consumer. No new source for this next increment is applied yet. Native
parent publication, directory/admission, captured delivery, raw wakes and bounded
Android scheduling are ready. RefreshCollaborationList already supports Background
scope; do not duplicate it. Cursor state must survive restart without a synthetic
company/session, preserve account boundaries and prevent a failing company starving
others. Advance/attempt/finish crash gaps must not falsely acknowledge all recovery.
Use encrypted account-scoped metadata under the protected history key namespace;
start new finite sweeps without resetting progress on every incoming wake.

Keep scheduler limits explicit: two OS jobs can run, so consumer admission owns
processing. Reusing an ENQUEUED backoff request proves eventual scheduling, not
immediate high-priority FCM latency; that integration/measurement remains separate.
Registered HRIS recovery task, real receive/decrypt, notification privacy/read/mute/
deep links and encrypted reply still remain. No backend activation or employee APK
update: phone15, all54 PRD groups open. Owned5562 stopped;5560 is unrelated CodePush.
Continue after each push and while hosted CI executes.

## Bounded Android wake scheduling proved — 2 November 2026

[Contract](bounded-work-scheduling.md) / [evidence](measurements/android-collapsed-work-2026-11-02.json):
opted-in fresh task keys use two independently named slots. A query after the
caller commits a durable wake reuses a waiting slot or fills a vacant slot with
KEEP; running slots are not reused. Ordinary keys retain APPEND_OR_REPLACE. Keys
and admitted callers are bounded to32; two running slots reject another request.
Application processing must handle two OS jobs without duplicate native ownership.
No employee task opts in yet; no all-company consumer/FCM activation is claimed.

Eight new common cases pass:13 worker desktop/13 browser in152s /155.762s with
proof Android compilation/static. Full2 passes3,024 desktop,215 Android host,13
worker-browser and10 build-logic in263s /267.084s on five unchanged inputs. A later
fixture-only review correction makes cleanup bounded and noncancellable, tracking
initial reads too; proof APK/static checks pass66s /70.22s. All three production/
shared test inputs remain identical to the full run. Actual Android run1 then
passes all three scenarios in22.458s on five unchanged final inputs/artifact:
40 competing requests (four accepted),20 backoff requests reuse one pending ID,
failed/cancelled slots recover, the other running slot survives, old completions
cannot erase later generations and a real process stop retains a recoverable wake.
SQLite input/network/backoff/no-dependency checks and cleanup pass. Synthetic proof
APK fd6dccc8 is343,275,325 bytes; it is not an employee APK or FCM acceptance.

**Continue immediately:** push this commit, then port the three generic core/worker
files plus the separately Mobile-proven AppleWorkWakeStoreTest wrapper to Starter.
AndroidWorkScheduler baseline matches exactly. Preserve Starter demo/configuration;
HRIS policy and the debug proof application remain in Mobile. Run independent root/
worker-browser/Android/lint/production Web checks, record exact source/report hashes,
push, then dispatch one Starter Apple-only CI and audit its downloaded XML/Xcode.
Continue HRIS consumer implementation while that hosted run executes.

Mobile [Apple run41396150119](https://github.com/fajarxfce/hris-mobile/actions/runs/41396150119)
at be8ebc7b is independently audited:112 cases/30 suites,zero failures/errors/skips,
all six AppleWorkWakeStoreTest cases in0.117s, compile584s/tests84s/Xcode1166s and
associations1s. [Storage evidence](measurements/work-wake-storage-2026-11-02.json).
This earlier source does not include current HRIS policy or generic slot scheduler.

Next actual work: native parent publication -> registered all-company consumer,
fair company/room cursors, incoming discovery/SDK sync independent of local outgoing
messages, then notifications/privacy/deep links/encrypted replies. Use current
native directory and fresh per-company admissions; no FCM data can select authority.
RefreshCollaborationList already accepts operation scope; trace Background support.
The old tag/chain proposal was rejected BEFORE implementation because WorkManager
can delete whole WorkSpecs and tags; do not revive it from historical notes.

Owned emulator5562/PID4105492 stopped; absence verified. Port5560 is now unrelated
CodePush AVDcodepush-ci08/PID4082879, preserved. Recheck ownership before any reuse.
Phone remains15 from30d7faab; private server unchanged/push disabled. All54 PRD
groups remain open. Temporary swap still active pending safe headroom. Continue
past every commit/push; local `.work/collapsed-scheduling-2026-11-02` is supplementary.

## HRIS durable recovery request policy validated — 2 November 2026

[Contract](collaboration-recovery-wakes.md) / [evidence](measurements/chat-recovery-wake-2026-11-02.json):
fixed-key wake mapping, fresh UUID generations, capacity32 and Request/Resume/Read/
Complete operations now preserve persisted hints through scheduling failure,
timeout, cancellation and competing completion. Ten domain/six data cases plus
assembled application DI/SQLite checks pass. Focused checks pass in154s /158.169s
(530 domain,300 data,two DI); full root/affected browser/app-Wasm passes in316s /
319.907s on12 unchanged inputs: 3016 desktop,215 Android host,
808 affected browser and10 build-logic cases, zero failures/errors/skips.
These use cases have NO registered all-company consumer/FCM entry caller yet.

**Continue immediately:** implement bounded Android scheduling. Three source/test
candidates and eight common concurrency/cancellation cases are prepared UNAPPLIED
under `.work/collapsed-scheduling-2026-11-02/candidate.json`; design.txt is corrected.
Earlier tag/APPEND_OR_REPLACE reasoning was insufficient: WorkManager deletes whole
WorkSpecs on failed/cancelled leaves, cascading tag deletion. Do not use that design.
Use two independently named one-time slots per fresh opted-in key. Query unfinished
states for both names after the durable write. Reuse a waiting slot; otherwise fill
a vacant slot with a fresh-ID request and KEEP for that independent name. A running
slot is never reused; two occupied running slots fail and leave durable recovery.
Existing ordinary keys retain APPEND_OR_REPLACE. Bound admitted callers/keys to32.
Actual Android proof must cover two simultaneous jobs, competing scheduler instances,
backoff, slot cancellation/failure, cancellation/overflow and process-death repair
before accepting or porting this generic scheduler. No HRIS activation yet.

Starter dab675a is pushed with15 identical raw database/test/schema files; independent
301s /305.231s root/browser/Android/lint/Web proof passes348 desktop,42 browser,six
build-logic,zero failures/skips. Apple wrapper still awaits Mobile hosted proof.
[CI41396150119](https://github.com/fajarxfce/hris-mobile/actions/runs/41396150119)
at exact be8ebc7b has completed compile/tests and is building Xcode at this observation.
Audit downloaded XML (six AppleWorkWakeStoreTest cases) and final steps before acceptance.
Do not dispatch another same-branch Apple run until done. Then port its isolated
wrapper to Starter and run independent Apple CI. This older run is not proof of
this later HRIS recovery policy. Continue local implementation meanwhile.

Then connect parent publication, fair native-directory/room cursors and actual
incoming acquisition independent of local outgoing messages. RefreshCollaborationList
already accepts operation scope; trace Background support before duplicating it.
Receive/decrypt/notification/privacy/deep links/encrypted replies remain. Phone
stays verified15 from30d7faab, server unchanged, push disabled, all54 PRD groups open.
Owned5560 remains stopped; preserve unrelated jobs/5558. Continue after every push.

## Durable raw work wakes validated — 2 November 2026

[Contract](work-wake-storage.md) / [evidence](measurements/work-wake-storage-2026-11-02.json):
AppDatabase now exposes bounded application-global key/generation/pending metadata.
It contains no account/company identity, credential or message. Requests collapse
per stable task key; same-current-generation replay retains existing acknowledgment.
An old completion cannot erase a newer generation. Transactional key capacity allows
existing keys to advance at capacity, with no implicit pruning of completed keys.
Room11→12 and IndexedDB10→11 upgrades preserve prior commands and inbox records.

The first63s /66.872s run found one real cancellation bug among44 SQLite cases:
Room committed an already-cancelled call before propagating cancellation. Explicit
checks now precede DAO operations; the common regression also covers acknowledgement.
Once I/O starts, a durable commit can still race cancellation and must be recovered.
Corrected focused checks pass in321s /325.382s:44 SQLite,42 browser and two actual
DI cases, proof Kotlin compilation and static checks. Full root/database-browser/
app-Wasm checks pass in298s /302.234s on17 unchanged inputs:3,000 desktop,
209 Android host,42 database-browser and10 build-logic cases, zero failures/skips.
Mobile's application container binds WorkWakeStore. Six isolated Apple wrappers
are included but NOT executed on Linux; hosted acceptance remains separate.

**Continue immediately:** after pushing this commit, dispatch Mobile ci.yml with
scope=apple, capture the exact head SHA/run ID, and audit the downloaded XML,
including all six AppleWorkWakeStoreTest cases. Last prior Mobile CI41334974289
is completed, so no active branch CI was found during preparation. Avoid cancelling
unrelated work. Continue local implementation while hosted checks execute.

Port the15 shared raw source/test/schema files to Starter only after this Mobile
proof. All14 pre-existing/new baselines match there. Prepared supplementary Starter
`.work/durable-wake-2026-11-02/port.py` verifies clean committed/pushed Mobile source
and exact hashes; its check.py runs independent root/database-browser/Android dev/
lint/production Web checks. Exclude Mobile ApplicationStorageModule: Starter exposes
raw stores via AppDatabase and has different app composition. Exclude the new Apple
wrapper until its Mobile hosted proof, then port it and run Starter Apple CI. Keep
Starter demo/application configuration intact and record the reviewed port in Git.

Then connect actual HRIS recovery. This primitive still has NO wake repository/use
case, OS scheduler, all-company coordinator or FCM receiver. Use a fixed registered
task key, fresh non-reused generations and operation-specific capacity mapping;
persist before requesting execution. Consume native directory/current admission,
keep fair company/room cursors and sweep startup/reconnect/periodically, including
after account change. Incoming discovery must work without outgoing local messages.
Current one-time Android scheduler uses APPEND_OR_REPLACE. Preserve its finishing
race protection while bounding requests: inspect only unfinished jobs after durable
wake commit, and test concurrent pending/running transitions, retry/backoff,
overload and write/enqueue/ack process-death gaps on actual WorkManager. Duplicate
APPEND of a retained WorkRequest ID can form self-dependency; do not assume otherwise.

Supplementary continuation: `.work/durable-wake-2026-11-02/next.txt` and the parent
publication scratch design/upstream source. Phone remains verified15 from30d7faab;
no newer APK/install or server rollout occurred. Native receive/decrypt, notification
privacy/read/mute/deep links and encrypted replies remain open. Backend push stays
disabled, private server stays schema103/API-worker2c2a59e3, and all54 PRD groups
remain open. Owned5560 stopped; preserve unrelated5558/jobs. Temporary8GiB swap
remains active pending safe RAM headroom. Continue after each push.

## Processed parent publication validated — 2 November 2026

[Contract](collaboration-parent-readiness.md#processed-publication-and-application-lifetime) /
[evidence](measurements/chat-parent-publication-2026-11-02.json): the same exclusive
Maintain collector now publishes processed retention, ownership result and cleanup
result separately. The legacy entry maps that stream with unchanged denial priority.
Application state distinguishes Pending/Observed/Stopped; close invalidates it
immediately and terminal atomic updates prevent late publication. Source exception
or completion clears ownership without implicit restart. Parent-only observation
does not schedule selected-company work or claim native acquisition readiness.

Four new domain and six application cases pass within520 domain and49 actual
application collaboration tests, including assembled DI. Proof Kotlin compilation,
format/architecture/detekt pass in215s /218.858s. Full root, affected browser and app
Wasm compilation pass in298s /301.988s on six unchanged inputs:2,993 desktop,
209 Android host,792 browser and10 build-logic cases; zero failures/errors/skips.
No new JNI/APK/install for this increment; phone remains verified15 from30d7faab.

**Continue immediately:** durable collapsed wakes and all-company fair recovery.
A nine-file raw storage candidate is UNAPPLIED/UNTESTED and incomplete under
`.work/durable-wake-2026-11-02/candidate.json`. It adds application-global bounded
work-wake metadata without synthetic account/session scope, Room11→12 and
IndexedDB10→11 additions. Same-generation requests repeat unchanged; acknowledgment
checks the exact pending generation, preserving newer requests. Complete common
concurrency/capacity/cancellation contracts, native migration/reopen and browser
adapter/reopen validation before applying or accepting it. It contains no message,
account, credential or grant payload. Registered keys/generation creation and
scheduling remain caller policy. Wire it into actual recovery after validation.

Scheduling trace/design: current one-time AndroidWorkScheduler uses
APPEND_OR_REPLACE. Official WorkManager2.12 source shows duplicate APPEND of a
retained WorkRequest ID can create self-dependency; do not assume idempotency.
A candidate bounded strategy persists the wake before querying unfinished work,
reuses a pending ENQUEUED/BLOCKED task, and appends a successor when only RUNNING
work exists. Validate ordering, concurrent enqueue, process death, retry/backoff
and capacity with actual WorkManager before acceptance. Bound query/enqueue admission
and unfinished work; preserve the existing finishing-worker race fix. Notes and
read upstream sources are supplementary under `.work/parent-publication-2026-11-02`.

Then use fresh native-directory/admission with fair company/room cursors, periodic/
reconnect sweeps, real receive/decrypt, notification privacy/read/mute/deep links
and encrypted inline replies. Native incoming receive may need captured list
refresh beyond the existing outgoing/draft worker. Backend push stays disabled;
all54 PRD groups remain open. No live rollout or generic Starter port yet. Proven
generic wake foundations should return to Starter after Mobile validation. Owned
emulator5560 is stopped; preserve unrelated5558/jobs. Temporary8GiB swap remains
active pending safe RAM headroom. Continue after each push.

## Android release15 delivered after native recovery checks — 2 November 2026

[Evidence](measurements/android-release15-2026-11-02.json): clean Mobile
`30d7faab73681cd04100dc4cea74012b62fd87b5` passes all five actual Android
SDK/HRIS/Synapse regressions in364s /365.046s, with all cleanup flags true.
This covers SDK E2EE exchange, selected worker queued/frozen/cancelled recovery,
scheduled process death and controlled native reference borrowing. It does not
accept the new all-company worker or incoming FCM path. Platform source was
`a9706280`; no live deployment occurred.

Proof assembly passed in46s /49.576s. Universal devRelease15 assembly/lint passed
in627s /630.891s; two actual StarterApplication cold starts each stayed alive12s,
resumed and rendered the onboarding page (35.690s total). Both screenshots were
inspected. ARM64 assembly/lint passed in73s /76.488s; all eight DEX files match the
startup-tested universal artifact. Lint has zero errors and one existing warning.

Authorized phone10.77.77.3:5555 now has **devRelease15**, version1.0.0,
package`dev.fajar.hris.mobile.dev`,87,363,576 bytes, SHA-256
`39a8bb9cfbaf95c394e228d16aebc2085448d8c4c3d2b0f051fadc13eca6a79e`.
Installation began05:14:03 UTC and finished with verification in112.767s.
Existing signer, UID10962 and first-install time remain. Install-only: no launch,
uninstall or data clear. A slow read-only preflight APK pull was interrupted and
retained in the evidence. Full installed-file SHA-256 computed on the phone matched
the retained signed14 APK before update and the new15 APK afterward; no post-install
full APK pull is claimed. The user confirmed11 opens, not a separate15 interaction.

**Continue immediately:** apply and validate the reviewed six-file/ten-case
processed-parent publication candidate in supplementary
`.work/parent-publication-2026-11-02/candidate.json` using its baseline hashes.
It is UNAPPLIED and UNTESTED at this checkpoint. Reuse the same Maintain collector;
separate processed retention, ownership denial and cleanup failure. Application
state must distinguish Pending/Observed/Stopped, invalidate on close/completion and
reject late publication without duplicate observers or automatic restart. Run the
focused actual app/DI/domain/proof checks and required root/browser checks, then
commit/push and continue durable all-company wake/fair cursor implementation.

Trace correction: one-time AndroidWorkScheduler already uses APPEND_OR_REPLACE;
only its periodic work uses KEEP. Preserve the existing finishing-worker race fix
while bounding/coalescing future FCM scheduling. Do not invent a session/account
scope for global wake metadata. Test persist/schedule/ack crash gaps explicitly.

Owned emulator5560 is stopped; unrelated5558 remains untouched. Owned temporary
8GiB swap remains active; remove only with sufficient RAM for used pages plus safe
headroom. Keep heavy builds sequential. Native receive/decrypt, notifications,
privacy/read/mute/deep links and encrypted inline replies remain open. Backend push
stays disabled, all54 PRD groups remain open, and no generic Starter/live rollout
is implied. Continue after each push; physical iPhone acceptance alone is waived.

## Native parent startup and monotonic ownership validated — 2 November 2026

[Contract](collaboration-parent-readiness.md) /
[evidence](measurements/chat-parent-readiness-2026-11-02.json): the single Maintain
collector publishes a valid parent before the first workspace emission. Same-parent
token updates seed the last observed workspace instead of clearing foreground.
A replacement parent starts empty. One bounded snapshot holds a monotonic effective
time floor and minimum hard-family expiry across repeated observations, read failures
and clock rollback. Initial stream expectations explicitly include parent-only state.

Seven new cases pass within516 domain and43 actual app collaboration tests, including
both DI cases. Proof adapter compilation and static checks pass in157s /160.787s.
Final three-input root/browser/app-Wasm checks pass in345s /348.943s:
2,983 desktop, 209 Android host, 788 browser and 10 build-logic cases,
zero failures/errors/skips in either run. Existing denial, renewal, observer exclusion
and late cleanup cases remain. No new JNI, employee APK or phone install yet.

**Continue immediately:** actual Android/provider regression and a meaningful release15
update to the authorized phone. Mobile prepared runners are in
`.work/android-recovery-2026-11-02-build15`: build.py (proof/release), start-emulator.py
and smoke.py; phone scripts are in `.work/release-phone-2026-11-02-build15`.
All are supplementary and unexecuted at this checkpoint. Build the proof APK from
clean committed source first; compileDebugKotlin alone does not refresh its bytes.
Platform prepared `.work/android-recovery-2026-11-02-build15/run-native.py` runs all
five actual SDK/HRIS/Synapse cases on owned emulator5560. Verify its input hashes and
artifact before/after, preserve every interruption/failure and cleanup record.
Details: `.work/parent-readiness-2026-11-02/next-android.txt`.

Then build signed devRelease15 with2048MiB/inprocess compilation, perform two actual
application cold starts on owned emulator, and build arm64 from the same clean head.
Require matching DEX between universal startup-tested APK and phone artifact. Notify
before installation; use explicit10.77.77.3:5555 and install-only, same signer/data.
Pull installed bytes and verify UID/firstInstall retention. Current phone remains14
from8c557716 until new evidence confirms installation. Stop only owned emulator when
done. Owned8GiB temporary swap remains active/full with about10GiB RAM available;
keep heavy builds sequential, preserve unrelated CodePush/AVD5558, clean swap only
when used pages and safe headroom fit RAM. Never delete active swap.

Continue explicit processed-parent publication for the future all-company coordinator:
ApplicationCollaborationLifetime.state defaults to Success(null), which is not proof
of publication. Reuse the same collector; keep failed cleanup separate from parent
identity so an eventual drain cannot leave a permanently false readiness marker.
Do not add a second observer or an implicit retry loop. Durable all-company wake
generations/fair cursors, directory/reconnect/periodic sweeps, receive/decrypt,
notification privacy/read/mute/deep links and encrypted inline replies remain open.
Backend delivery stays disabled. No generic Starter or live rollout; all54 PRD groups
remain open. Continue after every push. Physical iPhone acceptance is waived only.

## Shared admitted background worker validated — 2 November 2026

[Contract](collaboration-admitted-worker.md) /
[evidence](measurements/chat-admitted-worker-2026-11-02.json): the existing worker
accepts an ephemeral background admission. It loads exact setup/readmission before
captured local recovery and finite runtime borrowing, then shares all existing
room/outbox/history algorithms. It never reads or changes workspace selection in
that entry. The remaining original wall/monotonic budget includes setup/acquisition;
per-room12s limits remain. Both scheduled selected and background entries return
Retry(busy) immediately when occupied instead of joining a waiting queue.

The worker owns one finite reference and independent credential/runtime/local
access monitors. Interruption cannot wait for an initial access-flow emission;
failed references cannot start delivery. Pending work and observations are cancelled
and joined before reference release. Background history focus capacity defers
recovery without foreground reclamation or resending; foreground ownership survives
reference release. Platform job inputs stay task-key-only, never serialized grants.

All16 new application cases pass within43 actual app collaboration tests;509 domain
cases, two assembled DI cases, proof Kotlin compilation and static checks pass in
71s /74.704s. The first52s /56.244s run retained two assertion failures: an old test
expected the second worker invocation to wait, and a new test omitted the already
closed staging queue from its count. Both assertions now reflect explicit behavior;
product Kotlin stayed unchanged. Final seven-input root and app Wasm compilation
pass in327s /330.664s: 2,976 desktop, 209 Android host and 10 build-logic
cases, zero failures/skips. This app-only increment does not rerun browser tests;
prior d8ebe56f records781 passing affected browser cases. No new JNI/APK/phone run.

**Continue immediately:** fix MaintainCollaborationRuntimeOwnership initial parent
readiness while Workspace.observe is pending, preserve the last actual workspace
for same-parent token rotation, and anchor effective time/hard expiry monotonically
across repeated session/workspace emissions and clock rollback. Reuse the existing
single application collector; do not replace WorkspaceRepository. Detailed design
notes are supplementary at `.work/background-worker-2026-11-02/next-parent-notes.txt`.
A three-file/seven-case parent candidate is UNAPPLIED/UNTESTED at
`.work/background-worker-2026-11-02/parent-candidate.json`; verify baseline hashes
before applying and review its initial-parent-only observation expectations. Preserve prior collector/cleanup,
denial and timer regressions; test pending initial observation, replacement/logout,
old-source cancellation, foreground preservation and nonresurrection after expiry.

Then persist collapsed all-company wake generations and fair company/room cursors,
consume owned native-directory pages plus reconnect/periodic full sweeps, and wire
real receive/decrypt, notification privacy/read/mute/deep links and encrypted inline
reply. Background worker entry alone is not incoming-FCM acceptance. Keep backend
delivery disabled. Private server is still schema103/API-worker2c2a59e3, not branch116.
Phone remains verified14 from8c557716; next meaningful install15+ must be notified,
explicit serial, same signer/data, install-only. No generic Starter or live rollout.
All54 PRD groups remain open; continue after each push. Owned temporary swap remains
active pending safe cleanup; preserve unrelated CodePush jobs and AVD5558.

## Captured local delivery and frozen-draft recovery validated — 2 November 2026

[Contract](collaboration-captured-recovery.md) /
[evidence](measurements/chat-captured-recovery-2026-11-02.json): discovery/work
records, cursor advancement, queue/confirm/suspend and access observation share
Selected/Captured local scope. No synthetic workspace IDs or remote TTL for owned
facts. Original128-record/eight-draft bounds, stable fairness/CAS, frozen submission
ID, review holds and concurrent-editor protection remain shared. The application
has a captured draft-recovery entry and separate local/remote inner delivery scope;
its scheduled delivery entry is still selected-company-only at this checkpoint.

Nine new domain and two actual application cases pass within509 domain/27 app
collaboration tests, including both DI cases. Real encrypted frozen-draft recovery
after restart forbids workspace reads and preserves one original intent. Proof
adapter compilation and static checks pass in144s /147.572s. Initial compilation
stopped at a new test's collection type inference (36s /40.013s); the assertion was
made explicit and a new fixture's invalid enum name corrected. No product logic
changed for those corrections; the failed attempt remains in the evidence.
Final18-input root/browser checks pass in287s /290.976s: 2,960 desktop,
209 Android host, 781 browser and 10 build-logic cases, all zero failures/skips.
No new JNI, APK build, phone install or server change; phone remains verified14.

**Continue immediately:** an UNAPPLIED/UNTESTED six-file,16-test background-worker
candidate is at `.work/captured-recovery-2026-11-02/background-worker-candidate.json`.
Verify baseline hashes before applying. It adds a typed background entry to the
same worker, loads setup before local discovery and finite runtime borrowing, owns
only the borrowed reference and observes independent credential/interruption/local
access signals. Initial failed references cannot start work; native failure must
not wait for the first access-flow emission. Selected/background entries use the
same no-wait mutex and bounded shared room pass. Original grant budget includes
setup/acquisition. The fake SDK opts in explicitly; no provider acceptance claimed.
Its16 cases cover actual app persistence/room replay, history capacity deferral,
parent/company/SDK interruption, cancelled late acquisition, concurrency, deadlines,
foreground preservation and monitor cancellation/disposal. Validate actual app
collaboration cases, DI/proof compile, static/root checks and app Wasm compilation.

Then fix initial application-parent readiness while workspace observation is still
pending, preserving foreground retention across token rotation and monotonic parent
expiry. Implement durable all-company collapsed wakes, fair directory/room cursors,
reconnect/periodic full sweeps, notifications/privacy/read/mute and encrypted inline
reply. No WorkspaceRepository replacement in production; no foreground reclamation
from a background pass. Backend delivery stays disabled; all54 PRD groups remain
open. Continue after each push. No generic Starter port or live rollout. Owned
temporary swap remains active pending safe cleanup; preserve unrelated jobs/AVD5558.

## Background setup and conversation control validated — 2 November 2026

[Contract](collaboration-background-control.md) /
[evidence](measurements/chat-background-control-2026-11-02.json): LoadSetup and
Prepare/Replay/ReconcileConversation share Selected/Background remote admission.
Background never reads workspace selection and binds room requests to the exact
admitted device. Setup readmission requires the matching active device/platform and
nonregressing version before gate CAS. Retained denial and committed-intent rules
remain in force through cancellation, expiry and native-parent replacement.

Historical command failure is deliberately separate from a newly received denial.
A fresh admitted retry keeps its original operation without closing the company
again merely because its old explanatory failure was a denial. Newly observed HRIS
denials still retain the gate under failed cleanup or owner reads.

All 16 added cases pass within 500 domain and 118 presentation tests; both actual
app DI cases, proof adapter compilation and static checks pass in152s /155.518s.
Initial nine-input/12-case checks also passed496 domain,118 presentation and two DI
cases in154s /158.268s before the setup extension. Final ten-input root/browser checks
pass in249s /252.715s: 2,949 desktop, 209 Android host, 772 browser
and 10 build-logic cases, zero failures/errors/skips. No new JNI or APK run.
Phone remains verified14 from8c557716; no install or server change here.

**Continue immediately:** captured local delivery/draft discovery/cursors and
queue/confirm/suspend, then shared application worker integration. An eighteen-file
candidate with nine new domain and two actual application cases is UNAPPLIED and
UNTESTED at `.work/background-control-2026-11-02/next-local-candidate.json`.
Verify all baseline hashes before applying. It carries explicit local scope in work
records, preserves selected String entry points, reuses access observation and
prepares the existing worker's inner pass with separate local/remote scopes. It
adds a captured draft-recovery entry; scheduled delivery remains selected-only.
Validate domain, all actual app collaboration worker cases, proof compilation,
static checks and full root/browser checks. No fake workspace IDs or duplicated outbox.

Then hold an admitted finite background runtime reference across the shared worker,
observe interruptions/credential failure and release only that reference. Do not
call foreground Ensure/reclamation while borrowed; history capacity defers bounded
background recovery. Test no-wait worker admission and initial application-parent
readiness without a first workspace emission, preserving foreground retention and
monotonic parent deadlines across token rotation. Durable all-company collapsed
wake generations, fair cursors, directory/reconnect/periodic sweep, notifications
and encrypted inline replies remain required. Delivery stays disabled; all54 PRD
groups remain open. Continue after checkpoints. No generic Starter or live rollout.
Owned temporary swap remains active pending safe cleanup; preserve unrelated jobs.

## Captured local message/control ownership validated — 2 November 2026

[Contract](collaboration-captured-local-scope.md) /
[evidence](measurements/chat-captured-local-scope-2026-11-02.json): message observation,
receipt recovery/projection/retirement and retained setup/direct/room reads accept
Selected or Captured local scope. Existing String callers retain workspace behavior.
Captured access checks the active local session/account/audience and canonical company,
then the retained company gate, without workspace reads or remote permission deadlines.
Control reads also bind commands to the current native family without a live TTL.

All15 new cases pass within484 domain tests, including expired-credential recovery,
32-item retirement, late owner/cancellation, retained receipt/completion facts and
observer disposal. Actual DI's two cases, proof adapter compilation and static checks
pass in136s /139.684s. Initial12-file focused checks passed480 domain/two DI cases in
139s /143.072s before adding three control files/four cases; both scopes are recorded.
Final15-input root/browser checks pass in263s /267.22s:
2,933 desktop, 209 Android host, 756 browser and 10 build-logic cases, zero failures/skips.
No new JNI, employee APK build or phone install. Phone remains verified14 from8c557716.

**Continue immediately:** typed remote Prepare/Replay/ReconcileConversation, exact
background RequestRoom device binding and retained company gates. A nine-file,
12-test candidate is UNAPPLIED/UNTESTED in
`.work/captured-local-2026-11-02/remote-control-candidate.json`; verify its baseline
hashes before applying. It adds required list-repository DI and updates the existing
domain/presentation constructors. Validate domain/presentation, actual DI/proof
compile and full root/browser checks after applying.

Critical distinction: a historical command failure is not a newly received server
denial. Replay combines prior failure with transport/storage failures. Reusing that
combined value to restrict the company would reclose a freshly admitted gate and
prevent retry forever. The candidate separately carries contextResult and the actual
receivedResponse; only the latter confirms a new denial. Tests include explicit
readmission followed by successful retry, historical denial plus transport failure,
and new MFA/company denial under cancellation/failed cleanup. Supplementary detail:
`.work/captured-local-2026-11-02/next-control-notes.txt`.

Then convert local delivery/draft discovery, cursor/queue/confirm/suspend operations
and generalize the existing bounded worker. Do not fabricate workspace IDs or copy
outbox logic. Captured scope must come from owned records, never raw FCM fields; it
does not authorize remote I/O. Reuse ApplicationCollaborationLifetime and test initial
parent readiness while workspace observation is pending, maintaining foreground
retention across token rotation. Durable all-company wake/reconnect/periodic recovery,
notification privacy/read/mute and inline replies remain required. Delivery stays
disabled; all54 PRD groups remain open. No generic Starter port or live rollout.
Owned temporary swap remains active pending safe cleanup; preserve unrelated jobs.

## Background staging/delivery and SDK denial classification validated — 2 November 2026

[Contract](collaboration-background-outbox.md) /
[evidence](measurements/chat-background-outbox-2026-11-02.json): StageText and
DeliverMessages now use shared Selected/Background admission without consulting
workspace selection. Both require the exact admitted device. Existing uncertainty,
transaction mapping, receipt append, projection and cancellation rules remain shared.
Staging now reuses content-denial classification: SDK-token unauthorized stops the
native operation but does not close all local company history. Confirmed HRIS
company/session/MFA denials still retain their gate even under failed cleanup.

All 12 added cases pass within 469 domain tests, and both actual app DI cases pass.
Proof adapter Kotlin compilation, format/architecture and detekt pass in130s Gradle
/134.385s wall. The initial43s /46.539s run had one new-test expectation failure:
Sent mappings intentionally participate in later SDK queue inspection/alias recovery.
The corrected test models a drained second window and verifies the original mapping,
receipt and projection before that window; the fixture prohibits enqueue. No product
algorithm was changed to mask that assertion. The failed run remains in the evidence.

Final root/affected browser checks pass in254s /257.828s with five unchanged
Kotlin inputs: 2,918 desktop, 209 Android host, 741 browser and 10 build-logic cases.
No new JNI run or employee APK build/install here. Phone remains verified release14
from8c557716, which predates these overloads and the SDK classification correction.

**Continue immediately:** captured local ownership for message observation, receipt
recovery/projection and retirement, independent of remote grant expiry. A 12-file,
11-test candidate is UNAPPLIED/UNTESTED in
`.work/background-outbox-2026-11-02/next-local-candidate.json`; verify baseline hashes
before applying. Its current shape keeps Selected behavior and adds Captured access
with active account/session/audience fencing and retained gate checks. Review this
policy before broadening local discovery. Captured access is not remote authority
and must never be trusted directly from FCM fields. It has no remote TTL.

Then convert local ReadControl/discovery/cursor/drafts and remote room-control
commands, including exact background device binding and retained-denial handling.
Generalize the existing application worker; do not duplicate its bounded outbox or
replace WorkspaceRepository. Reuse the application-owned runtime collector and test
initial parent readiness while workspace observation is pending, preserving a
foreground client across token rotation. Incoming all-company collapsed hints,
reconnect/periodic sweep, notification privacy/read/mute and inline reply remain next.
Keep backend delivery disabled; all54 PRD groups remain open. No generic Starter
port or live rollout. Continue after every pushed checkpoint. Owned temporary swap
remains active pending safe cleanup; preserve unrelated CodePush jobs/AVD5558.

## Shared background native entry and history validated — 2 November 2026

[Contract](collaboration-background-history.md) /
[evidence](measurements/chat-background-history-2026-11-02.json): EnterConversation,
AcquireHistory and AcquireProviderHistory now share Selected/Background scope.
String callers retain their existing selected-workspace path. Background checks the
current native parent, original monotonic admission and exact device without reading
or changing workspace selection. Native child cleanup, bounded original merges,
provider-page CAS and retained denials remain shared. Local owned facts stay intact.

All 13 new cases pass within 457 domain tests. Actual container bindings pass both
cases; the initial run compiles the proof adapter and passes format/architecture,
then reports one detekt LongMethod violation (101 lines). The returned timeline's
ownership predicate was extracted without changing its conditions. That initial
202s /205.445s run remains recorded. Final root and affected browser checks pass in
326s Gradle /329.525s wall with all seven Kotlin inputs unchanged:
2,906 desktop, 209 Android host, 729 browser and 10 build-logic cases.
This increment has no new JNI run, employee APK build or phone install. Phone stays
on verified release14 from8c557716; its source predates these overloads.

**Continue immediately:** shared Stage/Deliver background scope plus the SDK-token
failure classification correction. A five-file candidate with 12 cases is
UNAPPLIED/UNTESTED in `.work/background-history-2026-11-02/next-outbox-candidate.json`;
verify its baseline hashes before applying. Preserve observed-send receipt append
before remote continuation, so expiry/cancellation cannot erase proof or cause a
second send. No native-parent close handle belongs to a child writer.

Then convert room-control commands and local discovery/reconciliation/draft cursor
operations with separate captured local ownership and remote admission. Local reads
must not inherit a60s remote TTL. Reuse the existing bounded delivery worker and
application lifetime; test initial parent readiness while workspace observation is
pending, retaining the foreground owner across token rotation. Detailed scratch
notes are in `continuation-design.txt`; Git contracts are authoritative. Continue
durable all-company hints/reconnect/periodic recovery, notification projection and
inline replies. Keep backend delivery disabled; all54 PRD groups remain open.
No live rollout or generic Starter port. The owned temporary swapfile below remains
active pending safe cleanup; preserve unrelated CodePush jobs/AVD5558.

## Release 14 installed on the authorized phone — 2 November 2026

[Installation evidence](measurements/android-release14-2026-11-02.json): clean
source `8c557716` built arm64 devRelease14 in 62s Gradle /65.98s wall with lint
passing. The 87,347,192-byte APK (`be3d4211`) has all eight DEX files identical to
startup-tested universal14. It retains the existing development signer and private
WireGuard API; no fixture endpoint or debuggable flag is present.

After notification and signer preflight, the install-only update to
`10.77.77.3:5555` completed and was verified by pulling the installed APK. Its
full hash matches. Version14, application UID and original first-install time are
verified; no uninstall, app-data clear or UI launch occurred. Installation plus
verification took85.325s. The user's latest confirmation was that release11 opens;
release14 has automated startup/code-equivalence proof, not a new user confirmation.

**Continue immediately:** apply the seven-file typed native/history scope candidate
mentioned below only after verifying its baseline hashes. Validate shared history
and child-resource cancellation rules, then continue shared outbox and durable
all-company incoming recovery. Phone now has14; older phone11 notes below are
historical. Keep backend delivery disabled and continue after each pushed checkpoint.
No live server rollout, generic Starter port or full PRD-group acceptance.

## Finite background runtime references validated — 2 November 2026

[Contract](collaboration-background-runtime.md) /
[evidence](measurements/chat-background-runtime-2026-11-02.json): background work
can borrow an existing published Android client or restore its protected store,
without a parent close handle or changing the selected company. Native parent and
foreground retention are separate. Five reserved native slots include pending
opens/unfinished drain; two finite references have no waiting queue. Acquisition
completion fences native destruction, and history reopening excludes concurrent
borrowing for its entire decision. Monotonic admission budgets include network and
acquisition time. Cancellation, late parent changes and retained denials are covered.

Focused checks pass 444 domain, 209 Android host and six proof-cleanup cases in
140s Gradle /143.769s wall; actual DI's two cases also pass. All five native cases
are now accepted on the same proof APK `91638cf6`, across two recorded runs against
Platform2308b337 (implementation8b77c4fa). The first run passed SDK, scheduled and
cancelled-draft scenarios, then the kernel killed owned emulator5560/PID3805888
for host OOM. Its two interrupted cases remain recorded as failures. The queued
and frozen-draft retry passes in169s /169.876s; both clean up successfully. The
interrupted fixture was removed only after verifying its exact APK. Actual native
borrowing preserves the cryptographic identity and the foreground client.

Required checks now pass: 2,893 desktop, 209 Android host, 716 browser and ten
build-logic cases, plus ten runner/17 distribution Python checks. Run7 completed
root/domain checks but two large IndexedDB fixtures exceeded Mocha's default2s.
A module-local10s budget is verified in generated Karma configuration; all272 data
browser cases then pass. Run8 subsequently hit D8's reduced1280MiB heap; run9's
remaining release targets pass at2048MiB in100s /103.642s. Failed runs remain in
the evidence. Keep `-Pkotlin.compiler.execution.strategy=in-process` for upcoming
checks to avoid changing all compiler inputs again; use2048MiB for APK assembly.

Universal devRelease14 is281,097,776bytes, SHA256 `64b05d53`. App lint has zero
errors/one existing warning. The actual StarterApplication passes two12s stable
cold starts with resumed/readable onboarding and inspected screenshots in35.695s.
The owned emulator was stopped and absence verified. Phone remains user-confirmed
release11 pending the separate arm64 update; there is no phone-install claim here.

**Continue immediately:** commit/push this checkpoint, then use the prepared
`.work/release-phone-2026-11-02-build14` tooling to build from the clean commit,
verify signer and DEX equivalence to the startup-tested universal14, preflight the
phone, notify, and install-only on10.77.77.3:5555. Preserve app data/signing and
verify installed bytes. Push installation evidence, then resume implementation.

Next: typed Selected/Background native entry/original/provider history, shared
outbox orchestration, durable all-company wake/reconnect/periodic recovery, incoming
notifications/privacy/read/mute/deep links and inline replies. A seven-file,
13-test candidate is **UNAPPLIED/UNTESTED** in
`.work/runtime-borrowing-2026-11-02/typed-native-scope-candidate.json`; check its
baseline hashes before applying. Git contracts remain authoritative. Reuse
ApplicationCollaborationLifetime, already started by StarterApplication; do not
start a second observer. Test initial parent readiness while the first workspace
observation is pending before wiring incoming recovery. No selection swapping or
copied integrity logic. Keep backend delivery disabled; all54 groups remain open.
No generic Starter port or live backend rollout in this increment.

Host resource note: an owned8GiB Btrfs swapfile
`/var/tmp/hris-validation-2026-11-02.swap` is active, mode0600, priority-3 and not in
fstab. It prevented further host-wide OOM during concurrent unrelated CodePush
work. Keep it until RAM can safely absorb its used pages plus a margin, then remove
only this swapfile. Preserve unrelated jobs/AVD5558 and existing swap. Creation and
kernel evidence are retained in this increment's measurement and scratch records.

## Native runtime publication and five native regressions validated — 2 November 2026

[Contract](collaboration-runtime-publication.md) /
[evidence](measurements/chat-runtime-publication-2026-11-02.json): completed SDK
acquisition stays private until synchronous publication after final owning-use-case
admission. All **four** acquisition paths are covered: Ensure, Install, Restore and
ReopenHistory. Install also waits for command retirement. No suspension separates
publication from transfer; failure/cancellation keeps cleanup with the original
caller. This is resource ownership, not permission to send or read remotely.

Focused430 domain/190 Android host/six proof cleanup cases, actual proof APK assembly,
lint, format/architecture and detekt pass in156s Gradle /160.313s wall. Proof lint
has zero errors/47 warnings. The earlier pusher interface addition had left the
proof adapter without its new method; it is now explicit and compiled. Four new
host cases use inert FFI handles; they are separate from actual native evidence.

The same proof APK `6f88fcd6` passes all five actual SDK/HRIS/Synapse cases against
Platform8b77c4fa in369s /369.345s. SDK56.804s, scheduled46.985s, three worker cases
240.250s; all results/cleanup pass and31 evidence hashes are retained. Queued,
frozen-only and cancelled-draft cases each retain older history and verify33
encrypted events. CI now pins that Platform revision; hosted execution is not yet
audited. The owned emulator5560 is stopped and absence verified.

Full root and affected browser checks pass in236s /239.946s:2,879 desktop,190
Android host,702 browser and10 build-logic tests, plus10 Android-runner/17
distribution Python checks. Sixteen Kotlin inputs stayed unchanged throughout;
the later CI-pin-only change makes17 full-check inputs. No employee APK build or
phone installation here. Phone remains user-confirmed release11; earlier
universal13 startup proof predates this change. Next useful update14+, notify
first and preserve signer/data with explicit10.77.77.3:5555 install-only.

**Continue immediately:** finite background runtime borrowing alongside foreground
retention, using one SDK/store per owner/device. Carry current native parent
separately from optional selected-company owner. Parent expiry must still fire
after foreground admission expires; token rotation must retain the same parent.
Reserve capacity/store identity before suspending acquisition and invalidate late
opens on parent replacement/shutdown. Unfinished cleanup retains capacity5. A
borrower gets a finite releasable reference, never a parent close handle; releasing
it must preserve a foreground client. Test pending opens, expiry, replacement,
denial and failed drain. Detailed supplementary notes are in
`.work/runtime-publication-2026-11-02/next-runtime.txt`.

Then extend existing Selected/Background scope through history/outbox instead of
copying their integrity/denial logic, and implement durable all-company collapsed
wake/reconnect/periodic recovery, receive/decrypt/privacy/read/mute/deep links and
inline replies. Classic SyncV2 remains required; NotificationClient needs sliding
sync. Keep push delivery disabled until real receive/recovery acceptance. No live
rollout or generic Starter change; all54 PRD groups remain unaccepted. Continue
after each pushed checkpoint.


## Shared conversation reads accept background scope — 2 November 2026

[Evidence](measurements/chat-background-read-scope-2026-11-02.json): typed
Selected/Background scope now feeds existing RefreshCollaborationList and
ReadCollaborationConversations. Existing String entry points preserve foreground
behavior. Background capture checks current account/native parent and the original
finite proof without reading/changing workspace selection. Publication, denial CAS,
cancellation and late-result rules remain shared. Seven new background cases pass,
including later use at40s, expiry/replacement, cancelled denial and late merge.

Focused425 domain/294 data/two actual DI cases pass in163s /166.687s. Full root,
affected browser suites, Android host, format/architecture and detekt pass in246s
Gradle /249.772s wall; seven inputs unchanged. Exact aggregate counts are in the
record. No APK build or phone install in this increment. Phone remains accepted11;
prior universal13 startup evidence is at a8558228 and predates this change.

**Continue immediately:** explicit native runtime publication after final owning
use-case admission/command retirement, before another foreground/background caller
can reuse it. Currently completeAcquisition makes the SDK entry visible before
Ensure/Install/Restore finishes its pending ownership checks; background borrowing
must not enter that window and then lose its parent to the earlier caller's cleanup.
Add a synchronous publish/accept step to the lease and those three owning use cases,
with native lease and pending/cancellation/retirement tests. Then finite background
borrow/reference ownership alongside foreground retention, parent expiry/replacement
and shutdown, reusing the same SDK/store. Extend typed scope through existing
history/outbox use cases instead of copying their integrity and denial rules.

The earlier runtime-next.txt notes remain useful. Its typed-scope-prepared.json is
now applied and refined; do not apply it again. This first scope increment covers
control/list reads only. Durable all-company hint/reconnect/periodic recovery,
FCM receive/decrypt/privacy/read/mute/deep links and inline replies remain next.
Keep delivery disabled; all54 catalog groups and remaining P0–P8 remain open. No
generic Starter change or live rollout. Continue after each pushed checkpoint.



## Native-parent discovery and company admission client validated — 2 November 2026

[Contract](collaboration-native-recovery.md) / [evidence](measurements/chat-native-recovery-client-2026-11-02.json):
one bounded device page and separate active-device company admission now work without
reading/changing foreground selection. Local/native-parent identity, current scope,
strict metadata, versions and request-time-calibrated deadlines are checked. Each
use case owns one15-second request without a waiting queue. Received company/MFA
denial stays gated despite cleanup failure; successful observation never clears it.

Nine new domain and six transport cases plus actual container bindings pass.
Focused418 domain/294 data/two DI cases pass in164s /167.899s. Full root check,
affected browser suites and signed universal devRelease13 assembly/lint pass in
546s Gradle /549.631s wall:2,867 desktop,186 Android host,690 browser and10 build-logic
cases; root Python10 runner/17 distribution checks. Fourteen inputs unchanged,
format/architecture74modules/detekt pass, lint zero errors/one existing warning.
Both actual-app cold starts remain stable12s with readable/resumed onboarding and
inspected screenshots,38.649s smoke. Owned emulator5560 stopped. Older unrelated
browser reports are excluded; no hosted/native-provider or phone-install claim.

Platform8b77c4fa has matching actual HTTP/SQL/API+worker proof. Continue typed
Selected/Background operation scope through the existing acquisition/outbox use
cases, then finite SDK background borrowing alongside foreground ownership. Never
swap WorkspaceRepository selection, duplicate a cryptographic store, expose a parent
close handle to a borrower or let late work survive parent replacement. Concrete
notes and a four-file UNAPPLIED/UNTESTED candidate are in
.work/native-recovery-client-2026-11-02/runtime-next.txt and typed-scope-prepared.json;
Git contracts remain authoritative. The candidate covers list/conversation reads
only and must gain tests, strict grant validation and runtime integration.

Use classic SyncV2 history for notification recovery; built-in NotificationClient
requires sliding sync. Durable all-company hint/reconnect/periodic recovery, receiver,
privacy/read/mute/deep links and inline replies still remain. Keep backend delivery
disabled. Phone remains accepted release11; next useful product update12+, notify
first, explicit10.77.77.3:5555 install-only and same signer/data. Release13 above is
only a local validation artifact. No generic Starter port or live schema/provider
rollout. All54 groups remain unaccepted; continue P0–P8 after each pushed checkpoint.



## Managed pusher client validated; background recovery next — 2 November 2026

[Contract](collaboration-notifications.md) / [evidence](measurements/chat-pusher-client-2026-11-02.json):
explicit pusher activation now checks current company/native-parent ownership,
active device and retained admission denial. Android borrows the owned SDK bearer,
registers one finite request as a runtime child and posts to the fixed gateway
endpoint. No implicit enrollment, restore, refresh or retry. Application DI resolves
its singleton. Automatic worker invocation and incoming notifications remain next.

Full root check, affected browser suites and signed universal release12 assembly/lint
pass: 2,852 desktop, 180 Android host, 675 browser and ten build-logic tests; zero
failures. Lint has zero errors/one DataExtractionRules warning. Final build 562s
Gradle /565.616s wall, 21 unchanged source inputs. Both actual StarterApplication
cold starts remain stable for12s with rendered/resumed onboarding; screenshots
inspected, smoke36.563s. Owned emulator5560 stopped. This is local evidence, not a
hosted native provider/FCM proof. Earlier fixture failures are retained in the record.

Platform e1da924b has the bounded default-disabled push worker. Its own-parent
paginated device directory and separate protected company admission endpoint are
undergoing actual API/worker/SQL tests. Continue the Mobile typed background
admission and finite runtime ownership, durable collapsed wake/reconnect recovery,
receive/decrypt/privacy and inline replies without changing visible workspace.
Current SDK NotificationClient requires sliding sync while product uses SyncV2;
do not assume compatibility or expose an unscoped sliding-sync gateway.

Keep backend delivery disabled until the receive/recovery path is validated. Phone
release11 remains user-confirmed working; no phone install in this API-only increment.
Next validated product update12+, notify first, explicit10.77.77.3:5555 install-only
and preserve signer/data. No generic Starter change in this HRIS adapter. All54
catalog groups remain unaccepted; continue P0–P8 after each pushed checkpoint.


## Release11 installed; user confirms startup recovered — 2 November 2026

Clean source56a46938 is installed in place over release10 on 10.77.77.3:5555.
[Evidence](measurements/android-release-startup-2026-11-02.json): arm64 build/lint
66s Gradle /70.331s wall, APK87,330,800 bytes, SHA256
`e914fad7f195d74ab55889b404c1c9da72cddd5deefb3c3522bf1a0de1c91579`.
All eight DEX files match the universal release that passed both actual-app cold
starts. VCS/private API/Firebase/signature/16KiB alignment verified. Installation
and post-pull verification59.620s preserve appId10962 and firstInstallTime; no
uninstall, data clearing or phone UI launch. User subsequently confirms **“Sudah
bisa dibuka”**. The force-close fix has physical user acceptance. Next product
phone update must use12+ and the same notification/install-only protocol.

Continue Platform push delivery. Focused55 domain plus18 SQL cases have just run;
one SQL retry fixture failed because it tried to rewrite available_at outside a
fenced lease transition. Preserve that database protection and fix the fixture's
clock/backoff approach before further tests. Seven other new delivery cases and
all ten callback cases pass. Handler/policy/DI and tests remain uncommitted there.
Worker adapter/flag, API+worker context validation, Mobile notifications/reply and
all-company recovery remain next, followed by remaining P0–P8 and reusable ports.


## Android release startup crash corrected — 1 November 2026

User-reported release10 force close is confirmed by its application crash stack:
StarterApplication resolves CollaborationDeliveryWorker, which constructs the SDK
endpoint from IdentityServer.audience (`.../api/v1/`). The strict root-only endpoint
rejects that path before any Activity appears. The Android host now uses an explicit
`fromApiBaseUrl` factory that validates the original configuration and retains only
its origin for the gateway. API audience, account partitioning, TLS/Dev opt-in and
strict directly configured root validation stay intact.

[Evidence](measurements/android-startup-gateway-2026-11-01.json): three regression
cases, full root check, 2,844 desktop +173 Android host +10 build-logic cases and
release assembly/lint pass (zero errors, one warning), 856s Gradle /859.865s wall.
Three source inputs remain unchanged. Actual **StarterApplication**, not the separate
provider proof app, passes two 12-second stable cold starts with readable/resumed
onboarding UI and inspected screenshots. Universal release11 test APK SHA256
0054ee2314c9ffa243ca22779bc8663ec5e097886920d4a905a49cd520d5ce12.
An emulator watchdog exit before install and obsolete Android35 activity-field
probe were fixture failures; no additional product change. Final run35.173s.
The isolated emulator is stopped before the next build.

**Continue immediately:** clean-source arm64 release11 build/install-only on
10.77.77.3:5555 with notification, existing signer/data and pulled-byte verification.
Helpers are prepared in `.work/release-phone-2026-11-01-build11/`. Then resume
Platform queue/FCM delivery. Platform211d5d65 is pushed; its uncommitted handler/
policy/DI and new SQL/policy tests are formatted but not yet run. Callback/TLS/queue
are already implemented. Worker adapter/flag, Mobile receive/decrypt/reply, all-company
recovery, Starter ports and remaining P0–P8 continue; all54 groups remain unaccepted.


## Hosted five-case cancellation recovery verified — 1 November 2026

[Hosted evidence](measurements/chat-cancellation-native-ci-2026-11-01.json) audits
41351236140 at Mobile 302aa192 / Platform d48ac2f8: all five native cases and six
cleanup cases pass, with 32 evidence hashes. Proof APK SHA-256
`5438147bc67881ec9b2a3f4547e261c1035f5f21336709b4aa9c471dd9427829`.
SDK suite 61.318s, scheduled 85.907s, three-worker suite 186.936s; worker method
times cancelled 54.154s, queued 53.463s, frozen 53.083s. Cancelled identity/archive
and draft revision survive real death beside one independent command; no delivery
receipt or replay wake appears, recovery clears only that draft and its original
is absent from acquired history. Each worker confirms one independent encrypted
original plus 32 peer seeds. This is not remote deletion or native queue erasure.
Exact pass counts and observed pending-key 503 counts are retained in the evidence.

Continue push implementation. Platform e104414e now has validated immutable encrypted
pusher binding storage/schema-115 recovery. Outgoing activation through current
HRIS admission and a fixed vendor endpoint is undergoing real API/worker checks;
private callback ingress, durable recovery, Mobile receive/reply and reusable
Starter ports remain next. No live provider/schema rollout or full PRD acceptance.
Phone remains installed release 10 from c0371a38; these are fixture/docs-only Mobile
changes. Next validated app product checkpoint must be code 11+, notify first,
explicit WireGuard serial and install-only. Continue after every commit/install.

## Installed-source native regression audited — 1 November 2026

[Hosted evidence](measurements/chat-cancellation-ui-native-ci-2026-11-01.json) audits
41347895253 at c0371a38, the release-10 product source installed on the phone.
Its older Platform bad75bd0 fixture passes SDK 60.410s, scheduled 88.926s and queued/
frozen pair 105.883s, six cleanup tests and 26 artifact hashes. Proof APK SHA-256
`86d3115afa863b36759b59d477c73e5365425cb31d61d0de5663320314c1dcd4`.
Both workers take one pass, acquire 33 originals/four history actions/43 references
(ten state-only); frozen recovery retains exact identity/revision and zero initial
commands. This headless run does not exercise the cancellation dialog or cancelled
original, and predates the corrected pending-key response. Separate newer local
five-case proof remains authoritative for those cancellation/publication boundaries.

The newer five-case 41351236140 at 302aa192 / Platform d48ac2f8 is now running.
Continue notifications: Platform a31cd57a adds validated FCM visible/background
profiles (15 push, 13 policy, eight real worker cases); fixed opaque Matrix pusher
transport is being validated next, followed by admitted binding/durable callback
recovery and Mobile receive/reply. No live provider rollout or PRD acceptance.
Phone remains verified release 10. Install the next validated product checkpoint
as code 11+ with notification, explicit WireGuard serial and install-only; do not
drive its UI. Continue the full implementation after each commit/install.

## Cancelled-draft native proof and publication retry corrected — 1 November 2026

[Five-case evidence](measurements/chat-cancellation-native-2026-11-01.json): 356s Gradle / 357.121s wall,
68 Mobile/five Platform inputs, APK `3c15eb832190528e92cd85c8385e3367815cd6cc6ae809f7dd26525af4c17275`.
CANCELLED_DRAFT retains an authenticated cancellation and matching frozen draft through real
death beside one independent command. Replay does not schedule or create a receipt; recovery
clears only that draft, excludes its history and sends the independent original once plus
32 peer seeds. QUEUED/FROZEN_DRAFT, SDK and WorkManager remain separate passing cases.

The first run failed frozen-only after bounded delivery retries (409s / 409.864s); the other
four cases including cancellation passed. Trace: temporary key upload/query 503, then
sendToDevice 400/device_keys_required, later successful uploads but no room send. Real-SQL
reproductions confirmed a valid version 0 reservation was being classified as malformed.
It now returns UNAVAILABLE/device_keys_pending (HTTP 503), preserving confirmed-key/fingerprint
and current owner/permission gates. No reservation still gives validation; no trust, schema,
DI or operation-deadline change. Three focused cases pass; full 40 domain/210 data/26 HTTP,
format/architecture pass 423s / 424.148s. Initial two failed reproductions and full native
failure are retained. The final QUEUED native case actually receives one pending-key 503, retries in two worker passes and completes exactly 33 encrypted sends; frozen/cancelled cases each take one pass. All cleanup succeeds and the owned emulator is stopped. Mobile proof/quality passed 145s / 148.995s, cleanup six; 69 Python cases pass.

Hosted [448ab3ad run 41344248284](measurements/chat-frozen-native-ci-2026-11-01.json) and
[648410c4 run 41345994459](measurements/chat-cancellation-policy-regression-native-ci-2026-11-01.json)
are audited separately, 26 hashes each. Both use the earlier four-case Platform fixture;
neither proves this newer native cancellation/pending-publication boundary. UI run 41347895253
at c0371a38 was still running with the old pin; audit its actual source if it completes.

Platform `d48ac2f866e7f6395cad035424b9fe76d9fdb743` is committed, pushed and pinned by Mobile collaboration-hris CI.
The full job budget is now 45 minutes (the old four-case hosted job took 29m30); operation
and test retry limits are unchanged. YAML and the remote source ref were verified.
**Continue immediately:** commit/push this Mobile fixture/evidence, then implement FCM/pusher
admission, opaque durable notification recovery and native inline replies. Platform `.work/collaboration-push-next.txt` records official Matrix v1.16 and current
transport/owner findings; move concrete implementation contracts into Git. Current FCM mapper
is inbox-only NORMAL/silent-Apple; current Mobile runtime and inbox push use selected company.
Do not silently switch workspace or claim all-company background support. Notifications must
use current account/company/device/room admission and the same encrypted message outbox.
Port only verified generic notification/scheduler primitives to Starter. All 54 groups remain
unaccepted; continue all P0–P8. Live backend/provider deployment is unchanged.

Phone remains verified release 10, source c0371a38, installed 20:44 UTC over 9 with exact pulled APK,
existing signer/data and no UI launch. These new Mobile edits are fixtures only, so another
phone build is unnecessary. Next validated app-feature install must use code 11+, notify first,
install-only on 10.77.77.3:5555. Never drive the phone UI; use the owned emulator for tests.

## Release 10 installed and verified; native cancellation proof next — 1 November 2026

Clean source `c0371a38d1ae2f8c890c300648ec849089c3836a` is installed in place over code 9 on
10.77.77.3:5555 at 20:44 UTC. Build passes in 241s Gradle / 245.272s wall; release lint
zero fatal/errors and one warning. Arm64 APK 87,330,800 bytes, SHA-256
`e366cdcd3eaa997c44004985f53b7de9d15dd75c18c21f0f4d70c0bbd84589c9`. All 64 reviewed inputs, embedded VCS,
private API/Firebase, existing signer and 16 KiB alignment match. Install and verification
42.888s; pulled APK matches exactly. App identity and first-install time are preserved,
with no uninstall, data clearing or UI launch. [Release evidence](measurements/android-release-cancellation-2026-11-01.json).
Future updates require code 11+, notification first and install-only.

**Continue immediately:** actual cancellation recovery with the real SDK/HRIS/Synapse.
Add CANCELLED_DRAFT alongside separate QUEUED/FROZEN_DRAFT cases: retain a cancelled frozen
original through death while one independent queued message sends once. Bind cancellation
ID/body hash/revision; archived replay must not wake, receipt remains absent, worker clears
only the matching frozen draft, and exact gateway sends remain one plus 32 peer seeds.
Platform fixture/driver changes are WIP; 69 Python cases and test compilation/quality pass
(30.671s wall). Apply the prepared Mobile helper, validate a matching proof APK, run the
owned emulator, retain source-bound evidence and pin the new Platform commit in hosted CI.
`.work/native-cancellation-next.txt` and `.work/apply-native-cancel-mobile.py` supplement this
contract. Hosted frozen audit41344248284 is ready for Git; it predates cancellation.
Then FCM/pusher/gateway integration, inline replies and all remaining P0–P8. No complete
feature group or live backend/provider rollout is claimed by this phone update.

## Queued cancellation UI validated; install release 10 next — 1 November 2026

The [cancellation contract](collaboration-cancellation.md) now includes explicit confirmation,
exact-selection retry after lost replies, duplicate suppression, a five-second waiter deadline,
changed/started-message rejection and late account/disposal fences. Only eligible owned Queued
rows expose the action. The current draft is preserved; modal interactions cannot stack.

[UI measurement](measurements/chat-cancellation-ui-2026-11-01.json): 64 cumulative inputs,
17 changed source files. Full check passes 205s Gradle / 208.895s wall with 2,840 desktop,
401 domain/263 data/101 presentation browser, 170 Android host, six cleanup cases, proof
APK/lint (zero fatal/errors, 47 warnings) and format/architecture/detekt. Focused cancellation
ViewModel ten and thread layout eight cases pass. Initial compilation failed only two test
generic-inference expressions; corrected and retained. A subsequent new desktop-only test
runs an actual 320 × 640 dialog at 200% font; desktop/quality passes 48s / 51.475s with fixed
Close/Retry controls while the long body scrolls. Three screenshots were visually inspected;
full and supplemental commands keep separate source/report attribution.

**Continue immediately:** commit/push, then clean signed release 10 using
`.work/release-phone-2026-11-01-build10/run-reviewed-build.py`. Notify before installing,
use install-only on 10.77.77.3:5555, verify the pulled APK and commit/push release evidence.
Phone remains release 9 until that succeeds. Then actual native cancellation proof,
FCM/replies and all remaining P0–P8. No complete feature group or live provider rollout.

Hosted four-case run 41344248284 at 448ab3ad / Platform bad75bd0 has completed successfully;
download and audit its exact artifacts next. It predates cancellation. The earlier three-case
41340935053 audit is already recorded. All previous checkpoints below are historical.

## Hosted recovery regression audited; continue cancellation UI — 1 November 2026

[Run 41340935053 audit](measurements/chat-draft-recovery-native-ci-2026-11-01.json) at 1ddfba6e /
Platform 79326538 passes SDK 54.085s, direct 63.689s and scheduled 62.336s, six cleanup cases
and twenty downloaded evidence hashes. APK `cd32c3984577324f919dee9eaa14e0e65bcb881f47fd2544aefc150ad24263c7`.
Ordinary queued/foreground proof covers 33 originals, four history actions, 43 references,
ten state-only and zero admission failures; WorkManager automatically recovers ordinary death
at attempt 1. It contains application draft recovery but does not directly test frozen-only
initial state. New four-case run 41344248284 at 448ab3ad / Platform bad75bd0 is still running;
95964599's pending run was superseded, not a product failure. Audit the completed source.

Queued cancellation policy/storage/application proof is now committed and pushed as 648410c4.
Implement its confirmation UI next using the immediately following checkpoint and
`.work/cancel-ui-next.txt`; then release 10+ install-only and continue the entire PRD. Phone is
still verified release 9. Platform audit d08f07c4 and ledger correction 4d064871 are pushed.
The ledger now points to completed native/draft evidence while retaining all 54 unaccepted
feature groups. No live backend/provider deployment occurred.

## Queued cancellation policy proved; implement confirmation UI now — 1 November 2026

The [cancellation contract](collaboration-cancellation.md) now uses existing full-record archive
CAS to arbitrate against Staging. Cancelled is archive-only with an authenticated version/kind
and distinct purpose; legacy sent proofs remain compatible. Same-ID insertion stays fenced,
Queue replay does not wake, and Stage avoids native acquisition for an already-cancelled ID.
Captured access and complete expected Queued record gate the explicit use case. Expired owned
local access remains usable; confirmed denial/caller cancellation/late ownership stays gated.
Frozen draft recovery clears only that exact cancelled intent, preserving newer drafts and
independent messages. No core schema/Starter business port or native queue erasure.

[Measurement](measurements/chat-cancellation-policy-2026-11-01.json): 56 cumulative inputs,
18 changed Kotlin files, 541 report hashes. Full check passes 348s Gradle / 352.19s wall:
2,827 desktop/436 suites; 401 domain, 263 data, 90 presentation browser; 170 Android host;
six cleanup cases, proof APK/lint (zero fatal/errors, 47 warnings), format/architecture/detekt.
Focused tests: eleven storage, nine policy, three real application SQLite/DI worker cases
plus two Stage cases. The first 143s/146.642s run failed test compilation because assertSame
needed an explicit Any type for two interfaces; corrected only the test and retained evidence.
The worker transport is fake; do not reassign earlier actual native proofs to this source.

**Continue immediately:** commit/push, then implement cancellation UI. Only unmatched owned
Queued/revision-0 rows expose the action. VM retains a complete immutable selected record and
captured access; confirmation invokes CancelCollaborationText, suppresses duplicate clicks,
and retries lost replies using that same selection even if the pending row disappeared.
Changed/started work is unavailable, never success. Scope loss/Stopped/disposal clears private
selection and cancels obsolete waiters; object identity plus access fences uncooperative late
results. Neutral Close wording must not imply undoing a committed cancellation. Keep controls
reachable with long text, 320 dp and 200% font; capture/inspect the separate dialog root at its
actual dimensions. Do not stack draft and cancellation dialogs. Add ViewModel/projection,
semantics/disposal/stale-selection tests, root/browser/Android checks and source-bound evidence.
`.work/cancel-ui-next.txt` retains more details. Then clean release 10+, notify/install-only,
verify the phone and continue native cancellation proof, push/replies and all P0–P8.
Phone remains verified release 9; no cancellation UI is installed yet. No full PRD group accepted.

## Release 9 installed; safe queued cancellation next — 1 November 2026

**Phone updated and verified.** Clean source `448ab3ad` is installed over code 8 at 20:01 UTC
on `10.77.77.3:5555`. Release build passes in 227s Gradle / 231.48s wall; lint zero
fatal/errors and one warning. Arm64 APK 87,314,200 bytes, SHA-256
`acc895f4d7fbb33470678bd0133f1d06edd7d680726c525ac4ca83365bc87db8`. All 41 reviewed source
hashes, embedded VCS, private API/Firebase, existing signer and 16 KiB alignment match.
Install plus verification takes 51.397s. Pulled APK matches exactly, app identity and original
first-install identity remain intact; no uninstall, data clearing or UI launch.
[Installation evidence](measurements/android-release-draft-retry-2026-11-01.json).
Future phone updates require version code 10+, notification first and install-only.

**Continue immediately:** safe queued-only cancellation. Use the existing full-record command
archive CAS so an original ID cannot be resurrected by draft or UI retry. Add archive-only
Cancelled state with authenticated version/kind and purpose-bound proof; preserve legacy sent
completion format and never interpret cancellation as delivery. Ordinary state updates cannot
create Cancelled. Cancel only a complete observed Queued record under captured access and
current local admission. Staging wins or cancellation wins atomically; uncertain native work
is always retained. Queue replay must not wake delivery, Stage must avoid SDK acquisition for
an already-cancelled original. Add SQLite/browser race/reopen/lost-response/key/owner tests,
domain policy and real application worker regression, then confirmation UI with stale-state,
disposal and large-text checks. `.work/queued-cancellation-next.txt` holds detailed notes.
Then incoming push/replies, rich messaging, backups, native/web, calls/meetings and all P0–P8.
No full PRD group or live provider deployment is claimed by this phone checkpoint.

## Draft contention feedback validated; release 9 next — 1 November 2026

Technical shared-partition movement and exhausted three-attempt CAS now return
`client.collaboration_draft_busy`. Composer Retry keeps the immutable write/text and does
not request reload; actual competing-target edits remain changed/reload. Worker discovery
retries both at initial and final boundaries. No encryption, schema or draft format change.

[Evidence](measurements/chat-draft-contention-2026-11-01.json) binds 41 cumulative inputs and
536 reports. Final full integration passes in 190s Gradle / 194.218s wall: 2,802 desktop,
390 domain/252 data/90 presentation browser, 170 Android host, six cleanup cases, proof APK,
lint zero fatal/errors (47 warnings), format/architecture/detekt. Focused storage 18, draft
ViewModel 14 and app worker six cases pass. The initial 202s run failed one leftover test
assertion expecting the old value after newly added successful retry; corrected the expected
replacement and retained the failed inputs/log/XML. This correction required no runtime edit.

**Continue immediately:** commit/push this fix, build clean signed release 9 using
`.work/release-phone-2026-11-01-build9/run-reviewed-build.py`, notify and install-only on
10.77.77.3:5555, then verify the pulled APK and commit/push installation evidence. Phone is
still release 8 until that succeeds. Do not launch or drive its UI. Continue safe queued
cancellation using immutable cancellation archives (`.work/queued-cancellation-next.txt`),
then incoming push/replies and all remaining P0–P8. No full PRD group is accepted.

Hosted native run 41340935053 at 1ddfba6e is still running; 41343165448 at 95964599 is pending
and may be superseded by the next push. Audit the actual completed source. New native CI
uses Platform bad75bd0 and four cases including frozen-only death; the older queued run
does not establish that new boundary. Local actual proof is already committed as 95964599
with its own source hashes and must not be relabelled as validation of this newer fix.

## Native frozen-draft recovery proved; fix contention feedback next — 1 November 2026

[Actual native evidence](measurements/chat-frozen-native-2026-11-01.json) binds 44 Mobile/four
Platform inputs, forty evidence hashes and proof APK `1a6645456001c0c06c1db8ec12ed405c2d9015986ff7366ca9fe32e1b2d2363d`.
Final run 227s/227.82s: four cases/three suites pass. SDK 48.362s, WorkManager 44.822s,
worker pair 110.945s (queued 50.339s/frozen 40.341s methods). Both workers finish in one pass,
with one encrypted original plus 32 peer seeds, four history actions, 43 references/10 state-only,
30/28/30 admissions and zero failures. Frozen-only checkpoints prove zero commands and the
same draft revision/body/ID across real death; matching confirmation clears it. Cleanup
passes and the owned emulator is stopped. Source still matches installed release 8 product
inputs; only fixture changes are newer, so no phone rebuild follows this proof.

Initial failures remain recorded: detekt rejected a 130-line fixture function (now split),
shared checkpoint validation omitted WorkManager PREPARED (restored with regression), and
the second worker case failed at provider login before Android. Its exact failure code was
not captured; provider and Spring trace are now isolated per test and safe kind/code
assertions diagnose future failures. Do not call the original failure an observed 429.
Mobile checks 80s/84.218s pass proof APK/lint/six cleanup cases/quality. Platform checks
28s/28.739s and 66 Python cases pass. Platform `bad75bd0d73c88afa66db7f16493e5e513ba9250` is pushed and
pinned in Mobile CI. Commit/push this companion fixture and audit its hosted run separately.

Prior [CI 41338809672](measurements/chat-draft-index-native-ci-2026-11-01.json) at 500a8edd
passes SDK 60.948s/direct 65.728s/scheduled 75.829s, APK 649f1640, twenty evidence hashes.
It includes indexed storage but predates app recovery; it does not prove frozen-only recovery.

**Continue immediately:** distinguish retryable technical draft contention from a changed
target. Current stable-read checkpoint movement uses client.collaboration_draft_changed,
which makes DraftRetry ask to reload even if only another conversation changed. Introduce
client.collaboration_draft_busy for valid-read movement and bounded technical CAS exhaustion;
keep true target conflicts as changed/reload. Worker retries both; UI retries the immutable
write with its text/ID intact. Add SQLite/browser, app-worker and ViewModel regression cases,
then required integration checks, commit/push and release 9+ install-only at a validated feature
checkpoint. `.work/draft-contention-ui-next.txt` holds details. Continue safe cancellation,
FCM/replies, rich messaging, backups, calls/meetings and all other unaccepted P0–P8 afterward.
No complete PRD group or live provider deployment is claimed. Phone remains release 8.

## Release 8 installed; continue native frozen-draft proof — 1 November 2026

**Phone updated.** Release 8 from clean source `43cce8c0031c310d714890f4912052fe3316cdae` is installed
in place over code 7 on `10.77.77.3:5555` at 19:22 UTC. Build passes in 230s Gradle /
234.022s wall, lint zero fatal/errors and one warning. Signed arm64 APK is 87,314,204 bytes,
SHA-256 `c2858829bcfe87139adde011333369e639f2700d215a652c2b1a569fb1e9f2c4`.
Private API/Firebase, embedded VCS, non-debuggable flags and 16 KiB alignment are verified.
Post-install pull exactly matches bytes/hash; package/signer/application identity and original
first-install identity are preserved. No uninstall, data clearing or UI launch. [Release evidence](measurements/android-release-drafts-2026-11-01.json).
This is the first installed composer-v2 build; do not downgrade to earlier APKs. Future
updates must use code 9+ and notify first, install-only. Helpers/artifacts remain in
`.work/release-phone-2026-11-01-build8`.

**Continue immediately:** actual native frozen-only process-death proof. WIP adds an explicit
FROZEN_DRAFT initial state to the Mobile proof app and Platform driver; pending command count
must remain zero before/after death and the exact draft revision/body/ID must survive. The
new server case retains a separate ordinary QUEUED case and uses isolated Spring trace state.
Python driver 65 cases pass; Kotlin/server compilation and matching proof APK/native execution
are still being completed. These fixture changes were made after release verification and
are not part of the installed APK. Capture source/results, retain failures and commit/push
them after proof. Then safe pending cancellation, push/replies and all remaining P0–P8.
No full communication group is marked complete. Physical iPhone tests remain waived only.

## Hosted native regressions and Starter Apple port audited — 1 November 2026

[Native f904 run 41335935547](measurements/chat-window-native-ci-2026-11-01.json) passes
SDK/direct/scheduled in 53.186s/56.09s/82.571s, APK `85583fca`, eighteen downloaded hashes.
[Native cca28 run 41338026431](measurements/chat-draft-composer-native-ci-2026-11-01.json)
passes in 42.32s/56.409s/59.092s, APK `c8185d69`, twenty hashes including sanitized gateway
observations. Both use Platform `79326538`, six cleanup cases, 33 original-history assertions
and automatic ordinary-death WorkManager recovery. Only the oldest original is individually
asserted after Stop. The prior bf83 exhausted-retry failure remains retained and unexplained.
The cca28 source includes composer-v1 UI; neither fixture exercises draft transition recovery,
and neither source includes current v2/application recovery. New source `1ddfba6e` is pushed.

[Starter Apple run 41338065450](measurements/apple-replica-hosted-2026-11-01.json) at `c46151e7`
passes 86 simulator cases/25 suites, including two actual SQLite replica tests in 0.715s.
Compile/tests/Xcode take 594s/68s/627s; associations pass, portable/Windows intentionally skip.
The fixture matches preceding Mobile proof. Starter audit `fdd7f7b` is pushed. No physical
iPhone execution, live provider deployment or additional phone installation is claimed here.

Continue immediately with clean release 8+ from the validated recovery input hashes, notify
and install-only; then actual native freeze-only proof and the rest of the communications PRD.
The phone remains on verified release 7 until that separate installation succeeds.

## Prepared-draft recovery integrated and validated — 1 November 2026

The application delivery worker now recovers up to eight indexed frozen drafts before draining
ordinary outbox work, using the original body/ID and captured account ownership. Its separate
encrypted cursor advances before attempts and wraps once. Freeze wakes the existing payload-free
task; startup independently wakes it. Confirmed unqueueable contexts retain review-required
originals outside the automatic index, so more than one failed batch cannot starve valid work.
No command is erased. Back flush is bounded to 1,500 ms and keeps an admitted writer alive.

[Contract](collaboration-drafts.md) and [source/report evidence](measurements/chat-draft-recovery-2026-11-01.json)
bind 41 cumulative inputs. Final integration passes in 286s Gradle / 290.427s wall:
2,801 desktop cases/433 suites; 390 domain, 252 data and 89 presentation browser cases;
170 Android host cases, six proof cleanup cases, proof APK, lint (zero fatal/errors,
47 warnings), format/architecture/detekt. Focused counts: six app/SQLite worker cases,
eighteen storage cases, nine recovery policy cases and thirteen draft ViewModel cases.
All report counts have zero failures/errors/skips. Initial full integration also passed
(423s/426.6s, 2,799 desktop); review then identified read contention incorrectly classified
as storage failure. Final tests prove initial/final discovery retries and no stale-page
publication when another conversation advances the checkpoint. Real malformed/key failures
remain blocked. The original passing inputs/log are retained in the final measurement.

**Continue immediately:** commit/push, clean signed release 8+ build from these reviewed
inputs, notify then install-only on 10.77.77.3:5555, verify the pulled APK and continue.
Release 7 is currently installed; nothing newer has yet been built/installed. The prepared
build8 helpers are local under `.work/release-phone-2026-11-01-build8`; run-reviewed-build
checks this measurement and a clean tree. Do not install old versions 5/6/7 over a newer app.

Application recovery tests use the real SQLite/DI/use cases with a fake native transport.
Add actual SDK/server freeze-only process-death proof next; current hosted native fixtures
still exercise ordinary queued recovery. Preserve the existing WorkManager queued scenario
and add an explicitly identified frozen-draft scenario, not a renamed queued assertion.
Then safe pending cancellation, incoming FCM/replies and all remaining P0–P8. No complete
PRD group, generic Starter policy port or new core schema is claimed.

## Indexed prepared drafts validated; app recovery next — 1 November 2026

`composer-v2` replaces the uninstalled v1 prototype with one account/company partition and
one encrypted document per conversation. The prepared tag and frozen body commit together;
indexed reads authenticate up to eight candidates plus one lookahead. Three bounded technical
CAS attempts tolerate unrelated-document checkpoint movement, never overwrite changed target
text. The same protected history key/AAD scope and empty logical revision fences remain.
No core database schema or Starter policy port. Verified installed release 7 has no drafts;
no first draft-capable build has been made or installed.

[Evidence](measurements/chat-draft-index-2026-11-01.json): 113s Gradle / 116.59s wall, fifteen
SQLite cases, 249 data browser and 381 domain cases per JVM/browser, quality checks pass.
603 mixed drafts yield 201 frozen candidates, maximum ten decryptions/page; reopen, atomic
index/body under a lost reply, competing conversations, bounded CAS and forged lookahead are
proved. Initial compilation failure from a nullable cross-module property was corrected and
retained. Full root/Android checks still follow application recovery integration.

**Continue immediately:** add owned indexed draft recovery/cursor use cases, wake the existing
payload-free delivery key after freeze, and have the application worker coordinate Queue and
Confirm before ordinary outbox delivery. Preserve original IDs on lost replies and clear only
matching confirmed drafts. Keep batches/deadlines/fairness explicit; permanently unavailable
individual conversations must not spin or starve others (consider a durable review-required
flag excluded from automatic discovery, with explicit retry retaining the ID). Storage or
scope failures must not erase intent. Then root/browser/Android/proof/lint, release 8+ with
install-only, and remaining PRD. A bounded Back flush/leave choice should also remain usable
while an uncooperative local write is pending; test that boundary during integration.

Native run 41335935547 at f904 is PASS; artifacts are downloading into `.work/ci-41335935547`
for audit. It predates composer UI/v2; do not infer those are accepted. Native cca28 run
41338026431 is in progress and contains controlled retry diagnostics. Starter Apple run
41338065450 at c46151e is compiling tests; Mobile Apple audit is already committed.

## Apple replica execution audited; continue indexed draft recovery — 1 November 2026

[Hosted source/XML audit](measurements/apple-replica-hosted-2026-11-01.json) proves run
41334974289 at Mobile `c0e91d6b`: 106 cases/29 suites, zero failures/errors/skips, including
two real SQLite replica cases in 1.047s. Compile/tests/Xcode take 628s/83s/941s; simulator
application and generated association checks pass. This source predates every draft addition.
The exact temporary-database fixture and two CI tasks are now ported to Starter after its
local quality checks. Starter's own hosted proof is being dispatched; audit it separately.

Composer commit `cca28f06` is pushed with focused UI/browser/DI checks, not root/Android
acceptance yet. Continue indexed prepared-send storage and application-owned recovery from
the preceding handoff, then all required integration checks and a newer install-only release.
The phone remains on verified release 7; no additional installation occurred for test/docs work.

## Composer connected and focused recovery tests pass — 1 November 2026

[Composer evidence](measurements/chat-draft-ui-2026-11-01.json) binds 14 inputs and the inspected
320 dp/200% text screenshot. 215s Gradle / 218.71s wall passes 102 JVM presentation cases,
88 browser cases and two application-binding cases plus format/architecture/detekt. Twelve
new scenarios cover loading, coalescing, uncertain edits, late emissions, recreation at
freeze-before-queue and queue-before-ACK, lost clear response/newer draft, invalid input,
explicit navigation/reload choices and hidden ownership loss. Initial 67s attempt had one
old assertion expecting thread failure; the composer-specific error assertion and localized
validation guidance were corrected. That failure is retained in the measurement.

One serial writer owns autosave. Sending flushes the latest text, persists original ID/body,
queues and confirms exact durable command/archive. Known frozen IDs skip re-preparation on
retry so a later clear/new draft cannot cause another insertion. Successful observer values
initialize once; they never overwrite active text. New input and immutable uncertain write
are separate. Back/Setup flush edits, with in-product confirmation on unsaved exit/reload.
**No new phone build:** release 7 remains installed. Full root/Android checks follow the
pending indexed recovery integration; focused tests are not full application acceptance.

**Continue immediately:** implement bounded indexed prepared-draft discovery and app-owned
Queue/Confirm recovery before the first draft-capable release. Preferred design is one
account/company replica partition with a document per conversation, authenticated prepared
tags committed atomically with the frozen body, and existing indexed queries; no new core DB
schema. The current per-conversation v1 has never been in the verified installed APK. Preserve
empty revision fences and complete-target CAS, handle unrelated-conversation checkpoint
contention with bounded technical retry, retain key-loss/ownership protections, and prove
603 mixed drafts plus freeze-only worker restart. `.work/chat-draft-index-next.txt` contains
concrete design notes. Do not use a separate non-atomic index or decrypt every conversation.
Then full root/browser/Android/proof/lint, signed release 8+ install-only, and all remaining PRD.

Mobile Apple run 41334974289 at `c0e91d6b` is now audited PASS: 106 cases/29 suites (two new
SQLite cases, 1.047s), compile 628s, tests 83s, Xcode 941s. Its fixture/CI step is being ported
to Starter, whose local quality check is running. Commit separate Apple evidence and dispatch
Starter hosted execution. Native f904 run 41335935547 was compiling server fixtures; diagnostic
bfbb run 41337171686 is pending. Keep audits tied to immutable sources; neither covers this UI.

## Failed native retry diagnostics preserved — 1 November 2026

[Audit and follow-up](measurements/chat-window-ci-failure-2026-11-01.json) retain run 41334029287
as failed, with SDK/scheduled passes and unknown direct delivery. The direct probe now throws
the final controlled HrisProofFailure category instead of a generic exhausted check. Room,
history, delivery and timeout aliases are bounded; retry count/waits are unchanged. Native CI
uploads the already-sanitized `gateway-observations.json` written by Platform's finally block.
Validation: 190s proof assembly, six cleanup tests, format/architecture/detekt pass; YAML parsed.
No runtime fix or new acceptance is claimed. Push and audit a matching hosted run while
continuing composer UI/recovery. Latest product APK remains installed release 7.

## Draft transition policy validated; composer wiring next — 1 November 2026

Five new domain use-case/helper files and twelve scenarios implement owned Observe/Edit/
Prepare/Confirm. Local age does not hide drafts; denial/cancellation/late owner checks bracket
I/O. Editing retries its caller-retained revision; freeze uses the prior editing revision as
submission ID and a fresh draft revision. Confirmation requires exact durable command or
archived completion and preserves newer text. [Evidence](measurements/chat-draft-policy-2026-11-01.json):
75s Gradle / 78.44s wall; 381 JVM and 381 browser cases in 32 suites each, quality checks pass.
No UI/DI integration yet. Release 7 is still installed. Continue immediately with a bounded
composer save owner, process reopen/retry, explicit prepare-to-outbox crash recovery, then all
remaining PRD work. Do not treat this policy checkpoint as completion.

Hosted native 41334029287 at `bf83c71e` **failed**: SDK 47.387s and scheduled 56.862s pass,
direct worker 163.103s exhausts four bounded attempts at HrisWorkerMatrixProbe:184 before
foreground assertions. All use proof APK `8d391c22`; driver cleanup true. Final retry reason
was lost to a generic check, so direct delivery is unknown. Artifacts/log are retained under
`.work/ci-41334029287`; audit/record and add bounded controlled retry diagnostics before
investigating/re-running. Apple 41334974289 at `c0e91d6b` was building its simulator app;
compile passed, test execution has not yet been audited. Do not port its fixture before proof.

## Composer storage validated; policy/UI next — 1 November 2026

[Draft storage](collaboration-drafts.md) adds eight domain/data/test inputs: immutable draft
slots, validation, raw repository contract and encrypted atomic snapshots under each history
partition's `/composer` suffix. Existing history credentials/AAD isolation protect all hosts;
no new key wiring. Empty revisions fence late writes; complete-record CAS and exact replay
survive lost replies. Get/replay recheck the checkpoint after decryption, and inactive
composer-only data prevents key regeneration. Maximum UTF-8 text uses a document, not the
smaller checkpoint. [Evidence](measurements/chat-draft-storage-2026-11-01.json): 101s focused
SQLite/browser/quality checks, ten new storage scenarios on each engine, no failures.
This is storage only; no user flow or new phone build yet. Root/application checks follow
its actual integration. Release 7 remains installed.

**Continue immediately:** add owned Observe/Edit/Prepare/Confirm draft use cases and UI.
Prepare can use the prior editing revision UUID as its stable message ID and a fresh draft
revision, so a lost freeze response can match `current.submissionId == expected.revision`
without conflating a later identical-text draft. Freeze the exact body before queueing;
confirm against `messages.get`, which already reads authenticated archived completions.
Clearing must preserve a newer draft; explicit retry reuses the saved ID. Bound save work,
retain dirty input across late observations, and test failure/account/cancellation races.
Decide/implement prepared-send recovery across the prepare-to-outbox crash gap; never claim
ordinary queued-worker recovery proves that separate boundary. Then safe pending cancellation,
incoming push/replies and all P0–P8. No generic Starter port for this HRIS storage policy.

Apple CI [41334974289](https://github.com/fajarxfce/hris-mobile/actions/runs/41334974289) runs at
`c0e91d6b` and was compiling simulator tests. Audit its exact XML before porting the new
iosTest/CI step to Starter. Native [41334029287](https://github.com/fajarxfce/hris-mobile/actions/runs/41334029287)
was executing emulator cases at `bf83c71e` (the installed release source). Both run against
immutable earlier sources; do not attribute them to this draft addition.

## Apple replica execution prepared — 1 November 2026

`AppleReplicaStoreTest` adds two isolated real Room/BundledSQLite cases under iosTest.
A UUID-named temporary directory keeps the application database untouched. Shared 603-record
reverse queries, publication/account/cancellation/bounds and close/reopen/reauthentication
are covered. Mobile CI now explicitly links and runs `core:database` simulator tests.
[Inputs/checks](measurements/apple-replica-2026-11-01.json): 34s local formatting/architecture/
detekt and parsed workflow pass. **No native execution result yet:** dispatch Apple CI at
this exact pushed source and audit the resulting XML before porting the new test/step to
Starter. No production change; release 7 remains installed. Continue durable draft/submission
recovery while hosted checks run, then all remaining P0–P8; do not stop at this fixture.

## Release 7 installed; hosted foreground proof audited — 1 November 2026

**Phone is updated.** Release 7 from Mobile `bf83c71e1688ce5f57ba1dad39f95900b82f8276`
was installed in place over code 4 on `10.77.77.3:5555` at 18:02 UTC. Versions 5/6 were
never installed. Build: 249s Gradle / 253.35s wall, clean before/after, private API/Firebase,
16 KiB alignment and embedded VCS verified; lint zero fatal/errors and one warning.
APK is 87,264,680 bytes, SHA-256 `1ab847df1e169cfaf5563296159d772148896e193aaa5cc1c969b05ba1f4a47b`.
Preflight confirmed the existing signer. Post-install pull matches exact bytes and confirms
version 1.0.0/code 7, non-debuggable, same application identity and first-install identity.
No uninstall, data clearing or UI launch. [Release evidence](measurements/android-release-window-2026-11-01.json).
The build7 helpers/artifacts are retained under `.work/release-phone-2026-11-01-build7`.
Do not install older prepared builds. Notify before each future install; keep install-only.

Native hosted [run 41331799148](https://github.com/fajarxfce/hris-mobile/actions/runs/41331799148)
is fully audited: Mobile `2b2d81b3`, Platform `79326538`, APK `d94ba860`, Android API 35.
All three cases pass: SDK 56.228s, direct worker/foreground 67.626s, scheduled worker 90.851s;
six cleanup unit cases also pass. Direct recovery verifies 33 encrypted originals, four
history actions, 43 references/ten state-only references, zero admission failures and complete
cleanup. The oldest original is explicitly retained after Stop; all 33 are checked before
Stop. Scheduled recovery keeps the OS job through ordinary death, starts automatically,
restores networking and finishes SUCCEEDED. [Audited 18 files](measurements/chat-foreground-ci-2026-11-01.json)
retain the earlier run 41325612922 ClosePending failure and its unknown direct delivery result.

This hosted result precedes the new 150/50 window: matching native run 41334029287 at
`bf83c71e` is still separate. Starter port `cd7022e` is pushed. No live server deployment.
**Continue:** apply `.work/apple-replica-test.txt` to an isolated iosTest and wire Mobile CI,
execute hosted simulator proof before porting that additional test/step; then durable drafts,
exact submission recovery, safe cancellation, incoming push/replies and every unaccepted
P0–P8 group. Physical iPhone tests remain waived. The prepared `.work/chat-drafts-next.txt`
is guidance only. Do not end the long run at this installed checkpoint.

## Bidirectional local history beyond 500 originals — 1 November 2026

The ten-page stop is removed. One coherent encrypted window reads at most 150 originals
plus one authenticated lookahead, retaining 50 overlapping keys on each move. Older/Newer
can traverse the 603-original SQLite/IndexedDB fixture in both directions across reopen;
there is one active observer and no growing cursor stack. Reaching the head resumes its
live query. Old windows exclude unrelated pending/live rows while matching edits/redactions
remain visible. Sending returns to the head after queue success and publication. Drafts
survive window navigation only; process-durable composer work remains next.

[Contract](collaboration-local-window.md), [measurement](measurements/chat-history-window-2026-11-01.json)
and [inspected screenshot](images/chat-thread/chat-thread-window-large-text.png) bind 30 inputs.
Final 595s checks pass root/quality/browser/Android-host/proof APK/lint: 2,742 desktop cases
in 428 suites, 369 domain / 234 data / 76 presentation browser cases, 170 Android-host and
six cleanup cases, zero failures/errors/skips. Proof lint has zero fatal/errors and 47 warnings.
The initial 93s attempt passed domain/data but failed the new lazy-list test's scroll action;
its reports/source are retained, and the corrected UI test covers 320 dp/200% text, stable
overlap and interruptible return to latest. Proof APK `8fc2abc6` is built, not installed.

Eight reusable raw-query files match the Starter port exactly. Both pass real SQLite and
IndexedDB checks; Starter's independent 268s run passes 341 desktop / 35 browser cases,
Android/lint (zero errors) and production Web. [Portable evidence](measurements/replica-reverse-query-2026-11-01.json).
No schema migration or HRIS policy was copied. Apple has no new simulator result for this
increment: add an isolated database iosTest and execute it in Mobile CI before porting that
additional wrapper. The existing physical-iPhone waiver does not remove this verification.

**Continue the long run.** Build release 7 from the clean pushed tree using the prepared
`.work/release-phone-2026-11-01-build7/run-reviewed-build.py`, verify its artifact and deliver
with preflight/install-only when phone `10.77.77.3:5555` returns. Notify before install; preserve
signer/data and never drive the phone UI. Release 6 remains ready/uninstalled, last confirmed
installed code is 4 and the reconnect question is pending. Do not reuse build6 install helpers
for release 7. Latest ADB attempt still timed out. No live service was changed.

Audit native CI run 41331799148 (Mobile 2b2d81b3 / Platform 79326538), currently executing
its emulator cases; it precedes this larger window. Earlier 41325612922 failed direct-worker
ClosePending while SDK/scheduled passed; downloaded artifacts and a bounded audit are in
`.work/ci-41325612922` and `.work/native-ci-predecessor.json`, awaiting the companion hosted
record. Keep every failure boundary. Next implement encrypted durable composer snapshots,
exact submission-ID recovery and safe cancellation, incoming push/replies and all remaining
P0–P8. `.work/chat-drafts-next.txt` is design guidance, not implemented functionality.

## Actual foreground history and server key-publication race — 1 November 2026

The native fixture now resolves the real ChatThreadViewModel after application-worker
recovery. It sends 32 encrypted peer seeds, confirms the oldest original is initially
absent, pages provider history, verifies all 33 originals and retains the oldest row
after Stop. Cleanup drains the existing close contract and preserves primary failures;
six unit tests cover bounded retry, all-resource cleanup and cancellation. Product
shutdown deadlines are unchanged. No forced peer key refresh remains in the fixture.

[Contract](collaboration-native-worker.md) and
[measurement](measurements/chat-foreground-native-2026-11-01.json) bind 10 Mobile/13
Platform inputs and all failed attempts. The same APK `d685231d` failed before and
passed after Platform's schema-114 publication correction: 94s, four older actions,
43 references, ten state-only references, no unresolved message references, admissions
30/28/30 with zero failures and complete cleanup. SDK/scheduled regressions pass in
120s combined (55.826s/46.057s test time): three/one encrypted sends, held room isolated,
automatic ordinary-death startup and restored network settings. These are local results;
new hosted artifacts still require their separate audit.

Platform `79326538942e83ef89bf6a2b4c251888db7fdd6d` is pushed and pinned by native CI.
Read-only discovery verifies provider keys against the prior immutable reservation;
room send, one-time claims and to-device send still require publication confirmation.
Final server checks: 110 registration/crypto-admission and 26 API cases pass, with
schema-114 upgrade/restore and recovery-gate evidence retained. The broad run's three
failures and their correction remain recorded. Mobile's final 305s run passes APK,
lint, formatting, architecture, detekt and root checks: 2,733 desktop cases/427 suites
plus six cleanup tests, zero failures/errors/skips. CI now runs and uploads cleanup
unit XML alongside all three native cases.

**Continue, do not stop at this checkpoint.** Push the companion commit and audit
hosted CI, then bounded local browsing beyond ten pages, durable drafts, safe pending
cancellation, incoming push/replies and all remaining P0–P8. No complete PRD group,
Apple/web acceptance, live deployment or Starter port. The emulator is stopped.
Release 6 remains prepared but uninstalled: latest WireGuard ADB attempt timed out,
reconnect question is pending and last confirmed installed code was 4. Use the build6
preflight/install-only helpers, notify before install, preserve signer/data and never
drive phone UI. Product release source remains `2d6753ee`; this increment's Mobile
changes are fixture/test infrastructure.

## Release 6 ready; three hosted native cases audited — 1 November 2026

Latest prepared private APK is now release **6**, superseding the uninstalled release 5:
`.work/release-phone-2026-11-01-build6/hris-mobile-dev-release-1.0.0-6-arm64.apk`.
SHA-256 `63cba8dccec9c50e73b13f39f4645304b98cec7b84d79b8091f5f5e9299d27ba`,
87,264,632 bytes, package `dev.fajar.hris.mobile.dev`, version 1.0.0/code 6. It is non-debuggable,
non-testOnly, arm64-only and ZIP-aligned at 16 KiB. API/Firebase, the previous installed
signer and embedded VCS commit `2d6753ee64f4616adec6ad05783ad2d197a535e9` match. Source
stayed clean before/after the 295s Gradle build (299.203s wall). Lint: zero errors/fatal,
one existing DataExtractionRules warning. [Release evidence](measurements/android-release-history-2026-11-01.json).

**Not installed.** Phone `10.77.77.3:5555` still times out; reconnect question pending,
last confirmed installed code remains 4. Updated `preflight.py` and `install.py --install-only`
are in the build6 directory. Notify before installation, verify the current signer/version
and exact identity, then verify pulled APK bytes and preserved data identity. Do not reuse
build5 helpers, uninstall, clear app data or drive the phone UI. Continue work independently.

Hosted native run [41322946959](https://github.com/fajarxfce/hris-mobile/actions/runs/41322946959)
succeeded and downloaded evidence is audited in [this record](measurements/chat-native-ci-2026-11-01.json).
It binds Mobile `1a114442`, Platform `075e63db` and one proof APK `9a42e773` on Android API 35:
scheduled case 70.507s, SDK case 61.405s, direct worker 27.600s. Three/one/one encrypted sends
respectively for SDK/direct/scheduled, zero admission failures, full cleanup; scheduled
job retained its namespace/CONNECTED/task-only input through ordinary death and restarted
automatically with the sender closed, then WorkInfo SUCCEEDED. Radio settings were restored.
This does not include foreground paging from `2d6753ee`, deliberate retry/cancel races or
capacity acceptance. Earlier run `41319810722` still failed worker cleanup with ClosePending;
a later successful run does not erase or diagnose that failure.

**Next: continue, do not stop at this release checkpoint.** Implement/validate bounded native
fixture close draining and primary-error preservation, then actual foreground history via
the real app ViewModel/container in the native worker scenario. `.work/native-foreground-next.txt`
is supplementary design only, not implemented. Extend local browsing beyond ten pages,
then durable drafts/cancellation and all remaining P0–P8. Review explicit Android 12+ backup
extraction exclusions as a separate reusable foundation change, prove in Mobile before porting
to Starter. No live backend/provider or Starter change was made here. Push each atomic commit.


## Foreground remote history validated — 1 November 2026

Older now coordinates retained reference reconciliation, one admitted provider page and at
most two original windows on the screen-owned signal consumer. Local pages remain first;
only potential message events become native anchors or missing-content notices. Unknown
references remain durable without starving later pages. Provider exhaustion is separate
from decryption; concurrent provider growth/replacement resets the scan. Separate loading
and failure state preserves the composer/local history, and exact-anchor capacity recovery
uses explicit admitted reopening. Stop/account denial prevents late publication.

[Contract](collaboration-remote-history.md), [measurement](measurements/chat-remote-history-2026-11-01.json)
and [screenshot](images/chat-thread/chat-thread-history-large-text.png) bind 18 source inputs.
Final root/browser run: 220s; 2,733 desktop cases/427 suites, 368 domain-browser cases,
70 presentation-browser cases, 82 presentation-desktop cases and 170 Android-host cases,
all with zero failures/errors/skips. The image was visually inspected at 320dp and 200% text.
Reader checks passed in 117s; first UI checks passed behavior/DI but failed the 100-line
method rule (101 lines). Extracting the screen-owned consumer fixed that. Full root/browser
then passed in 260s before two final review tests/corrections. The initial wrong Gradle task
name failed before tests. Every attempt is recorded; source hashes match the final run.

**Continue the long run; this is a bounded foreground increment, not completion.** The
existing local window still stops at ten 50-message pages: implement bounded sliding or
bidirectional browsing. Add actual SDK/provider foreground paging evidence; the new
orchestration currently has real use-case/repository-fake tests plus Compose layout checks.
Then durable drafts, safe pending cancellation and all remaining P0–P8. Starter and live
backend/provider are unchanged. No whole PRD group is accepted.

Before broadening the next feature, prepare the updated phone release and address native
fixture cleanup: hosted run `41319810722` passed the SDK scenario but failed direct-worker
instrumentation with `ClosePending` at `HrisWorkerProofContainer.close`. Driver cleanup
succeeded; it never read the worker result after instrumentation failed, so delivery cannot
be inferred from this failure. The lease-level proof helper already allows six bounded
close-pending waits; the application container currently asserts only one. Preserve the
primary failure if cleanup also fails. Run `41322946959` at `1a114442` is still active in
native tests; audit its terminal artifacts. Do not relax product ownership/drain guarantees.

Release 5 remains prepared but uninstalled; `10.77.77.3:5555` still timed out at 16:15 UTC.
The reconnect question remains pending. Prepare a higher validated build for these UI changes,
then notify and install-only with the saved signer/data preflight when the phone returns.
No phone UI interaction, uninstall or data clear. Push each validated atomic commit.


## Actual Android scheduling validated — 1 November 2026

The normal proof Activity now queues through AndroidWorkScheduler while the owned emulator
is offline. Read-only checks bind the exact WorkSpec, production worker class, CONNECTED
constraint and task-key-only input. The driver backgrounds the Activity, kills its exact
PID without force-stopping the package, and confirms the system job remains registered.
Restoring connectivity causes Android to start a new process and run the actual registered
worker; no instrumentation or manual job-run command starts it. The sender is closed at
bootstrap, then restored by the worker. Actual WorkInfo SUCCEEDED, original text/history,
archived completion and one encrypted gateway event establish delivery.

[Contract](collaboration-workmanager.md) and [measurement](measurements/chat-workmanager-2026-11-01.json)
bind six Mobile/four Platform inputs. Scheduled run: 76s, two processes/one ordinary death,
automatic startup, one encrypted send, zero admission failures and complete cleanup/network
restoration. Both prior native scenarios pass in a combined 112s on the same APK `3b5eb0d5`:
four-process/three-death sender recovery (three sends, held room never sent), and direct
worker recovery (one send and peer decryption). All 62 Python cases pass. Initial Android
build 97s, owner-initialization review build 79s, final build/lint/quality 85s; server compile
28s; root checks 57s with 2,710 desktop cases/426 suites and no failures/errors/skips.
The first native run failed because the fixture queried the default JobScheduler namespace;
actual WorkManager jobs use a dedicated namespace on Android 14+. Namespace-aware inspection
fixed the fixture. Every attempt is retained. Emulator is stopped.

**Continue remote older history, durable drafts and safe pending cancellation now, then
all remaining P0–P8.** Scheduled execution is accepted only for this scenario. Deliberate
interruption while sending, retry/cancellation races, production login recovery, incoming
FCM/replies, Apple/web runtime and scale remain separate criteria. No complete PRD group is
accepted. No live provider or Starter change. The native CI workflow now pins Platform `075e63dbdc0271ebf00bea4fd9756c7441408328`, runs all three scenarios
and uploads only bounded checkpoint/trace/result evidence. YAML/target checks pass; audit
the matching hosted run after push before claiming its acceptance. The older sender-only hosted run `41319561027` reports success; the two-
scenario run `41319810722` is running. Audit artifacts before claiming hosted equivalence.

Release build 5 remains prepared but uninstalled because WireGuard ADB is unreachable; the
reconnect question is still pending. Use the saved preflight/install-only helpers when the
phone returns, preserving signer/data and avoiding UI interaction. Continue independently
while unavailable and push every validated atomic commit. The implemented WorkManager
proof supersedes the earlier `.work/scheduled-proof-draft` design files.


## Release build 5 prepared; phone connection pending — 1 November 2026

The user asked to install progress and continue. The selected private `devRelease` APK is
`.work/release-phone-2026-11-01/hris-mobile-dev-release-1.0.0-5-arm64.apk`, SHA-256
`ab31489690b4d311665e0005878a7e221ee4b1ebba2f2aadb2964beb5d660036`, 87,248,188 bytes.
Package `dev.fajar.hris.mobile.dev`, version 1.0.0/code 5, non-debuggable, correct WireGuard
API/Firebase configuration and the existing signing certificate are verified. Its DEX
matches the universal release; only Crashlytics VCS resource metadata differs. APK ZIP
alignment passes at 16 KiB. Product sources match validated `4213d5f5`; the embedded Git
revision is `ccc7c4fc`. Root tests: 2,710/426 suites, zero failures/errors/skips. Release
lint: zero errors/one existing warning. First packaging failure, successful retry and
44-second arm64 assembly/lint are retained in the [measurement](measurements/android-release-progress-2026-11-01.json).
The [private update recipe](android-private-device-release.md) documents the procedure.

**Not installed yet.** `10.77.77.3:5555` still times out; the reconnect question is pending.
Last confirmed phone version is 4. When available, notify before installation, run the
saved preflight (including actual arm64 ABI/current signer/version), then install-only
and verify pulled APK hash plus preserved app ID/firstInstallTime. Helpers are under
`.work/release-phone-2026-11-01/`. Do not uninstall, clear data or drive the phone UI.
The live backend/provider are unchanged; this is not full communications acceptance.
Continue WorkManager and the remaining plan while the phone is unavailable.

Native worker CI at `55d5e15e` is queued as run `41319810722` behind the prior sender-only
run. Audit its terminal result/artifacts later; local native proof is already recorded
below. Do not claim the new CI run has passed until verified.


## Actual native application worker validated — 1 November 2026

The separate proof app now resolves the real application worker and registered SyncTask
through `apps.shared`, using real encrypted Android stores, protected SDK records and
HRIS/Synapse HTTP. Queued exact text/UUID survives a confirmed process death. The second
process proves its sender remains closed until the worker restores it; the peer decrypts
the same content, original history is acquired, completion retired and another worker
pass sends nothing. The gateway confirms exactly one encrypted send and live admission.

[Contract](collaboration-native-worker.md) and [measurement](measurements/chat-native-worker-2026-11-01.json)
record seven Mobile/four Platform source hashes, all failed/corrected attempts and both
actual SDK runs. The new worker test passes in 70s (two processes/one death/one send).
The prior sender regression passes in 78s (four processes/three deaths/three sends,
held room never sent, 22/22/22 admissions, zero failures). Both use proof APK `924cdd45`.
All 51 Python driver cases pass. All root checks completed: 2,710 desktop cases across
426 suites, zero failures/errors/skips. The first combined invocation then failed in
release APK packaging; an unchanged-code release-only retry passed in 62s. Release lint
has zero errors and one existing warning. That packaging failure's underlying cause was
not present in its log; do not relabel it a diagnosed cache issue. Emulator is stopped.

**Continue actual WorkManager lifecycle evidence now, then all P0–P8.** This scenario
invokes the worker directly; it does not accept platform scheduling or automatic restart
after force-stop. `.work/workmanager-native-next.md` and `.work/scheduled-proof-draft/*.txt`
are unimplemented design/drafts for a normal proof Activity, actual Android scheduler,
offline constraints, ordinary PID death and OS job startup. Do not count those as tests.
The native HRIS workflow now pins Platform `784fc9113a4f09fd1ed9bff7541479088263aca5` and runs both
SDK/sender and application-worker scenarios. It also triggers on apps.shared changes and
uploads only bounded evidence, never fixture credentials. YAML/target/artifact checks pass;
audit the hosted run after push before claiming CI acceptance. Remote history, drafts/cancel, push/reply, media/actions, key continuity/backups,
Apple/web, calls and meetings remain. No complete PRD group is accepted; Starter unchanged.

User additionally requested progress builds installed while the long run continues.
WireGuard phone `10.77.77.3:5555` is currently unreachable; an async reconnect question is
pending. Release build 5 is prepared/verified locally; installation has not started and
the last confirmed phone version remains 4. Use the preserved signing certificate and
install-only after explicit serial/signature/version preflight. Never drive the phone UI.
Keep implementing independently and push each validated atomic commit.


## Latest-head history recovery validated — 1 November 2026

`ReopenCollaborationHistory` now accepts an optional event anchor. Without one, it reads
and validates the complete owned SDK head, re-admits the exact ready room, then performs
one existing capacity recovery. An absent head neither closes the runtime nor acknowledges
anything. Owner/company/room changes and cancellation prevent late shutdown/restoration.
Known-anchor behavior is retained. The worker can recover a lost ACK when the native focus
cache is full without another enqueue/resume; original evidence still controls completion.
Initial runtime-opening/close-pending contention now backs off instead of blocking work.
Explicit thread Refresh on focus capacity releases the old child, performs the admitted
reopening and opens a new child while retaining owned local content; stopping prevents
late restore/publication. No automatic bootstrap, enrollment or screen-owned send.

[Contract](collaboration-history-recovery.md) and [measurement](measurements/chat-history-recovery-2026-11-01.json)
record nine matching source inputs. Focused checks pass in 169s, full checks in 240s:
2,710 desktop / 426 suites, 358 domain browser, 58 presentation browser, 170 Android
host, 14 worker integration and two binding cases, with zero failures/errors/skips.
All format/architecture/detekt and relevant Android/Wasm builds passed. Android lint:
zero errors and one existing warning. APK `98cd76d4`, AAR `0bba8390`, proof APK `75a576d1`
are built, not installed. An initial fixture formatting error stopped before Gradle and
was fixed; Android's incremental DirtyData/QueueCollaborationText cache error successfully
fell back to compilation. Six new domain, two worker and two thread cases passed.
No actual SDK/server/WorkManager/emulator, Apple runtime, phone/live deployment or Starter
change is claimed by these simulated-provider integration tests.

**Continue actual native worker interoperability now, then all remaining P0–P8.**
[Next work](collaboration-delivery-next.md) preserves the earlier four-process/three-death
provider proof as separate evidence. `.work/native-worker-proof-plan.md` has supplementary
composition notes: reuse the actual worker/application container with real encrypted
Android stores and real HRIS/SDK adapters, seeded fictional owner, task-key-only scheduler,
process death after Queued persistence, and exact original/retirement evidence. Do not
restore the sender before the worker; verify the worker restores it. A direct native
worker proof still needs separate WorkManager lifecycle acceptance. Remote paging,
drafts/cancel, push/inline replies, groups/rich actions, keys/backups, Apple/web adapters,
calls and meetings remain. No complete PRD group is accepted. Keep the authorized long
run moving and push every validated atomic commit.


## Application-owned background delivery validated — 1 November 2026

Text delivery now belongs to a registered singleton application SyncTask. Exact local
queue acknowledgement survives failed/bounded scheduling; task payloads contain only
`hris.collaboration-delivery`. Startup/owner renewal wakes the task. Local selected-company
discovery validates owned commands and an encrypted restart-safe CAS cursor provides
round-robin room turns. Worker passes are bounded to 60s / 12s per room / eight staging
candidates. It replays exact room operations, requires explicit failed-room retry, acquires
original evidence before uncertain native replay, and retires only proven completion.
Confirmed-only work does not resume native sending. Blocked rooms do not halt healthy
remaining batches. Cancellation and owner loss release late child timelines.

The thread now queues/observes and wakes delivery; it no longer stages, sends, reconciles
receipts or retires messages. Explicit failed-room retry persists control intent for the
worker. [Contract](collaboration-background-delivery.md), [measurement](measurements/chat-background-delivery-2026-11-01.json)
and [next implementation](collaboration-delivery-next.md) describe the exact boundary.

All 32 source inputs match. Full validation passes in 320s: 2,700 desktop / 426 suites,
352 domain browser, 233 data browser, 56 presentation browser, 170 Android host,
12 worker integration cases and two binding cases, with zero failures/errors/skips.
Format, architecture, detekt, app Wasm, Android APK/AAR and proof APK passed; Android lint
has zero errors and one existing warning. APK `4e47ea8c`, AAR `97a1280f`, proof APK
`17ad74cc` are built, not installed. All eight attempts are retained, including corrected
fixture contract, type and function-length failures and an earlier successful compiler
cache fallback. Worker tests use real encrypted repositories/SQLite with simulated wire
and SDK; container restart is not actual Android process-death evidence. No new native
server/emulator, Apple runtime, phone/live deployment or Starter change is claimed.

**Continue latest-head capacity recovery and actual native worker interoperability now,
then all P0–P8. Do not stop here.** Known Sent anchors support one capacity reopening;
uncertain work without a known event anchor still needs an admitted latest-head recovery
path. Drafts `.work/reopen-history-latest.txt` and `.work/reopen-history-latest-tests.txt`
are not applied or tested. Also handle repeated foreground incoming-history capacity.
Then extend the owned Android proof using the actual worker, preserving earlier provider
proof assertions. Remote paging, durable drafts/cancel, incoming notifications/inline reply,
groups/rich content/actions, cross-family keys, Apple/web adapters, calls and meetings
remain. No complete PRD feature group is accepted. Keep pushing each validated atomic
commit during the authorized long run. Other running Gradle daemons belong to experiment
or CodePush work; do not kill them while managing this repository's build memory.


## Owned text thread and application routes validated — 1 November 2026

Chat now opens from the company-gated Home service into inbox, participant selection,
thread and explicit device setup. The thread reads owned cached context/history before
live setup; queueing retains exact UUID/text across uncertain storage responses. Its
projection merges original history, durable commands and native presentation only with
trusted sender/content or complete native device/transaction evidence. New timeline
source metadata binds family/device/bootstrap/room, and entry preserves shared company
denials through cancellation. Local observers clear hidden content after scope loss.

Foreground entry restores an existing runtime; pending messages can request a room with
retained control IDs, finite readiness polling, exact staging/delivery and acquired-history
receipt recovery/retirement. Confirmed-only queues do not reopen delivery. A destination
owns only the child timeline and its cancellable work; the application owns the parent.
The compact multiline composer stays below the reversed list and handles safe/IME insets.
[Contract/screenshots](collaboration-thread.md), [measurement](measurements/chat-thread-2026-11-01.json)
and [next delivery implementation](collaboration-delivery-next.md) are committed.

All 30 source inputs match. Focused checks pass in 88s; full root/browser/Android checks
in 232s: 2,660 desktop / 421 suites, 332 domain browser, 55 presentation browser,
170 Android host, zero failures/errors/skips. Lint: zero errors and one existing warning.
New tests cover 12 domain, 10 projection, 13 ViewModel and 3 desktop layout cases.
All six attempts, including the corrected confirmed-only refresh and function-length
failures, are recorded. Fictional English/Indonesian 200% screenshots were opened and
reviewed. APK `2339d0a5`, AAR `bfab470b` and proof APK `52633bd5` are built, not installed.
No new actual-server/emulator/Apple runtime, phone/live deployment or Starter change.

**Continue platform-scheduled delivery now, then all remaining P0–P8. Do not stop at
this checkpoint.** Use existing SyncTask/WorkManager/ForegroundSyncWorker; the scheduler
stores only the task key and durable encrypted commands remain authoritative. The next
increment needs queue wakeups, owned company-wide local work discovery, bounded worker
orchestration, startup/ownership recovery and cancellation/retry tests. Remove route-owned
transport once the worker owns it. `.work/collaboration-delivery-discovery.txt` is only a
pending draft, not applied or tested. Local Older currently covers acquired history only;
drafts are volatile. Pending cancellation, remote paging, groups, rich actions/media,
notifications/inline reply, cross-family crypto continuity, Apple/web runtime adapters,
calls and meetings remain. No full PRD feature group is accepted. Keep pushing each
validated atomic commit during the authorized long run.


## Trusted local sender identity validated — 1 November 2026

`ReadCollaborationIdentity` now reads owned cached identity and can explicitly acquire
it from the initialized protected SDK record. Android opens only a short-lived protected
credential handle; no client, directory, sync or network is acquired and no credential
is rewritten. Handle cleanup covers cancellation/failure. The identity cache uses a
separate authenticated payload under `collaboration-history/self/<company>` and the
existing protected history key. First-write or exact replay only; changed provider
identity requires migration. Existing key-loss protection includes identity-only records.
Local account identity survives age and native-family replacement; native crypto keys
remain family-scoped. [Contract](collaboration-sender-identity.md) and
[measurement](measurements/collaboration-sender-identity-2026-11-01.json) retain 29 matched
source inputs and all attempts. The existing local owner capture was extracted and its
control tests still pass. Application DI resolves the new repository/use case.

Focused checks pass in 162s; full checks in 203s. Reports: 2,622 desktop /
417 suites, 320 domain browser, 226 data browser and 170 Android host, zero
failures/errors/skips; Android lint has zero errors and one existing warning. Seven
new storage cases run on both real SQLite and IndexedDB, nine new domain cases and
five new Android host cases pass. Initial attempts had an incorrect task name and an
incorrect expected CAS result; both are recorded. APK `51944ab4` and proof
APK `fe5b22b5` are built, not installed. No new actual-server/emulator or
Apple runtime result is claimed. No phone/live/Starter change or PRD group acceptance.

**Next: implement the message thread, application routes and owned delivery scheduling,
then all remaining P0–P8.** Draft `.work/chat-thread-projection.txt` and
`.work/chat-thread-integration-notes.md` are not applied/tested. Compose canonical history,
durable pending intent and SDK presentation using exact sender/content identity or
persisted transaction mapping; never a message-controlled logical ID alone. Preserve
stable outgoing keys through confirmation/retirement and test spoofed IDs, hash changes,
redaction, cancellation, local pagination and late owner changes. Inbox must remain
accessible offline; setup should be a separate action from a thread that lacks a ready
device. `QueueCollaborationText` accepts a caller-retained UUID after uncertain storage.
`EnterCollaborationConversation` owns a child timeline; application owns the parent SDK.
A screen-only send loop does not complete background/offline delivery requirements.
Continue the authorized long run and push each tested atomic commit.


## Recoverable Chat setup validated — 1 November 2026

The setup screen now distinguishes activation, provisioning wait/retry, saved operation
continuation, revoked access and readiness. Entry reads only; mutations require an
explicit typed action. Uncertain results replay their retained operation, confirmed
receipts reconcile without resending, and confirmed bootstrap transfers the protected
runtime to the application owner before retiring its command. The page receives only
device identity, with no parent close capability or gateway credentials in state.
Current active devices restore through Ensure; local ownership remains observed while
hidden, and late/cancelled resource acquisition is closed by the use case.

`ReadCollaborationControl` now implements owned local setup/direct/room intent reads;
its records survive ordinary credential age and application upgrades. `LoadCollaborationSetup`
now maintains shared company admission through received denial, cancellation, failed
persistence/retry and newer denial revisions. [Contract and screenshots](collaboration-setup-presentation.md)
and [measurement](measurements/chat-setup-2026-11-01.json) retain 21 matched source hashes
and every attempt. Focused presentation passes in 227s; full root/browser/Wasm/Android/
AAR/lint passes in 330s: 2,606 desktop / 415 suites, 311 domain browser, 32 presentation
browser, zero failures/errors/skips and zero lint errors (one existing warning).
APK `7d5df852` is built, not installed. No phone/live/Starter change.

**Next: trusted sender identity, message thread, app navigation and owned scheduling,
then all remaining P0–P8.** All three Chat screens are DI-registered; no Home service
route is exposed yet. No full PRD group is accepted. Draft identity design: cache an
immutable account/company Matrix sender from the owned protected SDK record, under an
authenticated `collaboration-history/self/…` checkpoint. That prefix must participate
in existing history key-loss protection. Add a local SDK metadata read that never opens
a client, directory or transport, then compose history/pending messages using exact
sender/content evidence. Drafts in `.work` are not implemented or validated.

Bootstrap still pins build/platform; cross-build uncertainty, expired issuance,
cross-family key continuity and lost-key recovery need explicit protocol work. The
new identity cache must not be presented as cryptographic key continuity. Continue
the authorized long run and push each validated atomic commit.


## Participant discovery and durable DM acquisition — 1 November 2026

Application DI now registers the Chat presentation module. Participant search uses
owned company admission, bounded/debounced queries and explicit pagination; selection
prepares/replays the retained Open operation, directly acquires the confirmed summary,
and reconciles the exact peer/version before navigation. Lost responses reuse the
same command. Confirmed results survive a failed follow-up read without another Open.
Directory and individual-resource denial policies stay separate. Company denial is
retained through cancellation, failed persistence and failed recovery; ordinary age
does not hide owned local access. The shared list publication mutex covers point reads.

[Contract and reviewed fictional screenshots](collaboration-discovery.md) and
[measurement](measurements/chat-participants-2026-11-01.json) contain 21 verified source
hashes and all six attempts, including three corrected test compilation/assertion
failures. Final focused pass takes 235s; full root/domain+presentation browser/Wasm/
Android/AAR/lint takes 401s. Reports: 2,577 desktop / 412 suites, 295 domain browser,
21 presentation browser, zero failures/errors/skips. Android lint has zero errors and
one existing warning. APK `3a093eba` is built, not installed. No phone/live change;
Starter remains unchanged because this increment is HRIS-specific.

**Next: setup recovery, message thread, app routes and owned scheduling, then all
remaining P0–P8.** No service destination is exposed yet, and no full PRD group is
accepted. `ReadCollaborationControl` is only a local draft, not implemented. Setup UI
must inspect retained action before preparing another operation: confirmed registration
can reconcile across builds, but bootstrap installation currently pins build/platform.
The native encrypted SDK store currently includes native session family in its scope;
explicit recovery must preserve keys and never silently reset uncertain work. Actual
`RuntimeRepository.enterTimeline` already starts owned sync; do not add an unnecessary
second sync owner. Canonical history needs a trusted local Matrix sender identity for
multi-device alignment and safe pending-message composition. Continue the long run and
push each validated atomic commit.


## Chat inbox presentation validated — 1 November 2026

The new `features/collaboration/presentation` module contains typed inbox MVI,
owned local observation, finite remote refresh, separate local/remote pagination
and compact Fluent rows. [Contract and screenshots](collaboration-presentation.md)
and [measurement](measurements/chat-inbox-2026-11-01.json) retain implementation,
17 source hashes and every validation attempt. Failed refresh preserves first-page
cache; stopped routes retain only local observation; account/denial changes clear
hidden content. Admitted refresh reopens completed local subscriptions. One Next
request advances after local publication. Header Add keeps Chat on one line at
320 dp / 200% Indonesian text; avatar initials are decorative.

Final root/browser/Wasm/Android/lint/AAR validation passes in 143s after the first
754s full build. Reports: 2,547 desktop cases / 410 suites, including 8 inbox VM and
4 Compose layout cases, plus 8 presentation browser cases; zero failures/errors/
skips and zero Android lint errors (one existing warning). APK `9f429467` is built,
not installed. Preparatory failures were a smart cast, a new-module package lock,
and a Gradle DSL scope; each is recorded. CI now includes presentation desktop,
browser, Android library and Apple common compilation/tests. This is HRIS-specific;
Starter is unchanged. No phone/live change and no complete PRD group accepted.

**Next: finish participant discovery, setup recovery, thread and app navigation.**
The module is intentionally not yet connected to a Home service; no dead destination
is exposed. Add exact conversation summary acquisition under the existing list
publication mutex, and observe local ownership/admission without reading message
bodies. Directory reads must retain company denials through cancelled/failed retries.
Then wire typed participant search to durable Open command preparation/replay, cache
the selected DM directly, reconcile its receipt and navigate. Continue device setup,
canonical-history/pending-message composition, owned delivery scheduling and all P0–P8.
Preserve exact operation IDs, offline facts and explicit resource lifetimes. Continue
the authorized long run; commit and push each validated increment.


## Hosted retirement control and Apple build — 1 November 2026

[Control run 41301133469](https://github.com/fajarxfce/hris-mobile/actions/runs/41301133469)
passes all 28 steps at Mobile `070d3ab5`: 523 desktop / 58 suites, 499 browser /
54 suites and 407 iOS simulator / 46 suites, zero failures/errors/skips. The downloaded
log contains one Xcode BUILD SUCCEEDED marker. All 308 recorded Mobile source hashes
match that immutable commit. The [retirement measurement](measurements/collaboration-retirement-2026-11-01.json)
retains this evidence separately from the actual Android four-process proof. It is
not evidence of Apple message transport interoperability or of the later Chat UI.

The first Chat inbox module is now pushed at Mobile `829b2566065cc78ea289445dc416c692dc63a6e5`;
its [contract and reviewed screenshots](https://github.com/fajarxfce/hris-mobile/blob/829b2566065cc78ea289445dc416c692dc63a6e5/docs/collaboration-presentation.md)
and source-bound measurement cover local ownership, paging, cancellation and layout.
Participant discovery, setup, thread, app navigation and all remaining P0–P8 work
continue. No complete PRD feature group is accepted and no phone/live change occurred.


## Hosted HRIS retirement proof — 1 November 2026

[Run 41301133411](https://github.com/fajarxfce/hris-mobile/actions/runs/41301133411)
passes at Mobile `070d3ab5f718229ab88dcc7b0a36ad3cd1f5a346` with Platform fixture
`f80be73987224fda12d5a3a08a96ec6716538cc9`: all 17 steps, 165 Android host cases
and the actual HRIS emulator case, zero failures/errors/skips. Fixture
`7d92ab3729f544daa05111bc4df00a75` takes 50.609s (suite 94.355s), confirms completion
cleanup after the third forced death, exactly three encrypted sends, no held-room
send, 22/22/22 successful admissions and cleanup true. All 308 recorded Mobile and
33 Platform source hashes match those immutable commits; the extra Mobile input
relative to the 307-file local pre-build set is the final HRIS workflow pin.

The existing [retirement measurement](measurements/collaboration-retirement-2026-11-01.json)
now retains the independently checked hosted results. Chat presentation remains in
progress; this proof accepts no complete PRD feature group. Continue P0–P8 and push
each tested commit. No phone or live service changed.


## Validated HRIS completion and journal retirement — 1 November 2026

Encrypted completion and owned local retirement now pass common, SQLite/IndexedDB,
application and actual Android/HRIS process-death proof. [Measurement](measurements/collaboration-retirement-2026-11-01.json)
binds 307 Mobile / 33 Platform inputs. Platform fixture `f80be73987224fda12d5a3a08a96ec6716538cc9`
is pushed and pinned by the Mobile HRIS workflow. [Contract](collaboration-retirement.md)
describes retained identity, exact replay, bounded cleanup and later aliases.

Message `get`/matching `insert` recover validated archives, while pending observations
exclude them. Proof encryption binds the complete retained raw command and owned
account/company; key loss cannot generate a replacement over archives. Exact Sent
mapping, durable receipt, trusted original sender/logical ID/full-content hash and
all aliases are required before local retirement. Legacy or missing evidence stays
pending. At most 32 completions are prepared and archived per call, then their exact
journal entries are removed under the current checkpoint CAS. An authenticated empty
checkpoint preserves journal key protection. Reconcile, ACK recovery and delivery
recognize archived journal entries, including later aliases that do not repeat the
old primary event; cleanup verifies new aliases against acquired original history.

Actual run `90ac7444ccff4f258b1a78f6c076a226` passes with four distinct processes and
three forced deaths, including after completion archival and before journal cleanup.
Process four starts no sync/delivery, recognizes both archived receipts, cleans the
two entries, reopens SQLite and verifies exact replay plus changed-intent rejection.
Server sees exactly three encrypted sends, no held-room send and 22/22/22 admission
reads with zero failures. Case 43.141s / suite 65.560s / Gradle 95s; cleanup true.
Proof APK `9e9dcdbd` matches actual and final full builds; emulator stopped.

Final focused integration passes in 150s: 280 domain desktop / 280 domain browser,
241 data desktop / 219 data browser, 165 Android host and two app binding cases,
plus format/architecture/detekt and proof build. Final fixture build/quality passes
in 55s; Platform compile/quality 31s; 45 Python driver cases pass. Full root/browser/
Wasm/Android/proof/lint passes in 197s with 2,535 desktop, 165 Android host, 280 domain
browser, 219 data browser and 35 database browser cases, zero failures/errors/skips
and zero lint errors. Initial completion-only attempts hit a test assertion type
inference error (116s, then an unchanged 37s rerun after a failed scripted edit);
explicit `assertSame<Any>` passed in 53s. Those attempts remain in the measurement.
Dev APK `5c9487c1` is built, not installed. No phone or live service change.

**Next: Chat presentation and owned scheduling, then all remaining P0–P8. No PRD
group is accepted by this storage increment.** There is currently no collaboration
presentation module. Add the Fluent Chat service entry, owned inbox, setup recovery,
participant discovery and DM navigation, then compose history with durable pending
messages without duplicate/disappearing rows. Domain setup/conversation/send/history
operations already exist; application lifetime currently closes obsolete runtimes.
Keep delivery/admission/cleanup lifetimes explicit and preserve offline local facts.
Then finish Apple/web owned adapters/interoperability, backup, rich messages and every
call/meeting phase. Physical iPhone testing is waived; implementation is required.
Continue the authorized long run with atomic commits pushed and Git handoffs.

Previous hosted audits remain separate: ACK control `41295813930` passes 484 desktop /
460 browser / 389 iOS simulator cases and Xcode, 279 hashes matched. Starter archive
Apple `41298641454` passes 84 simulator cases and Xcode with all 16 Apple steps and
15 matching hashes; other jobs were explicitly skipped. Starter evidence is pushed
at `6226bc21`; HRIS completion/retirement stays out of the generic template.


## Validated command completion archive — 1 November 2026

The generic raw archive now passes Mobile and Starter validation. All **15 source,
test and schema files** match exactly. [Evidence](measurements/command-archive-2026-11-01.json)
records Mobile base `9da09b6b` and Starter base `36116b2`, final source hashes,
initial checks and the review-driven strengthening. SQLite schema **11** and
IndexedDB version **10** preserve existing account bindings and pending commands.

`CommandStore.archive(scope, expectedRecord, evidence)` atomically retains the
complete encrypted command plus at most **64 KiB** of opaque proof and removes
pending work. Account/session and every observed field are checked together:
ID, resource/sort identity, request/state ciphertext and revision. A same-ID
replacement at equal revision cannot receive an old completion. Matching archive
replay is immutable; changed proof/record is rejected. The archive fences later
insertion, so an old operation cannot become pending again. Normal reads and
observations exclude archives; `archived` is an exact owned lookup. Archived
ciphertext remains visible to key-loss protection, including inactive accounts.
Pending capacity remains 128/account and 512/database and is released on archival.

Ten new scenarios pass on both actual SQLite and IndexedDB, including competing
writes, same-revision replacement, lost responses, cancellation, account changes,
capacity, UTF-8 bounds, reopen/reauthentication, previous-schema migration and a
forced abort between archive creation and pending deletion. Corrected focused
checks pass in **118s** with **37 SQLite desktop / 35 browser** cases and quality.
Corrected full Mobile checks pass in **326s**: **2,496 desktop / 165 Android host /
262 domain browser / 198 data browser / 35 database browser**, zero failures,
errors or skips and zero Android/proof lint errors. Starter root/database/browser/
Wasm/Android/lint checks pass in **152s**, **341 desktop / 35 database browser**,
zero failures/errors/skips and zero lint errors. Its source and portable regressions
were copied only after Mobile focused proof; template-specific docs were preserved.

The first version used revision-only matching and passed focused/full checks
(**95s / 793s Mobile / 167s Starter**). Review found the delete/reinsert case before
commit; the final API compares the full record. Initial sources, reports and APK
hashes remain under `initialRevisionOnly`; they are not attributed to the final
contract. No test attempt failed. Final Mobile dev APK **`ce57d7e0`**, proof APK
**`87a28466`**, Starter dev APK **`96eb96c5`** are built, not installed. No phone,
live service or emulator change occurred in this foundation increment.

**Next: integrate encrypted HRIS completion evidence, exact archived lookup/replay,
retirement policy and restartable journal cleanup; then Chat UI and all P0–P8.**
The raw store never decides that a command succeeded and does not call HTTP/SDK.
Keep uncertain legacy work; validate original history, aliases and immutable device/
transaction mapping before retirement. Account/denial checks and interrupted cleanup
must remain explicit. No complete product feature group is accepted by this port.

The next HRIS steps are recorded in [retirement integration](collaboration-retirement.md).

## Active collaboration implementation — 1 November 2026

**ACK-before-journal recovery now passes actual Android/HRIS process death.**
[Evidence](measurements/collaboration-original-receipts-2026-11-01.json) binds
**279 Mobile / 33 Platform source hashes** to run
`39775a6b851040e19d1f4c97059025c8`: **41.453s case / 62.142s native suite /
91s Gradle**, cleanup true. The driver verifies three different processes across
staging and ACK deaths. The final process recovers the missing receipt from exact
original history, reopens the database and projects Sent once. The ACK checkpoint
contains no event ID or body. The server verifies exactly **three encrypted sends**
for the initial message, reply and separate ACK message; held-room sends remain zero.
Live admission is **22/22/22 device/conversation/room reads, zero failures**.
`ackBeforeJournalRecovered`, driver-owned `processRestartedAfterAck` and the two
admitted latest-history boundaries are mandatory evidence.

New staging persists trusted sender and canonical full-content hash before enqueue.
The native writer verifies that identity before mutation; Staging/Staged/Sent keep
it immutable. Explicit plain text replaces the SDK Markdown constructor: Markdown,
HTML, emoji and newlines remain literal content, with empty mentions and the logical
ID included in the hash. Old envelopes remain readable without invented identity.
`RecoverCollaborationMessageReceipts` is a local-only, owned/gated operation: up to
128 command candidates, at most 32 appended receipts, exact sender/logical ID/hash,
all event aliases and confirmed redactions. Missing originals stay pending; conflicts
reject the batch before append. Lost writes and late denial retain journal facts.
It never fetches history, starts SDK delivery or projects commands implicitly.

Focused domain/storage/host/DI checks pass in **132s**, followed by proof build and
quality in **55s**, Platform fixture/quality in **40s**, and **40 Python cases**.
Full root/browser/Android/Wasm/proof/lint passes in **235s**: **2,486 desktop /
165 Android host / 262 domain browser / 198 data browser**, zero failures/errors/
skips and zero lint errors. All attempts for this increment pass first time.
Dev APK **`1eb5a9a4`** is built, not installed; actual proof uses **`8f066ba0`**.
Emulator stopped; no phone or live change. Starter remains `36116b2`; this increment
is HRIS-specific and has no generic port.

**Next: safe command/journal retirement, Chat UI, then all P0–P8. None of the 54
groups is accepted.** Retirement must free the shared 128-command capacity while
preserving complete durable identity and retry deduplication; deleting Sent rows
alone would let an old logical UUID enqueue again. Account-owned raw storage must
atomically fence insertion against retained completion evidence. Command and receipt
stores currently have separate transactions. Preserve uncertain legacy work and all
aliases; validate interrupted retirement and lost responses before enabling cleanup.
Then integrate scheduling/UI, Apple/web owned adapters and interoperability, backup,
rich messages and all call/meeting phases. Physical iPhone testing is waived; Apple
implementation remains required. Continue the authorized long run, with atomic
commits pushed and this handoff updated; do not treat this checkpoint as completion.

Predecessor control report audit `41290902945` at `388d0534` reports success and
contains **451 desktop / 427 browser / 358 iOS simulator** passing cases; 266
immutable source hashes match. The API returned no job/step records and an empty
run log, so this audit does not independently claim an Xcode host build. See the
explicit limitations in the restart measurement's `hostedControlReports`.

### Previous SDK reopening checkpoint

**Coordinated SDK reopening now passes actual Android/HRIS recovery.**
[Evidence](measurements/collaboration-history-reopening-2026-11-01.json) records
run **`e395fab292fd4d088cae138b4aaebd27`**, **35.536s case / 56.375s native suite /
87s Gradle**, cleanup true. The fixture fills the 16-focus budget, proves that a
real queue writer blocks history-driven close, then restores the protected client
and verifies the same encryption keys/bootstrap, original message body, paused
sibling transaction and encrypted reference checkpoint. Live admission is
**17/17/17 device/conversation/room reads, zero failures**. The new
`historyRuntimeReopened` Boolean is mandatory across Android, Python and server.

`ReopenCollaborationHistory` admits the owner/device/direct room before preparation,
before restore and after restore. `Available` leaves a retained focus or spare
capacity untouched; `SetupRequired` never enrolls a new identity. A late denied,
replaced or cancelled restored lease closes. The native operation shares the
client-wide writer exclusion with message queues. An active/unfinished writer
stays owned; already-requested parent cleanup can finish. No original, reference,
intent or receipt is erased, and no history read, sync start or delivery is implicit.
The repository retains clients; application lifetime only closes obsolete owners.

Twelve new common-domain cases, four Android-host cases and one common focus case
pass. Initial focused attempts exposed two assertion type issues, a cleanup-wait
fixture assumption and a function-length check; all are preserved in the evidence.
Corrected focused checks pass in **50s**. The first actual run stopped at a bounded
close; the corrected fixture permits at most six explicit common requests, only
for `close_pending`. Its corrected proof build/quality passes in **51s**. This is
fixture-only retry; the product operation never loops. Full root/browser/Android/
Wasm/proof/lint passes in **231s**: **2,466 desktop / 161 Android host / 247 domain
browser / 193 data browser**, zero failures/errors/skips and zero lint errors.
Platform fixture/quality passes in **28s**; 35 Python cases pass. All **272 Mobile /
33 Platform source hashes** match. Dev APK **`b437c344`** is built, not installed;
actual proof uses **`e693f636`**. Dedicated emulator stopped; no live/phone change.

**Next: ACK-before-journal recovery, command retirement and Chat UI, then all
P0–P8. None of the 54 groups is accepted.** Original history already indexes exact
sender/logical ID and canonical full-content hashes. Do not infer ACK from a missing
native transaction or matching text alone. Current queue sending calls the SDK's
Markdown constructor, which may add formatted content: recovery needs a trusted
sender binding and the exact original payload identity before enqueue, not a body-
only hash. Inspect the pinned SDK content factory and preserve uncertain old work.
The SDK reopening scenario preserves stores but does not compare a newly synchronized
cursor after reopening; earlier process-death/429 cursor proof remains separate.
Apple/web owned adapters, interoperability, backup, rich messages and every call/
meeting phase remain. Physical iPhone testing is waived; Apple implementation is
required. Starter stays at `36116b2`; this HRIS-specific increment has no generic port.

Additional predecessor audits are recorded independently. Common acquisition control
**`41287684753`** at `0cb6509a` passes all 28 steps, **421 desktop / 397 browser /
342 iOS simulator** cases and Xcode build, with 256 immutable hashes matched.
Explicit restart HRIS **`41290902971`** at `388d0534` passes all 17 steps and run
`8f5ec0ad` (**33.788s case / 71.175s suite**), cleanup true, 11/11/11 admissions,
156 host cases and 266 Mobile/33 Platform hashes matched. Resolution control
`41289463827` was superseded while pending; it is not claimed as passed.

New predecessor audits are recorded separately. Traversal control **`41286066709`**
at `e992a053` passes all 28 steps, **407 desktop / 383 browser / 328 iOS simulator**
cases and actual Xcode build, with 253 immutable hashes matched. Offline resolution
HRIS **`41289463686`** at `61c6b516` passes 17 steps, 156 host cases and run
`21032404` (**50.626s case / 91.170s suite**), cleanup true, strict reconciliation
flag and 262/33 hashes matched. Resolution protocol **`41289463684`** reports
success. Acquisition control `41287684753` is running; resolution control
`41289463827` was pending at the last check. Audit terminal artifacts and this
increment's own CI after push; earlier proofs do not validate later code.

The HRIS workflow pins the tested Platform fixture **`3fb67304`**, now pushed.

No live/schema/phone change. Atomic commits and push after every commit remain
authorized. Continue after this checkpoint; the user requested the full plan.

[Earlier November detail](collaboration-implementation-history-2026-11-01.md)

## Previous collaboration checkpoint — 31 October 2026

**Android room/timeline children now belong to their protected runtime.**
[Contract](collaboration-timeline.md) and
[evidence](measurements/collaboration-owned-timeline-2026-10-31.json) cover bounded
child capacity, partial acquisition, late/cancelled waiters, explicit failed
cleanup retry, sibling disposal and parent drain ordering. The native listener
copies/disposes FFI values, applies ordered vector batches atomically and retains
virtual positions. Text, redaction, unavailable keys and pending/failed/sent states
have typed projections. Local echoes keep unknown logical IDs until reconciliation.

Fifteen new common cases pass. Focused checks pass in 70s; final root/browser/
Android/Wasm/lint passes in 245s: 2,160 desktop cases, 64 data browser cases and zero
lint errors. A preceding mistyped Gradle task path failed in 13s before correction.
Actual SDK run `5aea81cc` passes in 34.340s (53s Gradle, 32 driver cases), checking
both decrypted timeline replies, explicit child disposal/sibling survival and
closed snapshots after parent close. All 15 Platform/120 Mobile hashes match
before/after. One provider-busy key upload returned 503 before successful exchange.
The emulator is stopped; dev APK `941ce4de` is built, not installed.

**Next: admitted common content ports and app entry, product timeline/history
windows, owned offline message projections, bootstrap/cross-family recovery,
logical-ID reconciliation and chat UI; continue all P0–P8.** The platform handle
currently opens known rooms only and gates vectors above 512 entries. No product
group is accepted; no live/schema/phone change. No reusable Starter change is
required by these HRIS SDK adapters.

Conversation command CI `39217be2`/`41248280288` now passes all 26 steps across both
jobs: 143 desktop, 119 browser and 119 iOS simulator cases; Android/Wasm/Apple
compilation succeeds. All 26 source hashes were checked against the immutable
commit. Its actual HRIS run `41248280306` remains in progress at this recording.
Apple native chat runtime remains pending.

## Earlier collaboration checkpoints

Use the latest checkpoint above for the active queue; earlier next-step notes are historical.

**Native conversation commands now persist and reconcile through application DI.**
[Contract](collaboration-conversations.md) and
[evidence](measurements/collaboration-conversation-commands-2026-10-31.json) cover
immutable per-peer/room intent, separate authenticated request/outcome envelopes,
uncertainty before HTTP, original-operation retry and exact-record retirement only
after observing owned metadata. Preparation validates current device/retry state;
ordinary app upgrades preserve these control operations. Both conversation and
setup replay now retain known server denials through failed transport retries.
No UI submission or automatic retry is connected yet.

Twenty-four new cases pass (12 common domain, four common data, eight real SQLite
storage). Initial fixture failure reused a unique sort key (53s); corrected focused
checks pass in 76s. Root/Wasm/Android/lint passed in 193s before denial review, then
final root/browser/assembly/Android/lint passed in 4m: 2,145 desktop cases, domain
browser 70/data browser 49, zero lint errors. APK built, not installed.

**Next: product room/timeline ownership and chat UI, owned offline message projections,
bootstrap/cross-family recovery, logical-ID reconciliation, then all P0–P8.**
No product group is accepted; no live/schema/phone change. CI `767ed980` now lets
running control/HRIS proofs finish, retaining only the newest pending workflow.
`fbb2f3ae` provider CI `41246770541` passes both jobs/all steps, classic 83.930s and
native 92.938s with two browser cases each; all 14 Platform hashes per mode match
`e0dbb567`. Its control/HRIS runs `41246770522`/`41246770543` also pass every job/step:
119 desktop, 103 browser and 103 iOS simulator cases; app Android/Wasm/Apple
compilation passes. Hosted actual HRIS SDK case `001256c3` passes in 70.969s with
cleanup complete. All 94 Mobile/15 Platform source hashes match the pinned commits.
The new command implementation has its own queued/running checks.
Starter AppAuth hosted verify `41245287269` passes all 15 verify steps at `9740cb5`,
with source hashes checked; Starter records closure at `36116b2`.

**Android SDK synchronization now belongs to its protected runtime lease.**
Concurrent starts share one acquisition even after caller cancellation; late or
failed acquisition, bounded waiting and failed/cancelled termination retain clear
ownership. Cleanup stops sync before database draining and exclusive-store unlock.
Credential failure stops sync; reopening is explicit and does not erase owned data.
[Evidence](measurements/collaboration-owned-sync-2026-10-31.json) retains eight new
common cases, 15 Platform/94 Mobile source hashes and proof APK `e5c07ded`.

Focused checks passed in 70s. Root/Wasm/Android/proof-lint passed in 192s: 2,121
desktop cases; data browser has 45 cases. Actual Android/server case `54ab8f5a`
passed in 35.750s (full fixture 1m), one API and 32 driver cases. Both directions
decrypt after real process death, original transaction and keys survive, and
server observation confirms no early encrypted send. Instrumentation/cleanup
complete and the owned emulator is stopped. One `keys/upload` HTTP 503 provider
busy is retained in the trace; the SDK exchange subsequently succeeds.

**Next: durable conversation commands, product room/timeline ownership and chat UI;
then bootstrap recovery, owned offline projections/logical-ID reconciliation and
all P0–P8.** No product group is accepted; no live/schema/phone change. Conversation
control is pushed at `50964794`; predecessor `9f83dcdc` hosted control/HRIS runs
were superseded/cancelled. `50964794` runs `41246022664` and `41246022568` are still
in progress at this checkpoint. Starter AppAuth port `9740cb5` remains locally
verified; hosted verify `41245287269` is separate and still running.


**Native conversation discovery/control adapters and scoped reads are now wired.**
[Contract](collaboration-conversations.md) and
[evidence](measurements/collaboration-conversation-control-2026-10-31.json) cover
participants, DM list/detail/open and room status/setup. Bounded responses validate
owner identities, UUID page progress, receipt versions and coherent room states.
Reads reacquire native/company admission after successful or failed I/O; late
selection/permission/session changes cannot publish obsolete data. Mutation ports
preserve operation/payload through refresh but are not yet product actions: durable
preparation/replay/reconciliation must come before submission from UI.

Twenty new common cases pass: seven domain and thirteen data. Focused desktop/
browser/assembly/quality passed in 58s after correcting a test enum name (35s
initial compile failure). Final root/Wasm/Android/lint passed in 196s: 2,113 desktop
cases, 58 domain browser and 37 data browser cases; zero lint errors. App bindings
resolve the new reader/repository. APK built, not installed. Predecessor hosted
control `41245001690` and HRIS `41245001517` are still running at this checkpoint.

**Next: SDK sync/room child ownership, durable conversation commands and chat UI;
then bootstrap recovery, owned offline projections/logical-ID reconciliation and
all P0–P8.** No product group is accepted and no live/phone change. Starter AppAuth
port `9740cb5` passed independent root/Android/lint (331 desktop cases); hosted
verify `41245287269` remains separate. HRIS control adapters/policy stay here.


**Application DI and Android process ownership are now wired and locally verified.**
`MaintainCollaborationRuntimeOwnership` closes obsolete SDK leases after session/
company/permission/deadline changes, retains authoritative denials through cleanup
failure, rejects a second collector and closes all on cancellation. One timer per
captured frame replaces polling; token rotation preserves its owner. Android's
Application starts `ApplicationCollaborationLifetime` once, with a protected native
runtime configured from the HRIS root. Other hosts explicitly report unavailable
runtime support. Startup does not enroll, bootstrap or open a native client.

[Evidence](measurements/collaboration-application-runtime-2026-10-31.json) retains
eight new common lifecycle and two application binding cases. Root/Android passed
in 328s; Wasm compiled but lint exposed the manifest's missing direct AppAuth
dependency. Declaring the existing catalog version fixed it; final root/Wasm/
Android assembly/lint passed in 70s, 2,093 desktop cases and zero lint errors.
Domain browser has 51 passing cases. The initial focused fixture compilation
needed explicit nullable-list type arguments; recovery passed in 62s. CI now
checks app bindings, Android assembly/lint, Wasm and Apple app compilation.

**Next: native conversation/participant/room adapters and chat UI, with SDK sync/
room children owned through shutdown; then bootstrap uncertainty/build recovery,
offline projections/logical-ID reconciliation and all P0–P8.** No product group is
accepted. No live/schema or phone change. The generic AppAuth declaration/lint
guard is now independently verified in Starter `9740cb5`; keep HRIS runtime policy here.

Corrected provider CI `3cedb0b1`/`41242901470` passes both jobs/all steps: classic
77.293s and native 75.327s, two browser cases each, cleanup true. All 14 Platform
source hashes match `e0dbb567`; results are in the offline-signal measurement.
Earlier cancelled runs and non-atomic-signal failures remain recorded below.


**Admitted restore now reopens existing protected runtime ownership.**
`RestoreCollaborationRuntime` returns a paused matching resource or explicit empty
result, never issues tokens/installs a replacement, and leaves setup commands intact.
Late account/company/permission changes, failed credential writes and cancelled
acquisition close returned leases. [Evidence](measurements/collaboration-runtime-restoration-2026-10-31.json)
retains eight new common cases and root/browser/data-assembly/quality passing in
72s: 2,083 desktop, 43 domain browser cases. **Next: application-wide runtime/SDK
child ownership, bootstrap uncertainty/build recovery, setup/chat UI and all P0–P8.**
Owned offline message projections remain separate; this is live-admitted setup
recovery. No product group accepted, live/phone/Starter change.

UI preparation read `ui-ux-pro-max` and `compose-component-design` plus modifier/
slot references. Verified searches match Chat & Messaging App and Compose LazyColumn;
the initial design-system landing pattern was unsuitable. Keep the existing Fluent
tokens, compact top bars, 48dp targets and direct conversation rows. No new UI code
has been added yet. `9c44fc56` hosted control/HRIS runs were superseded/cancelled;
its provider workflow still used the old non-atomic signal. Inspect the corrected
`3cedb0b1` protocol run `41242901470` and the next exact control/HRIS run after push.

**Admitted bootstrap installation now owns confirmed command retirement.**
`InstallCollaborationRuntime` verifies current owner/build/platform and the original
attempted bootstrap, acquires a paused protected lease and removes only the observed
command revision after matching its owner/device/operation and credential gate.
Cancellation or late denial closes the acquired resource; lost retirement responses
preserve already installed facts for restore. Twelve common cases pass, including
competing calls, credential/cleanup failures, account/company/permission replacement
and cancelled handoff. [Evidence](measurements/collaboration-runtime-installation-2026-10-31.json)
retains focused 63s and root/assembly 45s checks: 2,075 desktop cases, 35 domain browser
cases, quality passed. The initial 17s test compilation error used `copy` on an
intentionally non-data credential class; the fixture now constructs bounded variants.
**Next: explicit restore/application session ownership, bootstrap uncertainty/build
recovery, live delivery, app DI/UI and all P0–P8.** No product group accepted.

Provider-only CI `41240634061` at `70bfbd21` passed native sync but failed classic:
Android observed the empty `offline-ready.json` while its producer was still writing
(`ProofOfflineInvite.kt:28`). Both modes cleaned up. Platform `e0dbb567` now
publishes the completed private file by rename. The corrected actual classic proof
passes in 35.498s with two browser cases and cleanup true; all 14 source hashes are
verified. [Evidence](measurements/collaboration-offline-signal-2026-10-31.json) retains
both predecessor results and the correction. Protocol CI now pins that commit;
inspect the next hosted classic/native results. This is separate from the successful
protected HRIS SDK and common-control runs recorded below.

**Actual Android process death preserves the pending SDK transaction.**
[Evidence](measurements/collaboration-sdk-process-death-2026-10-31.json) binds APK
`a14d6bf1` to 71 Mobile/15 Platform sources. The driver force-stops the owned proof
process after durable enqueue, verifies its exit, then restores both protected
identities in another PID. The original transaction remains paused until explicit
release; gateway observation catches any premature encrypted send. Both directions
decrypt and server reads verify ciphertext. SDK case 33.512s; fixture/quality 62s,
four API and 32 Python cases pass. Mobile root/debug/lint/browser passes in 66s:
2,063 desktop/24 data browser cases. Cleanup completed; owned emulator stopped.

Explicit runtime opens now retry already-closing abandoned leases once, preserving
locks on failed drains. The fallible retrieval bridge normalizes unexpected nonfatal
exceptions. **Next: admitted bootstrap installation/recovery, application session
ownership, logical-message reconciliation and DI/UI; continue all P0–P8.** This is
SDK queue persistence, not full product outbox/notification or Apple acceptance.
No live/phone/Starter change, and no product feature group accepted.

Predecessor `70bfbd21` hosted runs `41240634120` (actual SDK, 68.188s, cleanup
true) and `41240634056` (61 desktop, 47 browser, 47 Apple simulator cases) pass
all jobs/steps. All 70 Mobile/15 Platform source hashes and retained XML are
verified in the protected-store measurement.

**Protected Android SDK reopening and encrypted HRIS exchange now pass.**
[Evidence](measurements/collaboration-protected-native-store-2026-10-31.json)
retains APK `18b072bc`, 70 Mobile/15 Platform source hashes and a 22.732s actual
SDK case, cleanup true. The proof now uses the real Keystore adapter/private
runtime: competing opens, a missing record and a wrong passphrase preserve the
original device keys after restoration. SDK `pause()` drains database stores
before unlock; shared shutdown tests cover competing callers, timeout, cancellation,
failed drain retry and repeated close. Final root/debug/lint/browser passes in 78s:
2,063 desktop/24 data browser cases. Four newly resolved AndroidX metadata files
match official Google Maven bytes; the initial lint verification failure is retained.

The exchange exposed a Platform key-shape bug after retry: fallback IDs may overlap
one-time IDs in their independent maps. The fix keeps all identity/signature/marker
checks. Platform's full 454s run passes 37 domain, 201 data, 39 API plus quality;
27 Python driver cases pass. Both earlier failed runs remain recorded.
The newer checkpoint above adds abandoned-close recovery, retrieval normalization
and actual Android message queue process death. Continue bootstrap installation/
recovery, app DI/UI and all P0–P8. No app DI/routes, live service,
phone or Starter change. The predecessor `4d124732` hosted run `41238521070` also
passes both jobs/all steps: 57 desktop, 43 browser and 43 iOS simulator cases; all
52 source hashes and artifact XML are verified in its runtime measurement.

**Android runtime composition now compiles and passes the shared checks.** The
application factory supplies Android Keystore records and `noBackupFilesDir`;
runtime installation pins bootstrap identity and preserves existing SDK-rotated
credentials on the same operation. Client ownership starts before restore. Closing
retains the store lock until the SDK's E2EE initialization task has finished;
callers wait at most five seconds per close, with five outstanding leases allowed.
The void callback records bridge failures without unwinding across FFI; a concurrent
successful write cannot clear that terminal gate. [Evidence](measurements/collaboration-sdk-runtime-2026-10-31.json)
retains focused 58s and root/assembly/browser 50s checks. All 2,059 desktop and
20 collaboration data browser cases pass. **Next: actual protected native store
reopen, missing-record/wrong-key preservation and queue process death in the owned
HRIS emulator fixture.** No app route/DI or physical device change yet. Continue
through all P0–P8; no product group accepted. Hosted predecessor `b464a805`, run
`41237201395`, passes both Linux and Apple; all 47 source hashes verified and XML
evidence appended to the storage measurement.

**SDK store and credential-write components now pass.** The data module has a
JVM/Android exclusive directory lease, strict scoped SDK record codec, per-lease
pending credential writer, and an Android factory that pauses queues before its
single restore. [Lifecycle notes](collaboration-sdk-lifecycle.md) and
[evidence](measurements/collaboration-sdk-storage-2026-10-31.json) retain the exact
scope and limits. Final root/assembly/browser checks pass in 57s: 2,058 desktop
cases and 19 data browser cases; quality zero findings, 73 modules. Six file lease,
six codec and six credential-write cases include cancellation after acquisition/
committed write, missing ownership, failed persistence and late callback closure.
Android factory is compiled, not yet run through the actual SDK fixture.

**Next: compose those parts in the Android runtime repository using the existing
AndroidCredentialStore and the private noBackupFilesDir; connect its safe SDK
session delegate, then run protected store reopen and message process-death proof.**
Do not treat the codec/writer fixture as actual platform Keystore acceptance.
Session save callback returns void; record failure and retain pending tokens without
throwing across FFI. Own every SDK listener/sync/task and directory lock through
shutdown. Inspect initialization tasks before releasing a store while native work
may still hold it. No provider logout/reset, no app route/DI or phone action yet.
Continue bootstrap recovery, UI/outbox and all P0–P8; no product group accepted.

Setup `fb1b1c13` hosted run `41235576905` now passes **both Linux and Apple, all
steps**: 38 desktop, 30 browser and 30 iOS simulator cases. All 39 source hashes
match the pushed commit; retained artifact XML hashes/counts are appended to its
setup-command measurement. Prior CI failures/cancellations remain documented.

**Durable product setup commands are implemented.** Prepare persists immutable
registration/retry/bootstrap input; replay records uncertainty before I/O and uses
one owner/mutex, captured native family, client build and original operation.
AES-GCM request/outcome envelopes use the protected shared command key, separate
from disposable caches. Account/company/family changes, lost keys, stale revisions,
late denials and cancellation preserve retained facts. Gateway credentials remain
encrypted until an SDK installation owner can consume them. Only matching observed
registration/new-job receipts retire; uncertain/bootstrap results stay retained.
[Contract](collaboration-setup.md) and [evidence](measurements/collaboration-setup-commands-2026-10-31.json)
retain sources and corrections. Final root/assembly/browser checks passed in 62s:
2,040 desktop cases, collaboration domain 23/data 15; browser domain 23/data 7.
Quality has zero findings. Apple runtime checks remain pending.

The preceding control run `41234732498` passed Android setup but portable checks
stopped at missing Wasm lock entries. The retained lock update adds only four local
module workspaces; no external dependency version changed. Inspect the next exact
hosted run. Local Chromium must be selected with `CHROME_BIN` as recorded in the
measurement. Do not put draft `.kt` files under `.work`: formatCheck scans them;
use an external owned temporary directory while keeping checked sources frozen.

**Next: product SDK store/session installation and actual process-restart proof.**
Read [SDK lifecycle findings](collaboration-sdk-lifecycle.md): exclusive owner lock,
protected passphrase, one restore per client, queue pause before restore/listener,
void credential-save callback failure handling and no logout/reset. Then bootstrap
recovery, app DI/chat UI/outbox and the full P0–P8 scope. No product feature is
accepted. Phone, dedicated emulator, live Platform and Starter remain unchanged.

Control CI run `41234420892` exposed setup-android v3's obsolete default SDK
package `tools`, before any portable tests ran. The workflow now requests
`platform-tools` and SDK/build-tools 36 explicitly, matching the passing protocol
workflows. Inspect the corrected hosted run. Durable setup command storage and
prepare/replay orchestration are in progress locally; eight real database cases
already pass, with full validation still pending. Continue implementation.

The product [native setup modules](collaboration-setup.md) now provide bounded
registration, status, retry and bootstrap adapters plus a scope-safe read use case.
Eight domain and seven transport cases pass; root checks and setup assembly pass
in 334s (2,017 desktop cases). [Evidence](measurements/collaboration-mobile-setup-2026-10-31.json)
retains sources and the initial compilation/UI-fixture corrections. The module is
not yet installed into app DI or navigation. `collaboration-control.yml` checks
desktop/browser and Apple targets; inspect its exact hosted result after push.
Next: encrypted durable setup commands, pinned bootstrap client build, SDK store
ownership and credentials, then chat UI/outbox and all P0–P8. No feature accepted.

Hosted SDK evidence is now retained: Mobile `538a1827` HRIS run `41231913617`
passed all steps, 63.437s, cleanup true, APK `9900b041`; twelve Mobile and ten
Platform hashes match their exact commits. Platform control `41231798310` passed
both jobs. Dual provider regression `41231913637` also passed both modes with
cleanup true: classic 35.088s/81.130s, native 21.795s/61.379s. All fourteen driver
hashes match `d425e6d8`; results are in the native HRIS measurement. These are
protocol fixtures, not product queue-restart or physical-device acceptance.

Existing expense UI scenarios now pin the observer clock to their October claim
fixtures. The company timezone had crossed into November, correctly excluding the
October data in production and exposing a nondeterministic test. No product date
behavior or screenshot tolerance changed. Both affected cases pass; full root
checks and the in-progress collaboration adapter assembly passed in 334s.
[Evidence](measurements/expense-fixture-month-2026-10-31.json) retains the failed
screenshot hash, 351s initial failure and 54s focused recovery. Continue collaboration
implementation; this test correction does not finish the approved plan.

Actual Android SDK ↔ HRIS gateway now passes in both directions against Platform
[`45ccd56f`](https://github.com/fajarxfce/hris-platform/commit/45ccd56f29871a3474aca676be3464cc7d43bee4).
Two fictional employees own separate encrypted SDK stores. Keys/claim/to-device,
Megolm send and classic sync pass real HRIS admission; both clients decrypt the
expected content and logical ID, and gateway event reads confirm ciphertext.
[Evidence](measurements/collaboration-native-hris-sdk-2026-10-31.json) retains
12 Mobile/10 Platform hashes, APK `3b10b319`, four initial timeout failures and
final 83s Platform checks: 19 API cases, format/architecture. All 27 Python driver
cases pass. Mobile root/debug/lint passed in 69s after fixing nested builder
structure; no rules were suppressed. Initial debug/lint passed in 274s.

The pinned SDK/Ruma sends **unprefixed `use_state_after`** even to v1.12. Platform
now accepts one boolean preference and returns its standard state-before response;
it does not advertise state-after support. Actual fallback is tested. SQL fixtures
activate the device/publish the HRIS room binding; registration/bootstrap, provider
room creation, gateway transport and client crypto are real. SDK handles the
observed transient concurrent key-upload provider-busy response. This is not product
queue-restart or verification acceptance. New `collaboration-hris.yml` pins the
exact Platform fixture and compiles before starting its dedicated CI emulator.
Inspect its hosted result after push; existing browser/native protocol CI remains.

Dedicated emulator is stopped with normal app data retained. Phone/live/Starter
unchanged. **Continue product SDK session/store ownership, durable queue restart
and logical-ID reconciliation, UI and all P0–P8; no product feature is accepted.**

Earlier provider-only evidence follows.

Latest source supports both classic `Client.syncV2` and native `SyncService`, with
matching builder/session modes and one restore per client. Both actual local
Android/browser runs passed two cases using APK `d835e1c1`: classic 19.219s / owned
38.121s; native 18.814s / owned 33.649s; cleanup true. Complete SDK membership,
not optional summary counts, now gates the offline first message. Final root,
debug build and lint passed in 23s: 2,002 desktop cases, zero lint errors (47
warnings). [Evidence](measurements/collaboration-dual-sync-2026-10-31.json) retains
the three corrected provider failures, compilation/nesting corrections and exact
source hashes. Workflow pins Platform `d425e6d8`. Exact `b061d390` hosted
[41212817421](https://github.com/fajarxfce/hris-mobile/actions/runs/41212817421)
passed every job/step in both modes: classic 31.659s / owned 72.695s, native
34.002s / owned 81.267s. Both cleanups succeeded; separate hosted APK hashes
`e5053bb5` / `f65bb8e8` and all source checks are retained in the measurement.
Driver browser CI `41212746123` also passed every step (10.128s / 33.605s). Dedicated emulator is
stopped with data retained; phone unchanged. Continue scoped recipient key/query,
sync/content and product UI/outbox integration; no feature is accepted by this lab.

Latest exact native protocol CI `41207178159` at `7b57865b` passed every job/step. Driver `047897f9` hashes match the sanitized artifact: two scenarios 33.604s, owned run 77.491s, cleanup true; hosted APK `b4cdef67` is retained in `docs/measurements/collaboration-sdk-protocol-surface-2026-10-31.json`. The same driver browser CI `41207148334` also passed every step (8.995s/32.972s). No Kotlin source or product acceptance changed.

The user approved the complete chat/call/meeting plan. Branch
`feat/communications` starts from `7e12251a` with no inherited edits. The canonical
[54-group PRD](https://github.com/fajarxfce/hris-platform/blob/feat/communications/docs/collaboration/prd.md)
and [delivery ledger](https://github.com/fajarxfce/hris-platform/blob/feat/communications/docs/collaboration/delivery.json)
live in Platform. P0 begins with actual isolated Matrix/LiveKit protocol proof;
no collaboration feature is accepted yet. Preserve current Firebase/HRIS
identity and build-4 phone installation. Continue all P0–P8 phases with matching
tests, source evidence and pushed atomic commits. Prove reusable foundations
here before porting them to Starter. The completed prior scope below is history,
not closure of this new implementation request.

The latest driver records bounded method/static-servlet request counters from
its owned Synapse metrics endpoint. Browser baseline passed in 15.512s; the
Android/browser pair passed in 39.009s (18.498s scenarios), with complete cleanup.
The unchanged proof APK `d3662eb9` matches source `e6dcb5ea`; no new compilation
or root-check claim is made. [Protocol evidence](measurements/collaboration-sdk-protocol-surface-2026-10-31.json)
retains both results and fifteen parser/ownership cases. Counters contain no
account/room/device IDs, tokens or payloads and include fixture control/negative
requests; they are not an automatic gateway allowlist.

The earlier fixture explicitly used SlidingSyncVersion.NATIVE with SyncService. Eighteen
SlidingSyncRestServlet requests occur only in the combined run; pinned Synapse
implements MSC4186 Simplified Sliding Sync. Classic Kotlin syncV2/syncOnceV2 entry
points exist but are not tested by this fixture. Product gateway integration must
scope native lists/subscriptions/extensions and keep unverified rooms out of sync.
Native CI now pins Platform `047897f9f5a7b126fd2db4ed5a918db3e11e364e`; inspect
its exact hosted run after push. The emulator is stopped with data retained; no
physical phone or live HRIS action occurred. Continue all implementation phases.

The Android sender now creates a version-12 encrypted room with `invited` history
while the enrolled browser peer is offline. It observes SDK encryption/member
state, sends through the paused/resumed queue, then closes its SDK. The driver
stops only the proof process before the peer joins, loads history and decrypts.
Encrypted logical IDs, the SDK transaction mapping, standard refresh and unchanged
signing key still pass. [Invitation evidence](measurements/collaboration-native-invited-2026-10-31.json)
records two scenarios in 18.486s (33.494s owned run), with complete cleanup. Full
root/debug/lint checks passed in 48s: 2,002 desktop cases, zero lint errors and 47
warnings. An initial Detekt long-method failure was fixed by extracting result
serialization; no check was suppressed.

Native CI pins Platform `54eb3a667ebe47ff06fcb0c2a973824215f383b8`. The
hosted [41204075013](https://github.com/fajarxfce/hris-mobile/actions/runs/41204075013)
passed every job/step at `e6dcb5ea`. Both scenarios passed in 34.359s
(82.746s owned run) with complete cleanup; driver hashes match the pin. This proves Android sender to an already-enrolled
offline browser invitee, not native recipient-before-join or Swift interoperability.
Never-enrolled recipients still need a pending product outbox until eligible keys
exist. Durable restart/coalescing, product sessions/content and UI remain pending.
The dedicated emulator is stopped with regular HRIS data retained. No physical
phone, live service or employee message was touched; no Starter port is due for
the isolated fixture. Continue all P0–P8 implementation.

The native queue/extra-content experiment now passes in both directions through
actual Android 35 and browser E2EE. `org.hris.message_id` remains inside encrypted
content. The SDK's paused queue emits its local transaction, resumes, then maps
that same transaction to the server event; standard refresh preserves the signing
key. Both scenarios pass in 29.090s with complete cleanup. [Queue evidence](measurements/collaboration-native-logical-queue-2026-10-31.json)
records tested source/APK hashes, twelve driver cases, full root checks with 2,002
desktop cases and debug assembly/lint (52s; zero errors, 47 warnings).

Two earlier runs failed at synchronous `Room.enableSendQueue` with native
`InternalException`. Safe class/method/line diagnostics locate the call without
logging provider messages/credentials. The client owner's asynchronous
`enableAllSendQueues` passes and is used for connectivity gating. This experiment
does not prove process-restart queue persistence or recipient deduplication;
implement durable logical IDs, event aliases/tombstones and conflict handling
before offline acceptance. No Starter port is due for this isolated proof.
The dedicated emulator is stopped with regular HRIS data retained; no phone action
occurred. Native CI now pins Platform `d0731afe`; inspect its exact result after push.

Hosted native queue proof passed at exact `e8a5ceb3` in
[41198048864](https://github.com/fajarxfce/hris-mobile/actions/runs/41198048864).
Every job/step succeeded. Both provider scenarios passed without failures/skips
in 84.382s, with complete cleanup and an independently built APK. The
[measurement](measurements/collaboration-native-logical-queue-2026-10-31.json)
retains that result; its driver hashes match pinned Platform `d0731afe`.
Continue HRIS gateway/content integration and durable recipient reconciliation.

P0 native checkpoint: [the separate Android protocol fixture](collaboration-protocol.md)
uses Matrix Rust Kotlin SDK 26.10.23. Actual Android 35 ↔ browser encrypted text
exchange and the existing web/media scenario passed together in 24.590s, with
full owned cleanup. Platform driver/source `a9100ece` records 12 guard tests and
ciphertext evidence; [Mobile source/artifact hashes](measurements/collaboration-android-protocol-2026-10-31.json)
bind the tested APK. Full root checks and debug lint passed in 4m42s, including
2,002 desktop cases across 349 suites (no failures/errors/skips); lint has zero
errors and 45 dependency-version/fixture-packaging warnings. Native CI is defined with that exact Platform driver; record
its result separately. This is still P0: product sessions, native durable queue,
Apple bindings, MatrixRTC and native media remain pending. No Starter code port
is due yet and no phone install was performed. Android proof source is `477f9c60`.
Its first hosted run `41181935938` failed before compilation because the SDK
setup action still defaults to Google's retired `tools` package. The workflow
now requests `platform-tools` explicitly. Corrected run
[41182128950](https://github.com/fajarxfce/hris-mobile/actions/runs/41182128950)
passed at `eefc4121`: both real-provider scenarios passed without failures/skips
in 66.016s including cleanup. [Hosted evidence](measurements/collaboration-android-ci-2026-10-31.json)
records the independently built APK hash and pinned Platform driver. The first
setup failure remains historical; it was not a passed native proof.

Apple binding work now lives in `tool/collaboration-apple-proof`, pinned to Swift
SDK 26.10.27. Its hosted workflow must pass real native key-store reopening,
wrong-key rejection and the iOS device compile. Linux has not run those checks.
The SDK requires iOS 16; only this isolated package changes its minimum so far.
Hosted run [41182072489](https://github.com/fajarxfce/hris-mobile/actions/runs/41182072489)
passed at `e210508a`: one actual native key-store case and the iOS device build,
using Xcode 16.4 / Swift 6.1.2. The resolved package revision and
[measurement](measurements/collaboration-apple-binding-2026-10-31.json) are retained
in Git. This does not prove Apple provider interoperability or native media.
Continue protected HRIS integration while those remaining P0 gates stay explicit.
The local dedicated emulator has been stopped after successful Android proof;
its regular HRIS data and the physical phone were untouched.

The workspace policy now recognizes `COLLABORATION` separately from the HRIS
Inbox's `COMMUNICATIONS` key. The existing data-boundary test retains future-key
filtering and round-trip validation. All 28 workspace-domain and nine workspace-data
cases pass; dev-debug assembly passed in 3m54s. No installation was performed:
this is policy plumbing for the ongoing chat integration, not a delivered chat UI.
Platform schema 105 and its dashboard policy UI carry the matching identifier.
No Starter port is appropriate for this HRIS company-module catalog entry.

The standard native Matrix refresh mechanism now passes against real Synapse and
the browser. Four-second native access credentials expire, the SDK refreshes, the
session delegate saves both replacement tokens and encrypted chat continues with
the same device signing key. Both provider scenarios passed in 30.586s with owned
cleanup. [Refresh evidence](measurements/collaboration-native-refresh-2026-10-31.json)
records hashes, twelve driver cases and the corrected full root check: 2,002
desktop cases, zero lint errors, debug APK built (2m54s). An initial Detekt long
method failure led to the separate `ProofSessionStore`; no check was suppressed.
Native CI pins Platform `1db0f9fe`; hosted run `41187875527` passed at
`d799e5cf`, both provider scenarios in 54.657s with full cleanup.
This is private fixture storage, not a production Keystore implementation. Use
SDK standard refresh, never restore again on an initialized SDK client. Continue
protected HRIS sessions/provisioning/gateway and actual chat UI. No Starter port
is due for this isolated proof. The dedicated emulator is stopped, data retained;
no physical phone action occurred.

Updated 31 October 2026 (UTC). The release-configuration audit and independently
verified Starter port are complete. This employee application was generated from
Compose Fluent Starter and is the proving ground for reusable foundation changes.

## Completed mobile layout and compact-filter review — 31 October 2026

The requested employee-page layout/component review, fictional sample data and
follow-up compact filters are complete. Mobile is the proving ground; reusable
components and tests were then ported and independently checked in Starter.

- Mobile application `e5af27fd` includes the earlier form/detail/document fixes
  from `b73031be`, then lightweight status filters and paired period chevrons on
  the right. Full labels reflow at narrow widths or large text. Month/year/week/
  revision limits and each feature's filter scope remain unchanged.
- Starter production `58623f5`, portable test `df2906f`, closure `44d164c` include
  the identical generic foundation. No HRIS policy, routes or configuration was
  copied. No additional port is pending.
- Platform `1363abee` created 23 fictional visible records for the existing
  user-linked employee plus leave balance examples, using authorized APIs and
  retained idempotency receipts. Do not seed again with a fresh manifest. No
  identity binding, permissions or real payroll/payment action was introduced.

Configured **devRelease 1.0.0 / build 4** is installed on the WireGuard phone.
SHA-256 `20adbce11c8f727d3cf80d24d4909ebfd9dfd1f8c94d39327dd70f91bd701e71`,
24,127,587 bytes. Signed assembly passed in 142.752s. Install-only and pulled-APK
verification passed in 25.852s; app identity, first-install time and data were
preserved. No phone UI was launched/driven. **Future builds require versionCode
>= 5 and the same signer**. Test/docs-only commits after `e5af27fd` do not require
another installation. The normal Firebase configuration and private API are
verified in the actual artifact. Never deliver an unconfigured emulator fixture.

The identical configured release was inspected on the dedicated Android 35
emulator: month/year/week stepping, status selection/reset, normal/200% text,
dark appearance and landscape. Overtime content begins 72 dp earlier and Expense
content 80 dp earlier than build 3, measured on matching fictional rows. See the
[review and screenshot galleries](layout-review-2026-10-31.md) and
[complete measurements](measurements/compact-filters-2026-10-31.json). The emulator
was restored to portrait, normal text and light appearance; only its own session
was logged out, then its process was stopped. Private helpers, raw captures and
immutable APKs remain under `.work/ui-review-2026-10-31/`; no secret belongs in Git.
An old emulator auth fixture has build 3: rebuild it with a sufficient version
before using it again, and keep it on the dedicated emulator only.

Final local checks passed **2,002 Mobile desktop cases / 349 suites** (212 shared
UI cases) and **331 Starter cases / 114 suites**, without failures/errors/skips.
Hosted validation is complete and explicitly matched to unchanged production
source: Mobile Windows/Web/Apple passed in [41170243851](https://github.com/fajarxfce/hris-mobile/actions/runs/41170243851)
at `e5af27fd`; corrected Linux root/browser/Android/app-link verification passed
in [41171544190](https://github.com/fajarxfce/hris-mobile/actions/runs/41171544190)
at `f742e09d`. Starter Windows/Apple passed in `41170061606`, and corrected Linux/
Android/Web verification passed in `41170810070`. The earlier full runs retained
failed Linux tests; they are not represented as entirely green runs.

Those test corrections preserve the intended contracts: host fonts may wrap
390 dp filter labels, while 640 dp proves a single row; checkpoint publication
may briefly replace facts collectors through `flatMapLatest`, so tests wait for
a settled collector and count fake subscriptions atomically. Saved facts,
no-automatic-fetch, offline recovery, ownership and keyboard/disabled-action
assertions remain. Production code and APK bytes did not change for these fixes.
The manual CI `verify` scope keeps default all/PR/push and Apple-only behavior.
Physical iPhone testing remains explicitly waived; Apple simulator tests and the
Xcode app build passed.

Installed guidance is recorded with source pins in the review: Chris Banes
`compose-component-design` and `compose-ui-testing-patterns`, plus
`ui-ux-pro-max`, under `/home/fajar/.codex/skills/`. They become discoverable on
subsequent turns; their guidance was read and applied to this change.
No implementation, validation, delivery, sample-data write or user input remains
pending for this review. Earlier sections below are retained history.

## Previous Android installation — build 2, 31 October 2026

The user explicitly requested the latest release build on the WireGuard phone.
Source `5ffb7300` retains the accepted `d170810f` application/build/test trees.
A signed `devRelease` APK, version `1.0.0` / build **2**, is now installed on
`10.77.77.3:5555` (Samsung SM-A725F, API 36). It is non-debuggable and uses
`http://10.77.77.1:8088/api/v1/`. Release assembly/vital lint passed in 132.050s.
The existing development signing certificate was deliberately reused for the
private in-place update and existing Firebase registration; this is a release
build, not a new production distribution signing identity.

The 24,111,115-byte installed APK exactly matches SHA-256
`fa9b20a67148d323b4d8975da147871df6a1d9b576cf9202d0cd8c8cdf45919d`.
Install-only plus installed-byte verification took 24.772s. The package app ID and
first-install time are unchanged; no uninstall, data clear or UI launch occurred.
[Measurements](measurements/android-release-phone-2026-10-31.json) retain build
inputs, source continuity, certificate/package/API checks and installation proof.
The immutable APK and prior debug APK are retained locally under
`.work/release-phone-2026-10-31/`.

That installation established versionCode 2; the current build-4 delivery above
supersedes it. The repository default remains 1 and would be a downgrade.
Preserve the same certificate and install in place. No application source or
server change, Firebase distribution upload or generic Starter port was needed.
Earlier phone hashes/builds below are retained history.

## Completed workspace audit — 30 October 2026

The full Platform/Mobile/applicable Starter audit is complete. Platform commit
`64eb0d14` records all 61 initial requirements and eleven detailed module reviews
accepted, with final [CI 41094285531](https://github.com/fajarxfce/hris-platform/actions/runs/41094285531)
at `93438f99`: all ten jobs passed, including 3,090 API cases, 104 active worker
cases, 1,111 mocked browser scenarios, 56 actual workflows and schema-103 recovery.
GitHub temporarily returned stale step details; final logs, reports and artifact
digests prove completion. No cancellation, rerun or source change was required.

The private API/worker now run verified application `2c2a59e3` after a fresh
schema-103 data/key checkpoint. Health, migration history, Google/person bindings,
active native session IDs, document integrity and runtime environments are verified;
SMTP stays disabled. Dashboard `1e0522c0` is unchanged. [Current Platform handoff](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/agent-handoff.md)
and [runtime evidence](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/measurements/final-development-rollout-2026-10-30.json)
retain current ownership, rollback images and recovery locations.

Mobile's [fifteen-area review](feature-review-2026-10-30.md) and
[source/CI index](measurements/feature-review-2026-10-30.json) remain accepted at
`f4f101a1`, against unchanged full-CI source `d170810f` (`41077194465`). The normal
private APK with application `3c6db4eb` is already installed and required no new
installation for this backend-only update. Actual Android Google/Home and FCM
acceptance remain source-scoped; physical iPhone testing is explicitly waived.
Apple simulator tests and the complete Xcode app passed.

All 657 identical shared files and 60 differences were reviewed. HRIS business
policy stays downstream; no additional generic Starter port remains. Starter's
accepted source `77063862` retains its independent full CI and separately proved
artifact-upload correction. This closing update changes documentation only.
No implementation, local test or CI remains active. For new work, continue proving
reusable changes in Mobile before porting them, and use the current Platform
inventory rather than reopening historical pending paragraphs below. The review
explicitly records undefined future extensions, including post-approval overtime
fact amendments; it does not label them implemented or claim production release.

## Retained acceptance history

Earlier pending statements below are historical; use the active queue above.

Current metadata acceptance: exact source `d170810f` passed all four jobs in
[CI 41077194465](https://github.com/fajarxfce/hris-mobile/actions/runs/41077194465).
Downloaded reports verify 1,994 desktop, 1,676 browser, ten build-logic and 104
Apple simulator cases, with zero failures/skips. The full Xcode app, three Android
variants, app links and BOM passed. App source remains `3c6db4eb`; the intervening
change only adjusts the bounded metadata test deadline and records native evidence.
The normal private API APK is installed on `10.77.77.3:5555`; installed SHA-256 is
`1a114fbc388c843b45749a8475296f506563493a38bccabd146323ceb87cf791`.
Install-only took 5.777s; no UI was launched/driven. [Final evidence](measurements/document-metadata-final-ci-2026-10-30.json)
retains every job/step, report hashes, failed predecessor and installed binary.
Platform's current runtime is schema 103 with dashboard snapshot `1e0522c0`;
older schema-101/phone-72341ef6 paragraphs below are historical. SMTP stays disabled.

Platform Administration correction `3c8fb15a` is locally accepted and pushed;
full CI `41081153648` is active. Reporting review reproduced seven final-policy
MFA failures and the corrected focused run passes; broader verification continues.
These are HRIS business changes. No new Starter implementation port is due.


The full PRD review remains active. Platform's [audit](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/feature-audit-2026-10-30.md)
now retains forty-one reviewed rows across Approvals, Communications, Identity, Expenses,
Leave, Documents, People and Administration; twenty Platform rows and full Mobile review remain. Keep the original
scope; accepted increments do not establish whole-product completion.

- Platform `8014d733` adds optional announcement email, atomic publication capture,
  bounded SMTP delivery and private observations. Local acceptance, schema-102
  recovery and all ten [full-CI jobs](https://github.com/fajarxfce/hris-platform/actions/runs/41005223374) passed.
- `bbb99b48` exposes immutable shared approval history. `fcfdaf26` corrects
  read-completion MFA/delegation expiry; 60 approval API and 13 domain cases,
  quality/packages and two actual API/browser workflows passed.
- `737d39a7` corrects thirteen Communications read-expiry reproductions. All
  106 selected API, 13 domain and three repository cases passed, plus the actual
  API/worker/browser/SMTP workflow (37.1s; 1.0m with setup).
- `a45d5a9b` records Identity's five initial rows and eight detailed sections.
  Its source trees match accepted `8014d733`; downloaded reports verify 190 API,
  35 domain, 22 data, 17 worker and 95 Chromium cases, including eight real workflows.

- Platform `7a1c0251` and `8e5dd3db` complete Document read/command assurance.
  Twenty read reproductions and nineteen command/enrollment reproductions now
  pass; one inventory-replay scenario was an already-correct positive control.
  All 243 selected API, 18 domain and nine data cases, quality/packages and four
  actual API/browser workflows passed (42.5s). Four Document rows retain acceptance. The explicit category requirement
  in `documents.02` is reopened after the People audit found missing metadata.

- Platform `5b60aba3` completes the Expense assurance/input correction and its
  six-row/nine-section review. Twenty-nine genuine failed cases now pass, plus
  ordinary-scope controls and delegator-account wait regressions. Broad/final runs
  cover 335 distinct API, 20 Expense domain and 115 Reporting cases; quality/packages
  and four actual browser workflows pass (1.2m with setup). Scope includes current
  enrollment, receipt and decision delegation, and stable admitted command inputs.

- Platform `c68b721a` and `70b92e59` complete Leave read/command/delegation
  assurance and its five-row/eleven-section review. Ten reader and twenty-five
  command/scope failures now pass. Broad and final targeted runs cover 485 distinct
  API/OpenAPI cases (471 broad plus fourteen added cases), with 65 domain cases,
  quality/packages, six actual browser workflows (50.4s) and three actual worker
  workflows (53.4s). Scope includes final MFA, original receipt approval, beneficiary
  exclusion, stable policy input and current delegated access. Eight added capacity
  compatibility cases preserve ordinary/direct reads without permitting oversized
  delegated decisions; fixture setup failures are explicitly excluded from defects.

[Approval CI](https://github.com/fajarxfce/hris-platform/actions/runs/41009137720)
passed all ten jobs for `fcfdaf26`. Communications/Documents source `8e5dd3db`
passed all ten [CI 41013958569](https://github.com/fajarxfce/hris-platform/actions/runs/41013958569) jobs.
Expense [CI 41018107365](https://github.com/fajarxfce/hris-platform/actions/runs/41018107365)
passed nine jobs and all fourteen worker scenarios, but exceeded the five-minute
step budget (5.2m); later storage/export checks were skipped. Platform `84d37ad6`
records the failure and sets bounded eight-minute worker/twenty-minute job budgets.
Final Leave source `70b92e59e7b41bdafe9f9d1c4b9d5106412777f5` passed all ten jobs in
[CI 41023645590](https://github.com/fajarxfce/hris-platform/actions/runs/41023645590),
including unchanged Expenses, worker, private exports and recovery. Platform
`7dae1e26` records the exact outcomes and the failed predecessor.

People reader correction `b0b7c21c` passed 427 API/OpenAPI, 50 domain and fourteen
repository cases, quality/packages (1124s), three actual browser workflows (36.5s)
and two actual worker/browser workflows (37.3s), including setup. The later command
and Organization correction has fifty reproduced failures plus four prior controls;
all 54 focused API, 52 domain and fourteen data cases plus quality/packages passed
(142s). Final broad acceptance passed 497 API cases. Two legacy worker-import
fixture failures were corrected; all 104 ordinary worker cases then passed with
one independently accepted manual test skipped (226s). Production/package hashes
matched the broad API run. Three normal browser workflows passed in 36.1s and two
worker/browser workflows in 36.8s, including setup. Platform `158b81ac` commits the
complete correction and evidence. Exact source `158b81acce89f637dbf715bb573fffb7b99b92c9`
passed all ten jobs in [CI 41032184925](https://github.com/fajarxfce/hris-platform/actions/runs/41032184925).
Platform retains the terminal job/step evidence in `06f8dede` and its predecessor.

The audit confirmed missing distinct document category and foreign identity/document
business-validity metadata in `documents.02` and `people.03`. Upload deadlines are
operational; tax residency already belongs separately to Payroll. The previous
Documents review omitted category, so that row is reopened. See the
[gap and continuation](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/document-identity-metadata-gap-2026-10-30.md).
People command acceptance is complete. Platform `27e7f77e` implements metadata
backend/dashboard with 689 distinct accepted API cases, 104 ordinary worker cases,
27 database, 18 domain and nine data cases, plus 4,054 dashboard cases, 25 mocked
and five actual browser workflows. [CI 41038369597](https://github.com/fajarxfce/hris-platform/actions/runs/41038369597)
tests exact `27e7f77e722da9e8ac2bde47836353ef92dcc3b3`: all eight shards passed,
but recovery rejected schema 103 because the reviewed verifier still stopped at 102.
Dashboard integration also passed: the original run finished with nine successful
jobs and one recovery failure. Platform `292a30f1` fixes the verifier; 42 Python tests
and actual schema-103 restore passed (57.356s), including metadata/history/RLS and
restored constraint behavior. [Replacement CI 41043798660](https://github.com/fajarxfce/hris-platform/actions/runs/41043798660)
passed all ten jobs for exact `292a30f14498b2eee97a9255f22fe645639658e5`, including
normal/worker browser integration, private exports and schema-103 recovery. Platform Git
retains every terminal job/step and the downloaded recovery summary: 104 objects,
2,098,795 bytes, restored metadata/history/RLS/constraints and completed cleanup.
The original run remains recorded as a failure; no CI run is pending for this source.

Mobile's [owned metadata projection](document-business-metadata.md) is locally
validated. A real mapper-loss reproduction now passes. Root `:check` passes 1,869
desktop tests, Document Chromium suites pass 123 cases, and the dev APK builds.
[Measurements](measurements/document-metadata-read-model-2026-10-30.json) preserve
source/APK hashes and each earlier fixture/setup/lock correction. Format 2 refreshes
canonical records even at unchanged document versions and when the server later
advertises support. Old encrypted facts remain visible during staging; failed
cleanup never removes a confirmed read denial. Finance/Leave DocumentEvidence
stays separate from owned category, identity number and validity facts.

Initial-upload metadata `41fe79f9` is committed/pushed and locally validated: the capability-aware form, typed
confirmation, authenticated payload and encrypted intent retain category/identity/
printed dates. Legacy omission and pending original operations remain unchanged.
All 1,878 desktop tests, 131 Document Chromium cases, root quality checks and dev APK
assembly pass (163.698s). The assembled form exposed and now fixes feedback hidden
above a long form. Read [upload evidence](measurements/document-upload-metadata-2026-10-30.json)
for earlier fixture failures, the real reproduction, source and APK hashes.

First definite upload rejection recovery is also locally validated. Two genuine
baseline failures now pass; unknown codes and earlier uncertainty remain pinned.
The fresh form retains prior fields, and explicit confirmation replaces only the
exact observed rejected intent while keeping file bytes and proof. Cancellation,
competing writes, capability loss and read-denial restoration are covered. Full
root checks, dev APK and Document browser suites pass in 189.354s: 1,888 desktop and
140 browser cases. [Evidence](measurements/document-upload-rejection-2026-10-30.json)
records the baseline controls, method-length correction and source/APK hashes.

The separate metadata header/history/correction transport is locally validated.
It preserves legacy unknown facts, bounded contiguous history and the exact confirmed
correction/receipt across retry; headers no longer depend on a published binary.
Ten new cases pass, including authenticated Ktor payloads and denial/cancellation
boundaries. Root checks, dev APK and both Document browser suites pass in 184.233s:
1,898 desktop and 150 browser cases, with no failures/errors/skips. Exact source,
case names and APK hashes are in [transport evidence](measurements/document-metadata-remote-2026-10-30.json).

Metadata storage and per-document admission now also pass complete local validation.
Account-owned encrypted headers/history commit at most twenty rows and survive
restart/reauthentication; a page decrypts exactly one header plus its selected rows.
Tests retain 1,000 immutable revisions and cover stale writers, scope changes,
cancellation, lost keys, manipulated ciphertext and pending/failed admission storage.
Root checks, dev APK and both Document browser suites pass in 184.621s: 1,912 desktop
and 164 browser cases, without failures/errors/skips. See [storage evidence](measurements/document-metadata-cache-2026-10-30.json).
The existing workspace key is read-only; no database primitive or Starter port changed.

Admitted metadata reads and the actual detail/history route are now locally validated.
Thirteen domain cases and two assembled UI workflows cover ownership/capability,
finite directory/history acquisition, cancellation, local history after credential age,
pending/failed denial recovery and explicit storage-observer restart. Two genuine
failures in the new publication path exposed policy expiry during cache/admission
writes; both now pass with the earlier denial retained. Root quality, Android dev
assembly and Document Chromium suites pass in 197.437s: 1,927 desktop and 177 browser
cases, all without failures/errors/skips. Three narrow screenshots were inspected.
[Read/UI evidence](measurements/document-metadata-read-ui-2026-10-30.json) records
source/APK hashes, actual cases and the earlier compilation/method-length corrections.

Correction command contracts and encrypted storage are also locally validated.
The existing shared CommandStore retains at most 128 account commands, one per
document, with immutable input, exact record/revision checks, bounded authenticated
envelopes and the shared command key. A prior uncertain attempt cannot become a
proven rejection; original receipts and terminal outcomes remain immutable. Three
domain and six actual SQLite/IndexedDB cases cover restart, sessions, capacity,
resource slots, stale writers, lost keys, corruption, cancellation and late accounts.
Full root checks, dev APK and Document browser suites pass in 193.331s: 1,936 desktop
and 186 browser cases, all zero failures/errors/skips. Initial focused tests passed
116 domain/69 data cases in 40.635s before the final terminal assertions.
[Command storage evidence](measurements/document-metadata-command-store-2026-10-30.json)
retains exact sources, tests and the uninstalled APK hash.

Command intake, scoped observation, manual delivery and explicit review are now
locally validated. Confirmation stores immutable input without HTTP; replay checks
current policy/ownership, persists uncertainty before sending, and uses the original
operation after timeouts or later header edits. Denials fence projections even when
outcome/admission storage fails. Acknowledgement requires acquired current metadata;
it never replaces a newer header with an old receipt. No automatic delivery task was
registered. Fourteen domain scenarios cover those boundaries and release per-resource
subscriptions. The initial twelve failures were a read-only fixture, not production
defects; the corrected write fixture and separate read-only control pass. Focused
retry passed 130 cases in 29.808s, then the final overview case was added. Full root
checks, dev APK and Document Chromium suites pass in 180.515s: 1,950 desktop and 200
browser cases, all without failures/errors/skips. [Orchestration evidence](measurements/document-metadata-command-flow-2026-10-30.json)
retains sources, exact cases, setup failure history and the uninstalled APK hash.

Mutable working-copy contracts and encrypted storage are also locally validated.
A bounded draft retains incomplete input and its reviewed header; ordinary saves
cannot change that baseline. The existing ReplicaStore/workspace key holds one
16-KiB authenticated checkpoint per document, protected by exact-copy and revision
checks. Copies reopen across sessions without age expiry. Indexed overview pages
read twenty copies plus a lookahead; a full page decrypts exactly twenty envelopes.
Two domain and six actual SQLite/IndexedDB cases cover restart, stable paging,
concurrent edits/deletion, key loss, corruption, cancellation and late accounts.
Focused tests passed 133 domain/75 data cases in 41.438s; full root checks, dev APK
and Document browser suites pass in 180.730s: 1,958 desktop/208 browser cases, all
without failures/errors/skips. [Draft storage evidence](measurements/document-metadata-working-store-2026-10-30.json)
retains exact cases, sources and the uninstalled APK hash.

Working-copy access and acknowledgement cleanup are now locally validated. Ten
new domain cases cover reopening the original baseline without published binary
facts, exact save/delete recovery, concurrent edits, read-only retention, pending
cancellation/timeouts, ownership changes and denial-before-storage subscription.
Confirmed command acknowledgement removes only a matching no-later draft; failed
cleanup retains the receipt and a concurrent replacement survives. Focused domain
checks passed 143 cases in 37.376s; full root checks, dev APK and Document browsers
passed in 182.812s: 1,968 desktop/218 browser cases, all zero failures/errors/skips.
[Working-copy orchestration evidence](measurements/document-metadata-working-flow-2026-10-30.json)
retains exact cases, source and the uninstalled APK hash.

Catalog resource admission is now locally validated. An actual assembled failure at
`36ddd5ed` showed a denied document's title/file still visible after returning from
details. The catalog now observes at most twenty resource gates, hides the denied
record, preserves other rows and the original cursor, and retains a neutral recovery
action. Unrelated catalog sync cannot clear this denial. Three domain, one state and
one assembled workflow cover subscription disposal, failed admission storage,
pending downloads and offline/pending/successful recovery. The first focused domain
failure was incomplete metadata in the new fixture and is recorded separately.
Focused checks passed 146 domain/four state/one UI case in 100.848s. Full root checks,
dev APK and Document Chromium suites passed 183.001s: 1,973 desktop/221 browser,
all zero failures/errors/skips. Two narrow screenshots were visually inspected.
[Catalog admission evidence](measurements/document-metadata-catalog-admission-2026-10-30.json)
retains the genuine reproduction, fixture correction, source and uninstalled APK hash.

The typed correction editor is now locally validated. Its serial writer coalesces UI
edits into storage revisions and retains the exact pending mutation after a lost local
acknowledgement. Back/confirmation/review flush edits; incomplete drafts reopen with
the original reviewed header. Confirmation and manual retry preserve the original
command, while acknowledgement uses acquired current facts and keeps newer drafts.
Three assembled flows cover retained incomplete input, successful correction, lost
response plus a later server edit, explicit stale-baseline reset and denied/offline
recovery. Two presentation cases cover coalescing and late acknowledgements. A real
new feedback failure put the stale notice above a scrolled form; explicit refresh now
reveals it. Control readiness also waits for pending initialization and avoids redundant
local reloads. Fixture permission, selector and timing failures remain recorded separately.
Two additional domain failures on `8ca77f19` exposed single-command storage errors
masking known/later resource denial. Per-document admission now precedes storage and
remains observed after storage failure. All 148 Document domain cases pass, and final
root checks, APK and Document browsers passed in 178.280s: 1,980 desktop/223 browser,
zero failures/errors/skips. The final full run repeats all three new assembled flows;
six screenshots were visually inspected. [Editor evidence](measurements/document-metadata-editor-2026-10-30.json)
retains source/APK hashes, actual cases, earlier failures and practical limits.

The independent retained overview is now locally validated. Catalog opens **Document
corrections** even when a published binary row disappears. Local commands/drafts
retain their original input, resource admission and draft page cursor; row selection
opens the existing editor or neutral admitted recovery. Opening/reloading does not
send HTTP or replay commands. A 45-copy domain case verifies bounded 20/20/5 paging
and released subscriptions. Typed neutral restriction targets prevent private titles
from appearing during resource recovery. Two new presentation regressions exposed
stale private rows when either local reader failed; both admitted projections must
now be ready before private rows display. Five state cases cover that boundary,
denial union, reload and command/draft display deduplication.

Both actual-storage/controlled-HTTP application workflows passed, including the
retained incomplete draft after binary deletion/offline recovery, and the original
uncertain correction retried after a later administrative edit. Final root checks,
APK and Document browsers passed in 190.845s: 1,987 desktop/224 browser, zero
failures/errors/skips. The full run repeats both workflows. Three screenshots were
inspected. [Overview evidence](measurements/document-metadata-changes-2026-10-30.json)
retains exact sources, reproduced failures, actual cases and the uninstalled APK.

Account's Pending actions now includes the separate metadata summary and direct
overview link. Six domain cases cover original outcomes, exact draft deduplication,
untouched input, age-independent bounded pages, resource/company admission, failed
storage, disposal and delayed session replacement. At most 128 commands and twenty
working copies contribute; a next cursor marks a lower bound. Known resource gates
remain observed through storage errors. Unknown/denied resources hide all counts.
The actual-storage/controlled-HTTP Account flow resumes the same draft, reloads
without additional HTTP, and counts one original uncertain delivery with no duplicate
draft. The existing Leave flow and large-text/Indonesian layout controls also pass.
Full root checks, dev APK and Document browsers passed in 173.186s: 1,994 desktop/230
browser cases, zero failures/errors/skips. Two screenshots were inspected; [summary
evidence](measurements/document-metadata-pending-2026-10-30.json) retains exact
sources, cases, runs and the uninstalled APK hash.

The actual Android application `3c6db4eb` now passes owned API/PostgreSQL/private
Garage acceptance, committed in Platform `1e0522c0`. Two offline process restarts
retain incomplete input and then the read-only original uncertain correction.
Retry after an independent later admin edit confirms the same operation/body;
Done removes its matching draft and command. All seven summary groups empty;
the current version-4 admin header, three history revisions and binary revision
remain intact. Summary reloads stay 36→36 and 48→48. The fixture observed 78 native
requests and exactly two successful company writes; it passed in 432.451s with
owned cleanup and five inspected screenshots. [Native evidence](measurements/document-metadata-native-2026-10-30.json)
retains the APK/source hashes, initial fixture-lifetime failure and precise limits.

Full CI `41071828262` at `3c6db4eb` passed Web/Windows and iOS simulator compilation/
tests, but Linux failed the 1,000-revision browser storage case at Mocha's default
two-second deadline. A module-local bounded twenty-second deadline preserves all
assertions; all 75 Document data browser cases pass locally in 21.607s. The complete
Apple app also passed, with 104 tests in 28 downloaded suites and all terminal
Apple steps successful. [First CI evidence](measurements/document-metadata-first-ci-2026-10-30.json)
retains its overall failure and exact source. Replacement full CI `41077194465`
is running at `d170810f03852f46a948f9f4d5dea9ca4104bdbd`; inspect every final job
and artifacts before accepting it. Do not relabel the failed predecessor.

Next: complete hosted Mobile/Apple checks, then coordinate the controlled schema-103
API/static-dashboard/Mobile rollout. Finish the detailed People review and continue
remaining Platform/Mobile/Starter scope. Keep shared finance/leave evidence free
of private identity numbers and preserve legacy omitted payloads. The browser
configuration covers an HRIS-only metadata module; Starter's generic database
capacity suite already has the same bounded I/O deadline. No implementation port
is due. The full goal remains active.

The private API remains schema 101 with SMTP disabled; preserve the accepted Google
binding and installed phone. The dashboard serves an isolated baseline directory
documented in Platform's handoff, so local builds cannot replace it. The new APK
has not been installed. Coordinate the controlled schema-103 API/static-dashboard/
Mobile rollout after the complete metadata feature is validated. Phone updates are
install-only with advance notice; physical iPhone testing stays waived.

## Distribution configuration — accepted

The audit fixes selected release API precedence over per-environment project
properties, optional app-link configuration through signing and packaging, and
31 missing SBOM POM verification entries. The original artifact hashes remain
unchanged. Android checks the normal test configuration before compiling the
selected release; Xcode forwards the selected backend/API explicitly. The 15
portable files retain Starter's application IDs, demo/remote choice and three-job
CI. HRIS's remote-only policy, build-version UI fixtures and explicit dev-network
XML attributes remain in Mobile.

Mobile source `3dc81d00` passed all 4 jobs in full
[CI 40909156570](https://github.com/fajarxfce/hris-mobile/actions/runs/40909156570). Downloaded Apple reports
confirm 104 tests in 28 suites, zero failures/errors/skips;
compilation/tests/Xcode took 791s/122s/1295s. The complete Xcode app, native/domain
artifacts and explicit selected distribution API/backend assertions passed.

Starter port `77063862` passed all 3 jobs in full
[CI 40916187442](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40916187442). Downloaded Apple reports
confirm 84 tests in 24 suites, zero failures/errors/skips;
compilation/tests/Xcode took 724s/81s/744s. The complete Xcode app, native/domain
artifacts and explicit selected distribution API/backend assertions passed.

Actual local signed Android APK/AAB/SBOM exercises passed in 215.572s on Mobile
and 136.647s on Starter. Both verify the selected release API and native manifest
in APK and AAB, actual signatures, retained domain files and checksums. Mobile
passed 1,860 desktop cases; Starter passed 326 desktop cases in 112 suites. Both
passed 17 distribution regressions. Normal dev builds and production SBOMs were
restored afterward (39s Mobile, 23s Starter), with fixture API/host values absent;
Starter's normal backend remains demo. Every disposable signing key was removed.

A workflow-only follow-up at Mobile `d7e445a8` and Starter `4c794e7` retains
`.well-known` files in the manual artifact upload. The pinned action's actual
`@actions/glob` 0.5.0 selection reproduced five files with both domain statements
missing on each signed fixture; the correction selects all seven and every
checksum matches. Both workflows include hidden files only in the packaged
artifact directory. This separate proof does not invoke upload; application/native
code and ordinary CI definitions remain at the full-CI sources above.

No CI, local check or reusable port remains pending for this audit. No distribution
workflow, upload or phone installation was performed; the installed Mobile APK
remains `72341ef6`. The private server and accepted Google binding are preserved.
Real signed Apple IPA, APNs, public domain/DNS/TLS activation, physical GPS and
production operation retain their separate target requirements. Physical iPhone testing was
explicitly waived; simulator and unsigned Xcode proof does not imply device delivery.

See [distribution evidence](measurements/distribution-2026-10-29.json).

## Previous implementation checkpoint — HTTPS links

Final Mobile source `72341ef6` passed all four jobs in full
[40895095518](https://github.com/fajarxfce/hris-mobile/actions/runs/40895095518).
Downloaded Apple reports confirm 104 cases in 28 suites, zero failures/errors/skips
and the complete Xcode app; compilation/tests/Xcode took 701s/77s/1040s.
Starter port `738db36` independently passed all three jobs in full
[40895798634](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40895798634),
with 84 Apple cases in 24 downloaded suites, no failures/errors/skips and Xcode;
compilation/tests/Xcode took 561s/84s/703s. Generated Android/Apple association
checks passed, and the Xcode/Apple artifact success markers were inspected.
No CI or owned local acceptance remains active from this increment.

The authorized phone has application `72341ef6`, installed with `--install-only`.
APK SHA-256 `5b3754d5097eb0a535d1a98cff5bc37e508ea12a008eec897895d9289797fe4d`,
32,872,857 bytes. Its registered certificate/private WireGuard API match, and
fixture values are absent from runtime code and the binary manifest. First-install
time was unchanged; no phone UI was driven. Actual Google/Home acceptance remains
attributed to the earlier `678783b3` user-operated APK. The private API/dashboard
still returned HTTP 200; preserve the Google binding, keys, volumes and running stack.

The implementation queue is complete for this development pass. Physical iPhone
testing was explicitly waived. Public domain/DNS/TLS association, actual APNs,
physical GPS and production deployment remain separate configuration/target
observations; no result for those is inferred from builds or fixtures. Default
native builds do not enable an unconfigured HTTPS host. Reopen implementation for
an observed failure or a documented requirement; do not recreate accepted modules
from old next-step paragraphs.

The source foundation `d36a1bc9` first passed full CI `40887772486` (103 Apple
cases/Xcode) and actual Android HTTPS cold/login/company/scope/offline/archive
acceptance: 28 requests, no company writes, unchanged read/ack state, 3.2m/3.6m.
Only then was the reusable foundation ported. The later Unicode correction first
reproduced a host alias, then passed seven Mobile JVM/seven Chromium cases before
copying the same two files to Starter. Its own root/Android/Chromium checks passed
326 desktop, seven common/four app Chromium and six generator cases in 88s.
[Measurements](measurements/app-links-2026-10-29.json) retain the source boundaries,
initial failures, corrected checks, final full CI and both phone deliveries.

## Current user direction — 29 October 2026

The user explicitly waived physical iPhone testing for this completion pass
("iphone gkpp gk ditest juga") and requested continued implementation through
completion, with each atomic commit pushed. Do not ask for an iPhone again or
keep that test as a completion blocker. Simulator/contract tests and Xcode builds
remain required; do not claim actual iOS sign-in or APNs delivery from them.
Configurable HTTPS admission/native associations and the proven Starter port
are now complete, with the source and destination evidence above.
The controlled domain remains a deployment setting, not a reason to leave the
configurable implementation unfinished. Preserve the accepted Google binding,
phone data and running private development stack.

The HTTPS implementation is now in Mobile: exact environment-host path admission,
optional ignored public registrations, generated domain/Apple files, variant Android
manifest transforms, Xcode preparation and Universal Link handoff. Root/Android
and Chromium passed in 7m25s with 1,859 desktop cases and 26 common/app Chromium
cases; build-logic tests cover removal and malformed inputs. Configured artifacts
and full hosted CI passed before the foundation port.
Platform's public `/app/...` handoff passed 4002 unit and 14 related browser cases
without session or business requests.
See [app links](app-links.md); no real domain is enabled by the default build.

## Start here

1. Read `AGENTS.md`, inspect `git status`, the branch, recent commits and upstream.
   The working branch at this checkpoint is `feat/native-push`.
2. Read [validation](validation.md), [local-first coverage](local-first.md), the
   affected module contract and [template updates](template-updates.md).
3. Read the [Platform handoff](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/agent-handoff.md)
   and [remaining PRD delivery](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/completion.md#remaining-delivery-sequence).
   Backend/dashboard work is on Platform `feat/firebase-auth`, with application
   integration `b7f42070` / schema 101 and subsequent native acceptance fixtures.
   Full CI at `9345c19b` passed all ten jobs after correcting recovery report upload
   and a closing-report browser assertion; its exact run is in the Platform handoff.
   Starter work is on `fix/push-token-lifecycle`.

The three local checkouts are siblings under `/home/fajar/hris`. A fresh clone
must be able to continue from tracked evidence; local `.work` logs, screenshots
and `/home/fajar/hris/PRD-PROGRESS.md` are supplementary. The user authorized ongoing
implementation and a push after every atomic commit. This does not authorize a
release. Preserve unrelated edits and keep implementation, tests and docs together.

## Current phone delivery: installed after authorized key replacement

Current implementation adds iOS Firebase email/password configuration loading,
Apple bundle restrictions and an owned Google system-browser PKCE/code exchange.
All three matching public plists are installed locally and ignored. Mobile first
passed local password checks at `64039536`; Apple run `40853359016` compiled and
passed the four reader cases but failed one existing cancellation fixture (81/82
native cases passed). `196c1171` replaces both Firebase transport fixtures' racy
Boolean assertion with explicit bounded cleanup acknowledgement. Its Apple
follow-up [40855843460](https://github.com/fajarxfce/hris-mobile/actions/runs/40855843460)
passed 82 simulator cases and full Xcode before the password/configuration port
to Starter `8a75de5`. Starter root/Android, 308 desktop and 31 Chromium cases passed.
Its independent Apple run `40861505322` also passed 62 native cases in 18 downloaded
suites and the full Xcode app, with no failures/errors/skips.

The Google increment passes 65 focused desktop identity cases and public dev OAuth
configuration preflight. Root checks, Android assembly and 82 identity/application
Chromium cases passed in 3m23s, with 1,854 cases in the desktop report set. Full run
[40858176601](https://github.com/fajarxfce/hris-mobile/actions/runs/40858176601) at
`43cd8ec6` passed all four jobs. Downloaded Apple reports confirm 97 native cases
in 26 suites, zero failures/errors/skips, and the complete Xcode app. This closes
the Mobile verification gate for the generic Google port. Starter `e0c6071`
passed root/Android, 320 desktop and 46 Chromium cases; its full
[run 40864341588](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40864341588)
passed all three jobs. Downloaded Apple reports confirm 77 native cases in 22 suites,
zero failures/errors/skips and the complete Xcode app. Compilation took 526s,
tests 87s and Xcode 589s. The generic Google port is accepted. Direct
iOS-configured HTTPS password preflight also
returned Firebase proof for the exact owned fictional fixture, without HRIS
exchange or binding changes. Source/run details are in
[measurements](measurements/apple-firebase-2026-10-29.json).

Actual iOS sign-in/APNs still require device/provider acceptance; injected-browser
native tests and an unsigned simulator build do not claim that result. Physical iPhone
testing is waived for this completion pass; the [optional iOS procedure](ios-acceptance.md)
records future setup, observations and cleanup. The provider/owned-browser/crypto changes are
reusable; HRIS session policy and configuration stay here. See the
[Firebase contract](firebase-auth.md) and [validation](validation.md).

The user subsequently tried Google on the phone. Firebase confirmed the exact
verified Google identity; Platform `533472ca` records the successful explicit
binding after retiring the fictional password UID. The user retried and confirmed
company/Home, with one new native session independently visible in authorized
server metadata. Actual Google → Firebase → HRIS sign-in is accepted on the phone.
The old emulator session is revoked, with cached data retained; do not reactivate
its old Firebase binding over the real user's account. Private paths and recovery
details remain in Platform's current runtime record.

The user requested installation on the WireGuard-connected Samsung SM-A725F and
subsequently authorized uninstall/reinstall after the signing-key mismatch was
explained, including loss of local HRIS login/drafts/data. Both uninstall and the
install-only command returned **Success** on `10.77.77.3:5555`. The signing-key
question is resolved; do not ask for that approval again.

The installed normal dev APK is `dev.fajar.hris.mobile.dev`, version 1.0.0 / code 1,
32,853,128 bytes, SHA-256
`1883d1da41fdeabe77165de9fc75f1d106ff7583ae93aa92878940c7a2addd0e`.
The phone's installed APK hash matches the prepared artifact. Package metadata
reports the new installation and `stopped=true`, `notLaunched=true`; no app launch
or phone UI interaction was performed. This build uses application `678783b3`,
built at `f740d658` in 26s, with the normal `http://10.77.77.1:8088/api/v1/`
endpoint. See [initial rejection and successful delivery](validation.md#phone-update-signing-key-mismatch).
The local HRIS app data was removed with the user's authorization.

The API was unavailable during installation. The user then authorized continuing
development startup: Platform `703af975` records a new persistent private `hris-dev`
stack at schema 101, with actual HTTP 200 API health from this phone and a dashboard
user service at `http://10.77.77.1:5173`. Firebase Management confirmed the exact dev
app and registered this APK's SHA-1; its web client still matches the installed
configuration. No replacement APK is needed for that provider registration.
See [current development runtime](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/current-development-runtime.md)
for ownership and private configuration/access paths. Fictional company/employee
provisioning is complete: operator MFA, worker-delivered local invitation, standard
employee access, independent person link, weekday schedule, demo policies and
independent approvals. The same normal APK passed actual Firebase email/password
login, company selection and Home on the owned Android 35 emulator. Its document
catalog synchronized a real worker-inspected PDF and the detail displayed
**Downloaded**, 734 B. Platform's private database/content/key checkpoint passed
isolated restoration, followed by successful host/phone network and browser checks.
See [runtime evidence](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/measurements/development-runtime-2026-10-29.json).
The emulator is stopped with this persistent demo account's data retained.
There is no application change or generic Starter port in this increment.
Human Google sign-in is accepted above; physical FCM/location acceptance remains
separate from successful package installation and network health. The prior
loopback APK remains ignored at `.work/fcm-native-validated.apk`; do not distribute
it to this phone.

## Accepted result: actual Android FCM passed

The sender credential question is resolved. Platform `55288bc4` installed the supplied service
account privately outside Git and accepted two actual FCM publications on the
owned Android 35 emulator, using unchanged Mobile `678783b3`. Both foreground and
background notifications displayed generic copy; tapping the latter opened the
correct company/Inbox item without changing either item's unread/unacknowledged
version-zero state. The 55 native requests made no company mutation. Disable/logout
removed the server token/hash. Fixture 6.2m/6.6m with setup; all owned resources
are cleaned and emulator data retained. See [exact evidence and timing limits](validation.md#actual-fcm-foreground-and-background-delivery).
The initial manual-readiness and two-minute observer timeouts remain recorded;
the final 350-second observer passed without changing application behavior.

There is no Mobile implementation change or generic Starter port in this increment.
The existing SDK/queue/navigation foundation passed this actual provider case.
Android Google exchange was subsequently accepted above. iOS/APNs, verified HTTPS associations and physical-location
acceptance still need their separate inputs. Do not ask for the supplied FCM
credential again; its private host path is in Platform's current handoff.

## Previous result: Apple CI correction passed

The earlier local application change `678783b3` corrected the English Expense count label to
`Expense items: %1$s`, after inspecting the accepted native screenshots. Root
checks and Android dev assembly passed in 2m31s; the initial SDK-path setup failure
and source/result boundary are recorded in [validation](validation.md#expense-item-count-copy).
Earlier native fixtures and hosted CI below still refer to `02743a9f` application
sources. No Starter port applies to this HRIS copy edit.

Full run `40815794384` finished: Windows, Web and Linux/Android passed.
Its overall conclusion remains failure because Apple timed out.
Apple timed out during cold test compilation, not an observed compilation error.
See [CI correction](validation.md#apple-ci-cold-compilation-budget). The workflow
now separates compile/test/Xcode time limits, preserves all three test targets,
and supports manual `scope=apple` with independent concurrency and retained reports.
Apple follow-up [40817592306](https://github.com/fajarxfce/hris-mobile/actions/runs/40817592306)
passed at `451a2c4d`: 27 simulator tests in ten downloaded JUnit suites, no
failures/errors/skips, complete unsigned Xcode build and retained artifact. Compile
took 8m3s, simulator execution 52s and the Xcode step 18m54s. The generic workflow
correction is accepted in Starter `1817e45`: independent Apple run
[40820482699](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40820482699)
passed 27 simulator tests, the full Xcode build and artifact upload. No port is
pending. [Measurements](measurements/ci-2026-10-29.json) retain both Mobile runs,
the later copy-only checks and Starter proof.
Those local native fixtures are all stopped/cleaned. iOS/physical inputs
remain external; the FCM credential and actual Android delivery are now accepted above.

## Accepted evidence: native Attendance capture recovery

Platform `a837320f` verifies actual Android/API/PostgreSQL/worker response loss,
offline cold restart and exact replay of an explicit capture without location.
Stable summary reload stayed 21 → 21; one version-zero event retained its original
capture/receipt time. All six local pending rows cleared while Home/server still
required independent review with zero accepted minutes. There were 30 requests/two
intended writes; fixture 4.6m/5.2m with setup. All owned local resources are cleaned,
location restored and emulator data retained. See
[validation](validation.md#attendance-capture-response-loss-on-android).

Cross-platform CI `40815794384` was dispatched at `b0a95391`, with the same
`02743a9f` application sources used by the native fixture. Its three non-Apple jobs
passed; the separately corrected Apple job and validated Starter port are recorded
above. Platform full [40820912608](https://github.com/fajarxfce/hris-platform/actions/runs/40820912608)
then passed all ten jobs at `9345c19b`, including real API/worker/export integration
and schema-101 restore with a downloaded sanitized artifact. Those CI runs are
complete; no generic port arose from the native HRIS business fixtures. The newer
Starter Google port has its separate accepted full run above. Physical/iOS acceptance
still needs its actual external inputs. Android Google, the supplied FCM credential
and Android delivery are accepted above.

## Accepted coverage: read replicas and commands

The [local-first coverage](local-first.md#current-coverage-and-continuation) now
matches all nine `ObserveCompanyData`/`SyncCompanyData` registrations and the six
pending-action feature observers. Its old next-work list incorrectly still called
document feeds and the shared pending summary unfinished. Directory, private
profile and document catalog storage were checked against their account-storage
mappers and company partitions. Expense/Leave/Overtime working-copy and file/
attendance command contracts already cover the current employee editors.
This is a source/documentation reconciliation, with no new runtime acceptance or
application change; previous measured outcomes remain in their feature ledger.

Platform's payroll period comparison passed 119 API, 94 worker and 12 data tests,
3,997 dashboard tests, 23 distinct Chromium scenarios and an actual five-actor
January/February workflow with retained tax continuity and live access withdrawal.
Push delivery observations also passed schema-101 recovery. Neither administrative
feature needs a Mobile/Starter port. Continue missing acceptance and concrete PRD
gaps without repeating completed implementation or the resolved FCM credential question.

## Accepted evidence: native upload and inspection review

The actual Android/API/PostgreSQL/worker/Garage fixture passed in 8.7m/9.2m with
setup. One committed chunk lost its response; offline restart retained one transfer
and retry preserved the original command. A stopped scanner-unavailable job became
one review item, surviving a second offline restart. Explicit inspection retry,
cancellation, local deletion and logout passed. All six summary rows became empty
before local deletion. Independent server checks observed 57 requests, nine intended
company writes, one unpublished cancelled revision and four single-attempt chunks.
Summary counters stayed 32 → 32 and 42 → 42. See
[complete evidence](validation.md#interrupted-upload-and-inspection-review-on-android)
and [Platform's fixture](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-upload-acceptance.md).

The unchanged `02743a9f` loopback APK was reused; no implementation/Starter port is
due. All owned resources were cleaned and emulator app data retained. Platform
subsequently accepted actual elapsed export retirement, including the unchanged
one-hour physical grace and lost deletion receipt retry, at `1abdbce9`. iOS/physical
cases still need their separate setup. Actual Android Google exchange and
foreground/background FCM hints were subsequently accepted above.

## Previous checkpoint: native Firebase registration recovery

Actual Android Firebase Messaging token acquisition and HRIS registration now
pass response loss, offline restart, original-command replay, explicit disable and
logout. The final fixture observed 51 requests, one registration, two identical
PUTs, one disable, version 1 and no retained server token. No company writes were
made. Full acceptance took 9.7m/10.3m with setup. The final emulator APK SHA-256 is
`2b603fff2e40e31ee9cf5a45e694ea6aae8c96ae6c72cd4168d920c2e239c0c9`.

Two HRIS-specific fixes were required: clamp submillisecond server expiry to the
session's retained millisecond boundary; and complete retirement when a previously
owned family returns the exact absent-session result. Other expiry overruns,
errors and accounts remain rejected/retained. The first upgrade recovered an
already retained registration command; the second cleared an absent family before
new registration. See [full evidence and corrections](validation.md#firebase-registration-and-offline-recovery-on-android)
and [Platform's tracked driver](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-push-acceptance.md).

Root checks passed 1,840 desktop/UI tests plus tool/static checks; identity browser
suites passed 64 domain and 49 data cases. Android assembly passed. Local setup
requires `ANDROID_HOME=/home/fajar/Android/Sdk`; browser tests use
`CHROME_BIN=/home/fajar/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome`.
The first command omitted these environment settings and is not a passing browser
result; the corrected full command passed in 1m59s. Services, control files and
forwarding were cleaned, connectivity restored, and emulator data retained. No
phone was connected. Generic SDK sources did not change; no Starter port applies.

At this earlier registration checkpoint, server FCM credentials were unavailable.
The user subsequently supplied the file and actual Android FCM delivery passed
above. Continue Google token exchange and iOS/physical acceptance when their
inputs are available; do not repeat the resolved credential question.

## Last accepted increments

- Partial-download recovery now passes actual API/PostgreSQL/worker/Garage/Android:
  one retained MiB and transfer count 1 through offline process restart, summary
  no-op HTTP (31 → 31), explicit resume from byte 1,048,576, native save matching
  3,145,841 bytes/SHA-256 and six empty summary rows afterward. The server observed
  49 requests and zero company mutations. Fixture 9.7m/10.2m with setup; unchanged
  loopback APK reused. All owned services, control files, forwarding and public
  copy were removed; emulator app data retained. See
  [executed evidence](validation.md#partial-download-recovery-on-android) and
  [Platform fixture](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-download-acceptance.md).

- `c650e0d7` adds an actual unconfirmed Inbox command: server commit
  followed by response loss, Pending actions count 1 through offline cold start,
  local summary reload with no HTTP (30 → 30), exact-operation Retry and zero
  pending afterward. The independent server observed two identical read commands,
  version 1 and no acknowledgment across 42 requests. Fixture 6.0m/6.4m with setup;
  APK assembly 45 seconds. All owned resources were removed and the emulator
  stopped without wiping data. See [executed evidence](validation.md#inbox-command-response-loss-on-android)
  and [Platform's tracked procedure](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/native-acceptance.md).
- `60456c48`: real Android 35/API/PostgreSQL/worker URL and Inbox archival
  acceptance. Cold-login/company gating, repeated/resumed navigation, scope/query
  rejection and offline restoration passed. Independent server reads proved no
  implicit read/acknowledgement or company writes. Archival stayed enforced after
  offline restart. See [exact URL evidence](deep-links.md#android-url-acceptance).
- `1c1f9104`: actual Android/API Pending actions acceptance for pristine and edited
  Leave drafts, offline process restart, local summary refresh, reconnect and
  explicit deletion. Subsequent cases now cover all six feature observers; consult
  their individual evidence above without generalizing to every possible state.
- `9a557dcc` records cross-platform Firebase CI. Provider implementation `d191c61d`
  passed 1,837 desktop/UI tests, 119 related Chromium cases and Android assembly.
  Actual Firebase email/password completed HRIS exchange, MFA, company selection
  and logout; the temporary provider account was removed. Google chooser/Back
  passed, but actual Google token exchange did not run.

Reusable Firebase adapters are already ported to Starter `9a13791` and documented
at `a16325e`. Notification resource paths, token retirement and incoming-link
ownership were also ported; consult the file/commit ledger in template updates
before repeating a port. The latest native registration increment also fixes the HRIS metadata/cleanup
policies described above.
Platform worker observations/global audit are HRIS administration work, and the
native transfer fixture changes no application implementation; no Starter port is due.

## Next work and actual external inputs

All six Pending actions feature observers now have actual Android/API recovery
examples: Inbox, Attendance, Overtime, submitted Leave, saved Expenses and document
transfers/inspection review. See the specific validation sections for unchanged
summary counters, exact replay, final server invariants and cleanup; they do not
prove every state combination or physical target. Nine company data-sync registrations
and existing employee working copies are reconciled in [local-first coverage](local-first.md).
Do not repeat these features or native cases because an older checkpoint called
them next work. Continue concrete uncovered requirements or observed failures.

Platform full CI `40820912608` passed all ten jobs. The earlier Mobile and Starter
Apple correction runs also passed. New Mobile provider CI `40858176601` is accepted;
Starter Google port run `40864341588` also passed all three jobs as recorded above.
Those provider runs are complete. The newer HTTPS run `40887772486` also passed all four jobs and 103 Apple
cases/Xcode. Its Starter port and the subsequent Unicode correction both passed their final
full CI runs above. Do not repeat completed provider-foundation
work or use the older provider CI as proof of the HTTPS increment.

Firebase project `cmp-fajar` has Email/password and Google enabled. Android configs
exist locally in ignored `apps/android/google-services.json`; do not commit provider
credentials. Actual FCM foreground/background delivery and notification navigation
and Android Google/Home are accepted above. Matching public iOS plists are installed.
Configurable native/route association is now implemented. Public domain
publication, APNs setup and physical location remain deployment observations.
Physical iPhone testing is waived; only the domain question remains pending.
Do not repeat it while waiting or request an iPhone again. The supplied sender credential is installed privately
in Platform's host configuration. Public Android config is a separate file; do
not repeat the resolved credential question.

## Validation and device ownership

Use JDK 21 and `ANDROID_HOME=/home/fajar/Android/Sdk`. On headless Linux, root
checks use `xvfb-run -a ./gradlew --no-daemon :check`; Android development assembly
is `:apps:android:assembleDevDebug`. Select related Chromium tasks from the owning
module and inspect [development](development.md) and [distribution](distribution.md).
Do not infer device/provider acceptance from compilation or fixture responses.

For this workspace, supply tool locations explicitly (adjust paths on another host):

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
ANDROID_HOME=/home/fajar/Android/Sdk \
ANDROID_SDK_ROOT=/home/fajar/Android/Sdk \
CHROME_BIN=/home/fajar/.cache/ms-playwright/chromium-1248/chrome-linux64/chrome \
PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH \
xvfb-run -a ./gradlew --no-daemon :check
```

Append the affected browser/build targets to that command. Keep fixture-specific
API overrides local and explicit; this command preserves normal app configuration.

Use AVD `hris-mobile-dev`, serial `emulator-5560`; never wipe its retained data.
The last acceptance used a build-specific loopback API override. Preserve the
normal environment configuration and inspect any fixture before starting another.
The accepted fixtures were stopped and private connection files removed. Phone
delivery is install-only under `AGENTS.md`: notify before installation, select its
serial explicitly and do not launch or operate its UI while it is in use.

Update this handoff and validation after each accepted increment. Include the
exact remaining work, known failed attempts and corrected causes, resource cleanup
and whether a reusable change still needs its validated Starter port.
