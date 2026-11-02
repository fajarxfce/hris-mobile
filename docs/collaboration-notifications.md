# Chat notification integration

[Per-conversation mute and preview storage](collaboration-notification-settings.md)
now supplies account-bound encrypted preferences and conditional offline changes.
[Local candidate preparation](collaboration-notification-projection.md) also filters
owned originals by mute/read/privacy state. System display, UI controls, cancellation
and native action consumers still need integration; live chat push remains disabled.

The managed-pusher client operation is implemented. It is not yet invoked
automatically by the application or worker. Native [push receive routing](collaboration-push-receive.md) now persists
recovery wakes. Live FCM acceptance, decrypted notifications and inline replies remain
required before enabling backend delivery.

`ActivateCollaborationPusher` requires the current admitted company/native parent,
an active matching registered device and an already installed owned runtime. It
checks ownership and the retained content-denial gate around pending operations.
Confirmed permission/MFA denial remains authoritative; ordinary SDK token expiry
does not erase owned local facts. The operation admits one invocation without a
waiting queue and has a15-second budget. No enrollment, runtime restore, credential
refresh or automatic retry occurs here.

Android reads the current SDK session and checks its user/device/homeserver against
the installed runtime. A single10-second HTTP request is registered as a runtime
child before I/O. Caller or parent cancellation stops that exact request; disposal
leaves the borrowed public HTTP pool alive. Current runtime/credential denial wins
over late transport completion. The fixed empty POST is
`/_matrix/client/unstable/dev.fajar.hris/pusher`, with only the SDK bearer. HRIS owns
the callback, capability key and current native push registration. Success requires
HTTP200 with an empty JSON object within1KiB. Redirects are disabled by the shared
public client. Malformed, oversized and unexpected successful responses fail.

The Platform gateway already reserves a stable encrypted binding before the external
Synapse request. An explicit retry after uncertain acknowledgment reuses that
binding. Client-side activation does not expose the capability or FCM token.

[Local evidence](measurements/chat-pusher-client-2026-11-02.json) records eight new
domain cases, seven Android host transport/lifetime cases, real shared-container
bindings, root checks, browser checks, release assembly/lint and two actual-app cold
starts. Android host transport uses MockEngine and a stubbed SDK session getter;
native SDK → HRIS → Synapse → FCM acceptance is still separate. The pusher-only
evidence predates the currently installed release18; see its
[verified installation](measurements/android-release-phone-2026-11-02-build18.json).
Release18 does not automatically activate the chat pusher.

The [native recovery client](collaboration-native-recovery.md) now discovers bounded
device references for the current parent and acquires separate company admission.
Discovery metadata is not an authority grant. The admitted background worker now
consumes this scoped recovery without requiring a selected workspace or outgoing
command. Owned leases coexist with the foreground runtime; captured account/session
ownership gates late results and avoids opening the same store twice. Incoming
company/room progress and push priority are separate durable positions. Notification
replies still need owned encrypted actions through the existing command pipeline,
with duplicate-action and process-death recovery.

The pinned Rust SDK NotificationClient requires sliding sync. The current product
uses classic SyncV2 with `SlidingSyncVersion.NONE`; do not wire it in on the
assumption that its context fallback bypasses that requirement. A scoped sliding
contract or notification projection over the existing owned decrypted history needs
its own implementation and proof. Privacy, mute/read state, account-bound actions,
Apple extensions and browser behavior also remain open. Keep
`HRIS_COLLABORATION_PUSH_DELIVERY_ENABLED=false` until recovery is usable.
