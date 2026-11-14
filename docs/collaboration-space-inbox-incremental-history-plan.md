# Indexed local replica change pages — prerequisite review

Purpose: bounded incremental local history/index backfill under existing account/Group ownership.
This is storage-only infrastructure; no new polling owner or transport endpoint. The raw capability is locally accepted; HRIS consumers and ownership remain pending.

Current ReplicaDocument.version is supplied by callers, independent of ReplicaCheckpoint.revision.
validateReplicaBatch allows any nonnegative document version; equal-version payload changes are
valid. Sorting a change feed by document.version would miss valid writes. Raw SQLite and IndexedDB
retain tombstones, but tag queries exclude their empty tags. A visible history window also excludes
locally hidden originals. Neither existing query is a complete incremental history change feed.

The accepted implementation retains an internal local changedAtRevision on each raw record, independently of the
unchanged public/source version. It is assigned atomically with the generation checkpoint:0 on
replacement, expectedRevision+1 on an effective commit update. Unchanged exact raw records retain
their earlier change position; ignored older versions cannot create a change. SQLite and IndexedDB
must preserve existing tombstone/version/prevention semantics. Index by owner/partition/generation/
changedAtRevision/id, including tombstones. No server timestamp or message ordering is inferred.

The public read returns one owned published checkpoint plus at most200 changed records and a
lookahead under one read transaction. A captured window has generation, exclusive completed lower
revision and inclusive upper revision. Continuation uses local change revision/id; higher writes
are deferred to the next window. An old row updated beyond the frozen upper limit is returned
in that next window, never silently considered caught up. New generation requires Restart; staging
cannot hide or advance the active generation. Empty delta after a checkpoint-only change remains
empty. The caller persists an After cursor only after confirming its derived publication/handoff.

Migration: SQLite13->14 adds changedAtRevision default0 and a compound index. IndexedDB12->13
backfills this field to0 in the same version-change transaction as index creation. Existing
ciphertext, payload versions, tags, original sort order and keys remain byte-identical. Initial
scan includes revision0 so migrated records cannot be skipped. A field/default/index migration
requires actual old-database preservation coverage, not only fresh database tests.

Required contracts: initial/current window; multiple pages; equal source-version changes; unchanged
raw replay; tombstones; ignored older source versions; sort/tag changes; checkpoint-only updates;
new writes on either side of an active page cursor; generation replacement; staging publication;
account replacement/reauthentication; mutable request input; bounded limits; missing partition;
source or second-replica conflict; rollback/lost response/restart; SQLite and IndexedDB upgrade.

Keep technical query/bounds in core/database and raw adapters. Domain use cases own local admission,
backfill policy, durable progress, work priority and app lifecycle. Consumers authenticate/decode
content and source coordinates; the raw change position is not a provider receipt or chat order.
This reusable capability must be proven in Mobile and ported with tests/migrations to Starter.
Apple fixture correction and its blocked gate-script attempt are separate; do not reproduce that
blocked payload. Do not change inputs of an active verification process.

Current raw storage acceptance:21 paths, focused176, browser171 and full16868. The initial full run failed4 browser cases; concrete cursor JSON and a complete browser9 predecessor fixture corrected them. All original cases and failed identities remain. Focused non-browser inputs are byte-identical across the two browser-only fixes. See replica-change-pages.md and its exact-source measurement. Independent Starter1049 is accepted and pushed at 092fbe6f70475efe950c697d3b7c6ec46b1fb267; its21 generic files are byte-identical. The next HRIS increment may proceed.

The authenticated history reader is locally accepted:12 paths,15 shared storage methods and focused55/full16898. See the current history-change capability and measurement; do not rerun its historical gates. Admitted local access, durable progress, existing incoming/repair integration and UI activation remain pending.

## Existing owner follow-up — not implemented

The accepted raw feed and drafted authenticated reader do not themselves schedule anything.
Actual writers EncryptedCollaborationSpaceHistoryRepository.merge/mergeEdits and the Company
history writer commit raw source changes without an inbox recovery wake. The existing Group
repair owner currently observes only original command inventory and its repair-demand stream.
It does not observe the canonical catalog or retained history. Merely adding a reader would
therefore leave an already-open list unaware of new incoming content.

Integration must provide all of these together:
- A coalesced account-owned history-change signal committed with effective source changes.
  WorkWakeStore alone is application-global and has no observe contract, so its existing atomic
  WorkWakeRequest support is insufficient for this local owner. Review the existing atomic replica
  mutation capability for an owned observable signal; do not add polling to bridge that gap.
  Exact replay must not generate fresh work. Company history may exist before Group binding, so
  signal identity cannot assume a current Group.
- The existing account/Group repair owner observes canonical catalog version plus the owned
  history signal. A newly materialized binding requests finite backfill of existing Company facts.
  No permanent per-room subscriptions, extra polling scheduler, or network acquisition is needed.
- Durable progress preserves a finite catalog pass and each actual source's captured raw window.
  Large source windows must yield to other conversations and resume after process/turn boundaries.
  New messages do not restart that captured interval. Completion uses window.throughRevision.
