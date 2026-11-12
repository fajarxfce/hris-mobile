# Incoming discovery without duplicate scheduling

The incoming worker could finish Company content and still retain a Published alias and physical inventory requirement for the same room. The alias then waited for the Original task and received operational backoff. A fresh native run demonstrated the collision through bounded diagnostic categories; read-only inspection independently confirmed completed content and remaining queue work.

Local binding discovery now uses one pure domain policy for both planning and generation allocation. It recognizes existing Published IDs, exact Company/conversation targets and joined physical work under the captured current registration. The use case checks its owning native parent, reads registration locally, and fences it before and after the queue write. Other registered device sources cannot suppress discovery. It advances its scan cursor without replacing those tasks. The physical source still must pass the existing registration and content admission checks.

Inventory can add its independent catch-up requirement to an unresolved Joined physical task in the same source and room. A fresh generation invalidates any earlier captured resolution, the head becomes unknown, and FIFO/backoff remain unchanged. Inventory progress commits atomically with that strengthened task. Logical work and Left membership are never overwritten. A later known-head sync cannot weaken the unresolved catch-up, while fresh generation and membership changes remain retained.

No persisted schema, retry duration, worker turn limit or native SDK is changed. Full native completion and covered-hint no-op still require independent validation; fixture and diagnostic progress is not a product acceptance claim.

Local ownership is checked before native-family comparison, preserving session-change and Unauthorized outcomes. Registration reads, queue replacement and readback are fenced against the same parent. These checks do not depend on credential age or acquire network authority.

Validation: 14,538 local cases accepted, comprising 12,500 fresh passing cases and 2,038 unchanged dependency-verified cases. Full check, Android host tests, release compilation/lint, Wasm tests, iOS source-set metadata, proof APK/unit tests, format, architecture and detekt pass. Two existing inventory method names changed with their preserved contract; all other predecessor test identities remain. The 185 selected policy/worker cases are included in this full run.

An earlier source-exact native run drained all logical/physical/inventory work and showed Company content before EOF. It still failed the four-turn completion assertion because global history discovery remained ready. This is not complete native worker acceptance; the final admission correction has only the full local validation above. The next investigation is bounded account metadata discovery. No phone build or live service change is included.
