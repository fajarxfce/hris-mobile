# Chat first-frame investigation — 6 November 2026

The user reports that the room transitions to a blank body before cached bubbles
appear, despite successful offline reading. This is reproduced on the retained
Bima emulator with the same release40 as the phone. No phone interaction or new
message publication was performed. [Baseline measurement](measurements/collaboration-chat-first-frame-baseline-2026-11-06.json).

| Trial | Header-to-bubble gap |
|---|---:|
| Offline1 | 500ms |
| Offline2 | 433ms |
| Online1 | 533ms |
| Online2 | 367ms |

These are video-region measurements at30fps (about33ms granularity), not tap
latency or physical-device benchmarks. All four contact sheets were inspected;
adjacent first-bubble frames were separately inspected for offline1/online1.
Offline trials retained8 messages. Online synchronization added one previously
unseen message before the final trial, so do not claim an identical dataset there.
The original emulator connectivity/touch settings were restored and its process
stopped with data/keys retained. Videos/screenshots stay in private .work storage;
Git evidence omits message contents.

The blank interval occurs with the network unavailable. Code inspection identifies
serial local context acquisition before history observation, repeated ownership/
workspace reads across independent subscriptions, and a history/outbox combine
barrier. Workspace reads currently decrypt the same protected selection repeatedly;
this is a candidate contributor, not yet a timed root-cause attribution. Thread
entry already uses STARTED lifecycle, so waiting for RESUMED is not its start gate.
The newer editor also waits for local edit outcomes before first projection.

Next: consolidate initial local thread acquisition in an owning domain use case,
using scoped repository reads and shared pure context policy. Preserve fresh
owner/admission checks before returning a combined immutable snapshot; do not add
an unscoped UI cache or weaken key-loss/account-change behavior. Independent local
reads can overlap. Verify missing context, storage failure, cancellation, account/
company replacement, denial and late responses. Measure the actual resulting
Android build with the same capture method before claiming improvement. Native
storage/decryption profiling is still pending; no performance fix is accepted yet.

This work continues alongside room-preparation ownership cleanup and the complete
chat/call/meeting PRD. PrepareCollaborationRoom focused gate2 passed81 tests/static/
architecture checks; full room-full1 passed5,428 fresh tests/674.163s with lint0. The editor's failed-change copy
focused gate passed131 tests. Neither has a new release/phone installation yet.
