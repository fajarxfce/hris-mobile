# One incoming worker for logical and physical work

The application retains one serialized incoming task. Domain selection shares four FIFO positions
between logical conversations and physical room hints. A preliminary local read does not acquire
an SDK. After adoption, the worker rereads against the exact installed original store, Matrix user
and device; continuation uses the same selection. Other sources remain pending without an immediate
resume loop. Older logical tasks without sourceId retain their established path.

Known local bindings transfer the exact physical generation, position, head, retry and source to
logical Latest work. Conversion does not acquire provider pages or create another wake. The next
turn processes that logical work. Missing bindings and unfinished prior logical work are deferred:
only the captured physical turn moves, with a retained retry delay from 30 seconds to 5 minutes. Source positions, inventory loss,
scan state and later physical generations survive. Waiting for prior keys cannot repeatedly take
the oldest FIFO slot. The existing worker handles cancellation, native-parent replacement, one
running/one waiting invocation and bounded provider work for both task kinds.

A known failed compare-and-set with an unchanged pending capture returns Unavailable with a
retry hint of 1 second. It is not a blocked conflict or a durable per-room failure. Exact converted/deferred
readback confirms an uncertain reply even after unrelated producer cursors advance. An uncertain
write without that exact witness keeps its storage failure. Current denial and invalid same-generation
mutations remain distinct. Domain owns selection, deferral and confirmation; data owns repository DI;
the app adapter invokes those usecases and the existing provider/content path.

Validation on 11 November 2026: 395 focused desktop cases and 361 browser cases (756 total), followed
by 11,362 full cases =9,324 fresh +2,038 exact accepted unchanged controls. No failures/errors/skips;
all prior case IDs and retained dependency closures verified. Format, architecture across 74 modules, detekt,
Android host/release compile/lint, browser/Wasm, iOS metadata and proof APK assembly/unit tests pass.
The 4,897-input source manifest is b271c2e8fec511dc14f8f09b7c09d4c6a7efd169e66de439de2ad0be06d54274.
Proof APK: 151,626,942 bytes; SHA-256 0a776eac82280283e872e7214e07877be02d5872e4e7a0553ec0368693afb829.
This increment has no new native SDK execution, Apple execution or phone installation.

Finite remote published metadata acquisition now has its owning consumer. Before starting serial
raw collection, complete original Company routing, bounded native room inventory and keys-only recovery. Logical/
scan write confirmation and the Group original physical metadata route are now accepted in their
linked increments. An unpublished Company original is not a Space conversation. The app raw
collector stays inactive; all54 PRD groups remain open. No generic Starter or live deployment
change is introduced by this increment.

Evidence: [measurement](measurements/collaboration-space-physical-worker-2026-11-11.json).

[Logical and scan write confirmation](collaboration-space-incoming-write-confirmation.md) now preserves independent producer progress
without false conflicts or durable backoff for normal queue contention. Raw activation still
requires original routing, inventory and keys-only consumers.

[Physical metadata acquisition](collaboration-space-physical-metadata-consumer.md) resolves missing published bindings with real
conversation metadata and exact retained evidence, then converts freshly loaded demand.

[Typed incoming targets](collaboration-space-original-incoming-target.md) now keep Published and Company-qualified Original tasks distinct.
Published consumers reject Original before I/O; the original consumer and raw collection remain disabled.

[Original metadata and Company history source storage](collaboration-space-original-room-metadata.md) are now locally validated.
The original consumer and its current-source/content admission remain pending before raw activation.
