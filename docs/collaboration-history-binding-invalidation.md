# Local history invalidation when a room is bound

A newly retained canonical binding now publishes the account history signal and existing recovery
wake in the same transaction. This exposes Company history acquired before an already-known
catalog entry was bound. Exact binding replay remains silent. No new source records, observer,
network request, polling scheduler, schema or key owner is introduced.

The original immutable pair and generation checks remain. Signal contention or wake capacity
rejects the entire mutation; malformed signal is not overwritten. Source ciphertext and catalog
metadata stay unchanged. Cancellation and lost responses retain their existing explicit replay
semantics. Existing fault injectors follow the binding mutation, while metadata publication keeps
its own original boundary. Legacy fixtures recreate the original pre-signal/pre-wake records.

Six new shared cases cover observed publication, silent replay, signal conflicts, malformed
signals, cancellation before mutation, renewal/scope isolation, and old Company content under
known metadata. All46 earlier binding/preparation cases remain. Focused79/full17022 retain all17010
prior identities plus12 desktop/browser executions. Apple compilation is separate from actual new
Apple execution, which remains outstanding. Durable owner progress and consumer activation follow.

Evidence: `docs/measurements/collaboration-history-binding-invalidation-2026-11-14.json`.
