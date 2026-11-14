# Retained inbox repair bootstrap

Opening the same account/Group repair owner now ensures that durable progress exists instead of
creating a new request on every initial observation. Pending demand, an active captured cursor,
and completed demand remain unchanged. Initialization writes only when progress is absent; a
concurrent genuine request wins on reread. An uncertain committed initialization is inspected on
reopen instead of being replaced with another request.

The inventory policy selects bounded actual terminal/retired targets. Live overflow still creates
one genuine finite request. Atomic command seeding and completion/cancellation archival retain
their existing durable request transaction. Existing recovery continues saved cursors and honors
cancellation, deadlines, bounded contention, failure parking and later real demand.

Nine new shared cases cover missing/pending/active/completed progress, contention, response loss,
unchanged repeated reopen and live overflow. All37 previous methods remain. The existing app worker
owns observation; no timer, network request, new observer, schema or dependency is introduced.
The history owner and its retained global trigger remain separate pending work. Apple test
compilation is recorded separately from actual Apple execution; no phone or live deployment.

Evidence: `docs/measurements/collaboration-inbox-repair-bootstrap-demand-2026-11-14.json`.
