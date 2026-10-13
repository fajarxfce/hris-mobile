# Company workspace

The selected company is an encrypted, session-owned replica. A restored identity
can reopen that workspace while offline, provided its admission has not expired.
Each feature still checks the current session, workspace and permission before
reading private content or sending a command. The backend independently authorizes
every request.

## Admission and renewal

`SelectWorkspace` reads the current account, company permissions and client policy. It creates
a new workspace ID and an admission bound to the local session, account, server
session family and API audience. Offline admission lasts at most one hour from
the start of that check, and ends earlier when the server family or required MFA
assurance expires. A future check timestamp is rejected.

`ObserveWorkspace` checks ownership and expiry before exposing the selection.
Application navigation uses `ObserveWorkspaceContext` to receive identity,
workspace and [company availability](company-policy.md) together.
It stops exposing an expired workspace without waiting for a database write.
The application context keeps only that owned selection's identifier for renewal;
expired company data and permissions are not exposed. Cold startup opens a
connection recovery page instead of treating expiry as an absent selection and
entering online account verification. Foreground entry or an explicit retry
revalidates the saved company. Successful renewal returns to that company without
requiring selection again. A failed request stays on recovery and does not poll.
Changing company remains an explicit local action. A mismatched account, session
family or API audience cannot retain this renewal identifier.
Inbox observation applies the earlier of its content expiry and workspace expiry.
Leaving a workspace removes the selection; ending the session clears derived
replicas through the account-storage boundary.

`RefreshWorkspace` rechecks the live account, permissions and client policy. Foreground entry
and the application worker can request it. A mutex serializes checks, and a
thirty-second deadline bounds each run. Transport failure never extends admission.
Authoritative session, company or MFA denial conditionally removes the old selection.
The foreground gate hides protected routes if that invalidation cannot be stored;
a later offline failure cannot reopen them.

A renewal that leaves the company and permissions unchanged preserves the
workspace ID and route owners. Permission or company changes create a new ID.
The MVI owner triggers checks on foreground/scope changes and policy freshness expiry,
not every lease update,
and cancels its pending request on background entry. Late results cannot overwrite
a newer selection or another account.

## Persistence

`EncryptedWorkspaceRepository` coordinates the raw `ReplicaStore`, HTTP datasource,
content cipher and atomic credential store. It performs mapping and technical
failure handling; admission and lifecycle decisions remain in use cases.

AES-GCM encrypts the complete company/permission/admission/policy record. Associated data
includes the audience, local session and storage generation. Android Keystore,
Apple Keychain, or the configured desktop credential manager protects a separate
content key. Missing or mismatched keys fail closed. The browser keeps its bearer
session and keys in memory, so page-close restoration is unavailable there.

The database replaces or removes the entire snapshot with a compare-and-set on
the observed checkpoint inside the account-owned transaction. Observers see one
complete record. A competing renewal, company selection or account switch cannot
be overwritten by a stale writer. The snapshot primitive and its SQLite/IndexedDB
tests also exist in Compose Fluent Starter; HRIS admission policy stays here.

## Validation boundaries

Domain tests cover MFA/session deadlines, expiry without storage events, unchanged
scope renewal, late success/denial, cancellation and timeout. SQLite/AES-GCM tests
cover reopening, logout, concurrent writers, missing keys and copied ciphertext.
Application tests cover foreground cancellation, renewal-loop prevention and
failed-invalidation recovery. See [validation](validation.md) for device results.

Offline admission is a bounded client policy, not immediate offline revocation or
tamper-resistant device attestation. Server-side changes become known on the next
successful check; lease timing uses the device clock.

The current application is not fully offline. Only previously acquired snapshots
can be restored, and the one-hour company admission also bounds feature reads.
First sign-in, new company selection and renewal after admission expiry require
the server. Coworker directory and personal-profile acquisition still use live
reads. A recovery screen makes that boundary visible; it does not extend a lease
or make stale private data readable.
