# Serialized inventory scheduling

Inventory recovery runs inside the existing incoming worker, mutex and parent lifetime.
The four-task logical/physical FIFO runs first; at most one eligible inventory page
follows. Full combined64-room capacity and future retry suppress inventory enumeration.
Other original sources remain pending when the installed source cannot process them.

The domain owns source eligibility and durable deferral. The usecase coordinates
demand, registration, directory, session and installed-source repositories within5s.
It makes one CAS and one readback, retaining the independent wake and newest continuous
sync position. Replaying a recorded deferral does not extend its deadline. A newer
inventory loss or cursor is never overwritten by the previous turn. Only uncertain
Storage replies may be confirmed from exact retained evidence; known CAS contention
keeps a1-second retry without persistent backoff. Ordinary failures use the shared
30/60/120/240/300-second schedule, including recovery after backward-clock adjustments.

Waiting/Capacity reloads the current cursor for the same inventory generation before
deferral. Advanced/Complete/Obsolete/Deferred do not add failure backoff. Known admission
failures block. The worker bounds its inventory callback to10s, and cancellation joins
the pending callback before the parent lifetime ends. No new client, store, sync loop,
collector or worker is created by these changes.

Local acceptance covers 12,622 cases, including38 new common cases on desktop/browser
and16 new application-worker cases. Focused checks include the existing actual encrypted
demand contract and prior worker cases. The hosted workflow explicitly selects the new
worker class. This does not establish native SDK, Apple native inventory, phone, or
whole-inventory performance acceptance.

Raw observation and Original producer remain disabled. Key-blocked rooms still need
durable keys-only discovery/demotion before a full queue can make progress. Pending
references must remain stored. Authoritative absence, native allocation bounds and
combined Company-storage/Group-native acceptance remain separate. All54 PRD groups stay open.

[Exact validation evidence](measurements/collaboration-space-inventory-scheduling-2026-11-11.json).
