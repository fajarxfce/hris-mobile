# Native read-receipt transport

Android's installed runtime now has finite public/private receipt operations and
an own-receipt read from the SDK store. These are data-layer operations; they do
not yet mark the product history read or publish a notification. The caller must
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

Product integration still requires encrypted local read intents, exact observed
message coverage, admitted replay, privacy/mute preferences and notification action
ownership. A read receipt is distinct from the recipient's durable delivery ACK.
Neither wall-clock ordering nor a receipt for one event proves an unseen history
gap was locally acquired. No whole communications feature is accepted by this
transport adapter, and live chat push remains disabled.
