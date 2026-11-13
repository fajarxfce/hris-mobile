# Exact command progress for Group inbox previews

A retained original reference is reconciled against its exact active or archived command. Only
matching Sent or Cancelled evidence changes the inbox. Missing, Queued, Staging and Staged records
remain pending without acquiring the index or inventing delivery evidence. Company, Group and
physical history namespaces remain separate even when their UUIDs match.

Sent updates delivery on the current anchor, preserving a newer edited body, actual timestamp and
stable position. Hidden anchors stay hidden. An earlier confirmation cannot replace a newer pending
preview or independent typing. Cancelling the newest original reveals the next indexed candidate;
contradictory cancellation cannot erase an already confirmed anchor. The ready preview and reference
completion marker publish in one transaction through the existing inbox repository.

The domain use case verifies current account/Group admission around suspension points. Ownership
is independent from credential age and HR company selection. Version/write contention retries at
most three times with fresh exact command/index facts. Storage failures return immediately without
a write loop; cancellation propagates. No transport, native runtime, discovery, scheduling or other
use case is called. The application graph can resolve this action, but no recovery owner invokes it
yet and the product Group graph remains disabled.

Validation adds eight pure-policy and nine use-case methods, executed on JVM/browser. They cover
newer edits, pending ordering, hidden/cancelled originals, namespace/intent mismatch, nonterminal
facts, bounded contention, cancellation, account replacement, denial and a lost committed reply.
Focused 85 and full 15,797 pass; all 15,763 preceding identities remain plus 34 new executions. Android
compilation/lint, Web, Apple source/test compilation and the proof APK pass. Actual execution of the
17 new methods on Apple is still a separate gate. No live service or phone UI changes in this slice.

Next: persist a finite local repair pass, integrate its admitted account/Group owner, seed legacy
exact-command references, publish incoming history and admit the product list/Group graph. Pending
edits require their own original reference and producer. Global backward-clock ordering remains an
explicit integration concern. Local prepared repair-owner drafts are untested; do not claim them
as part of this acceptance.

Evidence: `docs/measurements/collaboration-space-inbox-command-progress-2026-11-13.json`.

## Actual Apple and portable verification

Control 43427620202 at 42fd5d12c5353779d4a61cc50b8c53ae8c6c0bc6 passes 19,362 cases, including 5,692 actual
Apple executions and all 101 new repair/archival cases. The host and 16 push fields pass.
Earlier pending Apple status is superseded for this exact source. Later terminal publication,
worker and product integration remain separate. Evidence: `docs/measurements/collaboration-inbox-repair-hosted-2026-11-13.json`.
