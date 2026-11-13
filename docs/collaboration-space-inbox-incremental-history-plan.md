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

Current raw storage acceptance:21 paths, focused176, browser171 and full16868. The initial full run failed4 browser cases; concrete cursor JSON and a complete browser9 predecessor fixture corrected them. All original cases and failed identities remain. Focused non-browser inputs are byte-identical across the two browser-only fixes. See replica-change-pages.md and its exact-source measurement. Independent Starter1049 remains required before the next HRIS source increment.

The authenticated history reader has12 reviewed/formatted unapplied drafts and15 shared storage methods. Its focused55/full16898 gates have not run. Admitted local access, durable progress, existing incoming/repair integration and UI activation remain pending.
