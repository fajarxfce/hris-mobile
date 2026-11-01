# Message completion and pending capacity

HRIS completion storage and local retirement are implemented on top of the raw
[command archive](command-storage.md). A Sent command leaves the bounded pending
queue only after exact durable receipt and original-history validation. Its logical
ID remains reserved by immutable encrypted completion evidence. UI scheduling and
product acceptance are separate work.

## Encrypted completion

`EncryptedCollaborationMessageRepository` implements both message and completion
ports. Exact `get` and matching `insert` replay read validated archived records;
pending observations exclude them. A conflicting intent cannot reuse the ID, and
a first insertion racing another writer's completed send cannot recreate pending
work. The raw store compares the whole observed encrypted record in its atomic
archive transaction, including account/session, request/state, ordering and revision.

The completion envelope uses the existing command content key with a separate
authenticated purpose. Its associated data binds the owned audience/account/company
and a SHA-256 digest of the complete retained raw record. Even a separately encrypted,
semantically identical state cannot receive another envelope's proof. The encrypted
proof retains every receipt alias without duplicating message body, device mapping
or transaction already authenticated by the command. Plaintext is bounded at 48,000
bytes and Base64 ciphertext at 64 KiB; oversized aliases fail without truncation or
pending removal. Missing keys never regenerate over retained active or archived data.

## Local retirement

`RetireCollaborationMessages` requires owned local access and the company gate after
every suspension. It reads at most 128 journal entries and prepares at most 32 exact
completions before writing any. Only Sent mappings with a trusted origin can retire.
The acquired original must match sender, logical ID, full canonical content hash,
body unless confirmed redacted, and every relevant event alias. Missing originals,
legacy origin-less states and unsent commands remain pending; conflicting evidence
fails the prepared batch. The operation does not call HTTP, acquire an SDK client,
fetch history, start sync, resume delivery or retry automatically.

Completion commits precede journal cleanup. The two stores deliberately have separate
transactions: an interrupted or failed archive response retains the completion and
journal, allowing an explicit retry. Ownership or denial after a committed write
withholds success while preserving the durable fact.

`ReconcileCollaborationMessages`, `RecoverCollaborationMessageReceipts` and delivery
projection read exact completion proofs for journal entries absent from pending
observations. Pending work and receipt recovery are bounded independently at 128
each; resolving a full archived journal does not increase pending capacity. Unknown
receipts or changed intent/device/transaction remain unresolved. A later receipt may
contain only a new alias after previous cleanup; it cannot replace the original Sent
event or its immutable completion. Retirement validates the union of old proof and
new journal aliases against acquired original history before removing that journal.
New aliases remain in original history; the first completion envelope stays immutable.

## Exact journal cleanup

`CollaborationReceiptRetirementRepository.removeReceipts` accepts 1..32 detached,
distinct, exact observed receipts. The encrypted repository rereads the bounded ledger
and compares the entire receipt, including alias order. Changed aliases, missing
entries, checkpoint competition or account replacement reject cleanup. Tombstones and
the next encrypted summary commit in the same owned Replica transaction. Unselected
receipts keep their original authenticated documents.

Removing the final receipt retains an authenticated empty checkpoint and storage
key-loss protection. Tombstones are excluded from visible queries and can be followed
by a later receipt version. Cleanup never infers acknowledgement or deletes command
completion evidence. Archive bodies, original history and journal tombstone retention
must be included in later local deletion, retention and E2EE backup policy.

## Continuation

Keep the SQLite/IndexedDB, common-domain, binding and native process-death evidence in
Git alongside each commit. The Android fixture stops after completion archival and
before journal cleanup; its fourth process must recognize the exact retained receipts,
finish local cleanup, reopen storage and reject changed-intent replay without resending.
The driver independently validates every process and transaction boundary.

Next wire scheduling and Chat UI to merge pending messages with canonical history,
including the retirement transition without duplication or disappearance. Then finish
Apple/web owned adapters and interoperability, backup, rich messages, calls and all
meeting phases. These storage operations alone accept none of the 54 PRD groups.
