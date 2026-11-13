# Atomic terminal publication for Group inbox repair

Completing or cancelling a Group original now requires a prepared repair request. The encrypted
message repository archives the exact command and publishes that request in one database
transaction. A concurrent command or checkpoint change leaves both unchanged. The pure proposal
policy admits only a fresh Request and preserves a captured pass and its completed demand.

An exact archived replay confirms the retained result without reading or overwriting later repair
progress. This preserves later acknowledgements and ciphertext after a response is lost. Invalid
proposals still fail validation. Command content and history metadata use their separate existing
credential stores; a missing retained history key cannot be replaced during archival. No schema,
transport call, polling timer or automatic retry was added. Domain owners must decide admission,
retirement evidence and finite contention; repository code coordinates only the storage contracts.

Validation retains all 17 preceding message-storage methods and adds nine real-storage cases plus
six pure proposal cases. Both sent completion and local cancellation publish encrypted demand;
stale originals/checkpoints, raw failure, lost response, cancellation after commit, exact replay,
independent keys and reopening are covered. Focused 65 and full 15,969 pass locally, preserving
all 15,939 prior identities plus 30 JVM/browser executions. Android compilation/lint, browser
tests, Apple source/test compilation and proof assembly pass. The 15 new actual Apple executions
remain separate from control 43427620202, whose exact source predates this implementation.

This makes the storage contract mandatory; the Group domain retirement/cancellation callers and
the finite repair owner still require integration. The Company pipeline is unchanged. The generic
prerequisite is already validated and pushed in Starter 0d2b4ee0. A prepared local worker follows
only command changes and durable requests, never its own index writes; it is not yet accepted or
activated. Product Group navigation, incoming history, legacy references, pending edit references
and global backward-clock ordering remain. The phone remains release 92 and no live service changes.

Evidence: `docs/measurements/collaboration-space-inbox-terminal-publication-2026-11-13.json`.
