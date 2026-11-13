# Atomic organization draft and inbox publication

Saving a Group draft now commits its encrypted editor and conversation-list preview in one
conditional transaction. Clearing text retains the empty editor revision and reveals the
unchanged newest message. A competing source or index write changes neither partition; an
exact replay confirms both records without rewriting their ciphertext. The Company draft
protocol and original namespaces stay intact.

The edit use case owns account/Group admission, canonical metadata, immutable input and at
most three attempts after contention. A pure domain policy captures and validates the current
index components and derives the draft proposal. The source repository checks the exact source
and index versions, encrypts each with its original codec and calls ReplicaStore.mutate once.
There is no repository-to-repository call, screen policy or second best-effort index write.

Both source and indexed editor must confirm the saved revision. A lost committed response can
be recovered without rewriting; a legacy source-only draft is repaired under the source CAS.
Newer source content is never overwritten by an older gesture. Exact coherent replay remains
available locally after account renewal even if canonical metadata is absent. Cancellation and
late account/Group denial cannot publish a stale success.

Validation adds five pure-policy, eight use-case and nine real-storage methods, executed on
JVM and browser. Cases cover saving/clearing, independent source and index conflicts, bounded
retries, two concurrent writers, cancellation, account replacement, lost response and coherent
restart. Existing draft and Company assertions remain. Full validation retains all 15,645 prior
case identities plus 44 new executions, totaling 15,689. Android compilation/lint, Web, Apple
source/test compilation and proof-app build pass. Actual execution of these 22 methods on
Apple remains pending, along with 35 preceding raw-transaction/index methods.

The next producer is send handoff: original outgoing message, cleared editor, pending preview
and recovery wake must commit together. Command/history progress, retained repair references,
admitted list observation and product Group activation still follow. This increment changes
no live service or phone UI; the installed phone build remains 92.

Evidence: `docs/measurements/collaboration-space-inbox-draft-publication-2026-11-13.json`.

## Actual Apple and portable verification

Control 43403331941 at 8a48212f21c0e1c18802997fe46aabd4c567fba7 passes 19,059 cases: 13,468 portable and
5,591 actual Apple cases. All 81 new transaction/index/draft/send methods ran on Apple;
the iOS host and 16 push-envelope fields pass. Earlier pending statuses above are superseded
for this source. Later reference storage, command progress and product activation are separate.
Evidence: `docs/measurements/collaboration-space-inbox-hosted-2026-11-13.json`.
