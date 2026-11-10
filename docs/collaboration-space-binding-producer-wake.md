# Recovery work retained with a new room binding

A recovery worker can finish reading its room inventory just before another operation
stores a new room. Re-reading at the end cannot eliminate that race. New immutable
room bindings now retain a fresh recovery generation in the same database transaction
as their encrypted conversation/room indexes.

The repository contract explicitly owns this atomic retention. It uses the generic
[replica mutation primitive](replica-work-wakes.md), with the same bounded task-key
capacity as other organization recovery signals. A full capacity or stale revision
cannot leave a binding without its marker. A consumer acknowledging an earlier
generation cannot remove the work just committed by the producer.

`PrepareCollaborationSpaceRoomBinding` requests execution only after reading back the
exact retained binding. A lost write response is reconciled through those immutable
facts. The use case does not make a second wake write. OS scheduling is bounded to
1.5 seconds inside the existing acquisition deadline. Its failure leaves the durable
marker available to lifecycle recovery; success here means the binding was retained,
not that a background worker has executed. Cancellation still propagates, and ownership
or directory denial during scheduling prevents a late admitted result.

Previously retained bindings remain a local read. They do not rotate the marker,
start scheduling, renew native credentials or contact the provider. Identical retention
also remains a no-op, including for bindings created before producer wakes existed.
A native operation cannot gain content authority merely by retaining recovery work.

The regression first failed on the original producer because a successful binding
write left no marker. The new tests cover appending after a consumer's final inventory,
old acknowledgements, pending and acknowledged generations, capacity at initial and
subsequent writes, identical local reads, exact lost-response recovery, process/database
reopening, scheduling failure, timeout, cancellation and late ownership/denial.
The focused gate passes 87 fresh cases across domain, SQLite, browser and app DI.
The full gate passes 9,294 validated cases, including
7,301 fresh and 1,993 exact
accepted reused results; no failures, errors or skips. Source/library manifests and
archived/current reports agree. Required root, Android, Wasm/iOS source and quality
checks pass.

Both actual Android SDK scenarios pass in 193.275 seconds
on a separate 2,560 MiB emulator. New binding preparation verifies a fresh wake and
one scheduler request; retained binding reads verify unchanged wake and request count.
Original callback-loss, process death, exact original transactions, group readiness,
claim, pusher and idle-recovery checks remain. The compiler was stopped before the
emulator started, and the emulator is stopped afterward.
[Recorded evidence](measurements/collaboration-space-binding-producer-wake-2026-11-10.json).

This is a manual scheduler fixture, not a cloud-push or physical-phone timing result.
New SPACE enqueue, complete incoming history, product routing and platform delivery
remain separate work. No release or live activation is claimed; all 54 communications
acceptance groups remain open.
