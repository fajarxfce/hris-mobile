# Employee expenses

The expense feature has separate domain, data and presentation modules. Domain
use cases admit company/session access and acquire employee claim views. Data
adapters map the API and maintain an encrypted account-owned replica. Routes own MVI state,
filters, observation and acquisition lifetime.

## Reads

The employee directory uses `/expenses/me/employments`, independently of People
permissions. Every claim, history, submission and payment request explicitly
uses `scope=SELF`, including for accounts with finance permissions. The complete
owned directory is bounded to 200 employments; displayed directory pages contain
20. Lists query company-local creation months, employment, status and an indexed
cursor in local storage. Transaction dates remain expense-line facts.

`SyncExpenses` hydrates the explicit `EXPENSE_CLAIMS` and `EXPENSE_PAYMENTS`
collections. The canonical `/claims/{id}/record` returns the current claim,
latest submission/approval and latest activity under one server read boundary.
Every new claim invalidation is acquired even when its base version is unchanged:
approval updates have their own version. `/claims/{id}/payment-progress` has its
own version and claim ID. A payment-only update keeps an unchanged claim's action
review timestamp; if the local parent is missing or still precedes approval,
sync acquires that parent before publishing the combined view.

Cursor and records commit together. Two references per page, at most two parallel
claim acquisitions, ten pages and a 30-second execution budget bound each run.
UI and worker triggers share one acquisition mutex. Expired change cursors stage
a replacement while keeping the preceding published records readable. A failed
batch does not advance its cursor. No read implicitly submits a command.

`LoadExpenseClaim` reuses acquired detail and activity on entry. Explicit refresh
and command reconciliation request fresh facts. Older activity has a separate
bounded, resumable frontier: fetching it neither reacquires the current claim nor
renews its action-review deadline. Background runs process at most eight pending
activity pages, and manual history navigation can acquire a missing page.
Amounts remain exact decimal values; approval does not imply payment.

## Draft preparation

The preparation use case verifies the selected employment through the employee's
own directory, obtains the company date and, when editing, preserves the current
claim version and draft. Only draft and returned claims are editable. Read-only
expense permission does not authorize preparation.

Category discovery reads the effective policy for the selected transaction date.
Cost-center discovery uses the expense-specific, minimal organization endpoint;
it does not require general organization browsing. Both retain issued cursor
pages and validate bounded DTOs inside the HTTP failure boundary. Money remains
exact decimal and receipt requirements remain category policy.

Preparation and picker results have a short lease bounded by company policy,
session, workspace and scheduled maintenance. Use cases recheck ownership after
pending reads, including failed responses, reject cancellation and limit the
entire acquisition to 30 seconds. They do not retry or reserve a category/cost
center; the backend validates current eligibility again during submission.

## Offline access

The replica binds API audience, account and company independently of the login
session. Active-session guards still reject an obsolete writer. Acquired claims,
indexed summaries, payment progress, directory metadata and activity pages remain
readable after process restart and reauthentication for the same account. Ordinary
age does not hide data or start a refresh loop. New credentials and changed access
metadata require fresh action review, independently of local reading.

Each claim has an authenticated acquisition ID and up to sixteen 64 KiB chunks,
within a 1 MiB raw-record limit. Empty slots are encrypted too, so payloads can
shrink and grow at the same base version without reviving a deletion tombstone.
The acquisition ID prevents chunks from different equal-version invalidations
being combined. Summary indexes are authenticated and scoped. Missing/tampered
chunks fail without publishing partial data; temporary keys and plaintext byte
buffers are cleared. Checkpoint, changed records and directory metadata share a
compare-and-set transaction.

Confirmed access/MFA denials have their own encrypted admission record and a
process-owned write buffer. The denial remains authoritative through failed cleanup,
pending retries and ordinary transport failures. Only admitted full synchronization
started after that denial can reopen the projection; a later denial defeats an
older admission attempt. Claim refresh, historical reads and receipt export obey
this shared boundary. A matching preceding last-view snapshot remains a migration
fallback until the replica publishes; a complete feed's missing parent is authoritative.

## Durable commands

Draft creation/editing, submission, draft cancellation and submission withdrawal
have separate preparation and delivery use cases. Line preparation validates
exact amounts, dates and evidence identifiers. Saving is distinct from submitting;
a returned claim needs a new saved draft revision before submission.

Confirmation stores the immutable body, expected version and operation ID in an
authenticated command envelope before HTTP delivery. New-claim slots belong to an
employment; existing actions share a claim slot. A concurrent confirmation cannot
replace another retained body. Encryption and JSON work run off Main, with bounded
request/outcome envelopes and cleared temporary plaintext/key buffers.