- Unresolved references need a durable handoff before cursor advancement; a permanently unresolved
  alias must not block all later originals in that conversation or cause unbounded immediate retry.
  Extend repair ownership/storage deliberately instead of introducing another worker queue.
- Existing incoming completion can rely on a proven atomic durable handoff only after the source
  writer and recovery path are actually integrated and tested. Previously retained sources and
  pre-binding Company content require explicit startup/catalog recovery evidence. Otherwise split
  retained proof preparation from traversal resolution; do not complete on uncertain publication.
- Current global repair worker treats its 256-page/30-second budget as a failure and then waits
  for new demand. History continuation must distinguish successful finite progress with more work
  from operational failure: continue owned bounded turns with cancellation/yield, while failures
  park until a real signal or owner restart. Never spin on the same cursor or self-generated wake.

Required behavioral evidence includes stopped process between each durable handoff, missing and
later-created binding, old Company content, unchanged reopen, concurrent new messages while a
large interval is visited, a hot room alongside cold rooms, unresolved references alongside later
originals, denied/replaced account, joined cancellation, and no request loop for exact replay.
This review selects no new production entity/schema yet. It is a concrete integration checklist,
not acceptance or an active observer. Keep use-case orchestration out of repositories/ViewModels.

## Account-owned history change signal — unapplied draft

Prerequisite: authenticated history reader full16898 accepted, committed and pushed. Do not apply
while its compiler is running. No generic Starter delta, server deployment or phone release.

The existing Group/Company history repositories publish source facts and one coalesced account
signal using ReplicaStore.mutate. First insert uses Replace, later effective changes use Commit;
the second mutation replaces an exact observed signal checkpoint. Both share the raw account
scope. Contention returns false without either write; unknown/lost replies remain failures and
cancellation propagates. Existing no-op branches retain their checkpoint ownership check and do
not write the signal. No new retry policy has been introduced.

CollaborationHistorySignal maps only a fixed protocol marker and fresh opaque generation.
Its account-owned partition has no identity, chat body, provider status or secret payload. It is
an invalidation hint, not authenticated content, access permission or completed repair evidence.
The existing content codec still authenticates all retained history. Signal reads require no
key acquisition. A malformed existing marker fails instead of silently replacing a new format.

StoredCollaborationHistorySignalRepository observes one published account checkpoint and maps it
through safeStorageFlow. Multiple Groups of the same account receive the same coarse hint; their
own use cases must admit access and enumerate only actual canonical sources. Company writes can
precede Group binding. No per-conversation collector, polling timer, transport or new worker.
Private publishHistoryChange methods in the two repositories coordinate raw source/signal I/O;
the protocol object performs mapping only. There are no repository-to-repository dependencies.

Thirteen new shared actual-storage method drafts cover absence without keys, restart/login,
pre-binding Company changes, no-op/hide replay, edits/redaction, source and signal contention,
lost replies, pre/post-commit cancellation, changed account, malformed metadata and an active
observer's committed source visibility and cancellation. Seven old fault-injection overrides in
the existing22 Company and16 Group history methods now intercept mutate, preserving every old
method and failure scenario at the actual write boundary. App graph resolution is also covered.
Other history/traversal/inbox contracts remain in the full gate; no old checks are removed.

Thirteen reviewed source/test drafts; focused84/full16924 gates prepared but not run.
The original raw history reader's active source remains unchanged. Captured source enumeration,
durable progress and unresolved handoff plus fair bounded continuation under the existing Group
repair owner are still required before the list/product graph can use this signal. Old history
and first binding also require startup/catalog demand; a future signal does not backfill itself.

Owner integration facts reviewed after preparing these drafts:
- ReplicaStore.mutate accepts at most two partitions. Existing history publication already uses
  a source Check and one inbox mutation. Do not casually add a third progress write to that call.
- CollaborationSpaceInboxPublication and CollaborationSpaceInboxCodec currently encode entry,
  draft, activities and Group-original repair only. No history cursor/unresolved record exists.
- PublishCollaborationSpaceInboxHistory currently accepts canonical conversation plus references,
  resolves the actual source itself, and returns only published/unresolved event IDs. A future
  cursor-completion path must also bind publication proof to the exact supplied source owner;
  equal event IDs in a replacement/Company source cannot complete another source's raw window.
- A separate progress commit after confirmed index publication can safely replay an uncertain gap
  only if cursor/unresolved handoff commit together and completion records the destination index
  generation. Replaced index generation must cause source reconstruction even when history and
  source signal did not change. Merely remembering the last source revision can lose projections.
- Auxiliary-only source pages still need finite progress despite zero event references. The
  current publisher accepts1..16 IDs, so it cannot be called with an invented event for that case.
- Global pass completion must retain its captured signal/catalog version; per-source windows must
  retain a finite upper bound and yield between rooms. Operational failure is distinct from more
  successfully committed local work. Do not turn a page budget into either a permanent stall or
  an immediate retry of the same failed cursor.
These are constraints for the next implementation, not implemented progress storage or acceptance.

