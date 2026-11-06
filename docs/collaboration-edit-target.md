# Owned edit preparation

Opening an editor reads the original and its current accepted text from encrypted
local history. The target binds the room, original event/logical identity, sender,
content hash, original reply/forward context and earliest server acceptance time.
Replacement events cannot become original edit targets. Missing, hidden or
redacted messages remain unavailable; preparation creates no keys or drafts.

A coherent checkpoint read authenticates every retained original alias (at most128).
Legacy histories without an earliest timestamp compute it without rewriting
history or extending the edit window. Account/checkpoint changes, substituted
records and cancellation prevent publication. The owning use case reads retained
provider identity, checks the owned conversation and current local denial after
each suspension, and rechecks time after storage. Expired credentials alone do
not block owned recent local messages. Outgoing target/reply IDs obey the gateway
opaque ASCII bounds. Device time offers authoring eligibility only; received
server event time remains authoritative for actual acceptance.

[Validation](measurements/collaboration-edit-target-2026-11-06.json):185 focused
cases passed, then5,015 full cases passed on final source, no failures/errors/skips
and Android lint0. Five shared storage cases run on actual SQLite and IndexedDB;
five domain cases cover ownership, expired credentials, late denial/replacement,
cancellation and edit-window boundaries. Full root/browser/Android host/proof
compile/debug build/static checks passed.

This increment provides preparation, not the edit composer or sender. Durable
queue/staging, receipt recovery, retirement and failed-edit acknowledgement,
optimistic projection, native exchange and complete raw cold-revision acquisition
remain required before an authoring release. Phone40 remains installed.
