# Owned conversation opening preparation

`PrepareCollaborationSpaceConversation` first recovers an active same-peer Open
command or an already retained canonical conversation ID. It performs finite local
orchestration through repositories. It makes no HTTP request, starts no worker or SDK,
and never archives a command or replaces an uncertain original.

The two result types distinguish a canonical ID from an original command record.
Neither result grants current room or content access. The original record retains
Pending, Unconfirmed or Recorded state, its first operation ID and native provenance.
An existing original takes precedence over canonical metadata so that unfinished
reconciliation remains visible to its owning workflow.

## Ownership and new intent

Exact account, local login, API and organization ownership bracket every suspended
repository read and write. The bounded active list is copied before the next suspension
and checked for valid records, duplicate IDs and duplicate resources. Another account's
late result cannot be returned after ownership changes.

Token age, changed native parent and selected HR company do not hide an existing local
command or canonical ID. These paths need no fresh discovery and do not reopen denied
directory or message projections. A new login for the same account can recover the
original through its new local storage owner.

For a genuinely new intent, the use case checks the retained directory restriction and
requires matching, currently admitted native discovery. It allocates one operation ID
and persists the immutable peer intent. The five-second operation bound also covers
pending local reads; caller cancellation propagates.

After insertion, exact-ID reconciliation can recover a committed write whose response
was lost. A competing occupied resource recovers the winner's original or canonical
ID; it never silently substitutes a new native binding or retries insertion. Capacity,
scope and missing-result failures stay explicit. Once committed, returning the owned
command does not depend on the discovery still being fresh. Actual HTTP dispatch will
perform its own current admission.

The public preparation policy validates the complete active inventory within the shared
128-command account capacity. The use case coordinates repositories and policy; the
data module binds its constructor. No presentation or datasource performs this workflow.

## Validation and continuation

Thirteen domain cases cover canonical/original recovery after token expiry and parent
rotation, confirmed restrictions, new discovery, exact-ID lost responses, concurrent
occupation, capacity, corrupt inventory, mutation during suspension, account changes,
committed cancellation and finite timeout/disposal. The assembled application test
resolves the use case through the actual repository graph.

Focused validation passed 15 cases in 204.552 seconds on 4,385 unchanged inputs, with
zero failures, errors or skips and formatting, architecture and Detekt passed. Full
validation passed 3,626 fresh cases in 449 suites in 491.548 seconds on the same
unchanged inputs, with zero failures, errors, skips or Android release lint issues.
Root check, Android host tests, quality checks, all 13 actual Browser cases and shared
Wasm compilation passed. Apple CI for this source remains pending.
[Source hashes and results](measurements/collaboration-space-conversation-preparation-2026-11-07.json).

Product routing and a new APK are not added here. Opening a completely new chat while
offline still needs an explicit provisional mapping to its eventual canonical ID,
preserving original message transaction IDs. [Finite original replay and canonical publication before archival](collaboration-space-conversation-open-replay.md)
are now locally validated. Room request fencing, original SDK delivery/history and
product integration remain separate work. Physical-phone delivery latency remains open.
