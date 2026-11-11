# Atomic historical recovery page completion

A cancelled or lost completion response must not leave resolved-reference flags and the recovery
cursor in separate commits. `CollaborationHistoryRecoveryRepository.complete` now accepts an
attempted finite page and an explicit resolved subset for either Company or Space ownership.
The raw traversal store validates and commits both changes in one existing checkpoint CAS.

The operation verifies the captured active pass, immutable event identities, unique subset and
at most16 attempted references. A17-row pending-index lookahead proves that moving the cursor or
claiming EOF does not omit pending rows. Independent primary appends, primary cursor movement,
matching references already resolved by another owner and a later requested recovery pass remain
valid. Only the selected unresolved rows change; provider identity, source sequence/count and
primary state are preserved. Existing strict primary `resolve` semantics are unchanged.

The source and subset are copied before storage suspension. Cancellation is checked after all
checkpoint/reference encryption and immediately before the single commit. If the response is
lost after commit, both changes remain durable. Replaying an already advanced page returns false;
a changed or promoted generation is not fabricated proof of the previous operation.

This storage operation does not infer that content was retained. An admitted content owner must
provide outcomes, including a retry after keys arrive for still-unknown references. Local-fact
inspection alone cannot advance past those references. Content persistence and whole-account
recovery completion have separate ownership and confirmation rules.

Validation adds21 actual encrypted-database cases per target, each checking both Company and
Space. They cover16-row bounds, partial/zero/all resolution, replay/reopen, primary/request races,
forged or omitted source coverage, owner isolation, mutable inputs and cancellation/failure before
and after commit, including failed/cancelled encryption. The35 earlier storage cases stay intact.
Local acceptance requires 13,300 cases plus unchanged-control dependency checks. Native SDK,
whole-account consumer, key listeners, phone delivery and full blocked-queue liveness are outside
this change. All54 PRD groups remain open; no Starter or live-service change.

[Exact validation evidence](measurements/collaboration-history-recovery-completion-2026-11-11.json).
