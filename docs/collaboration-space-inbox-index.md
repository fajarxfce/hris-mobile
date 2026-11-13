# Persistent organization inbox activity

The Group index reads encrypted rows in global recency order. A pending send or editable draft
can enter the first page even when its conversation was outside the earlier50-row page. Reads
use one bounded indexed query, without history scans, transport acquisition or a reader per row.

Each conversation has separate editor, activity/anchor and aggregate documents. Empty/frozen
editors remain available for reconciliation but do not become draft previews. A hidden activity
retains its placement, allowing the next candidate to become visible and later exact readback
without assigning a new position. Original Group, original Company and physical history sources
have distinct authenticated keys. Metadata creation, existing anchors and insertion ordinals are
validated when publishing. Acknowledgement changes actual display time/status without moving
an established placement; SENT is not a fabricated peer/read receipt.

One index CAS publishes the component changes and aggregate row. A durable insertion counter
orders equal timestamps. Caller collections are captured before suspension; stale competing
proposals cannot partially replace rows. Exact unchanged publications preserve the checkpoint
and duplicate database notifications do not regenerate equal page cursors. A cursor authenticates
owner, generation and captured revision; a changed index explicitly requests Restart. Source
use cases must retain displayed rows while reacquiring local pages and admit account/Group access.

This increment provides the index repository and encryption codec that source repositories can
use for a second ReplicaStore mutation. Draft/composer/history source producers, bounded repair,
admitted list observation and product graph activation are still required. Projection-only writes
are for admitted seeding/repair; they are not a substitute for a source-guarded atomic publication.
Account-wide pending/repair traversal still needs an indexed retained-reference contract,
including pending edits whose original message already has SENT delivery. The current repository
reads per-conversation candidates and exact anchors; it does not scan all activities for repair.
No transport, live server or phone application changes are activated by this commit.

Validation covers15 real-storage methods on JVM and browser, six policy methods on both, and
actual application DI. Cases include first-page promotion, out-of-order ACK, draft clearing,
retained hidden anchors, restart, conflicting writers, lost committed response, mutable input,
encrypted field damage, missing keys, bounded reads and account/Group isolation. Full validation
retains all15,603 prior identities plus42 new executions, totaling15,645. Android compilation/lint,
Web compilation, iOS source/test compilation and the proof app build pass. Actual21 new Apple
executions remain pending alongside14 earlier atomic-storage methods. All earlier failed/rejected test
candidates and their reviewed corrections are retained; no failing production expectation was
removed. The user's phone retains release92.

Evidence: `docs/measurements/collaboration-space-inbox-index-2026-11-13.json`.
