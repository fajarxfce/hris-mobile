# Android HTTP observation lifetime

The Android runtime factory accepts an optional raw `AndroidCollaborationHttpObservation`
contract. Application composition can supply a diagnostic adapter without introducing
an inspector dependency or SDK-version change into the feature. The default is absent.
No inspector is enabled by this change.

Acquisition starts this observer inside the exact runtime lease before invoking restore.
Its coroutine is supervised independently of sync/delivery. A source exception cannot
change publication readiness; cancellation remains cancellation. The adapter must own
and release its subscription and must never close the native client. This is an invocation
boundary, not a guarantee that an asynchronous adapter captures every initial restore request.

A lease starts at most one observation. Closing rejects new starts, waits for acquisition
to finish, then cancels and joins the observer before authentication, persistence and
native-client cleanup. Pending observer cleanup keeps the client and directory owned.
One client's closure cannot cancel another client's observer.

Seven new ownership cases pass, within534 fresh Android cases. The full headless root
check, Wasm compile, formatting, architecture, detekt and Android release lint pass.
The first rejected fixture/LongMethod attempt is retained in the [measurement](measurements/collaboration-http-observation-lifetime-2026-11-07.json).
Actual Matrix capture remains in the separate reusable inspector adapter. SDK4, original
keys, queues, native credentials and delivery behavior remain unchanged by this hook.
