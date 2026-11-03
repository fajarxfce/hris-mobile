# Retained notification reply input

Notification reply input has a separate encrypted account-owned replica under the
history-key namespace. It retains typed text, the exact token, decoded reply context
and original native family. It does not overwrite the conversation composer or use
the shared employee-command queue for bookkeeping. Up to 64 active pending/review
inputs are retained; capacity returns an explicit result and never evicts unsent text.

The encrypted checkpoint authenticates the active document IDs, exact revisions,
local creation sequence and review status. Each document authenticates those fields
with its content. Reads reject missing referenced documents, changed ciphertext or
index/revision labels, and recheck the checkpoint after pending I/O. Document changes
and checkpoint membership commit atomically through the existing replica CAS. Creation
sequence is only local storage order, not provider message order or an ACK cursor.

Keyed IDs bind native parent, reply descriptor and text. Repeating that input recovers
the same record and its original exact token, even if the descriptor was sealed again
with another nonce. Changed text produces a separate record instead of overwriting the
first operation. Review/removal requires the exact retained version. A late removal
cannot erase a newer reviewed or re-created input with the same ID. After removal,
a duplicate callback may retain input again; the existing immutable message operation
is still responsible for preventing another send. This replica is not a second send
queue or an exactly-once callback receipt.

The retained history content/index key protects the record; this path never creates
a replacement key. AAD binds account/audience, generation, document identity and
revision/order. Same-account reauthentication can read retained private input while
its original native family stays recorded. That does not make its old token a current
send grant. Existing storage ownership keys and revisions remain storage metadata;
reply text, action context and tokens are encrypted, and document IDs use keyed hashes.

Input uses the existing message bounds: 16,384 UTF-16 units and 32,768 UTF-8 bytes.
Schema2 adds an optional working draft or immutable confirmed resolution; it stores
at most the original plus one working/confirmed body. Schema1 remains readable.
Encrypted input is bounded to 131,072 characters and plaintext to 98,200 bytes,
inside the unchanged generic replica limit. Legacy schema1 plaintext stays bounded
to 65,536 bytes. The checkpoint uses at most 32,768 characters/24,576 plaintext bytes, within
the existing raw storage bounds. No database migration or key reset is introduced.

`RetainCollaborationNotificationReply` authenticates the token against the current
native parent and accepts only a reply descriptor. It checks ownership after pending
decode/storage and has a ten-second budget with no waiting queue. Ordinary credential
expiry does not prevent retaining owned typed input. The use case does not read
company content or grant a send: a company denial or missing original must not silently
erase private text the user just entered. Other-account input is never stored under
its replacement. Cancellation and owner loss after a commit keep the input while
withholding a late acknowledgment.

Raw repository absence for an inactive account is not session authority. Consumer
use cases must capture/check the current owner around reads, review and removal, and
gate company content before exposing input in UI. The repository's remove operation
requires an exact retained-command proof or explicit discard from its caller; that
application policy and bounded recovery/UI workflow are implemented in the linked contracts.
The command dispatcher must use the exact stored token/text and preserve input after
failures or uncertainty. Permanent review needs reproduced facts, not a transient
storage/refresh failure. Native entry points must serialize input persistence so
ordinary concurrent callbacks do not become avoidable contention failures.

No Android receiver, native reply wrapper, display journal, notification UI or live
chat push flag is activated by this increment. Input-retention storage is not proof
of native process-death recovery. Those integrations remain required before enabling
the real notification path.

The implemented [reviewed-reply contract](collaboration-reviewed-replies.md) adds
exact-CAS draft/confirmation edits without changing index identity, checkpoint
schema or AAD namespace. Duplicate callbacks preserve these fields. Native callback,
display, worker and UI integration are now implemented and have separate acceptance
records; live chat push remains disabled until rollout acceptance.
