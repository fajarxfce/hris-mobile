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
credential refresh are separate responsibilities. The subsequent durable setup
owner must persist immutable operations before transport, pin bootstrap's client
build, retain uncertain responses and install credentials before retiring a
successful operation. A replay outside the server's 30-second gateway response
window requires explicit observation/reconciliation; it cannot invent another
operation silently.

The domain and adapter tests cover cancellation, scope changes during pending
I/O, policy denial overriding transport failure, malformed/foreign credentials,
response bounds and a 401 refresh replay with identical operation/body. The
focused collaboration workflow checks desktop, browser and Apple targets. Local
Linux assembly cannot establish Apple runtime acceptance. No collaboration PRD
group is accepted by these setup contracts alone.
