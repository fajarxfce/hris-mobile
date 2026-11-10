# Owned encrypted organization chat history

A typed organization history repository retains original messages, aliases and redactions under
account/Space/conversation ownership. The local observation usecase admits these owned facts
without transport acquisition. This increment does not activate an incoming stream or product route.

## Ownership and storage

`CollaborationSpaceHistoryRepository` takes `CollaborationSpaceLocalAccess`; no Company-typed
API receives a Space ID. The partition is `collaboration-history/space/v1/<space>/<conversation>`.
The owning account and API audience remain in the protected replica scope. Native-session age
is not the identity of durable content. Company history stays in its existing partition.

The shared history codec now accepts explicit typed Company or Space coordinates. Existing
Company HMAC and authenticated encryption byte frames remain unchanged. Space uses separate
`hris-space-history-index-v1` and `hris-space-history-content-v1` frames. Ciphertext copied between
a Company and Space with identical UUIDs, or between two Spaces, cannot be published as history.
Both repositories use existing content/index keys; a missing key cannot be regenerated over
retained active or inactive Company/Space records.

Raw repository operations reuse the existing codec, key acquisition and atomic history batch.
Original aliases retain sender/event/logical identity, immutable content hashes and timestamps.
Redactions survive replay, restart and arrival before the original. Exact replay leaves stored
revision and ciphertext unchanged. Conflicting facts reject a whole batch. Checkpoint conflicts
can retry only an uncommitted read plan; uncertain commits remain with the caller.

Indexed windows authenticate visible rows and lookahead, retain stable overlap and bound reads
at150 messages. Writes accept1..16 events. Pagination and observed revisions describe retained
local history; they do not claim provider exhaustion or advance its synchronization cursor.

## Local observation

`ObserveCollaborationSpaceHistory` owns session/account/audience and directory admission through
one collected flow. Failed or malformed retries cannot reopen a confirmed denial. Ownership
replacement cancels the old source and prevents buffered old-account content from escaping.
Expired live credentials, offline access and identical local admission do not restart or hide
owned history. The repository performs raw storage I/O and does not acquire permissions,
start an SDK, schedule recovery or retain UI state. Application DI resolves the new contracts.

## Validation and remaining work

See the accompanying measurement for exact source hashes, counts and elapsed times. Focused
SQLite and actual Chromium tests cover current local-admission behavior, existing Space message
observation, both Company/Space storage and DI. Tests traverse more than500 messages across
restarts, exercise competing writers, lost commit responses/cancellation, key loss and tampering.
Independent fixed HMAC/AAD vectors verify the Company byte protocol and distinct Space namespace.
Existing case identities are retained by the full acceptance auditor.

Receiving original SDK windows, persisting replacement edits, native update ownership, incoming
and older-history cursor progress, notifications/replies and product navigation remain separate
increments. This storage validation does not measure phone rendering or network latency, supply
the missing Apple Matrix runtime, or close any of the54 PRD acceptance groups.
