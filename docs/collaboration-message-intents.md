# Owned offline text intents

`QueueCollaborationText` saves an explicit send action in the encrypted command
store. Its caller creates one logical UUID for the submission and retains it until
the result is known. Retrying that UUID returns the original text and timestamp;
different content cannot replace it. Whitespace and multiline content are preserved.
Preparation requires an owned, acquired direct conversation and the existing
company read-admission gate. It does not need a fresh login, enroll a device, join
a room, or send a request. Delivery will require separate live admission.

`ObserveCollaborationMessages` reads retained intents for the selected conversation.
Ordinary authorization age does not hide those facts. Account/workspace replacement
and confirmed company denial stop observation. A denial that arrives while a local
write is pending prevents publication while leaving the committed intent intact.
Only the existing admitted acquisition can reopen the company gate; message
composition never clears it.

## Storage and state

The existing account command store owns the transactions and local-session fence.
One company partition holds immutable request envelopes and independently encrypted
state envelopes. AAD binds API audience, account, company, logical UUID, envelope
purpose and state revision. Authenticated request content also binds conversation,
text, timestamp and sort position. The OS-protected command content key is shared
with existing commands; missing keys over retained ciphertext cannot be regenerated.
Reauthentication can reopen owned intents without retargeting their native attempts.

The existing account-wide capacity is 128 retained commands, shared with other
workflows. This is an explicit initial bound, not a production chat-capacity claim.
Each text is limited to 16,384 UTF-16 units and 32,768 UTF-8 bytes, with valid Unicode
and no control characters beyond newline, carriage return and tab. State snapshots
contain at most 128 native transaction IDs, each at most 128 ASCII token characters.
The final encrypted/base64 request and state fit the database's 128/32 KiB limits.
Reads decrypt at most the shared 128-record capacity; they never trigger delivery.

States advance through `Queued`, `Staging`, `Staged` and `Sent` by exact-record CAS.
`Staging` retains the original native session, communication device, bootstrap
operation, room and complete paused queue IDs **before** enqueue. `Staged` binds the
logical message to its native transaction. `Sent` retains that transaction and its
server event. No transition discards uncertainty, changes device/room, invents a
replacement transaction or resets a sent message. Retirement requires a future
durable message projection; there is deliberately no removal operation yet.

## Native boundary to connect next

The pinned Matrix Rust FFI explicitly returns no `latest_json` for unsent local
echoes. Waiting for timeline hydration cannot recover their application logical
IDs. The earlier hydration proposal in the timeline research is superseded.
Per-room send-queue subscription does replay its captured transaction IDs before
returning. Under one exclusive enqueue owner with delivery paused, the difference
between a durable pre-enqueue snapshot and a complete current snapshot identifies
the original transaction. An older, already mapped in-flight send may disappear
without changing this result.

The pure recovery policy returns `Found`, `Missing`, `Ambiguous` or
`DifferentDevice`. `Missing` is meaningful only after draining any prior native
enqueue, on the same protected store, with a complete paused snapshot and no other
writer. A quiet or empty timeline is insufficient. Multiple new IDs and different
device families remain unresolved. A native operation must stay owned after caller
cancellation so a late SDK commit cannot race the next snapshot.

The exclusive native writer, lifecycle drain, durable mapping before delivery,
actual process-death integration, product history projection and UI connection
are the next implementation increment. The storage/pure-policy tests do not claim
that this product sender is already connected or that any PRD group is accepted.
