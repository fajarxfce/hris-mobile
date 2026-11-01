# Durable composer and prepared-send recovery

## Editor ownership

Each account/company/conversation has an encrypted draft slot with an immutable logical
revision. One serial writer coalesces waiting input while retaining an uncertain write's
exact revision and body. A late successful observer emission never replaces active input.
Initial loading gates editing; captured access fences every edit, preparation, queue and
confirmation against account replacement, including a co-participant in the same company.
Ordinary credential expiry does not hide owned local text; confirmed denial remains gated.

Send first flushes input, then freezes the exact body with the editing revision as its
stable submission ID. A lost preparation response can recover that original operation.
Queue admission and draft confirmation are separate durable steps. Confirmation requires
the exact command or authenticated completion, and preserves a newer draft. A review-held
revision can be cleared only for that same original ID/body. Empty tombstones retain a new
logical revision so late writers cannot resurrect cleared text.

Back waits up to 1,500 ms for the local writer and then offers an explicit leave choice.
Timing out the wait does not cancel an admitted write. Reloading a conflicting draft also
requires an in-product choice. Feedback is localized near the composer; the retained
[320 dp / 200% text screenshot](images/chat-thread/chat-draft-failure-large-text.png) shows
its failure actions remain reachable.

## Atomic indexed storage

The first distributed draft format is `composer-v2`: one account/company replica partition
with a separately authenticated document per conversation. The frozen body and `prepared`
tag commit in one SQLite/IndexedDB transaction. Editing, empty and review-held rows are
excluded from automatic discovery. Reads authenticate at most eight selected drafts and
one lookahead, including the checkpoint. No history scan or second best-effort index is used.

Complete-target CAS rejects another editor's change. At most three technical attempts handle
an unrelated conversation advancing the shared checkpoint, rereading the target each time.
A valid page followed by checkpoint movement returns retryable draft conflict, not generic
storage failure or stale data. Malformed ciphertext and missing protected keys remain storage
failures. Account, company, purpose, generation and record metadata are authenticated.

The existing protected history key owns drafts and their separate recovery cursor. Inactive
draft data prevents key regeneration. No new core schema or platform credential is needed.
The 603-row fixture contains 201 prepared drafts and 402 editing/empty drafts; at most ten
decryptions occur per page. Cursor state uses its own authenticated partition and generation
fence, independent of ordinary outbox delivery.

## Application recovery

Freezing an eligible draft wakes the existing payload-free delivery task, with a 1,500 ms
scheduler deadline; startup independently wakes the same key. The application worker first
recovers at most eight prepared drafts, within its existing 60-second deadline, then drains
ordinary outbox work. A full command queue therefore cannot prevent its own progress.
The cursor advances before each attempt and discovery wraps at most once, so one failed
conversation cannot monopolize a batch.

The worker coordinates Queue and Confirm using captured ownership and original ID/body.
Freeze-only restart, lost queue reply and lost clear reply all retain the same operation.
Retryable storage contention and capacity return platform retry. Shared authority/key/storage
failures retain data and wait for a later trigger. Individual unqueueable contexts are held
for explicit retry only after current command/list facts reproduce the reason. Held drafts
keep their text and submission ID and are atomically excluded from automatic discovery;
known racing queue admission cannot be mistaken for cancellation. No SDK command is erased.

[Integration evidence](measurements/chat-draft-recovery-2026-11-01.json) records root, browser,
Android host, proof APK/lint and quality checks, with six application/SQLite worker cases,
eighteen SQLite storage cases and the matching portable contracts. Application recovery tests
use a fake native transport: they do not establish native freeze-only process-death acceptance.
That actual SDK/server scenario remains the next proof. Existing hosted native regression
has an earlier source and must not be attributed to these new draft boundaries.

## Format history and remaining scope

The original `composer-v1` prototype was never distributed. Verified phone release 7 contains
neither draft format; v2 replaces v1 before the first draft-capable release, without a user-data
migration. Historical [storage](measurements/chat-draft-storage-2026-11-01.json),
[policy](measurements/chat-draft-policy-2026-11-01.json),
[UI](measurements/chat-draft-ui-2026-11-01.json) and
[index](measurements/chat-draft-index-2026-11-01.json) evidence remains separately attributable.

This is HRIS application policy and is not copied to Starter. It does not complete the
communications PRD: safe pending cancellation, incoming push/replies, rich messages,
backup/recovery, remaining native/web adapters, calls and meetings continue separately.
