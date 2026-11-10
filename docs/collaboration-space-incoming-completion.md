# Retained incoming-room completion

A metadata page count alone cannot advance a durable room task. Space page acquisition now
returns the captured runtime, traversal, origin and exact before/after checkpoints. After one
atomic page write, the owning usecase reads the retained checkpoint and validates its revision,
physical provider, reference count, pagination position and independent pending-reference seek.
A lost readback preserves committed facts and returns uncertainty without reissuing the page.
Saved exhaustion remains a local read. The Company acquisition result is unchanged.

AdvanceCollaborationSpaceIncomingProvider admits the current local account and directory, then
requires that exact receipt before moving Latest to Provider/Pending or Provider to Pending.
An exact already-applied phase is idempotent. A newer room generation survives late work;
changed facts under the same generation are rejected. No phase change acknowledges a wake.

CompleteCollaborationSpaceIncomingRoom requires Pending, the current binding, provider EOF and
an empty pending-reference index. A known captured event must also have an exact resolved source
under the same checkpoint. Pagination tokens are never compared with message IDs. One exact CAS
and readback remove only the captured room; lost responses, cancellation and newer requests keep
their durable evidence. Ordinary credential age does not hide owned local facts; current denial
and account replacement still stop work. These local owners have finite five-second budgets.

The incoming worker must serialize provider writes and completion. Queue producers can race;
the exact demand CAS preserves newer generations. These factories do not yet enable that worker,
native hints, UI behavior or live deployment. The incoming wake stays retained until an admitted
worker verifies the entire queue and rescan state, separately from outgoing acknowledgement.

Validation covers 16 completion, 21 phase and five additional acquisition scenarios; all 24
previous acquisition cases remain. Focused JVM/Chromium validation passes 336 cases in
337.89 seconds. Full validation passes 10,649 cases in
659.139 seconds (8,630 fresh and
2,019 unchanged controls), including root checks, Android host/release/lint,
Wasm/iOS source compilation, actual Chromium, proof build/tests and quality gates. All 4,821
inputs and library sources remained unchanged. The first focused run exposed a boxed Int/Long
comparison for initial revision zero; the policy now uses an explicit Long zero. Existing
first-page and phase cases reproduce that failure and pass after the correction.

The four prior actual Matrix scenarios also pass in 228.267 seconds on a fresh
2,560 MiB/two-core emulator, which was stopped afterward. They preserve original/mixed sending,
pending-reference completion, offline reopening and existing request budgets. The proof host
now checks exact retained receipt identity as well. This does not measure phone latency or
prove an integrated incoming worker. The immutable Matrix SDK remains version 6.

[Focused evidence](measurements/collaboration-space-incoming-completion-focused-2026-11-10.json),
[full evidence](measurements/collaboration-space-incoming-completion-full-2026-11-10.json), and
[actual native evidence](measurements/collaboration-space-incoming-completion-native-2026-11-10.json).
