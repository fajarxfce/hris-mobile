# Group thread state and composer ownership

`SpaceChatThreadViewModel` owns independent local history, draft/outbox and exact-message
progress subscriptions. Pending reads cannot hold up the other publications. Its typed
Group access remains independent of HR company selection and expired credentials.
Confirmed account or Group denial cancels its readers and clears private screen state.
The existing serial editor now accepts actual Company or Group ownership and captures
edit time once across retry. It keeps internal cleanup after cancellation while suppressing
callbacks to a disposed parent. Domain use cases still own admission, storage and recovery.

The pure composer projection retains originals across independently emitted outbox and
command snapshots, including a command already archived before the active inventory emits.
It preserves exact immutable content, current gesture order and recent terminal visual
identity. Group records and Original Company records keep separate ownership. Actual room,
provider event and immutable origin evidence is required before history represents a sent
Group original. Absent records keep originals pending; stale inventory cannot replace later
record evidence. These are presentation choices, never durable acknowledgements or deletion.

Typed events keep newer input available during older local writes. Back flushes the local
draft and stays in the editor after failure; retained-route re-entry clears leaving state.
Parent cancellation, late callbacks, explicit retry, lost handoff replies, cold outbox
continuity, pending progress and namespace collisions are covered by actual use-case tests.

Validation: 45 focused cases passed. The full audit accepts 15,401 cases: 1,589 newly
executed and 13,812 unchanged cases whose report and source hashes were verified. All 15,090
preceding local identities are
retained. The increase includes 64 new JVM/browser executions and 247 existing presentation
browser cases previously verified only in hosted CI; 279 presentation browser cases now run
locally. Exactly 109 unrelated historical XML reports/642 cases remain excluded. Android
release compile/lint, source checks, Wasm/iOS compilation and iOS test-source compilation
pass. The 32 new shared methods still require actual hosted Apple execution. Four rejected
candidates remain preserved; no quality limit or existing assertion was weakened.

This accepts the screen-state integration. The actual Group page, navigation activation,
contextual actions and rendering/IME measurements remain subsequent work. No Group live
rollout, new native execution, phone install or measured UI latency is claimed here.

See [compact evidence](measurements/collaboration-space-thread-presentation-2026-11-13.json).
