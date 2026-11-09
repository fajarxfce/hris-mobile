# Native direct transport observation

Status: implementation candidate, not compiled or accepted yet. The corresponding
backend route is a private candidate behind the ongoing organization-source merge;
organization routing stays disabled. No delivery, UI, worker or SDK caller is added.

`CollaborationSpaceDirectTransportRepository.read` requests one finite native GET at
`collaboration/spaces/{spaceId}/direct-conversations/{conversationId}/transport`.
The endpoint returns current public device/gateway metadata, the exact immutable pair,
its published room and explicit recipient key readiness. It returns no credentials,
private keys, messages or reusable sending authority. The application must separately
match the current runtime and admit each actual Matrix operation before sending.

The API uses the existing native authentication owner, canonical UUID paths and an
8 KiB response limit. It adds no company cookie, request body, idempotency key, setup,
retry loop or readiness polling. The shared authentication layer retains its single
401 replay; 403 and service failures return their existing classifications. Datasource
I/O stays raw; the repository validates ownership and maps inside `safeApiCall`;
pure domain policy validates the public facts. Generated DI resolves the repository.

The response must match the supplied account, native parent, space and complete
canonical conversation. The device must be active with a current hard gateway lifetime
at the response's observation time. Short access expiry is allowed; this metadata is
not a provider token. Local wall-clock age is not a reason to erase owned messages.

User origin, crypto-device origin and room origin are separate. A newly enrolled
`HRISG_` device may retain the selected original company Matrix user and original room.
A migrated `HRIS_` physical device keeps its own ID, which differs from the logical
organization device. Original rooms retain their exact original company/conversation
coordinates; another user's or company's identifier cannot be substituted. No key,
room or queue is recreated when the recipient is offline or not yet ready.

Thirteen Kotlin files include eight transport test methods and application DI
resolution. Tests cover one GET, false/true readiness, expired short access, both
original/new crypto-store combinations, exact immutable ownership, malformed/oversized
responses, authentication/service failure limits and cancellation. The existing room
mapper is factored without changing its validation; its prior transport tests remain
in the focused/browser checks. Formatting passed; Kotlin, browser, Android and root
checks are pending behind the one-heavy-local-job rule.

Private local runner: `.work/space-direct-transport-2026-11-09/check.py` (prepared, not
executed). Group full reconciliation33511 owns the heavy slot. Do not turn draft source
or a formatter result into a feature, Apple runtime or phone acceptance claim. Continue
with the owning original SDK queue delivery and group recipient-ready wake after the
backend and Mobile contract are validated. HRIS-specific policy is not a Starter port.
