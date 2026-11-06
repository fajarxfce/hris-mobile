# Durable organization bootstrap replacement

`PrepareCollaborationSpaceBootstrapReplacement` prepares an explicit replacement of
an uncertain bootstrap. `ReplayCollaborationSpaceSetup` sends the durable request
through the bounded native replacement endpoint. Ordinary preparation and retry do
not silently replace work. This is recovery of gateway setup metadata; it does not
acquire an SDK, prove possession of a private store, migrate a native parent or
activate organization chat.

## Local transaction and retained facts

The raw `CommandStore.archiveAndInsert` transaction compares the full original row,
its active account/session and the empty successor identity. It retains the original
row plus opaque evidence, removes that active row and inserts one pending successor
in the same account, partition and resource. SQLite and IndexedDB publish changes
only after the transaction commits. The active queue never contains a half handoff
and an already full queue can replace its occupied slot without extra capacity.

The raw store does not interpret evidence or retry the operation. A repeated call
returns false, including after a successor has completed or disappeared. It never
recreates missing work. The encrypted HRIS repository maps the exact old record,
an authenticated successor link and a new encrypted pending command into this one
transaction. The link uses the original account, space, parent, operation and revision
as authenticated data with a separate `successor` purpose. Swapping request,
outcome, revision or another operation's evidence fails authentication/validation.

An archived original keeps its exact original Pending/Unconfirmed outcome and adds
`successorId`. This link says that a replacement was prepared locally; it says nothing
about whether the original applied remotely. Existing schema-1 requests, outcomes and
exact Retried archives remain readable. Replacement requests and received replacement
proofs use explicit schema-2 kinds; the old wire/storage encoding remains unchanged.
All payloads remain bounded and encrypted with the existing account-owned command key.

## Recovery owner and authorization

Preparation captures one 15-second attempt, including local reads, with the original
native hard expiry, wall-clock rollback and monotonic elapsed checks. It acquires the
original Registered LEGACY anchor, exact provider identity and current native device
metadata. Both effects must share account, space, native parent, platform, device and
provider identity. Current versions cannot move behind the original expectation.
Current actual build metadata is used for the new operation; historical build metadata
remains part of the old effect descriptor. No historical HTTP headers are forged.

The owning use case writes the local handoff before any later HTTP request. A lost
local write response or a competing preparation is reconciled by the authenticated
original link and retained successor. The same invocation does not generate another
operation ID. Missing retained successor data remains a recoverable failure; it is
never silently recreated. Local link reads survive ordinary age and a same-account
login replacement, with ownership rechecked after suspension. New remote work still
requires current native admission and the original parent.

A bounded typed restriction carries known access/MFA denial into a pending successor,
including when a prior directory restriction write failed. Another build update before
HTTP retains that denial without inventing an attempted send. Repeated replacements
carry only the immediately superseded flat effect, not recursively nested payloads.
Unavailable or malformed responses cannot turn that denial into reopened access.
These operations never admit directory content or confer live SDK authority.

Replay persists Unconfirmed before transport and requires the full typed replacement
proof and current gateway coordinates before persisting BootstrapReplaced. A late
original response loses its local comparison once the original has been archived; it
cannot overwrite or publish the successor's credentials. Replaying the archived
original only returns the retained local fact. Completed outcomes remain local facts
across ordinary age/build changes, without becoming live credentials automatically.

## Validation and remaining work

Raw shared SQLite/browser handoff contracts passed 95 focused cases. The first
consumer run passed 38 storage/transport cases but failed domain test compilation;
that run remains failed. Corrected consumer validation passed 72 cases, including
10 new domain recovery scenarios and five new shared encrypted-store scenarios.

Complete Mobile validation passed in 723.803 seconds on 4,228 unchanged source/build
inputs: 5,941 fresh cases, zero failures/errors/skips. Root `:check`, database and
collaboration browser tests, Android-host tests, actual application DI, proof-app
compilation, Android dev assembly/lint, formatting and architecture pass. Android
lint reports zero issues. The first-source Apple workflow now includes raw database
simulator cases and the real encrypted repository's Apple host; its execution and
iOS application build still require CI acceptance after this commit is pushed.
[Evidence](measurements/collaboration-space-bootstrap-handoff-2026-11-06.json).

Private-store inventory/possession, hard-parent recovery, freeze/drain, original SDK
adoption and organization runtime/UI/background routing remain separate work. Generic
raw storage changes return to Starter after the real HRIS consumer is validated. HRIS
replacement policy, protocol adapters, encrypted formats and use cases stay in Mobile.
