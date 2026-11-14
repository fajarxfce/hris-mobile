# Durable history inbox source progress

Each canonical conversation retains its actual Company/Group source, finite history cursor,
captured pending interval, and unresolved references in an encrypted progress partition. One raw
transaction checks the authenticated current inbox checkpoint while committing source state and
reference outcomes. A same-generation index may advance; replacement or regression rejects the
handoff. Lost responses require inspecting retained state. No transport or scheduling is added.

The pure handoff policy partitions every captured reference exactly once, checks source owner and
window coverage against the publication receipt, and advances only through the original window.
An empty auxiliary page can continue; confirmed source absence creates no revision. A replacement
destination requires explicit reconstruction. The proposal is not itself a durable receipt.

Pending references keep their original insertion revision and keyed identity until removal.
Reinsertion gets a new revision; exact progress CAS prevents stale completion from removing it.
New additions cannot extend a captured pending window. Reads and mutations are bounded to16
references (plus one read lookahead); source metadata enumeration uses the existing published
partition index. Internal progress partitions do not become incoming history sources.

Thirteen domain cases and16 shared actual-storage cases cover captured windows, complete outcomes,
restart, stable pending positions, stale completion, index races/replacement, response loss,
concurrent writes, cancellation, account renewal/isolation and malformed storage/key retention.
The app graph resolves one repository instance. Global pass state, admitted source lifecycle and
handoff use cases, and worker/consumer activation remain separate work. No phone or live change.

Evidence: `docs/measurements/collaboration-history-inbox-progress-2026-11-14.json`.
