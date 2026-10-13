# Employee expenses

The expense feature has separate domain, data and presentation modules. Domain
use cases admit company/session access and acquire employee claim views. Data
adapters map the API and maintain encrypted snapshots. Routes own MVI state,
filters, observation and acquisition lifetime.

## Reads

The employee directory uses `/expenses/me/employments`, independently of People
permissions. Every claim, history, submission and payment request explicitly
uses `scope=SELF`, including for accounts with finance permissions. The list uses
company-local creation months, an owned employment, optional status and bounded
cursor pages. Transaction dates remain individual expense-line facts.

Detail acquisition reads the claim before and after its history, latest
submission and payment progress. A changed claim or inconsistent revision
returns a conflict without publishing a partial view or silently retrying.
Amounts remain exact decimal domain values; approval does not imply payment.

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

List and detail snapshots have independent, bounded partitions. Authenticated
encryption binds account, session, API audience, company, generation, view and
chunk identity. Bounded chunks fit the database record limits and publish in
one compare-and-set transaction. Missing or reordered chunks cannot restore a
partial snapshot. Temporary keys and plaintext buffers are cleared on exit.

Snapshots expire within one hour and no later than their admitted workspace,
session or scheduled maintenance boundary. Changes to identity, permissions or
company timezone invalidate an old view. Transport failure can retain admitted
content. Known access and MFA failures remain gated through pending/failed
retries, including when cache cleanup fails; a fresh admitted acquisition must
succeed before observation reopens. There is no automatic retry loop.

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

Manual delivery and the registered expense sync task share one execution owner.
Each attempt revalidates current company/session policy and marks its outcome
unconfirmed before transport. Retries retain the original body and operation ID.
Automatic attempts and batches are finite. A later rejection cannot disprove an
earlier uncertain commit; an unconfirmed operation cannot be discarded. Confirmed
cleanup requires an admitted claim view at least as recent as the receipt and an
atomic outcome revision match. Credentials never enter command storage.

These command boundaries are available for the native editor and detail actions;
the current pages still expose the read workflow described below.

## Pages

Expenses opens from All services. Month and status filters stay above the
scrolling list; multiple employments use a separate selection dialog. Detail
groups amounts, expense lines, submitted receipt metadata, approval, payment
progress and paged activity. Back and refresh remain in the fixed app bar.
The pages support English, Indonesian and large text.

Each screen registers separate typed events. Changing filters cancels obsolete
acquisition; leaving the route stops acquisition and observation. Only visible
claim IDs and issued cursors can trigger navigation or paging. Explicit refresh
can recover an ended storage subscription.

The current native increment provides reads and offline views. Draft editing,
receipt selection/export, submission and subsequent employee actions are being
connected separately to the backend's versioned, idempotent command contracts.

## Validation

Command integration passed 1,054 workspace desktop tests and 65 expense
domain/data tests in Chromium. Tests cover immutable retries, concurrent saves,
manual/worker serialization, scope changes, cancellation, lost responses,
acknowledgement persistence failures, bounded attempts and protected cleanup.
Real SQLite/AES tests reopen commands under a replacement account session,
reject copied/tampered envelopes and retain 20 Unicode lines within storage
bounds. Application DI verifies the shared delivery instance and signed-out gates.
Android dev assembly also passed.


Draft-preparation checks passed with 1,023 workspace desktop tests and 44
expense domain/data tests in Chromium. Coverage includes stale successes and
failures, pending cancellation, access changes, bounded policy lifetimes and
validated date/cursor/money transport. Android dev assembly also passed for
the preparation integration.

The initial read-page delivery passed 36 expense Chromium tests, Android dev
assembly and production Web compilation. Its coverage includes mixed revisions,
encrypted chunk integrity, failed cleanup and 200% text.

API 35 emulator acceptance used claims created through the development API.
It verified month/status filters, detail and activity, approval without payment,
both themes, 200% text, encrypted list/detail restoration after background
process death offline, and explicit recovery after reconnection.
