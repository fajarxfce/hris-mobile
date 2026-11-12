# Local thread before room publication

`ObserveCollaborationSpaceThread` now exposes locally known canonical conversations
and queued commands before a room binding is available. Its `Unresolved` snapshot
contains exact owned conversation metadata and the actual canonical command ledger.
An absent binding never creates an empty history page or proves that remote history
has ended. Missing or invalid canonical metadata cannot expose another conversation.

When storage publishes a valid binding, the same collector switches to the `Bound`
snapshot: typed Space or Original history and both applicable command ledgers. It
keeps the original Company coordinates as provenance and does not copy or relabel
content. Removing a binding returns to known unresolved metadata and retains the
canonical commands. Duplicate bindings do not restart history observation.

Binding selection sits in the existing session/admission pipeline before the only
content-producing child. Replacement cancels and joins old metadata, history and
command reads. A slow consumer cannot receive a queued snapshot from a previous
binding or admitted account. Confirmed Group denial stays closed through failed
recovery. Upstream validation failures reach the caller without entering data reads.

Unresolved metadata and commands read concurrently. A known failure does not wait
for another source's first value; successful publication requires both actual
results. Mutable command lists and staging baselines are captured before suspended
publication. Bound rooms use metadata from their authenticated binding and skip the
separate metadata lookup. The observer creates no room, key, request or retry.

Focused validation passes100 cases. Full validation accepts14,764: 12,726
freshly executed and2,038 exact unchanged cases. All14,738 prior test
identities remain, with26 new JVM/browser cases. Actual encrypted storage tests
cover binding arrival/removal, unchanged canonical commands and renewed-login
reopen. Source checks, Android release compile/lint, shared Wasm/iOS ARM64
compilation and proof assembly pass. [Evidence](measurements/collaboration-unresolved-space-thread-2026-11-12.json)
retains source/report/proof hashes and the first failing invalid-access candidate.
No assertion was weakened for that correction.

This is the local domain/storage boundary for the upcoming organization thread UI.
No new phone behavior, Apple native execution or live Group rollout is claimed.
Participant labels, sender context and composer integration remain next.
