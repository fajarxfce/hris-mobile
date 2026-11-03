# Protected input before an account session

`CollaborationNotificationInputRepository` protects a notification reply before its
account session or history key can be opened. It stores an unverified token and text
under a separate device key. Saving this input does not authenticate a notification,
open company content, prepare a command or authorize a send. Consumers must authenticate
the descriptor for the actual current account before moving input into account-owned
retention or showing it in account UI.

The raw [device record store](device-records.md) owns atomic insertion and exact
deletion. HRIS uses the `hris.notification-input.v1` namespace and a global capacity
of64 records. Nothing expires or evicts typed input. A keyed SHA-256 HMAC over a
domain separator, exact token and exact text supplies the opaque input ID. A new
candidate gets a random generation. Existing IDs return their original ciphertext
and generation even when a duplicate candidate used another encryption nonce.
Changed text or a different descriptor creates another identity.

The protected `collaboration-notification-input-key` record contains independent
32-byte content and index keys. Creation uses atomic `writeIfAbsent`, including
competing connection owners. Missing keys are never regenerated over existing
records. Before retaining more input, an existing record authenticates that the
current key belongs to this namespace; malformed or wrong keys leave the queue
unchanged. These keys have no rotation or logout deletion path.

Android uses the existing no-backup credential adapter and Android Keystore. iOS
supplies a separate Keychain client. Desktop uses its OS credential store even when
session persistence is disabled; this device input must outlive a session. Browser
uses protected IndexedDB credential storage with a non-extractable WebCrypto key.
The existing platform security limitations still apply. Memory credential defaults
support isolated fixtures; actual hosts explicitly supply the protected record.
Container disposal closes the key adapter, without deleting the retained key.

The existing AES-GCM provider creates random nonces and full authentication tags.
Associated data includes namespace, input ID and generation. The encrypted DTO
contains schema1, token and text. Opening it validates the schema, bounded canonical
token encoding and text, then recomputes the HMAC identity. Plaintext is bounded to
64KiB, ciphertext to96KiB and encoded payload to128KiB. Keys and plaintext buffers
are cleared after use. All public private-input values redact their diagnostics.

Syntax validation and canonical v1/v2 URL-base64 decoding precede key creation.
They do not verify the signed descriptor. Text follows the existing chat limits:
16,384 UTF-16 units and32,768 UTF-8 bytes, nonblank, with tabs and line breaks allowed.
Unknown schemas, malformed encoding, relabelled ciphertext or changed input do not
become usable records.

Retention reports success only after decrypting the actual retained record and
confirming an exact raw readback. An uncertain write or cancellation may leave a
durable record; replay recovers that original. Exact removal authenticates the stored
input and compares its complete raw record. It cannot delete a later generation.
Ordered paging returns at most20 opaque references without opening the key; it never
exposes unverified text to a notification or screen. A scan after concurrent insertion
must eventually restart from the beginning.

`ProtectCollaborationNotificationReply` provides a finite eight-second capture turn,
with at most eight concurrent admissions and no session dependency. Invalid input,
capacity exhaustion, contention and uncertain storage remain distinct failures.
Cancellation joins storage work and releases the admission slot. A failed result can
still leave a durable record if the response was lost after a committed write.

`TransferCollaborationNotificationInput` captures the actual account/session parent,
authenticates the original descriptor and retains its original issuing parent in the
account's encrypted reply queue. It rereads the exact account-owned copy before
removing the exact device generation. Parent guards follow each pending boundary.
No caller-supplied receipt substitutes for the stored copy. Replacement, missing copy,
uncertain cleanup and cancellation cannot claim completed transfer. The operation
has an eight-second deadline and refuses a second concurrent turn.

Transfer returns an opaque retained reply ID only after exact cleanup succeeds.
`Absent` means the requested device generation is absent; it is not evidence that a
reply was sent or transferred. Lost copy responses leave both copies recoverable.
Lost cleanup responses can leave only the account copy, which the existing reply
worker can recover independently. Replayed input reproduces the original command.
Current v2 action recovery supports an issuing family that differs from the current
family for the same account; v1 still needs its original family for authentication.
An old family is retained for the existing explicit review path, without a new send.

`CollaborationNotificationReplies.capture` protects the input, then requests the
existing durable recovery wake and scheduler. It returns only the opaque reference,
without account restoration, company admission or Matrix access. The whole callback
turn is bounded to eight seconds, including at most one second for scheduling. At most
eight capture/submission turns can be admitted. Failed scheduling does not undo a
successful capture; cancellation joins pending work before releasing its slot.

`resumeProtected` shares the existing reply execution mutex with ordinary submission
and account-input recovery. Transfer, exact device cleanup, reread and processing all
occur within that finite turn. Another local recovery cannot retire the account copy
while transfer still needs it. Capture remains available while this mutex is held.
An uncertain transfer does not start command processing. The account queue can later
recover an independently retained copy, and repeated capture reproduces its original
operation rather than a new send.

These application methods and use cases are bound in the actual container. The
[input recovery worker](collaboration-notification-input-recovery.md) discovers a
finite snapshot of protected device rows and resumes them before ordinary account
replies. Startup and registered periodic tasks can recover an interrupted enqueue.
[Android callback ownership and ordinary process-death recovery](collaboration-notification-callbacks.md)
now have isolated native proof. Input review UI and complete native action/status
handling remain before production activation. Explicit
discard must coordinate with account transfer and retained commands; deleting a
device row alone cannot cancel an already-copied reply.

Production chat notification hosts remain unbound and chat push is disabled. This is
not the planned Drive/iCloud backup format or acceptance of an OS reply callback.
Local and hosted validation are recorded separately in `docs/measurements`.

[Local validation](measurements/chat-notification-input-2026-11-03.json) includes real SQLite/browser storage, Koin ownership, full root/proof/Wasm, and the preserved empty-browser-filter correction. Hosted Apple for this increment remains a separate gate.

[Transfer validation](measurements/chat-notification-input-transfer-2026-11-03.json)
covers bounded capture, account replacement, cancellation, exact cleanup, response loss
and actual encrypted SQLite/Koin reopen with fictional transport. Native callbacks
and process death have a separate isolated
[acceptance record](measurements/chat-notification-callback-2026-11-03.json).

[Application-flow validation](measurements/chat-notification-input-flow-2026-11-03.json)
adds actual-container capture/wake/reopen, shared-mutex exclusion through cleanup,
independent capture, cancelled recovery, scheduling failure/deadline and response-loss
checks. [Worker validation](measurements/chat-notification-input-recovery-2026-11-03.json)
adds finite discovery, cancellation, failed-prefix fairness, arrivals and account
restoration with actual Koin and encrypted SQLite. The isolated Android callback
proof covers subsequent native delivery and ordinary process-death recovery.
