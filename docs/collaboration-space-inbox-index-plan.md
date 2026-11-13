# Next product integration: durable organization inbox activity

The catalog enumerates immutable canonical pairs. Its opaque HMAC order must never be
presented as recent-chat order or sorted only after taking a 50-row catalog page.
The user-visible list needs its own encrypted, persistent activity index with globally
ordered queries. Reads reopen that index immediately; network discovery/delta acquisition
remains independent. Equal source facts must preserve index rows and observation identity.

The domain catalog admission increment is locally accepted: eight source paths,17 domain
methods and15,575 full cases. It admits pages/version observation without credentials-age,
selected-company or native-client gates. Actual Apple execution follows in the combined control.

The next index implementation must retain:

- Account/Group ownership and canonical conversation ID, independent of physical Original
  Company versus Space history. Metadata enumeration seeds missing entries in bounded turns.
- Newest visible history reference, retained original composer submission and exact pending
  command namespace. An absent outbox row is not delivery; terminal status comes from exact
  retained command/event evidence. Do not infer read/delivered from send completion or time.
- Editable draft state, including removal. A frozen send gesture is not an additional draft.
  Pending text and drafts must participate in whole-list ordering, including chats outside
  the currently visible page. Never reorder only the selected page.
- Stable activity identity across optimistic submission and server confirmation. Display
  timestamps remain real; remote clock differences must not make an already placed local
  send jump through the list on acknowledgement.

A serialized account-owned projection operation should compare its previous index revision,
publish an immutable preview/rank only from admitted source facts, and leave identical content
untouched. Source mutation and durable repair demand need an atomic contract before activation;
an impermanent event bus or a second best-effort write after saving a draft is insufficient.
Inspect the existing composer handoff/recovery and incoming history completion boundaries.
Avoid source-to-source or repository-to-repository dependencies: use cases coordinate reads,
policies derive the index entry, and repositories coordinate raw storage contracts.

Do not solve repair by opening one history stream for every conversation or repeatedly
scanning all messages. Retained source generations/revisions identify what changed. Any initial
catalog scan and repair pass needs bounded work, cancellation, persisted continuation and a
later demand for insertions after its captured version. A cursor-bearing empty catalog page
still advances; only its next=null marks completion. Replacement generation means Restart.

Required behavioral checks include a newest pending send outside the first list page, draft
creation/removal, several sends confirmed out of order, index publication interruption, replay
after a committed source write, competing projection updates, local restart without network,
and account/Group revocation. Actual list/graph activation and Android transition/IME evidence
follow those checks. No activity index or product Group graph is activated by the catalog.

## Transaction approach to review during implementation

Composer drafts/outbox and history already use ReplicaStore. A bounded atomic publication
of two replica mutations could update the source partition and the Group inbox projection
together, preserving each expected generation/revision and account ownership. Encryption
and policy remain outside the database transaction; the datasource receives ready ciphertext.
Check the actual Room/IndexedDB transaction implementations before introducing this API.
Such a general storage capability belongs in core/database and should return to the starter
only after mobile acceptance. It must not call other datasource objects or acquire business
policy inside a transaction. No new table or migration is assumed necessary yet.

The separate CommandStore should remain authoritative for send/cancel/terminal evidence.
The inbox index can retain an exact pending original reference until command/history evidence
resolves it, rather than depending on a best-effort second write at command acknowledgement.
An indexed pending-reference subset has the same bounded pending population as the existing
composer/command limits and can repair stale progress after restart. A single bounded command
observation can trigger local projection updates while the process is active; never open the
same global queue once per row. Verify cancellation/removal and crash recovery before choosing
this design, including how a removed latest pending row reveals the previous activity.

Keep projection components separate: a history update cannot clear an editable draft, and a
command confirmation cannot replace a newer pending submission. The index CAS serializes
concurrent component updates. The newest activity identity should remain stable through
handoff/confirmation while visible receipts still come from actual evidence. This approach
is planning, not an implemented transaction/index or a justification to claim UI acceptance.
