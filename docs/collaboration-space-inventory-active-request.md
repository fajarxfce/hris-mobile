# Active inventory and later requests

New sync loss must not repeatedly restart the first inventory page. Each source now keeps
an active generation with its cursor and retry, plus one distinct coalesced generation
for a later pass. Discontinuity, explicit loss and omitted work update the real sync
position and request that later pass without rewinding active progress or its backoff.

Active EOF promotes the latest requested generation, clears its cursor/retry, and reports
Advanced. Complete means that no later pass remains. Partial pages preserve the request;
capacity and existing room work still stop before an unconsumed room. Other sources,
already queued work and the independent wake remain intact. Requested generation IDs
cannot alias retained tasks, scans or another source's active/requested pass.

The existing retention and deferral usecases already compare active generation, cursor
and retry around one CAS/readback. New requests and continuous source-position updates
therefore survive suspended reads and late replies. Changed active generations still
fence obsolete work. No extra worker, collector, usecase or native operation is added.

Encrypted storage uses explicit schema 6 and marker `incoming-demand/v6` when a requested
generation is present. Existing v1/v3/v4/v5 records remain readable. An authenticated older
header cannot conceal the advanced field. Existing limits remain 129 parts, 131,072 encoded characters per part and 4,194,304
encoded characters in total. Promotion can
return to an older encoding when no advanced fields are needed.

Local acceptance covers 12,740 cases: twelve new policy and eight owner cases plus
six actual encrypted-storage cases on both desktop and browser. Existing storage case
identities remain (47 to 53 per host). New coverage includes four finite pages with loss
between every page, cursor/backoff retention, capacity, CAS contention, requests during
readback, cancellation after commit, interrupted replies, encrypted reopen and bounds.
The finite-page test proves progress while capacity exists; it does not prove drainage
of a queue whose rooms are all waiting for keys.

The first focused run found a fourth old progress test still expecting an immediate
cursor reset. Its assertions now cover the full active-completion/promotion transition
and preserve the other source exactly before and after. Failed source/results are retained;
no production behavior, case identity or expected count was changed to resolve that failure.

Fair application scheduling, key-only recovery/demotion and native allocation/performance
remain separate. Raw observation and Original producer stay disabled. No phone, Starter
or live deployment changed; all 54 PRD groups remain open.

[Exact validation evidence](measurements/collaboration-space-inventory-active-request-2026-11-11.json).
