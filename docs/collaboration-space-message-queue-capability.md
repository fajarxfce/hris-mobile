# Installed Space message queue capability

A new staging-only lease captures the already installed Space runtime and its exclusive,
paused physical Matrix writer. It exposes canonical text preparation, complete original
queue inspection and one guarded enqueue. The separate original-only lease retains its
existing API; it cannot enqueue and is not castable to this capability.

The Android runtime shares one private acquisition resource between the two wrappers.
Acquisition does not install another client, select a new key, join a room or start sync.
Each wrapper owns its acquired child and delegates pause/drain/release to that child.
Retirement, cancellation and late failures retain the physical writer's lifetime.

Before enqueue, the adapter detaches the caller's expected inventory, validates the exact
runtime/binding/device and bounded unique transaction set, and compares it to the complete
paused native inventory. Changed captures and an already present logical message reject
the enqueue. It checks retirement again after suspension. A failed response cannot prove
that the SDK did not save the original; the owning staging usecase must retain Staging
before enqueue and recover the original mapping afterwards. The raw adapter does not own
admission, recipient readiness, command policy or retries.

Android host regression tests cover exclusive writers, changed/duplicate/full captures,
caller mutation, concurrent operations, retirement, lost responses, cancellation and
close/drain. They reuse the real queue ownership and inventory machinery. Raw FFI text
enqueue is replaced by a host fixture; these tests do not establish native enqueue
acceptance. Unsupported targets report the explicit unavailable result and propagate
cancellation. Apple compilation and fallback tests do not supply the missing Apple Matrix
runtime binding.

See [local acceptance](measurements/collaboration-space-message-queue-capability-2026-11-10.json).
The next owning usecase must stage exact Space intents before mixed delivery. New-room
provenance, complete pending-command inventory, native proof and product routing remain
required. This capability does not enable a new UI path or live sender.
