# Targeted retained-history recovery

Active and requested account recovery passes retain independent physical-room predicates. A null
predicate selects all owned history; a canonical nonempty list selects up to 64 known physical rooms.
Later requested targets union without changing active progress. Count or encoding-budget overflow
becomes an account request; an existing account request is never narrowed. Each predicate is bounded
to 24,000 UTF-8/JSON-escaped bytes, so both fit the existing encrypted header. Schema 8 carries targets;
versions 1–7 keep their prior meaning. Nested lists are copied before suspension.

The existing worker loads authenticated metadata, then skips unrelated histories before starting a
reference pass or requesting content. OriginalOutsideTarget and PublishedOutsideTarget are distinct
from unavailable history. Global completion independently rechecks exact source, historical device,
metadata and predicate before advancing. A normal content attempt for an excluded room is rejected.
Changed active scope and narrowed pending scope cannot confirm a lost write. Primary demotion still
requires an account-wide recovery witness. Missing local metadata remains unresolved.

This extends the existing bounded worker; it does not register key listeners, retain callback receipts,
classify key hints, start a new scheduler, or activate source/restore producers. The next owner must
classify known rooms through existing local indexes and confirm atomic intent before raw ACK. No live
deployment, new phone build, or native Apple adapter is implied by these protocol/storage tests.

[Validation evidence](measurements/collaboration-targeted-history-recovery-2026-11-12.json).
