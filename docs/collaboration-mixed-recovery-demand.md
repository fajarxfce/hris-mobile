# Local recovery demand for mixed and new Space rooms

ReadCollaborationSpaceRecoveryDemand now reads an optional Company ledger and the Space
ledger for the same admitted conversation. A newly published SPACE room needs no Company
command, receipt or completion access. LoadCollaborationSpaceRecoveryRoom also resolves its
valid retained claim without inventing legacy Company coordinates. The existing durable
binding cursor and namespace-generation checks remain unchanged.

The owning five-second read validates current local account/native-parent/directory access,
exact binding and complete original SDK provenance. A nonempty valid SDK queue requests
recovery immediately; it grants no sending permission. An empty SDK only permits idle after
both complete local ledgers have exact Sent acknowledgement or valid cancellation facts.
Missing records, acknowledgements or projections retain work. Neither absence nor the result
of this read changes a message state.

Each invocation owns separate Company and Space ledger captures. Mutable staging baselines
and receipt/archive aliases are detached before subsequent suspension. Exact original
acknowledgement policies preserve legacy Company missing-origin behavior and require Space
origin proof. Completed archive aliases remain part of cross-ledger ownership checks even
when a later journal contains another alias. Logical IDs, physical device/room/transactions
(including bootstrap aliases), and event aliases cannot belong to both namespaces. Combined
facts remain bounded to128 message identities and512 aliases.

Both live command lists and journals are rechecked after both initial ledger reads. An
observed change requests another pass. Idle additionally requires a final unchanged empty
SDK capture, exact retained binding and still-open current runtime. This is a bounded read,
not an atomic snapshot across repositories; existing atomic producer wakes and exact wake
completion protect work committed after the final read. Storage failure, empty completed
flows, malformed terminal facts, denied/replaced ownership and timeout cannot become idle.
Cancellation closes the owning read. No transport, acquisition, enqueue, resume, settlement,
projection, archive, schedule or usecase-to-usecase call occurs here.

Thirty new shared cases cover mixed/new-room idle and pending states, original ACK/archive
proof, cross-namespace identity collisions, complete malformed inventory, mutable capture,
concurrent append, exact binding replacement, expiry-independent local facts, scope/denial,
failure and cancellation. Two additional room-selection cases preserve namespace checks for
new rooms. All16 previous demand identities and assertions remain. Together with18 pending
inventory cases and2 application DI checks, focused Desktop/actual-Chromium validation is162.

This increment does not activate new Space command staging in the worker. Durable fair
pending claims, old progress migration, binding preparation and staging are next. The
application worker owns that orchestration while usecases own their individual admission
and repositories own I/O. Incoming history/edits, product routing, media, backups, calls,
meetings and the actual Apple Matrix runtime remain separate; all54 PRD groups remain open.
No phone or live-server change is claimed by these source checks.

[Local measurement](measurements/collaboration-mixed-recovery-demand-2026-11-10.json).
