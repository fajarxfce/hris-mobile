# Original outgoing row during remote echo

An actual release53 send from the original Bima account temporarily displayed the
same text in a checked server bubble and a pending local bubble. The recording is
private; [bounded observations](measurements/chat-self-send-baseline-2026-11-07.json)
record the actual test and its timing limits. No second explicit send occurred.

The Android mapper used the SDK's exclusive EventOrTransactionId enum as the sole
transaction source. Once that enum switched to EventId, transactionId became null.
The row projection requires the original transaction as well as device, sender,
ownership and logical message identity; the original test fixture supplied both
IDs, unlike this adapter output.

The bounded common JSON mapper now retains unsigned.transaction_id separately from
org.hris.message_id. Only a string of1–512 characters without controls is accepted;
a missing logical ID is never manufactured from the transaction. The Android
mapper preserves the enum's local transaction and uses this metadata for remote
echoes. An exact already-persisted Sent event ID can also match a remote echo when
unsigned transaction metadata is absent. A contradictory transaction still rejects
the match. Original sender/device/native-session/bootstrap/room/logical checks remain.

This changes presentation correlation only. It never writes a receipt, changes the
queue, creates an ACK from matching text, resends a message or replaces original
keys/transaction IDs. Missing both transaction and durable receipt remains uncertain.
SDK4 and its immutable native artifacts remain unchanged. Editing/redaction projection
is covered by the existing related regression cases.

Focused74451 passed33 cases/3 suites/157.361s. Full83806 passed2,343 fresh cases/
319 suites/519.474s; other unchanged task results were reused. Both had4,412 unchanged
inputs and zero failures/errors/skips. Root check, Android host/release lint,
formatting, architecture, Detekt, shared Wasm and33 explicitly verified Browser
cases passed. All jobs are reaped. [Exact source and reports](measurements/chat-remote-echo-2026-11-07.json).

A new phone build and actual-device confirmation remain next; these local tests do
not establish the final send latency or keyboard/viewport behavior. Relevant Apple
source CI remains required. No claim of runtime correction is based on tests alone.

## SDK local echoes after transport acceptance

Release54's original offline send produced a second outgoing row during recovery.
The SDK can attach a server event ID to a local echo before remote JSON and its
original logical/transaction metadata exist. Event-ID presence is not proof that
an item is a remote echo. The Android adapter now carries the SDK's `isRemote`
provenance through the DTO and domain entity as `localEcho`.

The final unmatched-row projection excludes these local echoes. Durable original
commands supply outgoing rows; exact correlated native evidence still supplies its
proven status. Incoming remote messages and archived original history remain visible.
This does not guess identity from text, grant cancellation, change delivery or fake
an acknowledgement. It uses the existing immutable SDK4 API.

Focused36539 passed35 cases/3 suites/183.799s. Full90580 passed3,784 fresh cases/
459 suites/609.149s on4,412 unchanged inputs, zero failures/errors/skips/lint. Root,
Android host/quality/shared Wasm and35 actual Browser cases pass. Both jobs are reaped.
[Source hashes and verification](measurements/chat-local-echo-2026-11-07.json).
No APK from this increment is accepted yet; offline renewal and composer behavior
still require the separate fixes and actual-device acceptance.
