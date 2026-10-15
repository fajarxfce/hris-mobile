# Employee payslips

`features/payroll/domain` owns employee read admission, acquisition, observation
and explicit PDF export. `features/payroll/data` maps the API and maintains
encrypted account-owned records. `features/payroll/presentation` owns the employee
list, details, year/revision selection, PDF language dialog and feature navigation.
The application installs the generated data and presentation modules.

## Employee pages

Payslips appears under All services when payroll is available. Its compact year
filter and back/refresh actions remain above the scrolling list. Detail groups
take-home pay, payment progress, earnings, deductions, employer contributions
and tax facts. Detail identifies the displayed assessment revision and the separate
payment balance revision. Amount rows stack their label and value on narrow screens
or at larger text sizes instead of squeezing numbers into half-width columns.
Recorded transfers, confirmed returns, remaining payment
and overpayment remain separate amounts. Amounts are published server values; the page does not calculate
salary or imply settlement from a planned payment date.

Each route owns a separately typed MVI event contract. Duplicate acquisition
and export requests are dropped. Selecting a year or list page changes a local
query without cancelling synchronization. Row navigation only accepts a visible
payslip ID. Refresh preserves an admitted snapshot on transport failure.
Back releases observation and pending
work; a known access denial remains gated through retries and failed cleanup.
An owned saved list or payslip does not expire because it is old. An explicit
refresh keeps it visible while pending or offline. A confirmed denial stays gated
until successful admitted acquisition; failures never start a retry loop.

## Reads and offline access

Every canonical detail, payment-progress and PDF request sends `scope=SELF`.
`SyncPayroll` explicitly selects `PAYSLIPS` and `PAYROLL_PAYMENTS` from the change
feed. Both self-service and finance read grants remain limited to the account's
own employments. Access requires the selected company, the PAYROLL module and
`payroll.self.read` or `payroll.read`; the backend enforces live ownership and
financial authority.

The UI observes the local replica. Year and page selection make indexed local
queries, defaulting to the company-local year. A query reads at most twenty-one
small summaries to produce a twenty-item page ordered by month descending and
assessment ID ascending. Full details and payment progress are stored separately
for every acquired payslip, so opening another year or detail does not replace
previously stored facts. A missing, never-acquired record is not an expired record.

`SyncPayroll` is one application-owned use case shared by routes and workers. Its
thirty-second budget includes waiting for its mutex. One run handles at most ten
pages of eight change references and hydrates at most four payslips concurrently.
Duplicate references are coalesced by resource/version. A failure cancels sibling
hydration, and a failed or cancelled page cannot advance the stored cursor.
Incomplete work resumes from its checkpoint on a later trigger.

Published amendments advance the payslip revision. The change feed carries the
current head; hydration stores the revision actually returned by the canonical
read, rejecting a response older than its reference. Full detail and indexed
summary advance atomically. Older changes cannot overwrite a newer stored head.
Payment progress has an independent monotonic version, current assessment revision
and settlement. Acquiring a slip also acquires payment progress before the first
transfer. A payment update does not rewrite the slip or its summary. The page never
derives a balance from a separately acquired slip. Parent deletion hides its detail
and summary even if an older payment record remains. Publication does not establish
bank settlement.

The payment mapper validates bounded exact-decimal amounts, confirmed transfer
totals, returns and the balance equation inside the data boundary. It rejects
incomplete current API responses. Legacy encrypted records keep their known
statuses without inventing missing transfer amounts or balances. Acquisition format
two stages a one-time complete replacement, retaining the published generation
through network failure, cancellation and process restart. An unfinished replacement
resumes its checkpoint; it does not continually restart or erase offline facts.
The encrypted container and associated-data format remain compatible.

Records belong to the API audience, account and company. A replacement login
cannot write with the preceding request identity, but it can read the same
account's retained facts. Other module grants do not change ownership; live
payroll entitlement is checked independently. The `payroll-replica-content-key`
is platform-owned and separate from the legacy workspace key. Authenticated
encryption binds content, version, generation, ordering and keyed filter indexes.
A missing retained key is an error; it is never replaced with a new key.

Data and cursor commit in the same storage transaction. Ordinary cursor expiry
starts a staging generation while the preceding published view remains readable;
only a complete replacement becomes visible. A confirmed scope/access denial
instead gates reads before bounded cleanup. Its encrypted admission record
survives restart, and a process-owned write buffer retains it when storage fails.
A retry cannot clear a newer denial. Reopening requires an admitted, caught-up
acquisition; failed cleanup does not make retained ciphertext readable. A scope
change schedules a bounded worker retry to rebuild the projection.

