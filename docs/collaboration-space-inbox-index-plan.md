# Next product integration: durable organization inbox activity

The catalog enumerates immutable canonical pairs. Its opaque HMAC order must never be
presented as recent-chat order or sorted only after taking a 50-row catalog page.
The user-visible list needs its own encrypted, persistent activity index with globally
ordered queries. Reads reopen that index immediately; network discovery/delta acquisition
remains independent. Equal source facts must preserve index rows and observation identity.

The domain catalog admission increment is locally accepted: eight source paths,17 domain
methods and15,575 full cases. It admits pages/version observation without credentials-age,
selected-company or native-client gates. Actual Apple execution is accepted in combined control43379266527.

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
and browser, full15,603. No schema migration is required. The Starter port is accepted at36fa855; actual14 Apple
executions still follow. The source repositories receive ready domain proposals, encrypt them and
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

## Encrypted index accepted; source producers next

The encrypted row/component index is now locally accepted:15 storage and6 policy methods on
JVM/browser, full15,645. The catalog's immutable HMAC order remains separate. Exact source/index
producers, admission and bounded repair are still required before UI activation. Group draft
writes are the next producer; preserve source and index checkpoints in the same raw transaction,
including repair after a committed source response was lost. Existing history/command identities
must remain exact. Actual21 Apple methods follow in the next native control.

## Draft producer accepted

Group editor and index publication is locally accepted: 22 shared methods, 15,689 full
executions. Composer handoff and command/history progress with bounded repair remain
before product activation. The source draft scripts are historical and must not be rerun.

## Composer producer accepted

Original send, editor successor, pending preview and recovery wake now publish atomically.
The 24 shared methods pass locally; full15737. Exact command/history progress, retained repair
references and admitted list/graph activation remain. Applied preparation scripts are historical.

## Original repair-reference storage accepted

Original retention and preview completion now share the index transaction; finite high-revision
reference reads pass thirteen shared storage methods locally, full15,763. Exact command progress,
owned recovery and legacy seeding remain before history/list/product activation.

## Exact command progress accepted

Matching terminal originals publish progress and reference completion atomically under local
admission. Seventeen domain methods pass JVM/browser, full15,797. Finite recovery ownership,
legacy seeding, history/list and product integration remain.

## Finite repair checkpoint and exact lookup accepted

One captured pass and one coalesced later demand now persist independently of visible index
revisions. Exact original lookup uses one document. Twenty-one new shared methods pass locally,
full15,839. Admitted owner, legacy seeding, history/list and product integration remain.

## Admitted repair actions accepted

Four admitted finite/exact read and checkpoint actions, plus the strengthened existing message
observer, pass35 new shared methods and all prior identities, full15,909. The finite worker,
legacy seeding, history/list and Group product integration remain.

## Retained history snapshot source — 13 November 2026

Bounded original/replacement reads preserve one authenticated revision and current hidden/edited
facts. Focused 60/full 16,411 pass locally; conditional inbox publication and admitted incoming
producers remain next. See `docs/collaboration-space-history-snapshot.md`. No new product collector is active.

### Incoming producer investigation (not implemented)

Existing Group command get() returns either active or authenticated archived records. There is
no intended absent interval during retirement. Original identity is retained before native send.
recoverCollaborationSpaceOriginalReceipt checks exact sender, logical ID, canonical content hash
and original text (unless redacted), plus the owned command. It accepts Staged only. A Sent record
can be inspected through its equivalent Staged mapping, but also require its acknowledged event
ID in the retained original aliases. Require record conversation and device room equal the actual
snapshot coordinates; matching UUID alone does not establish association. Canonical content hash
includes metadata that a plain-text comparison cannot prove. Company originals keep their own
access/conversation/room namespace and must not become Group originals.

Incoming policy must resolve source identity before first publication. A matching in-flight
Staging original may not yet expose its final native mapping; publishing a History source first
and relabeling it later risks two immutable anchors. Do not quietly create a second source or reuse
an existing ordinal for a new source (InboxCodec correctly rejects that). Determine admitted
ordering/recovery with the existing incoming owner and command lifetime before product activation.
Current Group composer publication seeds its original/index atomically before send; startup
seeding plus exact archived reads cover retained sources. A valid same-ID record from another
sender/conversation is not evidence for this incoming message. Preserve corrupt/failed local reads
as failures, not fabricated absence. Source history can be replayed without new merge content,
especially retained key-history references; incoming completion must include successful projection.

History primaryEventId and original timestamp stay immutable across aliases; firstAcceptedAt may
move earlier. Use the canonical retained original, never truncate its 128 aliases to fit the 32
activity bound. At most 16 requested events resolve to at most 16 canonical originals. Edits resolve
to their authentic original target, and hidden/redacted states are retained for preview removal.
Snapshot acquisition, pure preview/source policy, index/anchor reads and conditional publication
need current local admission around I/O and bounded contention owned by the use case. No use-case
calls, repository calls, new polling queue or VM orchestration helper.

The current publication draft shares the existing history key format with Inbox under distinct
cipher frames. It conditions an actual inbox write on the complete source checkpoint and keeps
no-op replay free of new revisions. It does not prove source association itself; that remains the
admitted domain producer. Separate clock policy is still needed for genuinely new local actions
under backward clock changes; historical imports must not be promoted as newly authored messages.

