# Selected organization identity on native clients

`ReadCollaborationSpaceIdentity` reads the current account/space's retained provider
user through bodyless native GET `/collaboration/spaces/{spaceId}/identity`. The
actual feature DI graph resolves its repository, raw datasource and API service.
No company selection, idempotency key, browser cookie, query or request body is
sent. Response acquisition is bounded to 4 KiB; the existing native transport permits
one 401 credential replay, never an application retry loop.

The nullable identity projection contains eight fields. Mapping validates exact
account/space, canonical UUIDs, version 0, bounded metadata/time, native LEGACY or
SPACE provenance and the exact Matrix user derived from its original company or
space and account. Legacy company is required only for LEGACY. Provider realm is
syntax-checked metadata; every HTTP request keeps the configured HRIS endpoint.
A preferred legacy DEVICE is deliberately absent: another phone can share the
same selected provider USER. A null identity never runs POST selection or proves
that this installation has no original SDK/private keys.

The identity and existing device-status use cases share an internal finite read
policy function, not calls between use cases. It captures the original native
hard deadline, wall time and monotonic budget before local admission/remote work.
A 15 s attempt includes local reads; owner/current session is reacquired before HTTP,
after its result and after final directory admission. Account/session replacement,
wall rollback, expiry, cancellation and late results cannot publish metadata.
Confirmed remote access/MFA denials are retained through cancellation with a bounded
2 s restriction write; failed persistence does not clear the owning directory gate.
An existing denial survives failed retries/timeouts. Successful metadata can help
recovery but never admits a denied directory. A newer concurrent denial wins.

This is a one-shot observation, not a cached directory/list or current device grant.
Old selected user creation dates remain valid metadata; no age-based discard or
network polling is added. Device enrollment, provisioning retry, gateway bootstrap,
SDK activation and physical-store ownership remain explicit separate operations.
No credentials, keys, preferred device IDs or native proof are returned or logged.

Seven new common HTTP cases and seven new domain cases pass, alongside 13 device
lifetime and 20 identity-selection regressions. Actual AppContainer resolves the new
reader. Final focused gate: 47 fresh cases / 126.165 s.
Root :check, Android-host and dev compilation: 2,610 fresh cases /
327.498 s. Browser data/domain: 1,537 cases / 133.924 s.
All final gates preserve identical 3,972 build/application inputs;
format, architecture and detekt pass. All gate sessions closed.
[Evidence](measurements/collaboration-space-identity-observation-2026-11-05.json).

Backend [316cd708](https://github.com/fajarxfce/hris-platform/commit/316cd7083bc154e2e28b9425349ed63d40d6425f)
provides the matching current-parent endpoint. Next implement durable device
registration/retry/bootstrap with original operation/native-parent/platform/realm
and actual retained SDK user/device/fingerprint provenance. Unlike identity POST,
registration/retry bind the original native parent; bootstrap also binds the actual
client build. Selected user origin and registered device origin are independent:
a new SPACE device may use an existing LEGACY user only after explicit retained-store
inventory/recovery permits it. Never replace the old SDK's native-family component
to make a fresh login appear to own its physical store. Missing current-parent status does not
prove no older device exists. Retained-store inventory, explicit hard-parent recovery,
group runtime/UI and typed push/replies remain outstanding.

No product UI change, new APK installation, live backend deployment, provider/FCM,
Apple runtime or performance acceptance. Phone release 29/source 084662c5 and live
backend 8b40/schema 116 remain unchanged. All 54 communications PRD groups stay open.
