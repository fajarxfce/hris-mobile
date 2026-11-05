# Native organization provisioning and gateway transport

The owning feature graph resolves `CollaborationSpaceSetupRepository` through its
native API/raw source and mapping repository. Explicit retry and bootstrap target
`/collaboration/spaces/{spaceId}/native-device/retry` and `/bootstrap`. Both send the
original canonical operation ID and exact current local session handle, without
selected company, cookies, provider credentials or provider metadata in the body.
The native transport permits one session-token rotation; it never schedules another
setup operation. Raw adapters preserve cancellation and technical exceptions.

Retry carries the original device ID, observed device version and provisioning
revision. Both observed versions are bounded to 0..999998. The response must name
the exact device and provisioning revision + 1; device version is a different fact.
Its operation does not bind an old build, so current default headers are preserved.
Responses are capped at 1 KiB before mapping.

Bootstrap carries the original device ID, observed device version and nullable
expected gateway version. The retained request also records original native binding,
platform/build and expected selected provider USER/DEVICE locally. Its operation
binds actual platform/build: serialization rejects a different build/platform and
never rewrites authenticated-client defaults to impersonate an older build. User
origin and device origin remain independent. A new SPACE device may use a selected
LEGACY user; this typed transport does not establish enrollment eligibility.

The 4 KiB response must match account/space/device/native parent, exact expected
Matrix user/device, canonical gateway UUID and expected gateway version + 1. Only
group `hris_mxg_a_`/`hris_mxg_r_` tokens with 43 URL-safe suffix characters are accepted;
old company tokens cannot substitute. The original provider realm validates identity
syntax but never changes the configured HTTP endpoint. Timestamps stay within the
supported calendar and access expiry cannot exceed hard expiry. Ordinary age does
not hide typed owned facts: current credential usability and native hard deadlines
remain the owning use case's responsibility. Sensitive request/result/DTO string
representations are redacted. The transport never opens an SDK or publishes UI.

The first focused compile exposed a cross-module nullable range check; using an
explicit nullable mapping fixed the type error without changing the request contract.

Twelve new common transport cases cover original native/body/operation identity,
actual Android/iOS headers, bootstrap build binding, independently selected user and
device origins, bounded/malformed responses, group token namespace, classified denial,
pending cancellation and noncooperative late response disposal. Actual AppContainer
resolves the new native setup repository. Focused: 26 cases /
96.609 s. Root :check, Android-host and dev compilation: 2,683
fresh cases / 343.862 s. Browser data/domain: 1,591 fresh cases /
136.713 s. Final gates preserve identical 3,998 build/application
inputs; format, architecture and detekt pass. All gate sessions closed.
[Evidence](measurements/collaboration-space-native-setup-2026-11-05.json).

Next retain retry/bootstrap intent and outcomes in encrypted account/space storage,
including uncertainty before HTTP and exact original operation on retry. Owning use
cases must enforce finite preparation/replay, current admission and native hard
expiry, persist received credentials before publication, and preserve confirmed
access/MFA denial across failed persistence/cancellation. An old-build uncertain
bootstrap requires explicit metadata reconciliation, never an edited operation.
Original registration anchors and SDK identity remain authoritative provenance;
public identity alone does not prove private-key possession.

Retained private-store inventory, explicit hard-parent recovery, old-runtime
freeze/drain/handover and original-store adoption, group UI/runtime and typed
push/background replies remain unfinished. No new durable setup flow or product
route is activated here. No new APK/phone, live deployment, provider/FCM, JNI/private
key proof, Apple runtime or performance acceptance. All 54 PRD groups stay open;
live backend 8b40/schema 116 and installed phone 29/source 084662c5 unchanged.
