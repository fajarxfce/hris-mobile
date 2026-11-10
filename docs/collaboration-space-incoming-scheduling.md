# Durable incoming scheduling

Incoming room discovery and retry state belong to the account/space-owned encrypted queue.
This capability supplies local scheduling operations for the incoming worker. The application
worker, realtime hints and phone delivery are separate integration steps.

## Ownership and storage

The domain Read usecase admits the current local account, captures an immutable queue, then
selects up to four eligible FIFO rooms at one clock instant. It performs no SDK/network I/O,
writes or scheduling. Repositories coordinate raw storage; the worker will own task/parent
lifetime, while retry, discovery and exact completion stay in their domain usecases.

Discovery reads at most four raw binding entries, limited by the free slots in a 64-room queue.
Cursor and newly discovered rooms commit in the SAME encrypted document transaction. A lost
reply cannot leave an advanced cursor without its work. Existing rooms preserve phase, head,
generation and retry. A full queue reads no inventory. Empty pages may advance a validated
cursor; a newer rescan survives the older scan's completion. Appended binding revisions retain
another scan instead of pretending the earlier boundary covered them.

Schema 1 remains readable. Schema 2 adds optional scan and retry fields; the cursor payload stores
only its raw generation/revision/index boundary, with the namespace reconstructed from the
account/space-authenticated document. The existing 65,536-byte plaintext and 131,072-byte encoded
limits remain unchanged. Maximum 64-room multibyte heads plus retry/scan are tested against the
real store. Generic ReplicaStore formats and checkpoint capacities are unchanged.

## Retry and completion

Deferral moves an exact captured room behind the current FIFO boundary. Transient failures retain
30, 60, 120, 240, then 300-second eligibility; repeated unrelated wakes cannot bypass it. A successful
provider phase clears its earlier failure delay. A backward clock change permits recovery rather
than hiding work indefinitely. Invalid time, retry, cursor and foreign namespace fail explicitly.
Newer requests survive old deferrals and lost-response replay never moves the same room twice.

An incoming wake is independent of outgoing recovery. Scan/phase/deferral writes preserve it.
New request producers atomically retain changed demand and a fresh wake. The Finish usecase
requires the current native root, local account admission, empty queue and no active/rescan demand
before acknowledging the exact captured wake. A later wake survives that acknowledgement race.
Only one incoming worker may serialize discovery, provider writes and completion.

## Integration still required

The application draft interleaves one provider page with one content page, avoiding an initial
history download before messages can be retained. It needs an admitted delayed Latest recheck
when a requested source is absent even though the provider reported EOF. Otherwise a Pending
room could repeat forever. Application lifecycle, current-parent SDK hints, push handoff,
overflow recovery and actual native-worker execution must pass before activation.

Existing SDK syncOnceV2 provides changed room IDs; its response does not expose event identities.
Do not invent a known content head from receipt/typing updates or assume a room-info preview
covers edits/redactions. Choose a bounded, correctly scoped hint handoff using verified SDK
capabilities, outside the SDK monitor, with startup recovery and no additional sync/client.

## Validation

The companion focused and full measurements record the accepted source fingerprint and tests.
Focused JVM/actual Chromium covers 196 domain and 71 real-store cases per target, plus two application binding tests. They include scan
pagination, atomic cursor/queue retention, lost replies, cancellation, racing producers, old
formats, capacity, retry deadlines, local ownership and exact wake finalization. Full checks also
cover application DI, Android host/release compilation and lint, Wasm/iOS source compilation,
proof-app compilation/tests and repository architecture/format/detekt checks.

Hosted completion run 42889565705 targets earlier 07e22182 and does not validate this increment.
No new native emulator or physical-device acceptance is claimed by these local scheduling tests.

[Focused evidence](measurements/collaboration-space-incoming-scheduling-focused-2026-11-10.json) and
[full evidence](measurements/collaboration-space-incoming-scheduling-full-2026-11-10.json).
