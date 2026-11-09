# Durable organization recovery progress

The organization worker has a separate encrypted checkpoint per account, organization
and original native family. It does not reuse the company sweep or readiness receipt.
The checkpoint captures one wake generation and a stable room-inventory boundary.

Before native recovery, the owning flow claims one raw inventory page and retains its
conversation ID and next cursor. An empty projected page remains a claimed step.
The pending claim survives interruption and must complete before the cursor advances.
Only an explicit exhausted page can finish the sweep. Every update compares the exact
old checkpoint and generates a fresh generation; late results cannot overwrite newer
work. New wake generations never reset an already active finite sweep.

Failure, successful bounded continuation and concurrent binding appends are separate
sticky facts. Any of them requires a fresh sweep without acknowledging the wake.
A clean exhausted sweep returns only its captured wake for separate exact-generation
acknowledgment. Lost acknowledgment may repeat a sweep, never fabricate completion.
Progress grants no SDK or content authority and does not enqueue messages.

Read and update use cases check current local session, account and native family
before and after storage. Expired credentials and selected-company changes do not hide
owned progress. Repositories coordinate the raw replica store and protected cipher;
the codec authenticates ownership, generation and schema. Missing reads create no
key; an absent key over existing encrypted records fails without replacing that key.
Strict bounded fields preserve one pending claim and its raw cursor.

Focused validation passes 28 cases in 244.986 seconds: fourteen domain cases,
twelve actual SQLite storage cases and two application graph cases. Full required
validation passes 4,376 fresh cases in 597.698 seconds: 3,721 desktop, 629 Android
host and 26 browser cases, with zero failures/errors/skips. The browser gate runs
the same fourteen domain and twelve actual IndexedDB storage cases. Root check,
Android release/lint, shared Wasm/iOS source and quality checks pass. Both gates
retain identical 4,611-input source manifests and unchanged Inspector bytes; every
fresh XML/count is audited. No candidate gate failed.

[Recorded evidence](measurements/collaboration-space-recovery-progress-2026-11-09.json).
The same storage contract has an Apple Room binding; hosted execution of this new
source remains separate. No actual recovery worker, app activation or release is
included in the checkpoint contract itself.
