# Native read-receipt transport

Android's installed runtime now has finite public/private receipt operations and
an own-receipt read from the SDK store. The common runtime contract now
exposes these operations, and finite admitted replay consumes durable local read
intents. Product worker/UI and notification invocation are still separate work. The caller must
admit the current owner/device/room and durably retain its read intent before a
write. Receipt timestamps never supply application event ordering.

Each invocation borrows one already-known encrypted room from its current parent.
Acquisition records ownership before validation, so a wrong room or failed check
still releases the acquired native handle. A separate lazy request starts only
after the caller survives acquisition. Caller cancellation, parent closure, a
credential failure or the ten-second operation budget interrupts the request.
Cleanup cancels and joins that request before releasing its room. A failed join
or room close stays parent-owned for a later cleanup attempt; no request is retried.

The runtime uses `Room.sendSingleReceipt` with `ReceiptThread.Unthreaded` and either
`READ` or `READ_PRIVATE`. Own metadata uses `Room.loadUserReceipt` for both types,
returning event IDs without choosing which event is later. There is no sync start,
room join, enrollment, message-queue activation or credential refresh in this
adapter. Ordinary SDK expiry does not authorize deleting or hiding separate owned
history, drafts, pending commands or future local read-state records.

The eight Android host cases cover exact types/ownership, invalid target denial,
malformed observations, caller cancellation, parent closure, technical timeout,
cancellation during late room acquisition and retained cleanup failure. They use
SDK doubles for precise lifecycle ordering. The owned native proof separately
requires real HRIS/Synapse receipt requests, peer-private isolation and SDK-store
persistence through process death before sync restarts. [Evidence](measurements/chat-read-receipt-client-2026-11-02.json) records the passed
109.111s real HRIS/Synapse/Android proof: five accepted receipt requests (three
private/two public), peer-private isolation and retained own metadata in the third
OS process before sync. Existing encrypted exchange, held-queue isolation and
three process-death recovery stages still pass. Full root/proof build275.337s
passes3,262 desktop/230 Android host/10 build-logic/six proof-host cases, including
explicit reused reports;1,683 cases/218 suites executed freshly. Initial focused
check97.483s passed all eight host cases but failed a101-line proof method; the
completed-exchange evidence builder was extracted before the successful full run.

`DeliverCollaborationReadReceipts` processes a bounded snapshot of one to eight
retained intents (four by default) within20seconds, with shared try-lock exclusion.
It validates exact local coverage and acquired history, then checks the current
HRIS owner, active device/platform, conversation pair and identical mapped room.
A second pending-intent read after that admission prevents a removed intent from
starting transport. SDK success is followed by another current device/room
admission before acknowledging only the identical queued entry. A concurrent new
point remains queued. Confirmed server denial stays buffered even if saving that
denial fails. Other failures preserve the intent; only an explicit later invocation
replays it. There is no SDK enrollment, join, sync start or local ordering inference.

The actual SDK proof marks two acquired originals through the local use case,
reopens the encrypted database, sends a private receipt through common replay and
interrupts its local acknowledgement. A subsequent real OS process sees the same
pending intent and exact coverage, replays it, retains its ACK over another database
reopen and performs a no-op repeat. Existing private-receipt isolation, protected
SDK recovery, three encrypted sends and the other process-death stages also pass.
[Evidence](measurements/chat-read-receipt-replay-2026-11-02.json): native113.216s,
seven HTTP200 receipt requests (five private/two public),133 Mobile and25 Platform
input hashes unchanged; fixture/package cleanup passed. Owned emulator5562/PID790095
was stopped afterward. No phone work occurred.

Twelve sender-policy cases plus two assembled-container cases pass133.705s. Full
root/proof assembly and twelve browser cases pass292.360s, with3299 desktop/230
Android host/10 build-logic/six proof-host cases including explicit reused tasks;
1732 cases/222 suites execute freshly, zero failures/errors/skips. Twenty-seven
Python driver cases pass0.104s and reject missing/non-Boolean recovery claims.

Product worker/foreground invocation, notification suppression/actions, privacy/mute
preferences and multi-device receipt reconciliation remain. A read receipt is
separate from recipient durable delivery ACK; neither wall-clock ordering nor one
receipt proves an unseen gap was acquired. No whole communications feature closes
here, and live chat push remains disabled. New common code still requires hosted
full Apple/Xcode coverage; a physical iPhone test waiver does not supply that result.
