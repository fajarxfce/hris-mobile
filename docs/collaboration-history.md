# Owned original-message history

The collaboration feature now has encrypted, paged storage for original text
messages and provider event aliases. `ObserveCollaborationHistory` reads acquired
facts offline under the same account/company admission as the conversation list.
`AcquireCollaborationHistory` can now acquire a finite original-event snapshot
around a supplied event or the latest visible event through the owned Android SDK runtime.
The Android runtime can also read bounded provider-page metadata through the same
gateway. Durable page staging, edit projection, command retirement and Chat UI
remain separate work.

## Canonical identity and deletion

`CollaborationHistoryChange.Text` carries an original decrypted event, its
authenticated sender, provider ID, optional logical UUID, original timestamp,
exact text and a SHA-256 fingerprint of the complete canonical original content.
The adapter must compute that fingerprint, including unknown content fields, and
verify the original event identity against the SDK. A latest edited presentation
must never be supplied as an original. The shared decoder verifies these fields
against primitive SDK identity, canonicalizes object-key ordering and includes
unknown nested content in the fingerprint. Replacement events cannot establish
an original. Unsupported content, unavailable keys and absent original JSON stay
explicitly unresolved; they do not acknowledge an empty provider page.

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

Focused cache ownership has a separate [SDK lifetime limit](collaboration-sdk-history-limits.md).
The native SDK retains those caches after view disposal, so at most 16 distinct
room/anchor keys may be created by one owned history runtime. Repeated keys remain
readable; the limit fails before creating another native focus and does not erase
local history. Continuous product paging and coordinated reopening are still pending.

## Latest visible event

[Validation and actual SDK evidence](measurements/collaboration-history-head-2026-11-01.json).

Omitting the anchor in `AcquireCollaborationHistory` selects the latest visible
provider event first. The Android runtime makes one `GET /messages?dir=b&limit=1`
through the existing fixed Matrix gateway. The SDK still owns its credentials,
crypto store, synchronization and delivery queue. The repository reads the current
SDK session, checks its user/device/homeserver against the retained runtime and
uses only the application-configured gateway URL. The bearer never enters domain,
UI, URLs or diagnostics. This read does not refresh credentials or retry itself;
an expired token fails the operation until the SDK session is usable again.

The API borrows the application's public HTTP pool (no HRIS authentication plugin,
no redirects, existing request/connect/socket deadlines). It accepts only HTTP 200,
bounds success bodies to 256 KiB and errors to 16 KiB, validates UTF-8 and nesting
before JSON parsing, and accepts at most one event with a valid event ID and matching
optional room ID. Only the opaque event ID escapes. Content and encrypted provider
positions are discarded; no sync/history cursor changes. An unsupported state event
is a valid anchor. A visible empty page is readmitted without opening an SDK window
or clearing acquired local facts; it does not establish the end of history.

A registered runtime child owns the request job before I/O starts. Caller or parent
closure cancels and joins that one request, preserving the application's HTTP pool.
Closed/full child registries cannot leave an unregistered job. The final owner/failure
check withholds late responses. Decoding runs on the background dispatcher. The
common use case checks exact head owner/device/room before original acquisition and
rechecks HRIS device/conversation/room afterward, including the empty result path.

A Matrix `M_UNKNOWN_TOKEN`/HTTP 401 cannot distinguish ordinary SDK token expiry
from revocation. It stops live acquisition/delivery but does not persist a company
read restriction. HRIS admission denials still close the existing company gate;
only admitted company-summary acquisition can reopen it. This distinction preserves
owned offline history and pending commands without granting new network access.

This finite discovery is not a second Matrix sync client, a provider pagination
cursor, a complete-history claim, ACK-before-journal recovery or a large-room memory
acceptance. Event-focused SDK construction still has the earlier native cache and
diagnostic allocation limitations.

## Provider-page metadata

[Validation and actual gateway evidence](measurements/collaboration-provider-page-2026-11-01.json).

