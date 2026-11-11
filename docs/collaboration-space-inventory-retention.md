# Admitted inventory page retention

One usecase reads a finite 16-room inventory page through the already installed original Matrix
runtime. It validates the complete original registration, local account/native parent, current
source and known denials around suspending operations. The invocation has a five-second deadline;
the existing runtime reader keeps native child ownership until cancellation or late I/O drains.
No new client, sync loop, independent collector or transport retry is created.

The domain policy consumes only an ordered prefix whose work can be retained. Joined rooms create
fresh physical requirements without inventing a known head; the existing resolver later starts
their logical work from Latest. An existing physical turn stops the prefix before that room and
keeps its head, generation, FIFO position and retry. Existing logical work remains independent.
Capacity is 64 logical and physical tasks combined. A partial page stops before the first room
that cannot be retained, including when the provider page itself says EOF.

Invited, Knocked, Left and Banned entries create no new incoming-content work. Inventory neither
joins rooms nor deletes local facts or existing work. It grants no content access; authoritative
membership/revocation and historical access remain separate. Finishing an inventory page does not
mean its queued room work or pending keys have completed.

The usecase reloads demand after enumeration, then performs one exact replacement containing the
consumed cursor, physical work and independent wake. One readback confirms that progress. Continuous
sync may advance its real position; a later loss generation cannot be overwritten or used to confirm
an old page. A known CAS miss uses the existing short contention result without an inline mutation
loop. A Storage failure permits readback; confirmed denials retain their classification after runtime
loss. Retry eligibility allows a backward clock adjustment instead of hiding retained work.

The data module owns the factory; the application graph resolves it without starting I/O. Eighteen
new policy cases and 27 owner cases cover partial capacity, existing work, independent sources,
continuous updates, lost commit replies, source/registration/account changes, denied and cancelled
reads, mutable native metadata, deadline and clock recovery. The full local gate accepts 12,530
cases. Hosted platform acceptance is recorded separately; fixtures are not native SDK execution.

Worker dispatch and durable inventory failure/backpressure deferral remain required. Key-blocked
rooms also need durable discovery and a finite recovery pass before the primary queue can release
their capacity. Raw observation and Original producer activation remain off. Client.rooms still
allocates the complete native handle list: finite output does not prove a native allocation bound
or the cost of repeated enumeration at scale.

[Exact validation evidence](measurements/collaboration-space-inventory-retention-2026-11-11.json).
