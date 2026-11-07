# Original queue facts under organization ownership

`CollaborationSpaceRuntimeRepository.inspectOriginalQueue` reads an already installed
runtime using its exact organization, native parent, device and bootstrap operation.
It returns a snapshot, without exposing a legacy runtime or a send capability. A stale
or retired logical identity cannot obtain the resource or publish a late result.

The snapshot retains the original physical binding, device, message bootstrap and
exact native transaction content. These remain separate from the adopted organization's
device and bootstrap. Nothing encodes an organization as an HR company or rewrites an
old transaction to fit a newer session.

The existing per-runtime queue owner acquires the same exclusive writer used by
delivery. While the queue remains paused, it compares complete captured identifiers
before and after reading the original records. Missing, extra, duplicate, changed or
oversized records withhold the snapshot. The bound is 128 messages; existing content
validation and fingerprints remain mandatory. An empty queue is a local fact, never
evidence that any message was accepted by the server.

Cancellation cannot release the writer before late native work drains. The child is
closed after every attempt. Failed cleanup remains owned and withholds a successful
result; it cannot replace an earlier read failure or cancellation. Parent restrictions
and credential failures suppress late results, and the repository rechecks the exact
logical resource after the child read and cleanup.

This operation does not open a store, join a room, start synchronization, enqueue,
settle, retry or resume delivery. It does not replace retained offline history with
an SDK acquisition. An owning use case must separately admit publication into the
organization projection and match these facts to immutable application commands.

[Validation](measurements/collaboration-space-original-queue-2026-11-07.json) records
the host regressions and complete local application checks. The host queue is simulated;
actual old pending-transaction adoption and delivery remain separate native integration
work. No product caller, group activation or physical-phone latency claim is added.