The preceding session-owned list/detail snapshots are migration fallbacks only
until the first complete replica publishes. They expose only their matching
selection or ID. Afterwards an empty result is authoritative. Stored data has no
age-based read limit, and freshness metadata never starts a retry timer or hides
content. Expired credentials pause server operations separately. PDF export still
requires live authorization; confirmed export access denials also gate other
payslip readers.

Amounts use the shared bounded `DecimalAmount` value. Its formatting retains
all server digits and supports English and Indonesian separators without binary
floating-point conversion or local payroll recalculation. A negative withholding
value remains a refund, not a parsing failure.

## Retained revisions

The detail page provides previous/next revision controls and a Latest action above
the scrolling content. Changing revision cancels the preceding acquisition. It
does not navigate to another route or acquire the full history. `ObservePayslip`
keeps the known current revision separate from the selected statement; missing
history is an explicit empty selection, never a fallback to a different revision.
The selected statement retains the latest stored payment projection. Viewing an
older salary statement does not roll back recorded transfers or balances.

`LoadPayslipRevision` coordinates the current parent, read admission, local archive
and exact-revision API request. Already acquired revisions use local storage with
no HTTP or renewed timestamps. A missing revision requires current company policy
and an admitted `scope=SELF&revision=N` read. Its thirty-second budget includes its
application-owned mutex. Ownership and read admission are checked around pending
work and publication. History acquisition never clears a confirmed denial;
reopening still requires caught-up `SyncPayroll` acquisition.

The encrypted `payroll-history/{company}` partition stores each acquired statement
once under its ID and revision, independently of feed generations. It shares the
payroll content-key owner and authenticates account, audience, company, generation,
revision and record identity. Writes cannot alter an existing revision. Each read
queries one bounded record; the page does not enumerate all historical revisions.
The repository implements both replica and history contracts so key creation and
archive cleanup have one owner.

Before replacing a current head, `SyncPayroll` retains its preceding published
statement and the acquired successor, including data from installations without
history support. Archive retention precedes the transaction that commits current
data and its cursor; an interrupted page does not claim feed progress. A cursor
replacement leaves history intact. A deleted parent hides its history, and
confirmed access cleanup also removes the archive. Account reauthentication and
ordinary age do not expire statements. Missing encryption keys remain errors.

## Saving a PDF

`ExportPayslip` receives the revision frozen when the employee opens the language
dialog. Authorized HEAD, conditional range and final metadata requests all pin that
revision; a later amendment cannot substitute a newer PDF. The proposed file name
includes `v{revision}`. HEAD metadata is acquired before opening the platform save
dialog. After selection it reacquires current company policy and requests the
exact conditional byte range. The repository validates size, PDF signature and
the complete SHA-256 ETag. The PDF is bounded to one MiB, held temporarily in
memory and cleared after success, failure or cancellation. It is not added to the
offline JSON cache.

The use case rechecks authority and document metadata before completing the
selected output. The save-dialog budget is five minutes; copying has a separate
two-minute budget. The existing file-output port owns native writers, flush and
close; release has a bounded noncancellable cleanup. It does not delete a selected
user path. A cancelled or failed save can leave a partial external file, and a
completed copy is managed outside the application.

The detail route owns export until disposal. A temporary Activity stop for the
Android system save dialog does not cancel the operation. Leaving the route,
changing company, or disposing its ViewModel cancels pending export work.

## Validation

Domain and MVI tests cover bounded synchronization, local year/page selection,
independent details, aged facts, changing request owners, late results, cancellation,
concurrent triggers, read-denial recovery and export lifetime. Shared SQLite and
IndexedDB contracts cover database reopening, indexed queries, immutable and
monotonic versions, tombstones, staged replacement, malformed records, missing
keys and interrupted encryption. Application checks resolve the generated graph
and verify one sync owner and content-key lifetime.

History contracts cover exact-revision reads, offline navigation, immutable
archives, parent deletion, account changes, interrupted writes and failed denial
cleanup. MVI tests cover cancellation when selection changes, current-head updates
while browsing history and export of the frozen historical revision. The assembled
UI checks revision controls at 200% text, offline return to acquired statements and
a revision-pinned PDF. These checks do not establish frame-time performance.

See [validation](validation.md) for executed builds, test results and device
acceptance. Earlier PDF acceptance verified its exact server SHA-256, native save
dialog cancellation, temporary Activity stops and 200% text.

The backend contract is documented in
[HRIS Platform payslips](https://github.com/fajarxfce/hris-platform/blob/main/docs/payroll-payslips.md).
