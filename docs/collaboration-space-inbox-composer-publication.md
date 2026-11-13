# Atomic organization send and pending inbox preview

A Group send now retains its immutable original, empty editor successor, pending inbox preview
and recovery wake in one conditional transaction. Its preview does not wait for server delivery.
Two competing source/index versions cannot partially clear the editor or publish a preview.
Each new original receives a durable placement; equal times preserve insertion order, and a
backwards local clock does not move a new send behind its earlier conversation activity. The
displayed timestamp remains the real captured send time. Global cross-conversation clock rollback
policy remains separate before product activation.

Replay reads the exact retained original or command. An existing anchor confirms without
canonical metadata and preserves newer typing, confirmed delivery and hidden content. A legacy
source with no anchor can repair its index under the source checkpoint, keeping original
ciphertext and recovery wake. Missing source/queue rows never establish delivery. An uncertain
response requires exact retained readback; no failed transaction becomes a sent message.

The use case owns admission, metadata, immutable input and at most three contention attempts.
Pure domain policy derives the proposed editor/aggregate/activity. The repository reads raw
source/index snapshots, enforces capacity and commits once. CollaborationSpaceComposerHandoffCodec
maps authenticated documents and creates the two ciphertext mutations; it performs no I/O or
retry/admission policy. A failed raw commit receives one checkpoint readback. Observable contention
returns CHANGED; unchanged or unowned empty snapshots retain the existing unproven Conflict
failure, including recovery-wake rejection. Account admission is rechecked by the use case.

Validation adds seven policy, eight use-case and nine real-storage methods, executed on JVM and
browser. Existing composer, Company outbox and screen/editor assertions remain. Cases cover
atomic original/editor/index/wake, competing conversations, lost committed response and restart,
newer typing, independent source/index conflicts, cancellation, account replacement, retained
SENT/hidden anchors and missing legacy index repair. Full validation retains all 15,689 prior
case identities plus 48 new executions, totaling 15,737. Android compilation/lint, Web, Apple
source/test compilation and proof-app build pass. Actual execution of these 24 shared methods
on Apple remains pending alongside 57 preceding raw-transaction/index/draft methods.

The rejected static-analysis/test-warning candidates, mistakenly started then cancelled full
run, and explicit raw-failure classification review remain recorded. Limits and previous accepted
test requirements were preserved. Exact command and incoming-history progress, retained repair references (including pending edits), admitted
list observation and product Group activation remain. No live service or phone UI is changed;
the installed phone build remains 92.

Evidence: `docs/measurements/collaboration-space-inbox-composer-publication-2026-11-13.json`.