Manual delivery and the registered expense command task share one execution owner.
Read synchronization has its own mutex shared with explicit claim acquisition.
Each attempt revalidates current company/session policy and marks its outcome
unconfirmed before transport. Retries retain the original body and operation ID.
Automatic attempts and batches are finite. A later rejection cannot disprove an
earlier uncertain commit; an unconfirmed operation cannot be discarded. Confirmed
cleanup requires an admitted claim view at least as recent as the receipt and an
atomic outcome revision match. Credentials never enter command storage.

The native draft editor and action review pages use these boundaries for saving,
submission, cancellation and withdrawal. Action preparation checks the version
reviewed by the employee. A changed version requires explicit review; entering a
reason never authorizes a silent rebase. Submission eligibility and the current
withdrawable approval are domain policy shared by presentation and preparation.

## Pages

Expenses opens from All services. Month and status filters stay above the
scrolling list; multiple employments use a separate selection dialog. Detail
groups amounts, expense lines, submitted receipt metadata, approval, payment
progress and paged activity. Back and refresh remain in the fixed app bar.
The pages support English, Indonesian and large text. Draft rows give amounts
their own wrapping line so large values do not disappear into truncated metadata.

Each screen registers separate typed events. Changing a filter replaces its local
query without restarting HTTP or cancelling background sync. Sync completion never
restores an earlier filter. Leaving the route stops owned acquisition and observation.
Only visible claim IDs and issued cursors can trigger navigation or paging. Explicit
refresh can recover an ended storage subscription. Admission remains observable
while protected record observation is paused.

The add action opens an employment-scoped draft. Draft and returned claims open
an editor from their detail app bar. A separate item page owns its transaction
date, effective category, exact IDR amount, description, cost center and receipts.
Its bounded pickers retain search and close controls above scrolling options;
the receipt picker accepts only the owned employment's published receipt catalog.
Selecting a new receipt currently requires Documents self-read access. Existing
draft labels use the narrower expense reference lookup described below.

Back and Save remain fixed above and below scrolling forms. Leaving a changed
form asks for confirmation. Backgrounding retains local input and cancels reads;
returning acquires fresh authority. Retrying a failed acquisition preserves local
input without asking to discard it. Unsaved fields remain process-local. Save
first retains an encrypted immutable command, so pending delivery survives a
process restart. The recovery card distinguishes sending, uncertain, rejected
and confirmed results; an uncertain operation cannot be edited or discarded.

The preparation use case rejects a changed server version against the version
originally edited, without silently rebasing input. The editor then offers an
explicit reload. After confirmed delivery, it acquires current claim facts and
reconciles the retained command before navigating. Employment pagination context
survives list, detail and editor navigation.

Detail exposes the actions available for its current revision. A retained command
replaces competing actions with recovery. Read-only accounts can still open details
without subscribing to command storage. Submission, cancellation and withdrawal
open separate review pages with fixed Back and confirmation controls. The reason
survives backgrounding; confirmed delivery refreshes the claim and reconciles the
retained outcome before returning to it. Pending and rejected input can be discarded
explicitly; an uncertain operation keeps its original body and operation ID.
Recovery retains the claim summary and the original reason. Back cancels the
page's reads and delivery immediately, before navigation disposes its owner;
the durable outcome remains available from detail.

## Receipt export

Submitted receipts can be saved from claim detail through the system file picker.
This uses explicit SELF claim access, independently of Documents catalog grants.
The use case rechecks company/session policy and the receipt resource before
selection, after selection and before completing the file. Scope changes stop
the transfer; MFA and access failures keep old claim data gated until a fresh
admitted refresh succeeds.

The raw datasource delegates HTTP operations to the API service. Its repository
validates the immutable receipt declaration, ETag, range and media type, then
streams sequential chunks of at most 1 MiB with a final SHA-256 check. A one-byte
GET preflight preserves structured error codes that a HEAD body cannot carry.
PDF, JPEG and PNG receipts use the same 100 MiB declaration limit as Documents.
There is no read-ahead queue; borrowed buffers are cleared after each collector
returns or is cancelled.

Detail owns one export job and disables competing actions until output cleanup
finishes. Stopping for the Android save dialog clears private screen content and
read subscriptions while keeping the export alive. Back, explicit cancellation
and route disposal cancel it. The UI holds only the selected receipt ID and
outcome, never a file buffer or platform handle. Picker, transfer and cleanup
deadlines are bounded. See [file output](file-output.md) for native destination
ownership and partial-file behavior.

## Validation

