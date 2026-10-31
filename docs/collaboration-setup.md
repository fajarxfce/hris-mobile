# Native collaboration setup

`features/collaboration/domain` owns setup entities, live admission and request
lifetime. `features/collaboration/data` implements the native registration,
provisioning retry, setup observation and gateway bootstrap API contracts.
This increment does not install a chat route or open a Matrix SDK store.

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
It cannot retire an uncertain operation or bootstrap credentials. SDK installation,
recovery after an expired bootstrap replay/build change, UI wiring and message
queue recovery remain separate required work.

Real SQLite tests cover reopening, changed local session, account/company/native
family isolation, missing/wrong keys, copied envelopes, competing revisions and
cancellation. Domain cases additionally exercise lost responses, recreated delivery
owners, storage failures on both sides of I/O, callback cancellation, late denials,
build changes, receipt reconciliation and exact gateway ownership. These tests
establish setup-command durability, not SDK message durability.
