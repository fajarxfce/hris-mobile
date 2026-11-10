# Independent incoming demand

Incoming demand records one pending task per conversation, with a stable generation, FIFO
position, optional requested provider head and explicit Latest/Provider/Pending phase. The
pure domain policy keeps these scheduling facts separate from content, runtime admission,
database transactions and outgoing delivery recovery.

A repeated known head preserves the task's position and phase. A newer head replaces its
generation; late acquisition, deferral or completion of an older capture leaves that new
task untouched. Changed facts under the same generation are rejected. A repeated unknown
scan attempt preserves its generation, while a new unknown scan explicitly requests Latest.

Provider acquisition advances Latest through finite Provider continuation to Pending.
Missing-key deferral preserves the current phase and moves behind the captured FIFO turn
boundary, allowing other rooms to proceed. Only a separately admitted owning completion
may remove Pending work after checking real provider exhaustion and unresolved sources.
The policy does not grant authority to acknowledge either scheduling wake.

At most 64 room tasks are retained. Overflow preserves all existing rooms and records a
separate rescan generation. An old completed scan cannot clear a newer overflow/startup
request. Clearing a scan leaves room work intact; completing every room does not clear a
scan. Position bounds and unique room/generation/order coordinates reject corrupt captures.
Results copy the input room list and diagnostic strings omit room/event coordinates.

Focused validation passes 124 cases in 213.856 seconds on JVM and actual
Chromium: sixteen new demand scenarios plus existing pending-reference and recovery controls
on each target. iOS domain compilation and formatting/architecture/detekt pass. Full validation
passes 10,479 cases in 749.875 seconds, comprising
8,460 freshly executed cases and 2,019
exact accepted controls. Root, Android host/release/lint, Wasm and iOS application source,
actual Chromium, proof build/unit tests and quality checks pass with 4,800 stable inputs.
[Focused evidence](measurements/collaboration-space-incoming-demand-focused-2026-11-10.json)
and [full evidence](measurements/collaboration-space-incoming-demand-full-2026-11-10.json).

[Encrypted atomic demand/wake storage](collaboration-space-incoming-demand-storage.md) is validated.
Admitted queue/worker integration remains next.
An owning request must exclude an already-retained idle provider head before queuing it.
Startup scanning requires its own durable cursor/claim and must consume work when the queue
is full. Missing-key retries must not start an all-room metadata scan. SDK hints retain the
existing parent owner; this policy enables no new client, sync, poll, consumer or UI behavior.
The earlier actual pending-reference SDK proof remains independently accepted.
