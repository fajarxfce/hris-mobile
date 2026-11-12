# Owned SDK room-key listener

The Android lease borrows its installed Matrix client for one permanent room-key registration,
outside the transient room-resource limit. It never creates a client or starts sync from observation.
The raw datasource owns TaskHandle and Encryption cleanup. A shared lifecycle component owns the
single acquisition independently of UI callers, retains failures, and synchronously fences retirement.
Null subscription means unavailable; repeated reads or start calls do not create acquisition loops.

Handle acquisition and assignment finish together even if their caller is cancelled. Shutdown waits
for that acquisition, cancels the task, waits up to five seconds for isFinished, then closes the handle
and Encryption. Failed cancellation, drain, or close retains ownership and blocks parent client pause,
destruction, and directory unlock. Explicit cleanup can retry; completed release is not repeated.
The parent owns the asynchronous shutdown producer beyond its caller's bounded wait.

Production initialization does not start the registration yet. Key-hint domain admission, durable
intent-before-ACK, bounded failure scheduling, source/restore triggers, and a native Apple adapter
remain required before activation. Protocol fakes and platform compilation are not native execution.
This work changes no live deployment, phone installation, or Starter business policy.

[Exact validation evidence](measurements/collaboration-sdk-room-key-lifecycle-2026-11-12.json).
