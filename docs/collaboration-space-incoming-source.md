# Admitted incoming-room requests

Repeated provider hints must not create repeated metadata waves. The owning room request now
checks exact locally acquired source references before creating work. Provider pagination tokens
are opaque positions; comparing one with a message ID provides no coverage evidence.

The typed Space history repository exposes one HMAC-indexed source with the checkpoint from the
same read transaction. Pending and resolved references are both retained and readable after
restart and a newer provider wave. Missing partitions create no key or checkpoint. Existing
account/space/conversation/device partitions and room-authenticated encryption remain unchanged.
A wrong room fails authentication; another owned partition remains absent. No schema migration
or provider request is required for the lookup, and source coverage grants no content permission.

QueueCollaborationSpaceIncomingRoom owns account/directory/binding admission around I/O.
Ordinary credential age does not hide local work. A source in an unfinished provider wave queues
Provider continuation; one with pending content queues Pending. A fully retained source creates
no work. A late acquired-source hint cannot replace a newer queued head or generation, and a
repeated queued hint performs no replacement, source query or scheduler request. Unknown startup
heads explicitly request Latest; a full queue retains the separate overflow-rescan generation.
An unresolved exact source with an empty pending index is rejected as inconsistent.

A changed request and independent incoming wake commit together. One exact readback confirms the
retained demand even if the write response was lost. Conflicting or unreadable state is never
reported as a confirmed write. Scheduling is bounded and best effort after confirmation; the
owning startup/worker must recover the durable wake if scheduling fails. No same-invocation retry
reissues a write. Cancellation, late account replacement and current denial stop further work.
The input and observed room lists are captured before later suspension can mutate them.

Twenty-one domain scenarios and six added real-store cases verify these behaviors. Focused
validation passes 204 cases in 352.143 seconds on JVM and actual Chromium, with iOS
data compilation and quality checks. Full validation passes 10,565 cases in
656.1 seconds (8,546 fresh and
2,019 exact prior controls). Root, Android host/release/lint, Wasm/iOS
application compilation, Chromium, proof build/unit tests and quality checks pass with 4,814
stable inputs. The initial focused run corrected a wrong-room test expectation; the original
authentication boundary was retained. [Focused evidence](measurements/collaboration-space-incoming-source-focused-2026-11-10.json)
and [full evidence](measurements/collaboration-space-incoming-source-full-2026-11-10.json).

The DI factory is registered, but no product callback or incoming worker is enabled yet. Next
come admitted clean room completion, committed provider-phase evidence, finite durable scan
claims, and a bounded current-parent worker. Its work key must use the existing collapsed OS
queue. No native SDK, live backend, phone behavior or generic Starter foundation changed here.
