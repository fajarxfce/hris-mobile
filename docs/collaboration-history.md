# Owned original-message history

The collaboration feature now has encrypted, paged storage for original text
messages and provider event aliases. `ObserveCollaborationHistory` reads acquired
facts offline under the same account/company admission as the conversation list.
This is a storage and application-policy foundation. Native history acquisition,
original-JSON decoding, edit projection, command retirement and Chat UI remain
separate implementation work.

## Canonical identity and deletion

`CollaborationHistoryChange.Text` carries an original decrypted event, its
authenticated sender, provider ID, optional logical UUID, original timestamp,
exact text and a SHA-256 fingerprint of the complete canonical original content.
The adapter must compute that fingerprint, including unknown content fields, and
verify the original event identity against the SDK. A latest edited presentation
must never be supplied as an original. No adapter currently supplies this new port.

Records are scoped by account, company, conversation and room. Within that scope,
sender plus logical UUID identifies an original send; clients without a usable
logical ID fall back to provider event ID. Matching retries retain all observed
aliases and preserve the first observed primary event and ordering timestamp.
Another sender copying a logical ID stays separate. Different original text or
content fingerprint, retargeted provider IDs and changed provider timestamps fail
the complete batch. Callers must retain uncertainty; this repository neither
skips a conflict nor advances a provider cursor.

A redacted provider event retains a tombstone even if its original has not arrived.
Once associated with an original, any confirmed alias redaction hides the logical
message and removes its visible text. Later originals, retries and replays cannot
revive it. Every alias remains resolvable. This does not assert secure deletion of
all SDK caches, provider copies, database journals or backups; those lifecycle and
retention flows still need their own implementation.

## Storage contract

The existing `ReplicaStore` supplies SQLite and IndexedDB transactions. The
`collaboration-history/{company}/{conversation}` partition holds an authenticated
checkpoint, canonical message rows and separate alias rows. Each batch updates
all affected records, aliases and checkpoint under one expected generation/revision.
There is no automatic retry. A lost commit response can be reread; cancellation
does not reset the original identity or delete retained facts.

The platform host owns `collaboration-history-content-key`, a separate credential
record containing two independently generated 256-bit keys. AES-GCM protects
content; HMAC-SHA-256 produces opaque message/alias indexes. Missing keys cannot
regenerate over any retained history, including inactive accounts. Payloads bind
the owner, conversation, generation, record ID, revision and ordering index. The
checkpoint authenticates the room binding, allowing offline readers to discover
it without an SDK runtime or HTTP request. Writes and explicit lookups also check
their expected room. Names, text, logical IDs and provider IDs stay encrypted;
the chronological sort index exposes ordering and timestamp information locally.

Current bounds are explicit:

- One atomic batch contains 1–16 original/redacted events and at most 32 changed
  rows; plaintext envelopes are at most 96,000 bytes and encrypted rows fit the
  existing 131,072-byte bound.
- One canonical message retains at most 128 aliases. Overflow fails without
  eviction. There is no shared 128-message outbox limit on history.
- A local page contains 1–50 messages, with one extra indexed result to determine
  the next cursor. The query never loads the whole conversation.
- Event-ID and sender/logical-ID lookups use keyed indexes. The latter enables
  future recovery when an ACK's event ID was not journaled. Read checkpoints are
  rechecked so a late generation/account change cannot combine incompatible facts.

The 137-message pagination test demonstrates reading beyond outbox capacity; it
is not large-company capacity acceptance. Provider windows, disk quotas, alias
overflow recovery and conflict presentation remain to be implemented.

## Admission and lifetime

`ObserveCollaborationHistory` uses owned local session/workspace access, so ordinary
credential age does not hide acquired history. Known company denial or failed
admission reads close the projection. The observer cancels pending storage when
admission changes, checks admission again before publishing each page and captures
current account ownership after that check. Only an admitted acquisition elsewhere
can reopen the company gate. It performs no network work, key bootstrap or SDK
restore. The caller owns the flow collection and cancellation.

## Verification

The shared storage contract runs against real SQLite and IndexedDB with actual
authenticated encryption: restart and reauthentication, copied sender IDs,
conflicting immutable originals, redaction before/after originals, bounded paging,
competing writes, lost responses, cancellation, account replacement, missing keys,
metadata tampering, mutable caller input and alias overflow. Six common observer
cases cover expired credentials, unavailable admission, late account replacement,
late denial, pending-read disposal/reopening and invalid selections. App DI checks
resolve both the history repository and its observer.

See the [measurement record](measurements/collaboration-history-2026-11-01.json)
for actual run results, source hashes and remaining acceptance limits.
