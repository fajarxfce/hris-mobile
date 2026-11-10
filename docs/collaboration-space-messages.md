# Local organization message intents

Status: local source validation accepted. Product routing and native
delivery of these new intents are not enabled. Existing company message envelopes keep
their original ownership and storage format.

An organization intent retains the exact account, API audience, communication space,
conversation, caller-generated ID, creation time, text and reply context. It has no
selected HR company or credential deadline. Retrying the original ID reads the retained
intent before consulting the clock or preparing another write. Conflicting content
cannot replace that identity. Message time keeps its full precision in the authenticated
ordering key, including across database reopen.

The encrypted repository owns a separate versioned command partition and authenticated
envelope. New command insertion and its recovery generation share the database transaction.
Identical active or terminal replay changes neither ciphertext nor the work marker.
Missing encryption keys never trigger replacement over retained ciphertext. Completion
and cancellation archives authenticate the original raw record and prevent resurrection;
only an untouched Queued record can be cancelled locally. Physical staging states require
an explicit original device and content origin.

`QueueCollaborationSpaceText` owns local account admission, canonical conversation checks,
exact readback after uncertain writes, and bounded scheduling after confirmed retention.
A failed scheduling hint cannot erase the command or its durable wake. Cancellation and
late ownership/access changes prevent publication to a superseded caller. No credential
renewal, remote room creation, SDK access or provider send occurs in this local operation.

`ObserveCollaborationSpaceMessages` owns one local subscription per collector. Credential
age and selected-company changes do not reopen storage or hide owned pending facts.
Confirmed directory denial cancels observation and remains authoritative through transient
session or admission read failures until a valid admitted result arrives. Disposal releases
the source. Identical facts do not create another emission or trigger acquisition.
Domain owns these policies; repository contracts own I/O; the data module binds constructors.
No ViewModel or UI change is needed to validate this boundary.

Before product activation, recovery must inventory these commands as well as the old
company ledger, including commands whose conversation has no retained room binding yet.
A binding-only scan must not discharge a wake while such commands remain unresolved.
Provider preparation stays bounded and separate from local observation.

An adopted physical Matrix queue may contain both command types. Match every captured
logical ID to exactly one authenticated ledger; reject missing, ambiguous or foreign
matches. Preserve distinct ownership types and exact original transactions, device,
sender, content origin and completion proof. Original-only queue leases must not silently
gain an enqueue operation. New staging, durable receipt journals, callback-loss recovery
and complete mixed-ledger delivery require separate implementation and native acceptance.

Focused validation passes68 fresh cases across domain policy,local queue/observation,
actual SQLite/Chromium storage and app DI,plus Apple source and quality checks.
Full validation passes9,418 verified results:7,399 fresh and2,019 exactly matching
accepted predecessor reports,zero failures/errors/skips. All4,677 source inputs and
library files stayed fixed; all90 AppFlow cases pass unchanged. Required root checks,
Android host/release compilation/lint,Wasm/iOS source,browser,proof APK/unit tests and
quality pass. The proof APK was compiled; no new native execution or phone installation
is inferred. [Accepted local evidence](measurements/collaboration-space-local-messages-2026-11-10.json).
Pinned Apple execution for these new contracts remains separate required work.
