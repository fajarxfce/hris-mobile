# Native organization setup owning flow

Explicit [bootstrap replacement and durable handoff](collaboration-space-bootstrap-handoff.md)
now extends this flow for uncertain original attempts and actual-build changes.
Ordinary preparation still never replaces existing work implicitly.

The feature owns explicit provisioning retry and group gateway bootstrap through
`PrepareCollaborationSpaceSetup`, `ReplayCollaborationSpaceSetup`,
`ReconcileCollaborationSpaceRetry` and `ObserveCollaborationSpaceSetup`. Constructors
are bound in the actual collaboration data module; replay has one application-owned
mutex. No product route, automatic retry, SDK acquisition or migration is activated.

## Preparation and original identity

Preparation requires the current native parent, a valid original Registered anchor,
the exact selected provider user and current native device metadata. The original
registration operation, parent, platform and public SDK provenance remain immutable.
The selected HR company is irrelevant. Only an existing LEGACY registration is
eligible here; a missing SDK or native device never authorizes new SPACE enrollment.
Setup does not reopen the SDK: a previously frozen store must remain frozen, and
private-store possession, freeze/drain and adoption belong to their owning flow.

Retry requires a provisioning device, supported observed device/provisioning revisions
and elapsed retryNotBefore (including expiry of queued/running work). Bootstrap
requires the active original device and supported, unrevoked gateway metadata. Its
new intent binds the actual current build/platform and original provider USER/DEVICE.
A retained slot returns its existing command unchanged, including after an insert
commits but its caller is cancelled. Preparation never replaces uncertain work.

## Finite replay and durable facts

An explicit attempt owns one 15 s budget, including local reads and mutex wait.
Current native ownership, the original captured hard expiry, wall-clock rollback
and monotonic elapsed time are checked after suspension; a same-family renewal
cannot extend that invocation's original deadline. Confirmed access/MFA denials are
remembered before a bounded non-cancellable restriction write. Failure, cancellation
or a later unavailable/malformed response cannot downgrade the known denial.
Concurrent new directory denial before HTTP or final publication closes the attempt;
these operations never reopen directory admission.

Pending commands verify the original registration, selected identity and exact
observed request versions before durable Unconfirmed is written with compare-and-set.
No native HTTP occurs if that write fails or loses the comparison. Unconfirmed
replays the original operation/body without re-preparing metadata or reopening a
possibly frozen SDK. Retry can use a newer actual build in the same native family;
bootstrap must retain its original actual build. A fresh native parent cannot edit
or replay another parent's operation. No forged old build headers are sent.

A received retry must match the original device and provisioning revision + 1.
A gateway must match original account/space/device/native parent, provider USER and
DEVICE, expected gateway revision, group-only tokens and supported lifetimes. Its
hard expiry cannot exceed the captured native hard expiry. The encrypted outcome is
saved before publication; access expiry is checked again after persistence. If access
expires during that write, the stored gateway remains an owned fact but the attempt
returns revalidation required. Lost receipt/credential writes retain uncertainty and
permit exact operation recovery within the server protocol's replay constraints.

Completed results, including archived retries, remain account-owned facts after
ordinary age, local login replacement, a fresh native parent or build changes. This
local recovery grants no live directory, SDK or provider authority. The observer
projects only command/device IDs, kind, phase, creation time, attempts and classified
failure, never gateway credentials. It bounds rows to 128, rejects duplicate/foreign
records and cancels queued old-account emissions on replacement or disposal.

## Retry reconciliation and remaining bootstrap recovery

Only Retried can be retired here. Reconciliation acquires current native device
metadata and checks the exact original device/provenance and provisioning revision
at least the recorded receipt revision before archiveRetried. The immutable archive
preserves the exact original completion while releasing its active slot. Lost archive
responses recover by original operation ID; Pending, Unconfirmed and Bootstrapped
cannot be archived by this flow. No terminal receipt is silently changed to Pending.

The server bootstrap replay window remains 30 s and binds actual build/platform and
current gateway head. An uncertain old-build/old-head operation needs explicit server
operation recovery or supersession. Null gateway metadata cannot exclude a late
original request. Do not erase uncertainty, issue an untracked replacement operation
or claim update/offline recovery from metadata alone. The original and any replacement
intent must remain durable through crashes. Hard-parent recovery is separately
required because original device ownership and physical SDK namespace are immutable.

Twenty-eight new common domain cases cover preparation (8), replay (13), retry
reconciliation (4) and local observation (3), including cancellation/late results,
original command recovery, concurrent denial, unchanged native/SDK provenance and
old-account emission disposal. Existing registration flows and setup transport/storage
are included as regressions. Actual AppContainer resolves the owning use cases and
single replay instance. Focused: 76 cases / 131.109 s.
Root :check, Android-host and dev compilation: 2,726 fresh cases /
340.396 s. Browser data/domain: 1,634 fresh cases /
141.395 s. Final gates preserve identical 4,018 build/application
inputs; format, architecture and detekt pass. All gate sessions closed.
[Evidence](measurements/collaboration-space-native-setup-flow-2026-11-05.json).


All 54 communications PRD groups remain open. Next: explicit bootstrap operation
recovery, retained private-store inventory, hard-parent recovery, old-runtime
freeze/drain/handover and adoption of the original store, group runtime/UI and typed
push/background replies. No new APK/phone, live deployment, provider/FCM, JNI/private
key proof, Apple runtime or performance acceptance follows from these owning use
cases. Live backend 8b40/schema 116 and phone release 29/source 084662c5 are unchanged.
