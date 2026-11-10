# Retained organization message edits

The existing encrypted Space history repository now implements a typed Space replacement
contract. It stores eligible revisions and exposes their exact event facts without treating
an edit as an original send acknowledgement. Local history observation reads the retained
latest eligible edit. This increment does not add incoming acquisition or edit UI.

## Behavior and ownership

`CollaborationSpaceMessageEditRepository` takes account/Space access and exact conversation/
room/event coordinates. It shares one repository instance, codec, content key and atomic batch
writer with Space originals. Company repository/source and its authenticated namespace stay
unchanged. Identical Company/Space UUIDs and event IDs do not share replacement content.

Each1..16 replacement batch requires retained originals. Conflicting event identity rejects
the whole batch; missing targets/foreign rooms cannot create records or new keys. Eligibility
and ordered fallback reuse the original edit policy. Redacted edits fall back through retained
revisions, and redacted originals cannot be resurrected. New original aliases can narrow the
eligibility interval without changing the first-observed message order or original receipt IDs.

Exact replay leaves the checkpoint and ciphertext unchanged. Only an uncommitted read plan
may retry a checkpoint race. Lost successful commit responses and cancellation remain explicit
outcomes; a later read/replay retains one original revision instead of inventing a new edit.
Caller-owned lists are captured before storage suspension. Replacement lookups check coherent
checkpoints and reject late logout/cancellation rather than publish mixed-owner facts.

## Evidence and remaining work

Focused real SQLite/Chromium cases cover17Space replacement scenarios,14unchanged Company edit
scenarios and16existing Space-history scenarios, plus application DI identity. Cases exercise
scope isolation, malformed/absent targets, replay/redaction/restart,65tied revisions, concurrent
checkpoint writers, uncertain successful responses, cancellation and caller mutation. The full
auditor retains predecessor test identities; exact totals/hashes are in the measurement.

The owning incoming usecase must acquire, validate and retain originals AND replacements before
advancing synchronization progress. Native update lifetime, traversal cursors, notifications,
quote/hidden-message APIs and product routing remain separate. No native runtime, phone latency,
Apple Matrix implementation or completed PRD-group claim follows from these storage checks.