Association and receipt confirmation are different questions. Do not blindly coerce a Staging
record to Staged just to reuse receipt recovery. Review whether its retained trusted sender,
complete content hash, logical intent, conversation and device room already prove which display
anchor owns the incoming original; that need not create a receipt or alter command progress.
A preview may retain PENDING until exact command acknowledgement while updating retained edit/
hiding facts. This could avoid blocking incoming completion solely on a concurrent staging write.
It still requires explicit policy/tests, and unproved legacy missing-origin records must not be
associated using text/UUID alone. This is a design option for the next producer, not accepted code.

## Conditional history publication — 13 November 2026

The existing history repository now guards an inbox write by the retained source revision without
changing source bytes, drafts or repair references. Focused 50/full 16,443 pass locally.
Source/preview policy, actual Company-history sources and incoming-owner integration remain next.
See `docs/collaboration-space-history-inbox-publication.md`; no new product collector is active.

## Canonical producer source review — 13 November 2026

Observed source facts:
- ObserveCollaborationSpaceThread resolves the immutable binding with
  resolveCollaborationSpaceHistoryLocation. SPACE reads the Group namespace; LEGACY reads the
  actual Company/conversation namespace. SpaceChatThreadRows consumes that selected history.
- AcquireCollaborationSpaceEvents retains Group history even for a LEGACY room/key-history
  coordinate. That can be recovery evidence without being the canonical thread's visible source.
- AcquireCollaborationSpaceOriginalEvents retains Company history and only owns original-room
  metadata/demand today; it does not yet look up the canonical Group binding.

Therefore review a producer rule that publishes only the SAME canonical source selected by the
thread. Do not publish both physical copies and try to repair duplicate immutable inbox anchors
later. In particular, Group LEGACY evidence should not overwrite Company hiding or newer edits.
A Group command can still associate to Company canonical history via its original physical room
and trusted content, retaining GroupOriginal identity; physical history location and command
namespace are separate axes.

The Company producer needs an admitted exact binding (existing findByRoom/access/server/room
contract is a candidate), whose original metadata matches its retained source. No binding means
no authority to invent a canonical conversation ID. Content may predate binding publication;
finite catalog/binding backfill and durable restart coverage must be designed before activation.
The existing command seed owner alone does not seed all incoming/history entries. Do not create
one permanent observer per conversation, infer a binding from UUID equality, or let completion
lose a required projection update. This is an implementation direction, not an accepted producer.

Legacy origin stamps are immutable through Staging -> Staged -> Sent (transition preserves
origin), so a missing-origin terminal record cannot later acquire a stamp via a normal ACK.
Still review actual receipt proof before choosing its display source; the association policy's
null is deliberately not authority to allocate an anchor. Preserve ambiguity and denial without
introducing a busy retry loop. New Group records require origin before delivery.

## Original association — 13 November 2026

Pure Group/Company source identity policies pass focused 28/full 16,479; association cannot
settle a receipt or use an unproved legacy UUID as an anchor. Pending edit/delivery precedence
and admitted existing-producer integration remain. See `docs/collaboration-space-inbox-original-association.md`.

Existing inbox conversation reads/capture support 1..33 candidates. For at most 16 changed
originals, read up to 17 candidates to retain the first unaffected visible fallback after a
hide/redaction batch. Avoid the default two and avoid scanning all history. Original association
PENDING does not clear an existing failure or downgrade SENT; command progress owns status.
Pending edit overlays require actual operation outcome before history can replace their text.

## Actual Company history source — 13 November 2026

The existing Company source and shared conditional writer pass focused 43/full 16,495.
Actual source coordinates and keys remain intact. Admitted canonical producer and bounded
history/catalog backfill remain before activation. See `docs/collaboration-company-history-inbox-source.md`.

## Canonical history source identity — 13 November 2026

Bounded canonical snapshot and exact source selection pass focused46/full16547. Both proven
namespaces and possible unstamped originals remain unresolved without allocating duplicate
anchors. Admitted preview publication and bounded backfill remain before activation. See `docs/collaboration-space-inbox-history-source.md`.

## Pending edit provenance — 13 November 2026

Body-free replacement pointers and immutable optimistic previews pass focused37/full16587.
Actual command authoring/outcome release and canonical incoming/history backfill remain before
activation. See `docs/collaboration-space-inbox-edit-provenance.md`.

## Atomic command insertion support

Actual command, source guard, prepared projection and wake now share one transaction. Fifteen shared methods plus two SQLite fault injections pass focused47/full16619. Immutable handoff origin, admitted edit authoring/outcome release, bounded history publication and list/graph activation remain; see `docs/command-insert-projection.md`.

## Atomic edit publication storage

Same-instance Group command and canonical history/index transaction support passes focused27/full16653. Seventeen shared storage methods preserve marked replay, legacy payloads and actual Company source. Admitted user edit policy, provider outcome release and product activation remain; see `docs/collaboration-space-edit-inbox-publication.md`.

## Admitted offline editing

One reviewed edit is admitted against canonical local history and atomically retained with its original inbox anchor. Focused36/full16687 pass. Provider outcome evidence, incoming/backfill ownership and product activation remain; see `docs/collaboration-space-edit-admission.md`.

## Coherent edit evidence

Existing canonical Group/Company snapshots retain bounded exact replacement operation evidence under the same source revision. Focused51/full16719 pass. Pure outcome policy, incoming/backfill ownership and product activation remain; see `docs/collaboration-history-edit-snapshot.md`.

## Exact edit outcome policy

Pending replacements and authenticated local successors have pure Group/Company admission policies. Focused41/full16765 pass. Admitted publication, incoming/backfill ownership and product activation remain; see `docs/collaboration-space-inbox-edit-outcomes.md`.
