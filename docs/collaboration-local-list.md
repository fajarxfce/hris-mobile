# Owned conversation list

The Mobile list stores acquired HRIS direct-conversation identities and counterpart
labels in the existing encrypted account replica. It does not store message bodies,
Matrix credentials, read receipts or message delivery state. The native SDK retains
its own protected crypto store and send queue.

`ObserveCollaborationList` checks local session/workspace ownership, the configured
audience, cached chat permission, module availability and known company restrictions.
Ordinary credential/policy age does not hide acquired facts. The current local
session fences every database read/write; account identity and audience own the
retained records across reauthentication. Companies have separate partitions.

`RefreshCollaborationList` performs one explicit bounded summary request. Its shared
instance serializes acquisition and publication so an older in-process request
cannot overwrite a later counterpart label. A database generation/revision compare
and set also rejects competing publication. The immutable conversation identity
must agree with any saved identity; only the acquired label can change. The result
contains the live server continuation for the next explicit request.

Server UUID cursors describe live navigation, not a change feed or a complete
snapshot. Each response merges up to 50 records atomically with a local checkpoint;
missing records are retained. An empty response does not erase acquired pages.
The checkpoint records only local revision, retained count and acquisition time.
Its document revisions are local publication order, never fabricated server versions.
The current direct-list capacity is 10,000 records per account/company. This bound
does not establish that the app or server can support that workload efficiently.

Local list selection uses an indexed UUID page with one-row overfetch: at most
51 encrypted documents are loaded/decrypted. Filters/paging never trigger network
requests. The counterpart label, conversation contents and checkpoint are encrypted;
account/company partition, UUID ordering, revisions and record counts remain local
metadata. Associated data authenticates audience/account/company, generation,
document identity, revision and sort key. Each plaintext envelope is at most 4 KiB.
A dedicated OS-protected content key uses atomic creation and is never replaced
when retained ciphertext exists. Loss/corruption is reported without resetting data.

The list has a separate encrypted admission record. A confirmed company-summary
access/MFA/maintenance rejection publishes to a process-owned buffer before disk I/O.
Failed storage or rejected compare-and-set publication leaves the denial observable,
including through same-account reauthentication. The buffer has 128 pending scope slots;
exhaustion closes its reads until process replacement rather than evicting a denial.
Each rejection receives a revision. A complete successful page acquisition can clear
only the revision observed before starting; a newer rejection defeats that attempt.
Received denial recording has a bounded two-second non-cancellable window, with the
immediate process buffer retained when the durable write times out. A recipient or
single conversation command rejection must not be fed into this company-wide gate.

Observation belongs to its collector. Selection, owner and admission changes cancel
the previous subscription; disposal cancels all local collection. Refresh does not
retry, enroll a device, provision a room or start the native SDK. A failed source
subscription must be reopened explicitly by its presentation owner after recovery.

Common domain tests exercise expiry, owner replacement, denial, cancellation,
serialized requests and collector disposal. The same storage contract runs against
real SQLite and IndexedDB with authenticated encryption, database reopen, key loss,
late writes and failed admission persistence. App entry and chat history remain
separate implementation work; this cache does not accept the full chat requirement.
