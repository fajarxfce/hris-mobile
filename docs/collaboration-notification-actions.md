# Encrypted notification action envelopes

The action-envelope repository seals and opens bounded native IPC metadata with the
retained history content key. It supports reply, mark read, mute and dismissal
descriptors. The encrypted target binds company, conversation, room, original event,
native notification ID and render ID; each action carries a stable operation UUID.
There is no reply text or transport credential in an envelope.

Authenticated data includes the API audience, account, local session and remote
native session. Same-account reauthentication changes that binding. A receiver must
capture the current native parent from session state, never take an owner from Intent
extras. Opening authenticates the envelope and captured family; it does not admit a
company, room, history item or write. Those checks remain required in the action
handler around pending operations.

The format is `v1.` followed by canonical URL-safe Base64 ciphertext, limited to 4096
characters. Plaintext is bounded to 3072 bytes and decoded fields retain the existing
UUID, Matrix ID and revision bounds. Unknown versions, kinds, preview values,
incomplete observed versions and mute-only fields on other actions reject. Cipher
authentication covers modifications and native-parent substitution. Cancellation
propagates; temporary plaintext/key byte arrays are cleared through existing key
ownership. Missing or changed keys never generate replacements for an action.

Sealing uses a fresh cipher nonce. The issuer must persist the exact envelope before
native publication and reuse it after uncertainty. Sealing the same descriptor again
produces a different envelope with the same logical operation; this primitive is not
an action receipt or a one-use guarantee.

Consumers should reuse the existing durable commands. A reply's operation UUID must
remain stable through interrupted enqueue and repeated callbacks. Mark read should
inspect exact existing coverage before adding another receipt intent. A mute action
carries the observed settings revision and preview choice, so replay cannot silently
overwrite a later preference. Dismissal and transient UI updates must match the
original render; an old action must not clear or overwrite an unrelated newer card.

[Current-family admission and local command dispatch](collaboration-notification-invocations.md)
now consume these descriptors through the existing durable commands. The display
journal, admitted issuance, native PendingIntent/RemoteInput integration, failed-reply
handling and OS display/cancellation remain pending. No Android receiver or UI action
is activated by this primitive. Decrypted chat content
must not enter the generic plaintext notification history. No database/key migration,
public read-receipt preference change or live chat push activation is included.
