# Chat notification integration

The managed-pusher client operation is implemented. It is not yet invoked
automatically by the application or worker. Incoming FCM recovery, decrypted
notifications and inline replies remain required before enabling backend delivery.

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
native SDK → HRIS → Synapse → FCM acceptance is still separate. Release12 is a local
universal validation APK; the phone retains accepted release11.

Next, discover all device references for the current native parent through the
bounded Platform directory, then acquire separate company admission before content
I/O. Discovery metadata is not an authority grant. Background leases must coexist
with the foreground runtime without changing the selected workspace, escaping an
account/session change or opening the same cryptographic store twice. Collapsed
push hints, reconnect and periodic recovery must visit every owned company with
bounded durable cursors. Notification replies must first persist owned encrypted
commands through the existing pipeline and survive duplicate actions/process death.

The pinned Rust SDK NotificationClient requires sliding sync. The current product
uses classic SyncV2 with `SlidingSyncVersion.NONE`; do not wire it in on the
assumption that its context fallback bypasses that requirement. A scoped sliding
contract or notification projection over the existing owned decrypted history needs
its own implementation and proof. Privacy, mute/read state, account-bound actions,
Apple extensions and browser behavior also remain open. Keep
`HRIS_COLLABORATION_PUSH_DELIVERY_ENABLED=false` until recovery is usable.
