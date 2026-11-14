# Retained history owner inputs

One admitted repair-progress subscription now exposes latest and completed demand identities
separately. Intermediate cursor/physical generation changes stay quiet. Finishing an older
captured pass cannot claim that a later request finished; clearing storage removes both signals.
The renamed observer retains current ownership checks, denial latching and cancellation.

The existing command repair worker uses latestDemand for request selection. A completion-only
update does not restart an unchanged failed command pass; actual new demand still retries it.
DI and actual repository integration use the typed stream. Three shared regression cases and
all prior observer/worker/integration identities remain. This prepares one shared input for the
future history owner; it does not activate that owner, add polling, or add a subscription.

Admitted source enumeration reads at most16 exact retained checkpoints without opening another
subscription, binding or history source. It captures lists before post-read admission, validates
owner/version/order/continuation, and discards late results after denial or cancellation. Eight
shared enumeration cases join the three repair-signal cases. The separate draft gates were never
run; there was no intermediate accepted17242 checkpoint.

Focused1 failed during compilation of three new test collection assertions, before any test ran.
The assertions received explicit collection types; no production behavior changed. Failed source
and result hashes plus the correction are retained in the evidence. Focused2 and full acceptance
refer to the corrected exact source.

All17236 prior identities remain with22 new desktop/browser executions. Actual new Apple execution,
global history persistence/scheduling and incoming/thread/list activation remain open. No phone,
live deployment, schema or reusable Starter foundation changed.

Evidence: `docs/measurements/collaboration-history-owner-inputs-2026-11-14.json`.
