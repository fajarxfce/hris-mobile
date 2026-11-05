# Durable organization identity selection

Before registering a native organization device, the application explicitly
selects the account's provider-user intent through the native group API. Selection
has its own durable command. It creates no local SDK store, provider device, room,
message or live access grant. Product chat routes still use the existing company
runtime until the remaining group integration is complete.

The immutable command records operation UUID, audience/account/space, original
native session family, platform/build and creation time. Its encryption namespace
belongs to the account and space, without an HR company or local/native session
in AAD. The replaceable local session fences raw database operations. The sealed
request retains the original native family; a new login can read that command
without rewriting its provenance. Identity selection is idempotent per actor account,
space and provider realm; its server operation fingerprint contains neither the
first native family nor client build. An explicit retry may therefore use a fresh
native login and current running client headers for the same audience/account/space.
Each attempt captures and rechecks that supplied parent. A real new login has a new
local session ID; an obsolete supplied owner or mid-attempt change still fails.
This rule is specific to identity selection. Device registration/bootstrap and SDK
adoption retain their own distinct provenance, version and continuity requirements.

One command occupies the identity resource in `hris.collaboration.space-identity/v1/{spaceId}`.
The repository uses the existing protected command content key, AES-GCM purpose,
account/space/operation/revision binding, bounded 4 KiB plaintext and 8 KiB encoded
ciphertext, and raw CommandStore insertion/revision CAS. A retained receipt cannot
be reopened or removed through this contract. Exact command replay preserves its
original ciphertext. Shared key replacement is forbidden over any retained command,
including inactive accounts. Empty reads allocate no key. Raw acquisition and
mapping stay inside safe storage boundaries; cancellation is propagated.

`ObserveCollaborationSpaceIdentityCommand` checks local account ownership without
credential age, HR company selection or network work. Same-owner metadata changes
keep one subscription; account replacement cancels pending results at the final
flow boundary. A saved Selected receipt is an owned fact after reauthentication,
not authority to resume a device or SDK. Recovery after a terminated storage source
requires explicit reobservation.

`PrepareCollaborationSpaceIdentity` accepts an already acquired discovery and
retains one immutable command within a two-second budget. Existing work is returned
for review, never overwritten. Both root publication and identity preparation use
the same original discovery/native/wall/monotonic lifetime validation. No HTTP is
sent during preparation.

`ReplayCollaborationSpaceIdentity` performs one explicit serialized attempt, with
mutex wait and cooperative I/O inside a 15-second budget. It captures the earliest
local/native hard deadline and rechecks owner and original wall/monotonic lifetime
after pending boundaries. It persists Unconfirmed before HTTP, preserves the same
operation and request binding after missing responses, and permits at most 1,000
explicit attempts. Cancellation, scope replacement, rejected storage and obsolete
late results cannot become successful publication. A non-cooperative dependency
can delay cancellation completion, but its late success is discarded.

The bodyless native POST uses `collaboration/spaces/{spaceId}/identity`, the exact
local-session transport fence and retained Idempotency-Key. Application-owned
platform/build headers reflect the current running client; the original command's
platform/build metadata remains immutable. A stored old build never overrides
actual HTTP defaults. Fresh native/client admission belongs to the server and the
current attempt; a selected receipt does not replace it. Response size is bounded to 1 KiB and only
an exact account ID/version 0 receipt may confirm selection. Authentication replay
is limited to the transport's single same-session replay.

A received group access/build denial reaches the existing directory restriction
owner even at cancellation, through a separately bounded two-second retention.
Failure to persist that restriction or a later failed retry cannot reopen reads.
Selection success finishes only its command; it never clears directory admission.
The server's receipt does not reveal which Matrix user was selected: original user,
device and key continuity still require verification before SDK adoption.

Nine shared SQLite/IndexedDB cases, six native HTTP cases and fourteen domain
lifecycle cases pass. The nine original root lifetime regressions also remain
green after sharing discovery validation. Real AppContainer resolves both
repositories and all three owning use cases, with one replay instance. Final root
:check, Android-host and Android dev compilation pass 124.829 s /
1,156 fresh cases; browser data/domain pass 150.292 s /
1,503 cases. Both preserve identical 3,950 source inputs.
[Evidence](measurements/collaboration-space-identity-command-2026-11-05.json)
records earlier test compilation fixes and the per-operation replay review.
No group product/provider/SDK, Apple runtime or performance acceptance is implied.