The account-owned replica passed root checks with 1,344 JVM/desktop tests,
184 expense tests in Chromium, Android dev assembly and production Web compilation.
SQLite and IndexedDB tests cover local month/status/page queries, reauthentication,
equal-version payload growth/shrink, authenticated chunk acquisition IDs, staged
replacement, atomic mapping failures, cancellation, and durable read-denial races.
Domain and MVI tests also cover bounded sync/history work, late scope changes,
unchanged action-review deadlines and local filters during pending synchronization.

The API 35 emulator cold-started without API connectivity after its clock advanced
73 hours. Four complete claims remained in encrypted storage with unchanged
payloads. Month/status filters, two individual claim details, approval and activity
remained readable. Restoring the clock and connectivity allowed explicit refresh
without losing the saved facts; the emulator's crash buffer contained no app crash.
The validated APK was installed on the authorized phone with install-only delivery.

The versioned reimbursement contract passed root checks with 1,327 JVM/desktop
tests, Android dev assembly and 108 expense domain/data and sync tests in Chromium.
Tests cover claim binding, independent payment versions, bounded response mapping,
cancellation and shared resource IDs across selected sync collections. The API 35
emulator opened a real dev claim, its approval, activity and empty payment progress
through the versioned payment endpoint. These checks preceded the replica migration.

The earlier receipt workflow passed 157 expense tests and 36 network tests in Chromium,
plus Android dev assembly and production Web compilation. CI for the preceding
action delivery passed Linux verification, Windows checks and the iOS simulator
build.

Coverage includes immutable retry bodies, concurrent saves, cancellation, late
results, protected cache cleanup, failed acknowledgement persistence, bounded
attempts, retained input during readmission, changed draft versions and pending
discard during worker progress. Real SQLite/AES tests reject copied or tampered
command envelopes. Picker tests cover issued cursors, expired choices and stale
receipt callbacks. Layout tests exercise fixed actions, long pickers and large
amounts without ellipsis at 200% text.
Action tests cover reviewed-version conflicts, read-only detail access, recovery
before competing commands, pending exit before owner disposal, and late failures
without duplicate navigation or loss of the uncertain outcome.

Receipt tests cover malformed/range-changing responses, digest mismatch,
backpressure, cleared buffers, cancellation during pending output, scope changes,
MFA through failed refresh, duplicate input, stopped/resumed screens and disposal.
The detail layout keeps long filenames, download actions, Back and cancellation
reachable at 200% text. A real Android API 35 emulator saved a published receipt
through the system picker; its bytes matched the server SHA-256. Cancelling the
picker and starting another export also succeeded.
Dark appearance, 200% text and returning from Android Settings were inspected.
The validated APK was delivered to the authorized phone with install-only; its
UI was not opened during delivery.

API 35 emulator acceptance created and edited a fictional claim through the app,
selected a dated category, cost center and inspected receipt, and verified exact
amounts and revisions through the API. It exercised a competing server edit,
explicit reload, unsaved Back confirmation, background input retention, an offline
readmission failure and recovery without discarding input. Light/dark appearance
and 200% text were inspected. The earlier read-page run also verified encrypted
list/detail restoration after background process death offline and reconnection.
Action acceptance retained a pending submission through offline process restart,
replayed it once, withdrew its approval, and cancelled the claim through detail
recovery. API reads verified the original reason, one submission and one history
entry per version. The cancellation review and retained-command page were checked
in dark appearance at 200% text. Emulator transport rules and display preferences
were restored; no runtime performance measurement was performed.
The validated APK was installed on the authorized phone with install-only delivery;
the phone's UI was not opened or driven.

### Draft display references

A separate bounded lookup resolves only the category, cost-center and receipt
references selected by an owned employment's draft. Editing grants suffice;
general organization or document browsing is not required. Unavailable records
retain their IDs with null metadata, and archived names remain recognizable.
Receipt status is display information, never a content or submission grant.

Domain constructs deduplicated sets from at most twenty lines and three receipt
revisions per line. Fresh company admission and a short lease bound publication,
including late failures, scope changes, clock rollback and cancellation. The data
boundary validates complete-or-absent metadata and exact requested reference sets;
the authenticated raw response is bounded to 256 KiB.

Validation: root checks passed with 1,061 desktop tests and 72 expense domain/data
Chromium tests. New cases exercise sparse/archived metadata, deduplicated queries,
exact scope/reference matching, response bounds, and structured access failures.
The shared preparation tests also cover cancellation, late failure, permission
loss, scheduled maintenance and timeouts for this lookup. No APK was delivered
for this data increment.
