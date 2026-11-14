# Global inbox history owner — design only, not implemented

Prerequisites are source progress, admitted raw handoff, bootstrap demand separation, source
lifecycle, pending admission and source inspection/Restart. Initial global state in
history-inbox-progress-2026-11-14/design/global-and-source-state-before-split.kt is insufficient
for fair resumable rounds. Review schema and transitions before writing production code.

## Retained demand and current destination

Use history signal, frozen catalog version, actual completed command-repair demand and destination
index generation. latestDemand alone can trigger history before command publication completes.
Do not add two independent subscriptions to the same repair progress: review returning typed
latest/completed signals from the admitted observer and sharing one owner lifetime. Current
ObserveCollaborationSpaceInboxRepairDemand deliberately emits only latestDemand; its existing
command behavior must remain intact. The observer's denial latch/current admission remains required.

Retain latest trigger, completed trigger and active captured pass. All-null trigger is a real empty
observation, distinct from never having completed a pass. Bootstrap once; unchanged reopen returns
state without a write. New demand is coalesced without relabeling an active older captured interval.
A changed destination generation invalidates active traversal explicitly. Catalog Page.changed and
version changes require a later finite pass; never infer end from empty items with a continuation.

Opaque signal IDs have no ordering. Global mutation uses an explicit expected checkpoint and no
implicit CAS retry: an older captured request must not overwrite a later one after rereading it.
On contention the owner must reacquire current signals/retained state, not blindly reapply stale
input. Physical global version is independent of source-history/index versions.

## Atomic global cursor retention

A global repository can use raw ReplicaStore, ContentCipher, credential storage and existing
history/index/source codecs; no repository-to-repository calls. Use a separate strict schema/key
prefix that account incoming discovery ignores. Progress writes must not emit the history signal.

Global mutations should atomically check current index metadata, expected global checkpoint and
bounded source checkpoints whose preparation/completion justifies advancing the global cursor.
Per-source success alone cannot justify a global cursor if that source was replaced before the
global commit. Decode/authenticate the exact source progress with the shared codec, then use raw
Check mutations in the same transaction. Bound catalog/source pages and guarded sources to16.
Preserve account/Group scope, absent-vs-present metadata, independent key retention, response loss,
cancellation and exact competing writes. No global/schema implementation exists yet.

## Catalog phase and fair source rounds

Catalog discovery prepares bounded source metadata first. Missing immutable bindings are deferred;
new binding publication already emits history invalidation. They must not block all other rooms.
A reconstructed global pass may encounter unfinished older source passes: inspect/resume the exact
old checkpoint, complete both lanes, then prepare the current pass and process its later interval.
Never repeatedly call preparation with a newer ID against the unchanged unfinished older pass.

Retain catalog continuation, source continuation and explicit round outcome. A source needing
another bounded page must yield to other sources; unresolved references are allowed to remain
after a captured pending interval completes. One bad room cannot monopolize the account owner.
A fully scanned round with only failures/no forward progress parks until real demand or explicit
owner recovery, rather than scheduling itself. A completed finite pass records its captured
trigger; a later captured trigger remains pending.

Raw Restart is not monotonic forward progress by itself. Bound restarts, try a fresh bounded read
within that source's budget, and do not let repeated generation resets continuously self-schedule
new turns. Distinguish actual page advancement, restart and deferred failure. Preserve the exact
checkpoint on turn deadline/cancellation; no fabricated completion or reference deletion.

## Existing application lifetime

Integrate with the existing CollaborationSpaceInboxRepairWorker/application module and command
inventory. Current worker processes inputs serially with a30-second turn deadline; adding a second
unbounded loop would delay command service. Review a finite owner turn/selection policy that
prioritizes new admitted command input and yields history after bounded useful progress. Keep one
account/Group lifetime, cancel/join on replacement, and explicitly own every subscription/job.

Observe index generation cheaply under that lifetime. Using observePage only to obtain generation
would decrypt recent rows after every index mutation; consider a metadata-only repository contract
instead of a new per-room observer. No polling, network acquisition, UI/VM orchestration, extra
native runtime or provider credential changes belong in this local owner.

## Required evidence before activation

Exercise unchanged reopen, active-cursor recovery, later demand, commit loss, stale global/source
CAS, index replacement, catalog empty continuation/change/restart, missing bindings, old source
passes after global reconstruction, finite repeated source restarts, two-lane fairness, failed room
alongside healthy rooms, command service under history backlog, denial/account replacement and
cancellation at each suspension. Test actual atomic storage on desktop/browser/Apple compilation;
actual new Apple execution remains a separate outstanding gate. Product/thread/list and incoming
completion integration are not implemented by this design.

## Additional transition constraints before implementation

Retain completed pass ID together with its trigger, not only completed trigger. Reusing that ID
for later demand would make already-completed per-source checkpoints falsely look current. Begin
must reject reuse of a completed pass ID; exact replay of an existing active pass may be unchanged.
This changes the original initial global-state draft and requires explicit schema tests.

Use explicit expected global checkpoints for mutations without implicit CAS retry. Trigger IDs
are opaque and cannot order stale input; a failed old request must reacquire current signals before
trying again. A changed destination generation supersedes active traversal; mere index revision
advancement must not generate new history demand.

Before a catalog/source cursor advances, retain bounded authenticated source checkpoint guards
in the same raw global transaction. A catalog page with missing bindings can defer those entries
because binding publication already signals availability. Never advance past a claimed prepared
source solely on an earlier return value if its checkpoint has since been replaced.

Round bookkeeping must distinguish monotonic handoff progress from Restart/deferred outcomes.
Source cursor enumeration alone is not evidence of useful progress: otherwise a round containing
only failed rooms will loop forever. Application orchestration maps specific admitted actions to
explicit domain visit outcomes; domain policy owns whether another finite round is warranted.
Raw/pending handoff advances its captured interval; old-complete to current-pass preparation is
bounded progression; a raw Restart alone does not authorize automatic continuation indefinitely.
Global storage checks the actual resulting source checkpoints before retaining those outcomes.

A reconstructed global pass must finish each older source interval before starting its later
interval. Current-source inspection and explicit Restart now exist only as five reviewed drafts
in .work/history-inbox-source-recovery-2026-11-14, with tests/gates unrun. Admitted source metadata
enumeration, typed shared repair signals, global storage/policy and owner activation still require
implementation. Do not collapse these pending obligations into an implemented claim.
