# Durable source inventory progress

Incoming source progress now retains an optional exclusive room-ID cursor and bounded retry
alongside its existing inventory generation. A cursor or retry without that generation is invalid.
Continuous SDK responses preserve both fields while advancing the actual opaque response position;
a new gap, explicit loss or capacity loss replaces the inventory generation and clears its old
cursor and retry. Replaying a retained response neither rewinds progress nor adds another wake.

The encrypted demand codec writes `incoming-demand/v5` with schema 5 when these advanced fields
are present. Its typed room parts preserve both Published and Original targets. Existing v1
(schema 1/2), partitioned v3 and typed v4 remain readable. Older markers reject advanced fields,
including when their authenticated header has the matching older schema. Clearing the advanced
fields can use the existing encoding again without losing original targets or source position.
The 129-part, per-document and total snapshot limits remain unchanged. Source DTO mapping retains
the exact opaque UTF-8 position through Base64; diagnostics remain redacted.

Twelve new domain cases exercise continuous updates, response replay, new loss, capacity and
cross-source isolation. Seven new actual database cases run on desktop and browser, including
encrypted reopen, interrupted commit reply, mixed marker rejection, maximum-size source records
and atomic retention of cursor, physical work and independent wake. Existing 40 demand storage
cases remain. Full local validation accepts 12,440 cases; platform CI acceptance is recorded
separately in the measurement file.

This change provides durable representation. It does not acquire inventory pages, advance a cursor
on its own, discharge source loss, schedule a new worker or activate raw observation. Page admission,
atomic work retention, authoritative absence handling and key-recovery backpressure remain with
their owning flows. No native SDK or phone acceptance is inferred from these storage tests.

[Exact validation evidence](measurements/collaboration-space-inventory-progress-2026-11-11.json).
