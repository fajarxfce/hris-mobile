Status: implemented and accepted; see [contract and evidence](collaboration-space-thread-history.md).
The following records the implementation boundary and the corrected admission decision.

# Canonical Space thread history — local routing before product UI

Base Mobile312f294d / Groupfdbb8efd; native10, proof21, full14,626 accepted. Domain implementation accepted; product UI remains separate.
The current presentation has no CollaborationSpace references; ObserveCollaborationThread reads Company
history, while ObserveCollaborationSpaceHistory deliberately reads only the Space namespace. Real native
incoming workers preserve LEGACY content in Company history. Product routing must not fabricate companyId.

Implement one explicit domain local-history location with Space and Original variants, derived only from
validated retained CollaborationSpaceRoomBinding for the requested canonical conversation. Validate exact
account/space/canonical pair, room origin and immutable original Company/conversation/room facts through
existing validCollaborationSpaceRoomBinding. No date, SDK, native credential or selected-workspace gate.
A missing binding stays unavailable; it never means an empty history or permission to fall back by UUID.

The owning observation use case should coordinate SessionRepository, Space directory admission, immutable
binding repository, and the selected history repository. Group directory admission owns both routes;
the original Company is a physical content coordinate, not current HR authority. Existing Original
acquisition and native adoption contracts explicitly preserve this separation. The earlier proposal to
add Company list admission was rejected during contract review: HR selection/denial must not replace
organization ownership. Keep confirmed Group denial sticky. Cancel and join old reads
on account/admission replacement; confirm current scope after suspending binding reads and before publication.
Never call another use case/repository implementation, mutate stores, fetch API/SDK, invent IDs or copy history.
Preserve typed logical identity and original physical coordinates alongside bounded history pages. Reuse
existing window validation and encrypted history mechanisms, with exact duplicate suppression.

Test both namespaces, canonical/original ID difference, malformed/missing/foreign bindings, no fallback,
expired credentials with readable owned history, unchanged local snapshots, confirmed denial through failed
cleanup/read/retry, late binding/page results, replacement session, cancellation, and bounded window forwarding.
Then actual repository/indexed storage binding and common DI tests; keep UI route/compose work a separate
review after this admission boundary is accepted. No live Group rollout. Keep original ObserveSpaceHistory
contract intact for raw Space-only consumers. Do not claim user-visible routing before wiring the screen.

Apple CI inventory43273697021 portable accepted12,604 with Chrome156→157 environment suffix recorded;
Apple job still running. Store43279731370 remains pending; NEVER replace it with another same-branch run.
Native SDK8 Intel43267867675 still running. Phone91 accepted/uninstalled; latest18:27 ADB timeout,
18:40 WireGuard last handshake8hours. Focused3 and full1 accepted after the slow-consumer boundary correction. Push every accepted commit.
