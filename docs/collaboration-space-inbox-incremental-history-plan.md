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

The authenticated history reader has12 reviewed/formatted unapplied drafts and15 shared storage methods. Its focused55 is accepted and full16898 is running52064. Admitted local access, durable progress, existing incoming/repair integration and UI activation remain pending.

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

Twelve formatted reviewed source/test drafts; focused68/full16924 gates prepared but not run.
The original raw history reader's active source remains unchanged. Captured source enumeration,
durable progress and unresolved handoff plus fair bounded continuation under the existing Group
repair owner are still required before the list/product graph can use this signal. Old history
and first binding also require startup/catalog demand; a future signal does not backfill itself.
