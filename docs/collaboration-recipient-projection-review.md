# Recipient projection and canonical handoff

Local recipient projection, destination observation, message composition and the feature route
are implemented and tested. [Owned local contact labels](collaboration-recipient-contact.md) are also accepted. Remaining work concerns application routing, draft
and inbox continuity, finite setup ownership and rollout. Follow [the product integration
review](collaboration-recipient-product-review.md) and [current handoff](agent-handoff.md).

## Reuse the existing sources

[The recipient projection](collaboration-recipient-projection.md) reads the exact peer draft
and source marker under one published checkpoint. It detaches raw buffers before suspension,
authenticates a bounded original inventory only when its source version changes, then selects
the peer's originals in stable order. Typing under the same source token does not reload the
inventory. Collector-owned caches are cleared on invalid acquisition, replacement and disposal.
Checkpoint races wait on the already-owned publication stream; no polling or transport retry
is introduced. The accepted raw queries and database remain the source of these local facts.

`CollaborationSpaceConversationMetadataRepository.findByPeer` already exposes the immutable
peer-to-canonical association. Its encrypted codec publishes two HMAC-indexed records atomically,
cross-checks their fact/insertion revision and rejects rebinding. Do not create a second table.
[The destination observer](collaboration-recipient-destination.md) retains one admitted pair
within its collector; unrelated catalog insertions do not reacquire it. Absence means waiting
for meaningful local catalog changes, not inventing a conversation or initiating setup.

[Shared progress](collaboration-recipient-progress.md), [display continuity](collaboration-recipient-presentation.md)
and [stable rows](collaboration-recipient-rows.md) compose original, canonical and provider facts
with exact identity/provenance checks. Source absence alone cannot remove a bubble or establish
delivery. The consumer publishes coherent displayed rows before releasing represented actor
originals. [Selection](collaboration-recipient-selection.md) uses actual canonical coordinates
where present; pre-association Copy can match only the exact private recipient original.

The domain owns session/admission policy, cancellation and denial fencing. Repositories own
raw acquisition/mapping; datasources own raw I/O. The page renders state while the route owns
lifecycle, clipboard and viewport effects. Host query counts and timing remain separate from
physical Android frame or network-latency acceptance.

## Remaining draft and index transition

Canonical and recipient drafts can coexist. A canonical inbox entry must choose the correct
editor without silently replacing either draft. A transferred original can disappear from its
recipient source before all history/index consumers publish; preserve exact original proof,
stable order and later typing throughout that interval.

The existing canonical inbox already ranks pending messages and drafts, but its public row key
is a canonical conversation ID. A pre-association recipient needs a distinct durable identity
and bounded global ordering. UI-only deduplication or a separate temporary list cannot solve
this handoff. Evaluate the bounded index option in the product review only after defining
source/index atomicity, independent-draft semantics and exact cursor/anchor behavior. No index
migration or unified draft policy is accepted yet.

## Finite setup after retained Send

`PrepareCollaborationSpaceConversation` performs finite local command preparation. It reuses
an existing Open command or immutable peer mapping; creating a new Open requires current
discovery and inserts one retained command. The Company delivery worker uses a different use
case. No Group application owner for this preparation has been integrated.
`DiscoverCollaborationSpace` is a separate finite 15-second remote operation with current parent
checks and a monotonic deadline; it also has no application caller yet. The existing local inbox
owner intentionally has no native dependency and must continue while offline. A separate preparation
collector should receive the application lifetime's published state through an owned callback,
following its existing incoming-work pattern, rather than introducing a constructor cycle or
making local transfer depend on network availability. Define consumption for retained originals,
new peers, unchanged renewals, connectivity recovery and readiness before wiring it. The read-only
`.work/recipient-next-runtime-review-2026-11-15` records traced entry points, not implemented behavior.

`SubmitCollaborationSpaceRecipientComposer` intentionally retains the original without setup;
the recipient transfer owner waits for real metadata. Add an explicitly owned app coordinator
after a durable Send, preserving the local recipient owner independently of live discovery.
Use bounded/deduplicated peers and meaningful source/readiness changes. Do not call one use case
from another, add a timer, or prepare on passive opening/typing/ordinary renewal: those triggers
would recreate the earlier wake loop. Existing command delivery owns the later remote setup.

Verify immediate offline retention, one Open for multiple messages to the same peer, unchanged
readiness deduplication, cancellation, denial and source retirement. Group remains OFF and
V117–140 undeployed until their rollout gates; source-level acceptance does not activate it.