`AndroidCollaborationRuntimeLease.readProviderHistory` makes one backward gateway
read with a 20-event limit and an optional opaque position. Head discovery and
pages share the same registered request owner, current SDK session validation,
application HTTP pool and cancellation/failure rules. The page body has a 2 MiB
limit; positions are 1–4,096 printable ASCII characters, JSON nesting is at most
16 and every event must have a unique valid ID, sender, type and numeric timestamp.
Foreign optional room IDs or malformed entries fail the whole page. Provider order
is preserved even when timestamps disagree. Only event metadata and positions
escape; bodies and ciphertext are discarded.

An empty page may advance. Only an absent/null `end` signals no continuation for
that response. A repeated start/request position cannot masquerade as progress.
Neither a short chunk nor the count of decrypted timeline items determines the
end of history. The SDK remains the crypto, send-queue and synchronization owner;
these positions belong to explicit history traversal, not its sync cursor.

The actual proof reads an initial page, replays its returned start and follows its
next position once when present, under HRIS device/conversation/room checks before
and after. It verifies both encrypted message IDs and exact runtime binding. This
finite proof does not establish durable progression or complete-history recovery.
The next common operation must atomically retain every unresolved reference and
its page position before publishing traversal progress, then use the owned SDK to
acquire original content. Until that storage/policy operation exists, the metadata
adapter is exposed only on the platform runtime and is not a Chat UI action.

## Explicit acquisition

The common runtime port returns a window bound to account/company/native session,
device, room and requested anchor. The use case validates live owner, device,
DIRECT conversation and READY room before the SDK read and reacquires those HRIS
controls afterward. Company/session denials share the existing buffered encrypted
company gate; failed restriction persistence cannot clear a known denial. A single
unavailable recipient does not close unrelated company history. Only the existing
company-summary admission flow reopens that gate.

The use case detaches both returned collections before further suspension, checks
their identity and all event primitives, and rejects duplicates, foreign windows
or more than 128 combined entries before writing. Each 1–16-event storage batch
retains its own atomic commit; a later conflict, cancellation or lost response
does not roll back earlier acquired facts. Admission and current owner are checked
after each commit. There is no automatic repeat, provider-position advancement,
native queue work or sender-receipt inference. Callers own invocation/cancellation.
The report counts acquired event facts, not newly inserted messages; unresolved
entries can include state events and are not a count of unreadable chat messages.

## Verification

The Android prototype owns an event-focused SDK timeline with a 20-event context,
read-receipt tracking disabled and no automatic thread expansion. It takes only
the pinned SDK listener's synchronous initial reset, bounded to 128 items, and
disposes later callbacks. It copies `debugInfo.originalJson` once per text event,
not the edited display text or `latestJson`. That binding also formats a full
diagnostic model: only aggregate extraction time and character counts leave the
proof. Original JSON is bounded to 96,000 UTF-8 bytes and depth 16 before recursive
parsing; cumulative model/original character counts are each capped at 4,194,304.
These checks cannot prevent the SDK's preceding diagnostic allocation or its
internal event-cache snapshot. They do not establish large-room memory bounds.
The Android adapter decodes and hashes the snapshot on `Dispatchers.Default`
within the caller's structured operation. A cancelled invocation still releases
its native child even if decoding is waiting in the dispatcher queue.

The parent runtime owns partial initialization and listener/task/timeline/room
cleanup. A pending or failed release retains ownership, and a late credential
denial withholds results. The prototype uses an already known encrypted room; it
does not join, bootstrap another client, start sync or enable the send queue.
Its known anchor is not head discovery or a provider cursor. The application
still needs bounded provider progress and the other platform adapters.

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
The [original-window evidence](measurements/collaboration-original-window-2026-11-01.json)
adds the actual Android/HRIS context read, encrypted database reopen and measured
extraction, including the failed filter and fixture attempts that preceded it.
The [common acquisition evidence](measurements/collaboration-history-acquisition-2026-11-01.json)
then proves the application use case performs that first write, brackets the SDK
read with live HRIS admission and retains cancellation/late-result protection.
