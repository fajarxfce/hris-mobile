# Complete local Space pending inventory

ReadCollaborationSpacePendingMessages reads the complete local Space command partition
through the existing repository observe(access,null). It does not depend on a room binding,
selected HR company, current token expiry, or remote metadata. Native parent, local session
and directory admission are checked before and after the bounded read. These checks only
permit local discovery; returned commands do not grant SDK or transport authority.

The pure inventory policy validates the entire list against its account/space and the
existing128-command account bound, including unique original IDs and valid terminal rows.
It detaches mutable Staging.before sets before subsequent ownership reads can suspend and
orders by original creation time, then canonical ID. Only after complete validation does
the usecase exclude Sent and Cancelled. Queued, uncertain Staging and mapped Staged remain
visible to later recovery, including conversations whose room has not been acquired.

Empty completed storage flows fail with a controlled Conflict. Unavailable observations
are bounded to five seconds; cancellation closes the observation and propagates. Storage
failure, malformed records, changed parent or confirmed denial cannot become an empty
successful result. The owner performs no writes, schedules, acknowledgements, metadata
lookups, HTTP or SDK acquisition and never calls another usecase. Repository/datasource
responsibilities and existing atomic command+wake storage remain unchanged.

Eighteen new shared cases cover full capacity, unbound/multiple conversations, offline and
expired-token local discovery, pending states, invalid terminal rows, immutable capture,
ordering, ownership replacement/denial, storage failure, empty flow, cancellation and
deadline. Existing observing, queueing and recovery-demand cases plus DI give116 focused
Desktop/actual-Chromium checks. The full measurement records all required platform gates.

This increment exposes discovery; it does not yet route the worker to new Space commands.
Both-ledger/new-room idle demand, fair durable command claims, binding preparation and
staging must be integrated into the application worker while preserving authenticated old
progress and exact wake generations. Incoming history/edits, media, backups, calls, meetings
and the actual Apple Matrix runtime remain separate. All54 PRD acceptance groups remain open.

[Local measurement](measurements/collaboration-space-pending-inventory-2026-11-10.json).
