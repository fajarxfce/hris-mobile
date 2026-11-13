# Company history as a canonical inbox source

The existing Company history repository now exposes bounded source snapshots and conditional
inbox publication through a typed Company contract. Actual Company/conversation coordinates
remain separate from the canonical Group destination. The same account scope is required;
source ciphertext is never copied to a different namespace.

The snapshot reuses the accepted bounded reader and its alias/edit evidence validation. The
conditional publication reuses a raw writer extracted from the Group repository. Both owning
repositories still acquire existing keys and authenticate the original source namespace/room;
no repository calls another repository. The shared writer owns one index read and conditional
Check-plus-write transaction, preserving source bytes, drafts, cancellation and no-op replay.
It owns no admission, retry or collector lifetime.

Eight new actual-storage methods exercise Company/Group namespace isolation and reopening,
authentic alias/edit/local-hide operations, another canonical target ID, byte-preserving replay,
source/index races with ongoing typing, missing keys/wrong rooms/foreign account rejection,
source changes during bounded acquisition, cancellation/join and empty/invalid input. Existing
Group snapshot/publication contracts and the application binding identity remain covered.
Focused 43/full 16,495 pass locally, retaining all 16,479 preceding identities plus 16 new
JVM/browser executions. Apple compilation is separate from actual new Apple execution.

The admitted incoming producers and canonical catalog/history backfill remain before product
activation. Publish from the same binding-selected history source used by the thread; two
physical copies of a LEGACY room must not produce independent inbox anchors. No product graph,
phone installation, SDK send, polling queue or live backend rollout is activated here.

Evidence: `docs/measurements/collaboration-company-history-inbox-source-2026-11-13.json`.
