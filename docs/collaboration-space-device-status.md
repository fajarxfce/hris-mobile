# Native organization device status

`ReadCollaborationSpaceDeviceStatus` performs one explicit native GET at
`collaboration/spaces/{spaceId}/native-device`. It observes the device bound to
that current native parent, independently of the selected HR company. Reading
status neither enrolls a device nor retries provisioning, bootstraps credentials,
opens a provider SDK, or changes directory admission.

A successful null device is an explicit observation for that parent. In particular,
a fresh login may return null while the same account still owns an older physical
SDK store. Never translate null into permission to register a replacement SPACE
device or regenerate keys. The response contains public origin (LEGACY/SPACE),
label, device/version/state, provisioning progress/outcome and gateway metadata;
it supplies no Matrix identity, legacy position, original key proof or live grant.
Original registration provenance must remain separately retained for later setup.

The data boundary acquires and maps inside `safeApiCall`, bounds the JSON response
to 8 KiB, binds the exact originating local session, and uses the existing single
401 replay. No company header, cookie, idempotency key or request body is added.
Strict mapping validates UUIDs, native platforms, enums, labels, versions, bounded
attempts, retry metadata, terminal outcome consistency and timestamp order. A
pending retry may retain an earlier terminal outcome; terminal heads must name
their own outcome. Expired provisioning/gateway metadata remains valid metadata.
It never becomes current authority merely because it was decoded successfully.

The use case captures the native parent and its earliest hard deadline. Initial
local reads, HTTP and final admission checks share a 15-second cooperative budget,
with wall-clock rollback and monotonic elapsed-time checks. It rechecks the exact
local/native owner across pending boundaries. Stale parents, replacement accounts,
cancelled requests and late responses cannot publish successful status. A
non-cooperative dependency may delay cancellation completion, but cannot publish
late success. There is no polling, automatic retry or retained UI subscription.

Received access/build denials are retained through the directory restriction
owner even if cancellation arrives with the response; that retention has its own
bounded two-second cancellation path. Failed storage or a failed/timeout retry
cannot replace a known denial. A newly received concurrent directory denial wins
over pending successful metadata. Successful status under an unchanged earlier
denial can inform explicit setup recovery, but does not clear that directory gate.
Only the separate admitted directory acquisition owns reopening directory reads.

Seven native HTTP and thirteen domain lifecycle cases pass. Actual AppContainer
resolves the repository and owning use case. Final root :check, Android-host and
Android dev compilation pass 342.801 s / 2,584 fresh cases; browser
data/domain pass 136.437 s / 1,523 cases. Both preserve identical
3,962 source inputs; format, architecture and detekt pass. All gate sessions
are closed. [Evidence](measurements/collaboration-space-device-status-2026-11-05.json).

Next: durable registration, provisioning retry and gateway bootstrap commands,
followed by original SDK-store adoption/current authority and explicit hard-parent
recovery. Review each operation's actual server idempotency contract separately:
registration and provisioning retry bind the original native parent but do not
fingerprint client build; bootstrap binds native parent, versions and client build.
Original command provenance remains immutable, while current admission is still
required. Do not copy identity selection's account-wide replay rule into device
commands, or force all command types to use bootstrap's stricter build rule.

The existing physical SDK namespace hashes audience, account, company, original
native family, device and endpoint. Substituting a fresh native family would select
a different store. Keep that immutable physical owner separate from current
admission. The existing Android lease can observe real local SDK user/device/key
fingerprint under child ownership; protected `selfIdentity` metadata alone only
contains the Matrix user. Before new SPACE enrollment, an account-owned inventory
must establish the absence of retained stores; no such new inventory or recovery
port is implemented here. Existing LEGACY migration must retain its exact original
company/device/key observation and must not stage handover merely to inspect keys.

Current product remains company chat. No group product runtime, provider/device
mutation, Apple runtime, typed group push or performance acceptance is implied.
Phone release 29 remains installed from earlier source 084662c5; this increment is
not in that APK. All 54 communications PRD groups remain open.
