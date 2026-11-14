# Finite history turns — locally accepted, parent activation pending

The finite turn implementation passed focused63/full17459. See [evidence](collaboration-history-owner-turn.md).
Application lifetime/mailbox activation and product consumers remain open. Earlier draft notes
below retain design rationale; current acceptance is described by the linked capability.


Fourteen formatted UNAPPLIED paths now contain global/source/page selection, one finite turn
worker,42 new shared test drafts, and DI/graph wiring. No validation gates or application lifetime
activation exists. The prerequisite global progress23 package passed focused59; full17372 is
running. Do not apply until prerequisite acceptance/push and this integration contract is reviewed.
Actual turn storage integration and the parent lifetime scheduler remain open.

The finite turn worker dispatches domain-selected actions to admitted use cases. It opens no
collector, timer or transport. One failed operation consumes its budget; its actual source state
is inspected once before the other lane so lost replies cannot cause blind writes or hide useful
progress. Both source lanes remain bounded. The raw page policy permits only one reset then a
fresh read; empty pages choose confirmation rather than cursor completion on acquisition.
Worker14 drafts cover bounded heads, fair lanes, lost replies, old passes, reset, denial,
cancellation and global/index conflicts. All42 cases remain UNRUN; do not claim acceptance.

Domain chooses the next explicit global action from a captured checkpoint and current trigger.
It retains an observation before visiting old work, honors queued heads before requesting pages,
and distinguishes idle/parked work from completion still needing its atomic destination guard.
Physical destination revisions do not create new demand. Selected checkpoint queues are copied.

Per-source selection finishes older captured intervals before preparing a later pass. Preparation,
history and pending operations are each attempted once per source visit, including failed attempts.
A finished older interval yields before preparing its later interval on another visit. A raw reset
does not renew the history budget. A history operation must itself bound one restart plus one fresh
read before yielding; a reset alone cannot justify an automatic global round. Failed history may
yield to the pending lane only while admission remains valid. Denial/cancellation always stops.

## Worker execution still to implement and review

Keep global and source selection in pure domain policy. The existing application repair lifetime
owns collection, cancellation and mapping selected actions to admitted use-case results. Do not
call a use case from another use case or hide repository orchestration behind a generic helper.
Use cases retain their repository/policy responsibilities; no transport acquisition belongs here.

Share the existing typed repair observation, observing history/catalog/destination metadata once
under the same account/Group lifetime. Compare destination generation rather than raw revision.
A conflated input mailbox must service newly arrived command input between bounded history steps;
self-produced checkpoint writes must not cancel or restart their own work. Cancel and join every
collector/turn before replacing ownership. Existing command seed/target/scan recovery stays intact.

Each action must retain its exact expected global checkpoint, current destination and actual source
before/after positions. Read source state before each visit. Capture catalog/source pages before
visits. Complete old source raw/pending windows before current-pass preparation. Missing preparation
or raw publication failure must remain recoverable and cannot erase pending references. Mark an
operation attempted before awaiting it. Never retry an old opaque trigger after CAS conflict;
reacquire current input/state first. A lost write response is uncertain, not a reason to repeat
within the same source budget.

Only useful finite continuation can schedule another local turn. A parked/failed turn waits for a
real history/catalog/completed-repair/destination change or explicit lifetime restart. Unrelated
message inventory emissions must not repeatedly resume failed history. Owner recovery is consumed
once per lifetime; a boolean left true after every write would recreate the original loop.

Required actual worker/integration tests: unchanged reopen, coalesced demand, catalog continuation,
failed preparation beside healthy rooms, older intervals, bounded raw restart, command fairness,
source/global/index CAS, lost reply, current denial, cancellation and account replacement. Existing
worker13 and integration3 identities must remain. No polling or extra per-room native runtime.

Phone92/live unchanged. All54 PRD groups remain open. Actual new Apple execution and product
incoming/thread/list/SDK8 Apple activation still require independent evidence.
