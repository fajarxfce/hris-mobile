# Notification process reconciliation

`CollaborationNotificationProcess` owns the surface owner's startup/disposal, one cold
status collector, a conflated wake channel, registered finite work and at most 64 opaque
publication references with their source subscriptions. A platform host must own one
instance for its native namespace. App DI composes the real discovery, observation and
reconciliation use cases through the included `applicationNotificationModule`; construction
is lazy and does not start a native host. The [Android application runtime](collaboration-android-notification-runtime.md)
now explicitly starts that host from the actual application collaboration lifetime.

Complete discovery reads pages of four within an eight-second cooperative budget, checking
ownership, active state, shape, ordering, continuation and capacity before changing the
subscriptions. Applying that snapshot has a separate eight-second budget. Obsolete records
receive exact native removal before their subscription is forgotten, preserving a newer
native render. Discovery or uncertain cleanup failure pauses the namespace without needing
account keys. The gate stays closed until explicit recovery; a timeout does not prove an
uncooperative OS call has stopped.

Each new source begins unavailable. Observations carry no preview or message text. Current
source changes reconcile silently; failures bypass publication. Normal completion or an
exception closes the source instead of starting a retry loop. Explicit recovery can reopen
subscriptions. Parent changes and disposal cancel work and join source/native cleanup.
Source/request callbacks only cancel work based on an older captured observation/revision;
they never wait on the actor they notify. Lazy registration precedes the second ownership
and observation check.

The actor rescans after potentially journal-changing work. An existing company/conversation
keeps its observer across its own ACK/version changes, so its handled source does not become
dirty merely because acknowledgment changed the record. Contention retains the stale exact
reference and retries after discovery finds a different reference. Repeated discovery of
the same contended reference does not spin. A distinct surface-busy outcome bypasses semantic
cleanup and waits for idle plus a completion revision different from the failed attempt.
A generic storage failure does not retry on its own completion wake.

[Validation](measurements/chat-notification-process-2026-11-03.json) records 20 virtual-time
process cases, 30 mechanical-owner cases, ten finite-reconciler integration cases and three
actual Koin/encrypted SQLite process cases. The latter exercise privacy replacement,
mute/private-read retirement across reopen, and company denial outside the selected company,
using fictional acquisition and a fake OS. The full run passes 3,693 desktop cases, 230
Android-host cases, six proof-host cases and ten build-logic cases, with proof assembly,
shared Wasm compilation and root quality checks. Earlier failed fixture and module-length attempts
remain recorded separately. No device/provider acceptance is inferred from these tests.

The current process reconciles existing journal records. New-candidate delivery, focused
thread ownership, initial/backfill alert classification, native callback authentication,
encrypted input review and notification-specific process-death proof remain next. No HRIS
native entry point starts this process yet and chat push remains off. No new product APK
was delivered. Capacity and flow bounds are not a claim of device performance; measure
realistic loads when integrating candidate delivery and the native host.


[Finite candidate delivery](collaboration-notification-candidates.md) is now implemented
inside the same actor, with provisional source readiness, bounded caller-owned requests,
joined cancellation and silent uncertainty replay. Incoming priority handoff and native
host/focus/callback/review integration remain next; chat push is still off.
