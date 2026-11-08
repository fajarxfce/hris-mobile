# Native timelines only when writing

Release 69 recorded three queue timeline builds of 267, 115 and 136 ms during
settlement, staging and delivery. The settlement lease only reads native queue
inventory and exact completion evidence. Its full timeline was unused.

The Android raw queue source now initializes the paused native queue through the
SDK's asynchronous subscription API, then cancels and joins that temporary
subscription. Synchronous queue controls run only after this initialization.
Initial and late callback payloads are disposed; failed disposal fails initialization
without throwing through the native callback. No send window is enabled.

A timeline is acquired on the first enqueue or nonempty retry inspection and retained
only for that exclusive writer lease. A late handle is stored before cancellation
checks so normal owned cleanup can close it. Stop during acquisition cannot enqueue
or enable delivery. Inventory and completed-original settlement retain their existing
snapshots, current admission and native queue fences. No SDK binary, transaction
identity, retry classification, local authorization or UI change is made.

The focused source gate passed 92 fresh cases without failures, errors or skips in
134.888 seconds. It includes actual Android fake-FFI lifecycle/delivery/retry/original
queue tests, shared child/writer ownership and domain settlement/prepared delivery.
All 4,488 app inputs and 82 library files stayed unchanged. The full root, Android,
browser, web/iOS source and quality gate passed 5,763 fresh cases with no failures,
errors or skips in 1,230.997 seconds. Source inputs remained unchanged; source is accepted.
[Exact attempt](measurements/collaboration-lazy-queue-timeline-2026-11-08.json).

Release 70 preparation requires an accepted and pushed source gate, verified APK/SDK,
and the original emulator updated from 69. A single new original must produce two
successful QueueBuild and three QueueInitialize events, with exact old message/draft
retention and cold offline evidence. Do not replay any previous test original or send
the retained unsent draft. No latency improvement or new phone installation is claimed.

Release70 is held for the independently verified recipient-readiness retry loop.
This timeline change does not fix that loop and is not yet device-accepted.
