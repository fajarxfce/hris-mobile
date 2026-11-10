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

Two regression assertions fail without the recovery call (red46986,87.46s), and the
15 new domain cases plus24 existing original-delivery cases pass with it. Complete
Android/app/quality validation and the deterministic native successor are still running
or pending; no finished fix, new APK or live rollout is claimed yet. The successor
fixture blocks the second send until the first app receipt is durable, waits for both
SDK rows to disappear with only one app receipt, then kills the process before projection.
All54 communications acceptance groups remain open.
