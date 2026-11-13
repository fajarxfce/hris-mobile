# Incremental local replica pages

ReplicaStore.changedDocuments returns bounded coalesced changes in the published local generation,
including raw tombstones. A storage-owned changedAtRevision is separate from ReplicaDocument.version,
which retains the caller's source meaning. Replacement rows start at zero; effective conditional
commits record their new checkpoint revision. Exact raw replays, older versions and prevented
resurrection do not manufacture changes. Checkpoint-only writes can therefore produce an empty page.

Each read owns the account, published checkpoint, records and lookahead in one SQLite or IndexedDB
transaction. The composite index seeks by owner/partition/generation/change revision/ID and reads
at most200 records plus one lookahead. Initial reads include revision zero. After cursors capture
a new exclusive-lower/inclusive-upper interval; Within cursors retain that finite upper bound.
Newer overwrites move into the next interval, so this API is not a historical payload snapshot.
Generation replacement returns Restart; staging never hides the active generation. Exact raw
scope validation prevents silently carrying a cursor across a different login session.

The caller must confirm its derived publication or durable handoff before persisting completion
through the upper revision. Empty pages do not prove that a source has stopped changing. This raw
API does not own access policy, transport retries, chat delivery, message order or any observer.
Domain use cases retain those responsibilities and repositories authenticate/decode source facts.

SQLite13->14 adds the local position/default and compound index; IndexedDB12->13 initializes old
records, including asynchronous copies from the pre-account schema. Existing encrypted payloads,
caller versions, sort keys, tags, active/staged checkpoints and tombstones remain intact. Actual
SQLite13 and browser12/7 migration tests verify retained facts and the first subsequent delta.
An existing browser9 archive migration fixture now models the complete predecessor and verifies
retained replica content as well as commands. The browser JSON next value uses its concrete
Within schema; the incoming polymorphic cursor retains its discriminator. Initial full attempt1
failed4 cases; the artifacts and original source hashes are preserved. Browser171 rechecks all157
old cases plus14 new cases and all4 failure identities. Non-browser inputs remain byte-identical
to the accepted focused176 execution; its original source hash is retained explicitly.
The generated SQLite migration rebuilds its table; the actual old-database test also verifies
that the dependent tag lookup and staging records survive that migration.

Twelve shared storage methods cover finite pagination, equal source-version updates, no-op replay,
tombstones, writes across a captured boundary, published generations, account replacement,
validation/absence, maximum bounds, conditional rollback, competing writers, cancellation and
close/reopen. Focused176, browser171 and full16868 retain all16841 prior cases plus27 new desktop/browser
executions. Linux Apple source/test compilation is separate from actual Apple execution, which
is still outstanding. No chat owner, UI, phone build or live deployment was activated here.

The exact21 generic code/schema/test files are independently accepted in Compose Fluent Starter
with1049 cases and pushed at 092fbe6f70475efe950c697d3b7c6ec46b1fb267. HRIS readers still need authenticated
history-reference mapping, durable bounded progress and integration with existing incoming/repair
owners before product activation. See the incremental history plan for those remaining boundaries.

Evidence: `docs/measurements/replica-change-pages-2026-11-13.json`.
