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

This repository is the storage boundary. Authenticated transfer and completion use
cases, callback ownership, worker scheduling, input review UI and native notification
input process-death tests remain separate work. In particular, transferring must first
prove account-owned durable retention before deleting the device copy. Session changes,
unverified account ownership and uncertain responses cannot be interpreted as a send.
Current v2 action recovery supports an issuing family that differs from the current
family for the same account; v1 still needs its original family for authentication.

Production chat notification hosts remain unbound and chat push is disabled. This is
not the planned Drive/iCloud backup format or acceptance of an OS reply callback.
Local and hosted validation are recorded separately in `docs/measurements`.
