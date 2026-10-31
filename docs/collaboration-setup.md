# Native collaboration setup

`features/collaboration/domain` owns setup entities, live admission and request
lifetime. `features/collaboration/data` implements the native registration,
provisioning retry, setup observation and gateway bootstrap API contracts.
The runtime installer transfers confirmed bootstrap credentials into protected SDK
storage. App session ownership and chat routes remain in progress.

`LoadCollaborationSetup` captures the local session, selected company and native
session family. It admits the collaboration permission/module and current native
client policy, then reacquires admission after the response, including failed
responses. Cancellation and replacement accounts, selections or native families
cannot publish a late result. Ordinary token rotation preserves the same owner.
Reading status never enrolls a device, retries a job or issues credentials.

The authenticated Ktor client retains its existing captured-session refresh
contract. Operations carry the caller's UUID and observed versions; adapters do
not generate retry identities or add delivery loops. A provisioning retry receipt
identifies its new job, not the unchanged device. Bootstrap pins the expected head
generation and exact company/account/device Matrix identities. Only opaque HRIS
gateway tokens are accepted, with bounded responses and redacted diagnostics.
The Matrix server name in an identity cannot select the HTTP origin.

Expired setup and gateway deadlines remain readable metadata. They do not remove
local facts, credentials or queued work. Live authorization, durable ownership and
credential refresh are separate responsibilities. The durable setup owner now
persists immutable operations before transport, pins the native platform/client
build and retains uncertain responses. Credential installation must finish before
retiring a successful bootstrap. A replay outside the server's 30-second gateway response
window requires explicit observation/reconciliation; it cannot invent another
operation silently.

The domain and adapter tests cover cancellation, scope changes during pending
I/O, policy denial overriding transport failure, malformed/foreign credentials,
response bounds and a 401 refresh replay with identical operation/body. The
focused collaboration workflow checks desktop, browser and Apple targets. Local
Linux assembly cannot establish Apple runtime acceptance. No collaboration PRD
group is accepted by these setup contracts alone.

## Durable setup commands

`PrepareCollaborationSetup` acquires current setup facts and saves one immutable
intent per company/native session family. Returning to the same pending action
returns its original operation. A different action or build requires review;
preparation never rewrites an existing request. Ordinary native access-token
rotation does not change the operation's owner.

`ReplayCollaborationSetup` has one application-owned delivery mutex. Each explicit
attempt commits `Unconfirmed` before transport and sends the original operation,
input and observed versions. It makes no scheduling loop. Failed storage or a lost
revision prevents transport; cancellation or a lost response leaves the attempt
retained. Confirmed permission failures remain authoritative if saving their
outcome fails. Admission is checked again after pending reads, writes and HTTP;
replacement accounts, companies and native families cannot publish a late result.
The 1,000-attempt ceiling retains the command for review instead of deleting it.

`EncryptedCollaborationSetupCommandRepository` uses the existing durable command
database and protected command content key. Separate AES-GCM request/outcome
envelopes authenticate audience, account, company, native family, operation,
purpose and outcome revision. The replaceable local session handle is checked by
the database transaction, not embedded as a permanent ciphertext owner. Missing
keys never generate replacements over retained ciphertext. Setup shares the
existing 128-command account / 512-command database bounds with business actions;
the Matrix message queue is a separate SDK-owned concern.

Gateway credentials are encrypted in the confirmed bootstrap outcome. Their owner,
device and head generation must match the original command; expired metadata stays
readable. `ReconcileCollaborationSetupReceipt` removes only an observed confirmed
registration or new provisioning-job receipt with a matching command revision.
It cannot retire an uncertain operation or bootstrap credentials. The runtime
installer below owns confirmed bootstrap retirement. Recovery after an expired
bootstrap replay/build change, app ownership and UI wiring remain required work.

Real SQLite tests cover reopening, changed local session, account/company/native
family isolation, missing/wrong keys, copied envelopes, competing revisions and
cancellation. Domain cases additionally exercise lost responses, recreated delivery
owners, storage failures on both sides of I/O, callback cancellation, late denials,
build changes, receipt reconciliation and exact gateway ownership. These tests
establish setup-command durability, not SDK message durability.

## Admitted runtime installation

`InstallCollaborationRuntime` serializes explicit installation calls. It captures
current account/company/native-family admission, reads the retained command and
checks the original operation, build/platform, attempted bootstrap outcome and
gateway identity. It owns a successful SDK lease before any later suspending check.
Only a durably installed matching owner/device/operation without a credential-write
failure may retire the observed command revision. The returned lease remains paused;
the caller owns it and must separately acquire live delivery authority.

Cancellation, late owner/permission changes, failed installation, wrong lease
identity and failed or competing retirement close the acquired lease. Cleanup runs
even when the caller was cancelled; the runtime repository retains unfinished drain
ownership. A failed cleanup does not replace a confirmed authorization denial.
When retirement committed but its response was lost, protected SDK facts remain
available for explicit restore. No logout, token issuance, retry loop or data reset
is performed by this use case. Old gateway deadline metadata remains a local fact,
not permission to send messages.

[Installation evidence](measurements/collaboration-runtime-installation-2026-10-31.json)
retains 12 common cases, 35 domain desktop/browser cases and passing root checks
(2,075 desktop cases). Android/data assembly compiles the binding. Apple runtime,
bootstrap recovery and application DI/navigation still require implementation.

`RestoreCollaborationRuntime` now recovers an existing protected runtime under
current setup admission without issuing credentials, installing a replacement or
changing retained commands. It owns a returned resource before checking admission
again, rejects mismatched owner/device or failed credential persistence, and closes
late results after cancellation or scope replacement. A genuinely empty store is
an explicit null result; occupied stores with missing keys still fail in the native
repository. Ordinary token rotation preserves the same runtime owner.

[Restoration evidence](measurements/collaboration-runtime-restoration-2026-10-31.json)
retains eight new common cases and passing root/domain-browser/data-assembly checks
in 72s: 2,083 desktop cases and 43 domain browser cases. This is admitted setup
recovery, not an offline message projection or live delivery grant. Application-wide
SDK child ownership, explicit bootstrap recovery and app routes are next.
