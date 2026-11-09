# Exact original reference on published rooms

Published organization room metadata now carries `original` for a LEGACY room: the
original company ID, original conversation ID, room ID and microsecond binding time.
The Mobile entity retains the two IDs together as `CollaborationOriginalConversationPosition`.
They are not inferred from the selected HR company or substituted with a Space ID.
SPACE rooms have no original reference.

The API datasource still performs only the existing bounded native HTTP read. The
repository maps the DTO and validates the complete view through the domain policy.
A LEGACY response without the complete reference, with invalid or zero IDs, a different
room ID, an invalid/over-precise timestamp, or a binding time later than publication
cannot become a successful view. SPACE cannot claim an original. Parsing a partial
wire object fails rather than treating it as null. No fallback lookup or extra request
is added to the transport layer.

This is metadata mapping, not local binding adoption. The owning follow-up must read
the exact original conversation and compare account ownership, canonical pair, server,
room and binding time before persisting the immutable encrypted relation. Current
content admission remains separate from retaining those local facts. No SDK send,
queue reset, key rotation, UI route or company selection changes in this increment.

[Validation](measurements/collaboration-space-published-room-reference-2026-11-09.json)
passes49 focused cases in225.769s. Full89590 is reaped PASS536.843s:4,162 fresh cases
(3,549 desktop,613 Android host), zero failures/errors/skips and lint issues. All4,535
application inputs and the inspector inputs stayed fixed;445 preexisting report suites
are not counted as fresh. Root check, Android release compilation/lint, Wasm/iOS source,
format/architecture/Detekt pass. Existing transport/preparation cases carry complete
LEGACY references; two transport regressions cover malformed, missing and contradictory
references, valid SPACE responses, recovery after invalid responses and exactly one
request for a partial wire object. Actual Apple execution remains separate.

The matching backend contract is on Platform's separate organization branch and has
not been deployed. Mobile binding persistence and product activation follow separately.
