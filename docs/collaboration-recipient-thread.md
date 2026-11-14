# Recipient thread consumer

RecipientChatThreadViewModel consumes the accepted local source projection, immutable destination observation, exact recipient message progress and physical thread history. One request stream owns their bounded merge and joins obsolete subscriptions before replacing them. Every publication carries its request identity; history/progress also carry the actual canonical destination. The ViewModel owns screen state and one serial recipient editor. Use cases retain admission, storage, transfer and delivery policy; the screen does not start setup, network or timed retries.

An explicit Send captures and displays a bubble synchronously through the existing editor while local handoff runs independently. Typing remains independent of earlier submitted originals. A first local source binds the retained draft and latest submitted time; replayed snapshots do not overwrite the current editor. Pure display and row projections combine incoming facts, retain exact originals across source retirement and preserve their visual identity. Coherent screen state is published before represented originals may be released from the actor; absent source data alone never means delivery.

Any reader or writer denial invalidates the complete request, clears private screen/editor state and rejects late successes from other readers. A new explicit entry or a fresh admitted Group root may acquire again. SpaceChatRootOwner already closes its store on denied admission and creates a new one after ObserveLocalCollaborationSpace readmits access. Preparation may acquire bounded local facts while hidden but cannot type or Send. Ordinary exit retains local readers for account redaction. Disposal closes the serial editor and fences late callbacks.

The new consumer is separate from the existing composer-only regression control while thread/root integration is developed. They must never be bound as two editors for one route. Existing composer and actor tests remain unchanged, and the actual application DI test resolves both independently and disposes their stores. Move the old control to the completed thread/root contract before removing it; do not weaken its hidden-admission assertion to permit stale merged results.

Ten new common cases cover rapid Send during held handoff, passive re-entry without setup, exact terminal status after source retirement, failed source reads, cross-reader denial, account replacement, history releasing actor/source rows, late write denial, disposal with held handoff and hidden preparation. These are local state/lifetime checks, not rendered first-frame or device-latency measurements.

No product route is active. Selection/actions, back flushing, shared bounded navigation ownership, actual page and rendered/root integration, independent canonical/recipient drafts, global pending/draft inbox identity and finite setup after retained Send remain required. Recipient drafts currently support plain text; no outgoing reply/forward capability is claimed. Phone 93 remains installed from earlier source. Group OFF; V117–140 undeployed; all 54 PRD groups OPEN. HRIS policy stays out of Starter. Preserve failed Apple evidence, blocked SQLite preparation and protected stores.

Validation: 18,351 cases (2,219 fresh / 16,132 exact unchanged), preserving all 18,331 prior identities plus 20 executions. Focused 34 and full root/Android/browser/Apple-source gates passed.

Earlier focused attempts are preserved separately; only the accepted source/gates establish this result.

[Exact validation](measurements/collaboration-recipient-thread-2026-11-14.json).
