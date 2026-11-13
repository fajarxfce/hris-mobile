# Pending edit provenance in the inbox

An optional pending-edit pointer distinguishes optimistic text from retained history. It records
the actual Group/Company replacement command, the exact original target, local operation time and
first index publication revision. The preview already holds the visible text; the pointer carries
no second body. New operations use the conditional index revision without allocating a placement
ordinal or moving the original message, conversation or independent draft.

The encrypted codec preserves the same operation's target, time, revision and optimistic text.
Receipt updates retain the pointer. A hidden or deleted preview cannot revive while retaining the
same pointer. Captured activity/publication and stored-document checks reject future provenance.
Ordinary payloads omit the optional field and old payloads still decode with existing keys/AAD.
Forward reading by older strict decoders is not claimed; never reset stores to handle an upgrade.

12 pure and eight actual-storage shared methods pass focused37/full16587, preserving all16547
prior identities plus40 JVM/browser executions. Coverage includes physical source/target shape,
revision bounds, maximal text with an independent draft, exact replay, receipt updates, immutable
operations, consecutive edits with a backward clock, stale/uncertain write replies, hiding,
deletion and reopening. The existing15 inbox storage methods and app bindings remain covered.
The first focused attempt is retained but rejected: a fixture read the wrong partition and a
redundant assertion warned. Corrected focused-2 reads actual inbox bytes. Apple compilation passes; actual execution of these new methods requires later hosted evidence.

This is storage/provenance support. Raw removal of a pointer is NOT proof of edit success. Exact
command authoring and provider application/rejection/supersession remain domain responsibilities;
an ACK alone cannot release optimistic content. No command is queued and no product graph changes.

Next implement atomic actual edit-command/index authoring and source-bound outcome release, then
bounded history preview publication under existing incoming ownership. Reuse source/index checks,
preserve17 fallback candidates for a16-original hide batch, and keep unresolved identity in a
durable local handoff instead of ordinary incoming network retries. Finite canonical catalog and
history backfill is still needed when content predates its Group binding. HRIS policy stays here;
this schema is not a generic Starter port.

Evidence: `docs/measurements/collaboration-space-inbox-edit-provenance-2026-11-13.json`.
