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


## History acquisition signals

The same remote provenance now controls passive history acquisition. An SDK local
echo changing from pending to failed or acknowledged still updates the screen, but
does not request original history. Even a local echo with a server event ID is not
a remote original. Volatile row keys, local transaction metadata and delivery state
do not invalidate an otherwise identical remote event; timeline reordering or
window removal alone also does not request another history window.

New remote events, edits, redactions, decryption recovery and changes to authenticated
content coordinates still request history. Explicit entry, recovery and acquisition
retry keep their existing signals. The pure domain policy compares two snapshots
from the same leased room; it is not an authorization cache. The ViewModel still
projects every changed SDK snapshot and forwards only relevant history signals.
A successful local enqueue also no longer asks the connected screen to acquire history:
the queue already schedules delivery, and remote content signals its eventual original.
A disconnected screen can still reconnect. No request permission, receipt, durable command,
cursor or server route is changed.

Before the correction, two focused screen regressions fail: four local status changes
produce four extra history acquisitions, and an acknowledged local echo produces
one extra acquisition. The final focused gate passes167cases, including local enqueue
without redundant history work. The complete gate passes5,618fresh cases with zero
failures/errors/skips, nine actual desktop and nine Chrome policy cases, Android
compile/lint0, shared Wasm/iOS source and quality checks.
[Exact validation](measurements/chat-history-signals-2026-11-08.json).
Build63 device request counts and send timing remain separate acceptance.
