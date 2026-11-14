# Finite history inbox turns

The application can now execute one explicit local history step through admitted use cases.
Pure domain policies choose observation, queue capture, source visit, final completion or idle.
A retained head is visited before fetching another page; an unchanged completed or parked pass
starts no work. Current destination generation and explicit expected checkpoints remain required.

Each source visit attempts preparation, raw history and pending references at most once. Older
captured intervals finish before a later pass is prepared. A raw restart permits one fresh read;
it cannot renew the visit budget indefinitely. Empty pages require metadata confirmation, and
nonempty pages require publication before atomic retention. A failed operation consumes its
budget and inspects actual retained source state once; it never blindly replays an uncertain
write. This preserves useful progress in the other lane. Denial/cancellation stops the turn.

The finite worker maps domain-selected actions to existing use cases; those use cases own
repository coordination and admission. The worker starts no collector, timer, provider session or
transport. A conditional global acknowledgement follows each queued outcome. The existing
command worker and its subscription behavior remain unchanged.

Focused1 stopped at Detekt before tests because the test fixture factory exceeded the method
length limit. Five named fake operations preserve its assertions and behavior; production code
was unchanged. Failed source/result evidence and the correction remain in the measurement.

Local evidence includes42 shared regressions and3 actual application SQLite integration cases:
completed reopen, failed preparation followed by binding publication, and interruption between
source preparation and global acknowledgement. All17372 prior test identities remain. Apple test
compilation is included; actual new Apple execution remains open. Parent lifetime/mailbox
activation, command fairness under backlog and product consumers are separate pending work.
No phone installation, live deployment or reusable Starter foundation changed.

Evidence: `docs/measurements/collaboration-history-owner-turn-2026-11-14.json`.
