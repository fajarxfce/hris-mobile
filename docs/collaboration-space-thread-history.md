# Canonical organization thread history

`ObserveCollaborationSpaceThreadHistory` resolves a retained canonical room binding and
observes its existing local history. `Space` selects the organization partition;
`Original` selects the exact original Company/conversation/room coordinates. The output
retains canonical identity separately from physical location. Equal UUID values do not
make the two encrypted namespaces interchangeable. A missing or malformed binding
returns a failure and never authorizes a guessed location or an empty conversation.

Group directory admission owns this read, including Original content. The Company
coordinate describes storage provenance, not current HR workspace authority. This follows
existing Original acquisition and native adoption contracts; changing HR selection or
losing one Company's access cannot replace organization ownership. A confirmed Group
restriction stays closed through failed reads, malformed admission and session recovery.
Only observed valid admission reopens it. This replaces the initial proposal to add a
Company list gate, which would have confused physical storage with Group authority.

One collector owns its denial state and child subscriptions. Account, audience and local
session replacement cancel old reads. Current ownership and admission are checked after
binding acquisition and before each publication, including results returned late by a
suspending repository. A rendezvous boundary prevents queued old content escaping after
revocation. Messages and event aliases are captured before the suspending final check.

The slow-consumer regression initially failed: an outer `flow.buffer(0)` introduced a
second coroutine handoff. It had already received an admitted page and could hold it
outside the cancelled selected-reader child. Removing that extra handoff keeps final
emission inside the selected reader's cancellation boundary. The exact failing candidate
and unchanged regression are retained; the current 27 domain cases pass.

The selected repository receives the exact room, limit, overlap and forward/reverse
anchor. Existing indexed history bounds remain 150 messages and 50 overlap. Null room
is accepted only for an empty, uncheckpointed history; a known binding is still required.
Duplicate results and ordinary token rotation do not restart observation. A completed
failed source has no automatic retry; its lifecycle owner may reopen after acquisition.
This use case performs no API/SDK operation, room creation, storage migration or write.
The existing raw Space-only observer keeps its separate contract.

Domain tests cover both locations, malformed and absent bindings, offline ownership,
bounds, sticky denial, failed cleanup, replacement accounts, late results, cancellation
and snapshot isolation. The shared storage contract uses actual encrypted indexes for
Original reopen, equal Company/Space UUIDs and persisted Group denial. Application DI
resolves both history repositories through their existing registrations. Focused validation passes36 cases. Full validation accepts14,686 cases with all14,626
prior identities preserved:12,648 executed now and2,038 unchanged reports reused. Android release
compile/lint, shared Wasm/iOS ARM64 compilation, source quality checks and proof assembly pass.
[Exact evidence](measurements/collaboration-space-thread-history-2026-11-12.json) records the
failed test-compilation attempt, demonstrated stale-page failure and historical-report audit
correction. Linux compilation does not execute the new Apple test cases.

This increment does not wire organization chat presentation, pending-message projection,
room setup, notifications or calls. It makes no new phone latency or Apple SDK runtime
claim. Product routing follows this boundary; Group live rollout remains disabled.
