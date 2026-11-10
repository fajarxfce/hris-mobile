# Original receipt lost after SDK dequeue

The earlier native51720 failure is traced to a retained-storage gap. A read-only clone
contains two original application commands, one receipt and zero pending or dependent
SDK transactions in both original stores. Command revisions are3 and2. Original image
hashes are unchanged. No application was launched, APK installed or message sent by
inspection. The dedicated2560MiB/two-core emulator is stopped.

The inspected SDK send path commits removal of a successful request before broadcasting
its asynchronous SentEvent callback. The application journals that callback separately.
A hard process death between those operations can leave a Staged application command
with no SDK original and no application receipt. Group delivery reconciled its journal
and still-present SDK originals, but had no original-history recovery for this case.
An empty SDK queue correctly did not invent an acknowledgement; the missing owning
recovery path left the message unresolved.

[Inspection evidence](measurements/collaboration-original-lost-callback-2026-11-10.json)
contains only counts and hashes. The old outcome reason remains unavailable, but the
storage gap and missing recovery are established. The copied archive had two known
stderr diagnostics; only exact lines at tar block boundaries were removed, then all
headers/end blocks and SQLite quick checks were validated. Raw evidence is retained.

The active implementation adds recovery only for disappeared pending originals. It
first resolves exact acquired local history, then one bounded original SDK window
when needed. Logical runtime, original physical binding/device/room, sender, logical ID,
complete original-content hash and text must agree. It appends a receipt before normal
projection, without a new message, SDK transaction, key or automatic retry. Ordinary
queued messages do not acquire history. Replacements and redactions alone do not prove
an unknown original. The bounded recent window is not complete older-history ingestion.

Two regression assertions fail without the recovery call (87.46s). The final focused
check passes70 cases; the full check validates6,338 cases, including4,521 fresh and
1,817 exact reused reports. All4,651 Mobile source inputs, Inspector sources and
archived/current reports match. Required root, Android host/release/lint, Wasm/iOS
source, browser, proof compilation, formatting, architecture and detekt checks pass.
The backend fixture compiles with8,734 stable inputs;15 Python driver cases pass.

The deterministic Android successor passes both native cases in208.523s. The first
provider acknowledgement is journaled, the second encrypted send is released, both
SDK transactions disappear while only one app receipt exists, and the process is
hard-killed. Recovery retains both original transactions, obtains the missing original
history evidence, and projects two Sent records from exactly two provider events.
The measured recovery invocation takes544ms in this fictional isolated fixture;
it is not a phone send-latency or throughput claim. A later offline process reads both
records before transport. The original SDK6 artifact and keys remain unchanged.

The existing group worker/pusher case also passes:25 native and17 Matrix requests,
exactly one managed-pusher request, two original provider events, no401/403/server
errors and no additional direct transport for a fresh idle sweep. Native source/APK
hashes match; no compiler ran during the2560MiB/two-core emulator, now stopped.

The first successor passed original recovery but failed the worker handshake. The
shared driver waited for an original-only first-ACK marker before relaying the group's
listening/control markers. Each scenario now owns its interruption checkpoint;
original callback-loss and group durable-claim assertions are both retained. The same
Mobile source/APK passes the corrected successor. Failed userdata remains preserved.

[Acceptance evidence](measurements/collaboration-space-original-receipt-recovery-2026-11-10.json)
contains counts, source hashes, boundaries and the failed-driver explanation. Hosted
Apple acceptance for this source remains separate. No product APK, live group rollout,
cloud callback or new iOS native runtime is established. Complete older/group incoming
history, producer wakes, rich media, backup, calls and meetings remain. All54
communications acceptance groups remain open.
