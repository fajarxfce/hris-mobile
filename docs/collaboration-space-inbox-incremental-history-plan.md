# Indexed local replica change pages — prerequisite review

Purpose: bounded incremental local history/index backfill under existing account/Group ownership.
This is storage-only infrastructure; no new polling owner or transport endpoint. No source applied.

Current ReplicaDocument.version is supplied by callers, independent of ReplicaCheckpoint.revision.
validateReplicaBatch allows any nonnegative document version; equal-version payload changes are
valid. Sorting a change feed by document.version would miss valid writes. Raw SQLite and IndexedDB
retain tombstones, but tag queries exclude their empty tags. A visible history window also excludes
locally hidden originals. Neither existing query is a complete incremental history change feed.

Proposal: retain an internal local changedAtRevision on each raw record, independently of the
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
backfills this field to0 through its upgrade cursor before creating/querying the index. Existing
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
blocked payload. No new changes may enter the active producer compiler until it is reaped.

Current draft:15 unapplied files,12 shared storage methods with Desktop/Apple/browser wrappers.
Standalone formatting and browser-read syntax check only; no compilation or behavior execution.
SQLite14 schema output and old-database migration tests remain mandatory before application.


## Current validation — 13 November 2026

Twenty raw code/schema/test paths are applied in Mobile. New SQLite storage12 andmigration1 reports pass; focused176/session52335 still runs. Browser12/7 migrations, full16868 andindependent Starter1049 remain. No history owner, UI or device change is activated. Starter port scripts are prepared only. See current agent handoff for exact source and generation-only capture caveat.
