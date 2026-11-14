# Exact recipient message progress

ObserveCollaborationSpaceComposerProgress gains an explicit recipients entry point while preserving its existing canonical composer API. Both use one privately typed observation, bounded mailbox and exact-record reader set. The two original types remain distinct public domain values; no peer ID is aliased as a conversation ID and no canonical draft is manufactured from recipient text. Existing policies validate the immutable canonical pair and full recipient origin before a record can match.

The domain use case coordinates the existing message, directory and session repositories. It does not call another use case, add a repository, open transport, prepare a conversation, schedule delivery or mutate storage. Repository observeRecord supplies active and authenticated terminal facts. Missing records preserve the original with an absent status; they never imply delivery or cancellation. Source/target/text/timestamps and recipient provenance must match exactly.

Each collector captures at most 128 originals plus one overflow lookahead before suspension. Unchanged originals retain their exact-record reader; removing or replacing an original cancels and joins its old reader. New originals add only their own bounded reader. A 32-event mailbox serializes updates and rejects late events from replaced readers. Mutable Staging evidence is detached before the final admission guard. Failed records preserve the failure and do not restart when time passes or ordinary credentials rotate.

Both public entry points now invalidate their private content when the parent session or admission observation ends. Confirmed denial stays latched through failed retries and new admitted observation is required to reopen. Disposal cancels input and record subscriptions and the collected coroutine scope joins them before ownership replacement.

Fourteen new common cases cover recipient provenance, terminal and missing records, independent additions/removals,128/129 bounds, malformed target, failure without timed retry, account replacement, denial/readmission, mutable input, parent completion for both APIs and disposal. The 20 existing canonical progress and lifetime cases remain unchanged; actual application DI must still resolve the same use case. Desktop/browser execution and Apple source compilation are distinct from device runtime.

This prerequisite does not activate a recipient thread or retire source originals. Next compose pending and canonical history rows using exact record/history evidence, preserve stable visual identity and independent editor/index continuity, then wire product routes and finite setup ownership. Phone 93 remains installed; it predates these components. Group remains OFF with V117–140 undeployed and all 54 PRD groups OPEN. This HRIS policy is not a Starter port. Preserve the blocked SQLite preparation, failed Apple run and protected stores.

Validation: 18,277 cases (16,088 fresh / 2,189 exact unchanged), preserving all 18,249 prior identities plus 28 executions. Focused 36 and full root/Android/browser/Apple-source gates passed. Source and report manifests are recorded below.

[Exact validation](measurements/collaboration-recipient-progress-2026-11-14.json).
