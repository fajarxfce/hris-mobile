# Company workspace

The selected company is an encrypted, session-owned record. An owned selection
reopens offline without waiting for the server or expiring after one hour.
Features check account, API audience, company, permissions and known company
restrictions before exposing local data. The backend authorizes every request.
See [local-first storage and synchronization](local-first.md) for coverage and limits.

## Local ownership and live authorization

`SelectWorkspace` reads the account, company permissions and client policy. It
creates a workspace ID bound to the local session, account, server family and API
audience. Its admission contains a recent authorization deadline: at most one
hour, shortened by family/MFA expiry. This bounds operation preparation, not the
lifetime of the selection or saved read models.

`ObserveWorkspace` validates local ownership. `ObserveWorkspaceContext` supplies
identity, company and [known availability](company-policy.md) together. Neither
removes an owned selection because authorization is old. Cold startup restores
Home from local storage while foreground revalidation runs independently. A
missing selection still opens company selection; an account/family/audience
mismatch cannot restore another selection.

`RefreshWorkspace` rechecks the account, permissions and client policy. Foreground
entry and the worker can request it. A mutex serializes runs and a thirty-second
budget bounds each run. Transport failure retains the selection without updating
its checked timestamp. Expired local credentials pause synchronization with
`client.authentication_required`; they do not erase locally owned data.

Confirmed session, company or MFA denial conditionally removes the selection. If
that invalidation cannot be stored, the foreground failure gate hides protected
routes through subsequent failed retries. A successful admitted renewal clears
that gate. Logout removes the local identity and derived read replicas.

A renewal with unchanged company and permissions preserves the workspace ID and
route owners. Permission/company changes create a new ID. Application MVI checks
on foreground entry, scope changes and a transition to stale policy, not every
metadata update. Background entry cancels its request. Late results cannot
replace another account or a newer selection; failed renewal does not poll.

## Persistence

`EncryptedWorkspaceRepository` coordinates raw storage, HTTP, encryption and key
contracts. Mapping and technical failure handling belong in data; selection,
authorization and lifecycle policy remain in use cases and application MVI.

AES-GCM protects the complete company/permission/admission/policy record.
Associated data includes API audience, local session and storage generation.
Android Keystore, Apple Keychain or the configured desktop credential manager
protects the separate content key. Missing/mismatched keys fail closed. Browser
session keys are memory-only, so page-close restoration is unavailable there.

Replacement/removal compares the observed generation within the account-owned
database transaction. Observers see complete records; stale writers cannot
replace a competing renewal or selection. The storage primitive also exists in
Compose Fluent Starter; HRIS authorization policy belongs here.

## Validation boundaries

Domain cases cover three-day local reads, expired credentials, account/family/API
mismatch, known maintenance, live denial, pending cancellation and late results.
SQLite/encryption tests cover reopening, concurrent writers, missing keys and
copied ciphertext. Application tests cover offline startup, finite foreground
renewal and failed-invalidation recovery. See [validation](validation.md) for
completed runs and device observations.

First sign-in and selection of a new company need the server. Previously unfetched
data is unavailable offline. Personal profile and coworker directory currently
use live reads; existing feature snapshots cover the last acquired view, not all
history. New sign-in changes the local session and clears temporary read
replicas. Inbox uses separate account-owned records that survive reauthentication;
other feature projections still need that migration.
An offline device cannot discover a new revocation before reconnecting.
