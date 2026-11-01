# Foreground remote chat history

The thread's Older action first opens retained local pages. When that projection has no
next page and the admitted timeline is connected, it schedules one finite history operation
on the same screen-owned signal consumer. Delivery remains application-owned.

ReadCollaborationHistoryReferences exposes at most twenty pending, validated metadata entries
from a coherent owned local snapshot. It detaches input before the final ownership/admission
check, propagates cancellation and needs neither live credentials nor a native SDK session.
Known denial wins over concurrent storage/session failure. It does not acknowledge, decrypt,
advance a provider position or call a second use case. Existing reconciliation shares its
snapshot-validation policy.

The presentation coordinator acknowledges already-retained original/tombstone facts, reads
pending references and acquires at most one provider page when needed. It requests at most
two original windows around the bounded slice's potential-message endpoints. Only exact event facts acknowledge
references. Opaque local and provider positions remain separate. Unknown references are
retained while the scan can continue to later pages. An exhausted provider is not proof that
all messages were decrypted. Explicit Refresh can revisit unavailable content. The visible
notice concerns potential message events, not unrelated membership/state metadata.

Separate loading/error state keeps composition and locally owned messages usable while older
history is being fetched. Timeline updates cannot clear a paging error. Stop/account change
cancels acquisition and closes the child; late non-cancellable I/O cannot publish success.
A cache-capacity failure retains its exact older anchor for explicit admitted runtime reopening.

Limitations: the pre-existing local window still caps ten pages of fifty original messages.
Extend this to bounded bidirectional/sliding paging next; do not describe this increment as
unlimited browsing. Two windows do not guarantee that all encrypted references can be decoded;
unsupported rich content and unavailable keys remain pending. Native adapter proofs are
separate from this new foreground orchestration: add a real provider/UI integration scenario
before claiming end-to-end native paging acceptance. No live provider/backend or Starter
change is included. Incoming push/replies, durable drafts/cancellation, other communication
features, Apple/web SDK support and every unaccepted PRD group remain in scope for the long run.

[Validation](measurements/chat-remote-history-2026-11-01.json) records every attempt, source hashes,
root/domain/presentation/browser/Android-host reports and the [large-text screenshot](images/chat-thread/chat-thread-history-large-text.png).