The existing index-publication/reopen test also verifies that projecting a source into the inbox
does not renew its source signal. This guards against an observer feeding its own writes back
into an endless repair loop. All existing index-publication method identities remain.

## Durable history progress — initial design, not accepted

Prerequisite: atomic source signal source accepted/pushed. One domain entity draft exists. No
repository, policy, codec, use-case/worker binding, test or gate has been prepared. Do not apply.

The intended owner is the existing CollaborationSpaceInboxRepairWorker. Preserve its original
command lane. Add durable catalog discovery and unfinished-source records under this same owner;
no new worker, timer or permanent per-room observation. Complete one finite catalog pass, then
visit bounded pages of unfinished sources in rounds. Each source consumes at most one pending
reference page and one changed-history page per visit. Unresolved references retain their original
insertion positions and do not immediately retry in the same captured pending interval.

Global completion remembers the captured history signal/catalog/original repair signal tuple.
Unchanged reopen resumes durable work or stays idle. A changed tuple requests a later pass without
restarting current finite source windows. Per-source identity retains the actual Company/Group
owner and destination index generation. Index replacement must rebuild even without new history.
An empty-reference page can advance raw progress without inventing a publication event.

Before code is extended, review pending-window persistence/validation (the draft now represents an explicit
generation/upper bound and completed empty interval), destination-generation change notification, source activation/read pagination, exact source-bound publication proof and atomic
cursor/unresolved handoff. Existing publisher accepts only event IDs and returns IDs, so it cannot
yet prove another caller's source window. Canonical binding repository preserves an existing pair
immutably; do not add expensive speculative per-read binding queries without a real invariant.

A separate cursor commit after confirmed index publication is replay-safe only with a proven
source identity, retained destination generation and atomic unresolved handoff. Lost responses,
replacement, denied/replaced account, cancellation, hot-room fairness, old content before binding,
empty pages and unchanged reopen need behavior tests. These are open design obligations, not
implemented guarantees. Keep all scheduling/admission policy in use cases and the existing owner.


## Admitted history draft and publication gap — 14 November


Prerequisite: accept and push atomic source signal. Ten paths: four new domain source files,
23 shared use-case tests in two classes with two fixtures, data DI, and existing app graph assertions.
No changes to storage, native runtime, product worker, UI, phone or live server.

ReadCollaborationSpaceHistoryChanges admits account/session/audience and Group access around
binding and source reads, selects the actual immutable Company/Group namespace, and acquires one
bounded page (1..16). It freezes references before later admission can suspend. The pure policy
checks exact owner, generation, frozen interval, bounded opaque source revision and advancing continuation. Revision encoding
remains in storage; domain uses only the typed window for cursor arithmetic. Empty
reference pages can continue; absent/restart remain explicit. Source acquisition never completes
repair. A foreign cursor cannot be reused in another namespace or conversation.

ObserveCollaborationSpaceHistoryChanges owns one local account signal under Group admission,
retains confirmed denial through failed revalidation, joins cancelled subscriptions, and restarts
only after admitted ownership. Token rotation does not replace unchanged readers. No scan, key
creation, network request, timer or permanent per-room observer.

Tests cover both physical namespaces and offline credentials, invalid bounds, missing binding,
wrong cursor ownership, denial/replacement during suspension, malformed or non-advancing pages,
empty continuation, saved After intervals, mutable responses, explicit absence/restart, failure
without retry, cancellation/late completion, signal denial/reopen, slow consumers and disposal.
Expected focused53 and full16970 (all16924 previous identities +46 shared desktop/browser cases).
Apple source/test compilation will be checked separately from actual Apple execution.

Then return to durable owner progress design in .work/history-inbox-progress-2026-11-14. The
existing Group repair owner still needs source-bound publication proof, destination generation,
atomic cursor/unresolved handoff and fair finite continuation. These contracts alone activate none
of those consumers. No progress completion may be inferred from reading a page or signal.


The admitted reader/signal contracts are drafted separately in
.work/history-changes-admission-2026-11-14 (10 paths,23 methods, no apply yet). They select the
actual immutable namespace, validate typed cursor windows, and leave the source revision token
opaque. They do not solve durable publication completion.

CollaborationHistoryInboxWriter currently returns Boolean. On a first index publication the
codec allocates the destination generation internally; the publisher cannot know it from its
proposal. Reading it afterward cannot prove it is the generation written if replacement raced.
The conditional publication contract therefore needs to expose the actual committed version
(including confirmed no-op), with conflict/uncertain failure still explicit. Only six existing
source/test files currently override or call publishInbox; changing the contract is tractable.

PublishCollaborationSpaceInboxHistory also returns only retained/unresolved IDs and exits before
reading the index when no originals resolve. Durable source cursor handoff needs explicit source
identity and destination generation even for unresolved-only work. Do not parse the opaque
sourceRevision in domain to infer generation. Typed source version/proof and atomic unresolved
handoff must be designed together before marking raw intervals complete. Empty raw pages must
not invent an event to fit the publisher's existing1..16-ID API.
