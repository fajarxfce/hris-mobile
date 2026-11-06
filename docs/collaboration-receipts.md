# Durable outbound acknowledgements

`CollaborationReceiptRepository` retains observed server acknowledgements independently
of mutable command state. A receipt binds the complete original intent, native
session/device/bootstrap/room, transaction ID, and every observed event alias.
Repeated append unions aliases in original observation order. Neither content nor
the original mapping can change. An event cannot belong to two logical messages
in the same room; a room/device/transaction cannot be assigned to another intent,
including by changing only its bootstrap or native-session metadata.

The eventual sender must append acknowledged facts **before** projecting command
`Sent`. Those two stores deliberately do not pretend to share a transaction.
`ReconcileCollaborationMessages` repairs an interrupted projection from the journal:
only an exact original `Staged` mapping advances, and an existing `Sent` keeps its
primary event if that event belongs to the retained aliases. Queued/staging work,
unknown intent IDs, conflicting content/mappings and malformed aliases remain
unresolved. All retained pairs are validated before any command changes. A rejected
CAS or lost response leaves the journal available for the next explicit attempt.
No receipt means no acknowledgement; an empty native queue never supplies one.

Reconciliation needs local account/workspace ownership and the company read gate.
It works after ordinary login/admission age, without HTTP, native SDK acquisition,
enrollment, sending, or retries. Gate and owner are checked after every I/O, with
owner capture **after** the gate read. Confirmed company denial remains authoritative
through failed storage/session reads. A late owner/denial prevents publication;
already committed facts remain durable. Reconciliation never clears the gate.

## Receipt observation during delivery

The queue lease reports cumulative observations within its existing bounded native
window. The Android datasource delivers immutable SDK snapshots on the owning
coroutine, outside the native callback lock. Its repository preserves a failed
observer as a typed failure; the window pauses and disposes its listener. No new
send, independent retry, or unbounded receipt collector is introduced.

`DeliverCollaborationMessages` authenticates each observed transaction/event pair
against its captured mapping, appends the receipt journal, then projects through
the original invocation's context and admission checks. The first acknowledged
message can become `Sent` while another message in that window is still pending.
Repeated cumulative observations keep an existing primary event and revision.
The final window result remains a recovery path for observations not delivered
before stop, timeout, or synchronous completion.

Native work belongs to the SDK parent and can outlive cancellation of its waiter.
An already observed late receipt uses bounded noncancellable journal retention
under the captured account fence. Its projection runs only in the original active
invocation; cancellation cannot move publication into the native parent's lifetime.
A replacement account cannot receive the old receipt. Closing a lease drains the
owned operation and observer before releasing the exclusive writer.

[Increment evidence](measurements/collaboration-progressive-receipts-2026-11-06.json)
tracks the failing-then-passing first-receipt regression and subsequent validation.
The user still reports long pending on release 47; this contract is not yet a
physical-phone resolution claim.

## Storage bounds and ownership

The adapter uses existing SQLite/IndexedDB `ReplicaStore` transactions. Each owned
company/conversation partition has one published generation with an authenticated
count/alias checkpoint and one encrypted document per logical UUID. Checkpoint and
documents commit together under generation/revision CAS and the raw account fence.
One bounded read requests at most 129 rows to detect overflow; every retained row
must match the checkpoint, order and encrypted identity. There is no unbounded scan
or independently visible alias index. Native transactions, provider event IDs and
message bodies remain inside ciphertext.

AAD binds audience/account/company/conversation, generation, logical ID or checkpoint
purpose, and revision. Document ID/sort equality and empty tags are verified. The
immutable mapping and complete original message are authenticated in the encrypted
payload. A separate OS-protected `collaboration-receipt-content-key` avoids coupling
key creation to the command namespace. Lost keys cannot regenerate over **any**
retained receipt partition, including an inactive account. Hosts provide Android
protected storage, Apple Keychain, desktop credentials, or browser protected storage.

This initial outbound journal holds at most **128 messages / 512 total aliases per
conversation**, at most **128 aliases per message**, and **1–32 distinct messages per
append**. Envelopes are bounded to 128 KiB per row / 4 MiB per write by the existing
storage contract; the codec bounds plaintext to 98,000 bytes. Inputs are detached
before suspension. Capacity/conflict never evicts prior acknowledgements. The
existing shared 128-command account bound still applies; no command retirement is
enabled here. These are explicit initial bounds, not production chat capacity.

## Evidence and remaining integration

[Receipt measurement](measurements/collaboration-receipt-2026-11-01.json) records
11 identical actual SQLite/IndexedDB contract cases and ten common reconciliation
cases. Coverage includes restart/reauthentication, exact immutable content, every
alias, collision rejection, simultaneous CAS, lost/cancelled commit responses,
account replacement, inactive-account key loss, tampered indexes/checkpoint,
input mutation, capacity, expired authority and late denial.

The [common live-admitted sender](collaboration-sender.md) now connects native
delivery results to this journal under original account fencing before projection.
`RecoverCollaborationMessageReceipts` separately repairs an ACK that never reached
this journal, using [exact owned originals](collaboration-original-receipts.md).
Native queue absence alone remains insufficient. Command retirement and Chat UI
remain subsequent work. No complete PRD group or capacity target is accepted by
this storage increment.

## Completion retirement

Exact journal cleanup and recovery of archived commands are implemented by the
[retirement contract](collaboration-retirement.md). Cleanup retains an encrypted
empty checkpoint and only removes exact observed receipts after durable completion
and original-history validation. It does not imply UI scheduling or product acceptance.
