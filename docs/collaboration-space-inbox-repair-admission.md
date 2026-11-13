# Admitted local inbox repair actions

Finite reference pages, exact original lookup, checkpoint reads and checkpoint transitions now
run under current local account/Group admission. Readers validate returned ownership, original
identity and progress before exposing immutable facts. They recheck account and Group access after
suspending storage calls. Credential age and selected HR company do not govern owned local reads;
none of these operations acquires transport or schedules message delivery.

A reference page contains at most32 originals (default16), a valid index version and an advancing
bounded cursor. Duplicates, foreign intent, future/out-of-order insertion revisions and malformed
continuation are rejected. An empty initial store is distinct from an explicit generation restart.
Exact lookup checks conversation and Group original together; an absent or completed reference
returns null without inferring delivery. Reading a checkpoint never requests another pass.

The checkpoint action applies the existing pure transition policy with fresh CAS generations.
Only a competing null write retries, at most three attempts. Operational failure and cancellation
stop immediately. A later request cannot reset the active pass, a stale page cannot complete its
replacement, and an uncertain committed Request can be confirmed by exact replay without writing
again. Finishing pending-only work reaches idle; native key availability does not create local
repair demand.

The existing ObserveCollaborationSpaceMessages use case is strengthened in place, avoiding a
second equivalent observer. It supports one account/Group inventory or an explicitly requested
conversation. The existing bounded inventory policy validates and detaches at most128 records,
including mutable Staging predecessor sets. Each result gets current admission before publication;
a conversation query rejects unrelated returned records. Ordinary token rotation preserves the
subscription. Revocation/replacement cancels old observation, and confirmed denial survives failed
admission until an admitted gate reopens. Completed failures never create a retry loop. This
observer reads command storage, never index revisions; the future owner can compare changed and
retired originals without feeding its own index writes back into demand.

Validation retains all eight preceding message-observer methods and adds35 shared methods:
reference pages9, exact lookup6, checkpoint reads5, transitions10 and observer validation5.
Focused63 and full15,909 pass; all15,839 preceding identities remain plus70 JVM/browser executions.
Android compilation/lint, Web, Apple source/test compilation and the proof APK pass. Actual Apple
execution of these35 methods remains pending alongside51 earlier reference/progress/checkpoint
methods; accepted control43403331941 at8a48212f excludes them.

Next: an account/Group-owned finite repair worker, changed/retired original selection and bounded
command-only legacy seeding; then incoming history producers and admitted product list/Group
navigation. The worker must cancel/join obsolete jobs, keep one captured pass plus one coalesced
later request, become idle after a pending-only pass, and retain unchanged visible list rows through
invisible index revisions. Pending outgoing edits and global backward-clock ordering remain
explicit integration work. No product graph, live service, native runtime or phone update is
activated by these admitted actions.

Evidence: `docs/measurements/collaboration-space-inbox-repair-admission-2026-11-13.json`.

## Actual Apple and portable verification

Control 43427620202 at 42fd5d12c5353779d4a61cc50b8c53ae8c6c0bc6 passes 19,362 cases, including 5,692 actual
Apple executions and all 101 new repair/archival cases. The host and 16 push fields pass.
Earlier pending Apple status is superseded for this exact source. Later terminal publication,
worker and product integration remain separate. Evidence: `docs/measurements/collaboration-inbox-repair-hosted-2026-11-13.json`.
