# One collected inbox repair lifetime

The local account/Group repair worker now shares command repair and finite history recovery under
one collected coroutine lifetime. Five child observations update distinct fields of one immutable
latest-input state. No initial field can be lost by conflating differently typed messages. One
repair observation carries latest and completed demand. Missing history metadata does not block
command initialization or service. New command input is serviced between bounded history steps,
and a completed command result is emitted before waiting for history work.

Domain policy selects continuation and consumes explicit owner recovery before suspension.
Advanced steps continue a retained finite pass; idle/failure stops continuation. Physical inbox
revisions and unrelated pending inventory never restart failed history. A failed metadata field
remains failed until its admitted observation restores it; other fields cannot clear denial.
Restoration permits one recovery. A five-second deadline cancels a stalled history step without
adding a timer retry. A late cancelled result cannot schedule new work. History success never
clears an outstanding command failure. Exact conditional-write contention is a distinct domain
outcome: it parks without reporting a command failure or inventing history completion. Other
conflicts, storage errors and access denial remain failures. The owning coroutine scope joins
all five observations.

Focused1 failed one existing SQLite cancellation assertion. Two isolated diagnostic runs retained
the same assertion and identified history Conflict/client.collaboration_inbox_busy in the shared
output while cancellation itself completed. The domain distinction above fixes that boundary;
three new shared regressions check exact classification, parked behavior and visible real errors.
Focused2 then exposed a separate ordering error: history could report success before command
inventory had initialized. Combined output now waits for a command outcome; a delayed-inventory
regression enforces this boundary. All failed source/result hashes and diagnostic evidence remain
in the measurement.

Twenty-three new shared cases cover scheduling, independent initial inputs, command fairness, physical
revision floods, real wakes, denial/restoration, command failure, timeout, cancellation and owner
restart. Existing command13 and six SQLite integration identities are retained. A seventh actual
SQLite case collects the real DI worker, completes both history lanes, cancels and reopens storage,
then verifies the same progress without native SDK activity. All17459 prior identities remain.

This factory still has no product account/Group collector. Product activation must cancel/join
before replacing ownership, then integrate incoming completion and the Group inbox/thread flows.
No phone installation, server deployment, Starter change or actual new Apple execution is claimed.

Evidence: `docs/measurements/collaboration-inbox-repair-lifetime-2026-11-14.json`.
