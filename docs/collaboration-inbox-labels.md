# Bounded local inbox labels

ObserveCollaborationSpaceInboxLabels resolves only the requested visible conversation IDs through
canonical metadata and published contact indexes. It never scans the directory, opens a native
client, queries an API, modifies a raw-query bound or reads message history. Requests contain at
most50 distinct canonical IDs and are copied before collection. Every collector owns an independent
bounded cache and at most50 contact subscriptions; cancellation joins all children.

Successful canonical pairs are immutable within their published generation. Unrelated catalog
insertions do not reread those pairs or restart unchanged contact sets. Missing/failed pair reads
wait for a real catalog publication; there is no timer retry. Replacing/removing the generation
clears the cache. Contact updates, errors and explicit removal are independent of message previews.
Partial snapshots omit unresolved labels; omission never means a contact was removed. An emitted
label with no name is an explicit completed absence. Per-row failures stay separate from Group
admission, and the consumer must preserve this distinction while retaining existing visible names.

Current session and Group admission guard every output. Confirmed denial remains latched through
failed or malformed recovery; reopening needs observed and current admitted facts. Late cancelled
metadata cannot subscribe a contact or publish old labels. Public name validation requires the
exact checked peer and bounds the contact fields; output captures immutable names, not mutable
company-label lists. These public labels grant no HR resource permission.

Eighteen shared cases and one actual SQLite application case were added. The application case
publishes50 canonical pairs/contacts, resolves their labels through real DI, reopens SQLite and
compares the exact result with no native child or delivery. Initial/reopen durations are recorded
as local diagnostic samples only, including collection disposal; they are not UI frame-time, device, percentile or network claims.
The first focused attempt failed before label observation because the test fixture supplied unsorted directory contacts. Only fixture publication order was corrected; assertions and production behavior were unchanged. The second attempt reached initial resolution but timed out after reopen because the shared fixture omitted persistent directory credentials. Its independent in-memory store is now retained across test container opens, matching existing platform entry points. No timeout was increased or assertion weakened. Both failed source/result records are retained. All17578 prior executed identities remain. The existing raw query bound remains1..4.

Group inbox presentation, retained pagination and end-to-end UI measurements remain next. No phone
installation, live rollout, Starter port or actual new Apple execution is claimed.

Evidence: `docs/measurements/collaboration-inbox-labels-2026-11-14.json`.
