# Local SDK identity observation

`CollaborationLocalDeviceIdentityRepository` is a narrow local port implemented by
the existing application-owned Android runtime. DI aliases that same runtime
instance when the host supports the port; unsupported hosts return an explicit
unavailable result. Resolving the port does not create a client or start I/O.

`readLocalIdentity` looks up the exact already opened legacy owner/device. It
neither restores a closed store nor reads or rewrites its protected credentials,
registers a device, acquires server access, starts sync, stages handover or changes
physical namespace. A missing open client returns unavailable; it is never an
empty-inventory result or permission to replace old keys.

The existing Android lease borrows a child owner before the SDK key observation.
The raw SDK source reads public user/device and Ed25519/Curve25519 identity, closes
its temporary encryption handle, and the mapper computes the established HRIS
fingerprint. Private keys stay inside the SDK. Cancellation propagates, and an
in-flight getter keeps the SDK/directory owned until it actually returns. A client
that is unpublished, closing or credential-failed cannot supply successful identity.
The repository checks cancellation, credential failure and the same current runtime
again after the read; a known credential failure wins over a generic stopped result.

This is a local observation, not a cryptographic possession proof, current native
admission or completed migration. Its caller owns a finite request lifetime and
must revalidate account/admission and original provider identity before using the
result for a retained registration intent. No owning migration use case or UI is
activated by this port. Closed-store inspection/recovery requires a separate
explicit resource owner; the port never opens an arbitrary old store on demand.
The original physical namespace includes original native family and stays unchanged.

Five new Android-host cases cover absent-runtime I/O, owned public-key observation,
invalid/failed clients, pending cancellation/resource release and concurrent credential
failure. Existing Android handover, background and publication regressions also pass.
These use inert FFI handles, not JNI/provider proof. Real AppContainer resolves the
port to the same runtime instance without starting observation. Final root :check,
Android-host and Android dev compilation pass 350.063 s / 2,589 fresh
cases; browser data/domain pass 134.092 s / 1,523 cases. Both retain
identical 3,964 source inputs; format, architecture and detekt pass. All
gate sessions are closed. [Evidence](measurements/collaboration-local-sdk-identity-2026-11-05.json).

Before registration, add a bounded native read of the already selected provider-user
intent. The existing selection receipt contains only account/version and cannot
identify which old SDK user to retain. Selection may prefer a different company's
provider user from the currently selected HR workspace. Match the selected Matrix
user against the actual local SDK user; do not require the identity preference's
original device ID to equal this phone's device (several devices can share one user).
A read must never select/enroll by itself. No such new server read is implemented here.

Continue durable native group registration/retry/bootstrap commands with separately
retained original SDK provenance, followed by explicit hard-parent recovery and
current group SDK ownership. New SPACE enrollment still requires retained-store
inventory; a null device status or unavailable local observation is insufficient.
Global UI and typed group push/background/replies remain outstanding. Product uses
company chat; all 54 PRD groups remain open. Phone release29/source084662c5 remains
installed. No new APK installation, phone interaction or provider/FCM operation.
