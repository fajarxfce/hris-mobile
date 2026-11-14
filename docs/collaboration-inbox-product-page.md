# Admitted local inbox pages

ObserveCollaborationSpaceInboxPage reads one bounded page from the owned encrypted recency index.
It coordinates the inbox, directory and session repositories directly. It never opens a native
client, renews credentials, acquires API data, writes source state or calls another use case.
One collected observation owns its admission latch and its sole page subscription. Token rotation
and ordinary credential age do not replace that local ownership.

A pure policy validates requests and page facts: at most50 entries, bounded opaque cursors, exact
valid generation/revision, unique conversations, descending retained recency, ordinal coverage,
valid draft/activity previews and a real continuation. A bounded list copy precedes suspended
admission checks. Null next is terminal; Restart is meaningful only for a previous cursor. Neither
outcome automatically starts another page read. The product consumer separately owns pagination.

Confirmed denial survives failed or malformed recovery. Reopening requires observed and currently
admitted facts. Account replacement and disposal cancel and join the old page subscription;
uncooperative late results cannot publish. Failures never become an empty successful page. Pending
messages and independent drafts preserve their retained positions and actual message timestamps.

Seventeen shared policy/observation cases and one actual SQLite application case were added. The
actual case uses real DI composer submission and draft editing, reads pending plus independent
draft through the admitted observer, closes/reopens storage, and compares exact retained facts
without native child acquisition or delivery. All17543 prior executed identities remain. The first focused attempt failed during test compilation at three collection assertions; explicit generic types fixed only those tests. No assertions were weakened and no production behavior changed. Failed source/result hashes remain in the measurement.

This is the admitted page boundary, not an activated Group UI or a measured rendering improvement.
Visible label/contact enrichment, retained pagination, Group hub/thread activation and end-to-end
rollout remain next. No raw storage schema, Starter port, phone action or new Apple execution is claimed.

Evidence: `docs/measurements/collaboration-inbox-product-page-2026-11-14.json`.
