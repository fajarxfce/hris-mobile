# Native HRIS identity

## API and ownership

Android/iOS use `/api/v1/auth/native/login`, `/auth/native/refresh`, the native MFA
endpoints, `/me`, and `/companies/{companyId}/me/access`. API services perform HTTP
calls; datasource contracts return DTOs; repositories map DTOs and technical errors;
use cases coordinate session and company policy. Feature ViewModels own screen work.

Password requests carry the original idempotency key while the credentials remain
unchanged. Editing the form starts another attempt. A successful response retains
the server family ID, API audience, and family expiry in secure storage. A separate
local session ID owns caches and navigation. A changed API audience invalidates
that identity before credentials can be sent elsewhere. Reaching family expiry
blocks remote acquisition with `client.authentication_required` while retaining
the local owner and its encrypted read models. A confirmed refresh rejection
still removes the rejected session.

One container-owned mutex serializes refresh and MFA credential rotation. Refresh
persists its operation ID before network I/O. MFA proof calls use explicit credentials
through the public client, so a rejected authenticator code cannot trigger automatic
401 refresh/replay. Cancellation and a replaced session prevent late publication.

The account response determines whether MFA is required.
`account.mfaConfigured` distinguishes enrollment from verification.
`assurance.setupAvailable` only describes server capability and can remain true for
an already enrolled account. Enrollment displays the
setup key; confirmation persists rotated credentials before displaying recovery
codes. Navigation waits for explicit acknowledgement. Secrets are not placed in
saved navigation arguments, preferences, diagnostics, or `toString` output.

A lost confirmation response is ambiguous: the UI requires fresh sign-in instead
of resubmitting a potentially consumed proof. This matches the backend contract;
confirmation is not a replayable recovery-code download.

## Company scope

Company selection rechecks `/me` and current company access. Concurrent selections
use compare-and-set so a late request cannot overwrite another accepted choice.
Every selection creates a new workspace ID, including a return to the same company.
The root navigation graph changes with that ID and disposes the former route owners.

The encrypted selection binds the account, session family and API audience.
Foreground entry rechecks live access without blocking local restoration. Transport
failure cannot renew authorization, but age alone does not hide owned records.
See [company workspaces](workspace.md) for ownership, persistence and races,
and [inbox](inbox.md) for encrypted content and durable employee actions.
The same snapshot includes [company availability](company-policy.md). Maintenance,
minimum-build and module restrictions pause company operations without treating
the policy change as a revoked account or deleting durable inbox commands.

## Active sessions and sign-out

Account → Active sessions reads the bounded native session directory. The current
device is identified by the remote family ID. Ending another session requires an
explicit confirmation and refreshes the directory after a confirmed response.
Cancelled or failed reads cannot publish into a replacement local identity.

Sign-out attempts to revoke the originating family and then clears that local
identity, including credentials rotated during the remote call. The authenticated
request does not hold the credential-mutation mutex; its client can refresh normally.
The short local deletion finishes even when navigation disposal cancels the caller.
A replacement account is preserved. A failed remote attempt still permits local
logout, with an unconfirmed remote result; no screen claims that every device has
signed out. Local storage failures remain explicit.

Reads and revocations use the ordinary transport boundary and never request MFA,
retry, or change local credentials from the repository. See [local session ownership](session-ownership.md).

## Remaining identity integration

A native OIDC callback handoff remains to be connected.
The backend's existing browser OIDC flow is not a native exchange endpoint, so
provider buttons remain unavailable rather than trusting client-side claims.

Android/iOS provide uppercase `X-HRIS-Client-Platform` and monotonic
`X-HRIS-Client-Build` headers. Desktop preview uses the backend's unversioned native
path; configured native minimum-build requirements reject it. Browser deployment
needs an explicit cookie/CORS integration. These are not production delivery claims
for desktop or browser authentication.

## Validation

Tests cover secure-store restoration, original refresh intent, audience/family
expiry, cancellation, replacement sessions, MFA/refresh serialization, company
selection races, and hidden mismatched company scopes. Compose tests exercise
password login, MFA setup and rejection, recovery acknowledgement, lost confirmation,
company switching, language changes, active-session confirmation/cancellation,
session-list access-token recovery, and online/unconfirmed logout through a test-only HTTP engine.
Runtime composition uses platform HTTP engines.

A local HRIS Platform instance was also exercised with native password sign-in,
MFA enrollment/confirmation, and two fictional companies. This API probe is separate
from the Android device checks recorded in [validation](validation.md).

A separate disposable native family was issued against that local server to verify
the pending-MFA directory, owned-family revocation (204), and rejection of both its
access and refresh tokens afterward (401 `native_session_invalid`). Only the
verification family was revoked. Workspace checks, native-session policy and
transport tests, application-flow tests, the Android dev build, and Wasm compilation
passed. Android 16 device checks additionally covered MFA enrollment, invalid/valid
authenticator proofs, recovery acknowledgement and sign-in, restoration after
process termination, session-revocation cancellation/confirmation, and logout.
These used fictional accounts and locally generated proofs, separately from an
external authenticator application. Browser deployment and external SSO acceptance
remain separate.
