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

## Accepted raw transaction and next integration

ReplicaStore.mutate now accepts one or two distinct partition mutations under one account.
Every checkpoint/generation and the optional wake are checked before writing. Room and
IndexedDB rollback/concurrency/cancellation/restart cases pass locally:14 methods on JVM
and browser, full15,603. No schema migration is required. Starter port and actual14 Apple
executions follow. The source repositories receive ready domain proposals, encrypt them and
publish both raw mutations; business projection policy stays in the owning use case.

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
handoff/confirmation while visible receipts still come from actual evidence. The raw transaction
is accepted; the inbox index and its product UI are still implementation work.

## Concrete next boundary

The source use case can read the current inbox components, apply a pure domain change and
pass the proposed publication to the owning source repository. The repository encrypts the
source and proposed index documents and calls ReplicaStore.mutate. It must not call the inbox
repository, run UI ranking policy itself, or accept an unguarded second write. The proposal
carries the expected index generation/revision as well as the immutable source expectation.
An index conflict invalidates the entire proposal; any finite retry retains the original
gesture and re-reads the components. Source-specific admission stays in the use case.

Retain separate indexed component records for editable drafts, outgoing originals, current
history candidates and stable placement anchors. A bounded aggregate record per conversation
can then supply the visible preview and whole-list rank. On removal/cancellation, query the
next candidate from that conversation's index rather than guessing from the current page.
Keeping only one pending scalar cannot recover the previous pending message after removal.

Anchors retain their first position across acknowledgement, provider alias changes and
command retirement. Equal send timestamps need an insertion tie-breaker that survives
restart; UUID lexical order does not establish the user's gesture order. Do not use a
mutable command/hold revision as the original insertion order. Display timestamps remain
the captured real message time and are separate from the retained position.

Resolve an outgoing history alias through existing intent/receipt/origin validation, not
through logicalId alone. The pending Group command has no native sender before acquisition,
and the same logical ID can appear under another sender or original Company namespace.
Existing Space/Original message receipts retain the physical mapping and exact original.

Editing an earlier message changes its content without creating a new conversation activity.
Pending edit overlays must target the original candidate and preserve its placement; they
cannot enter the same new-send candidate bucket. Cancellation and confirmed local hiding
need the same bounded fallback rule. Non-text/decryption outcomes retain their actual kind;
the UI supplies short localized preview labels without exposing protocol terms.

The current domain delivery enum is PENDING/FAILED/SENT. Server acknowledgement is distinct
from peer delivery/read evidence. Reuse those actual facts; do not manufacture additional
check marks from queue absence or elapsed time. A frozen composer submission cannot become
an editable Draft preview, even if the source still retains its body during handoff.
