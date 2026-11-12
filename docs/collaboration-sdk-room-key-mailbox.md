# Retained room-key callback batches

`SdkRoomKeyMailbox` is a process-local data component for one native registration. It owns one
stable delivered batch and one waiting accumulator. Each slot retains at most64 unique room IDs,
with a1024character input bound. Truncated input and union overflow retain a reconciliation flag.
Native callback lists are copied synchronously, and exposed snapshots do not mutate retained data.

Observation does not acknowledge work. An exact delivered receipt promotes the waiting batch;
a stale or repeated acknowledgment cannot clear a newer one. New callbacks remain waiting even
when their room IDs match the delivered batch. Waiting callbacks coalesce before delivery. Atomic
StateFlow updates preserve these semantics when callbacks, acknowledgment and closure interleave.
Closure stops observation and disposes the registration's local buffer; it is not durable success.

Sixteen common tests cover copied input/output, bounded work, overflow, replay after an unconfirmed
write, cancellation, equal-room occurrences, acknowledgment and privacy. Four JVM tests exercise
real threads racing callback/ACK, duplicate ACK, bounded callback bursts and disposal. Native
TaskHandle ownership and storage-before-ACK remain separate contracts.

This component performs no network, SDK acquisition, storage, scheduling, root/session admission
or key import. It is not connected to the native listener or the application worker yet. A future
consumer must confirm durable recovery demand before acknowledgment and retain failed delivery
under a bounded retry owner. Registration reconciliation and startup/inventory handle process loss;
this buffer alone does not provide crash durability. Main already uses SDK8; this change does not
modify that dependency, live services, the Starter or the install-only SDK6 release89 artifact.

[Exact validation evidence](measurements/collaboration-sdk-room-key-mailbox-2026-11-12.json).
