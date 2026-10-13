# Employee payslips

`features/payroll/domain` owns employee read admission, acquisition, observation
and explicit PDF export. `features/payroll/data` maps the API and maintains
encrypted read snapshots. `features/payroll/presentation` owns the employee
list, details, year selection, PDF language dialog and feature navigation.
The application installs the generated data and presentation modules.

## Employee pages

Payslips appears under All services when payroll is available. Its compact year
filter and back/refresh actions remain above the scrolling list. Detail groups
take-home pay, payment progress, earnings, deductions, employer contributions
and tax facts. Amounts are published server values; the page does not calculate
salary or imply settlement from a planned payment date.

Each route owns a separately typed MVI event contract. Duplicate acquisition
and export requests are dropped; selecting a different year cancels obsolete
work. Row navigation only accepts a visible payslip ID. Refresh preserves an
admitted snapshot on transport failure. Back releases observation and pending
work; a known access denial remains gated through retries and failed cleanup.

## Reads and offline access

Every list, detail, payment-progress and PDF request sends `scope=SELF`. A finance
grant cannot broaden this employee workflow to other people's payslips. Access
requires the selected company, the PAYROLL module and `payroll.self.read` or
`payroll.read`. The backend enforces current ownership and financial read authority.

The list defaults to the current year in the company timezone, obtained from a
fresh company policy. It loads at most twenty records per page, ordered by month
descending and assessment ID ascending. A cursor replaces the visible page;
identical IDs and nonadvancing cursors are rejected. Detail combines immutable
published amounts with separately versioned payment attempts. A failed payment
read retains the previously admitted aggregate, without publishing half a detail.
Publication is not proof of bank settlement.

List and detail use separate encrypted partitions under the selected session,
account, API audience and company. Both reuse the workspace content key; a
missing key fails rather than generating a replacement. Publication uses an
atomic observed-generation check. A competing or cancelled acquisition cannot
overwrite the accepted snapshot. The last read remains eligible for at most an
hour, further bounded by the workspace lease and scheduled maintenance.

Session, company, permissions and live company policy are rechecked around
acquisition. Remote reads have a thirty-second budget. Access-denial cleanup has
its own two-second budget so a stalled deletion cannot turn a known denial into
a transient timeout. Presentation must keep that denial gated through pending
and failed retries, reopening observation only after fresh admitted acquisition.
Cache observations end with their owner and emit expiry without polling.

Amounts use the shared bounded `DecimalAmount` value. Its formatting retains
all server digits and supports English and Indonesian separators without binary
floating-point conversion or local payroll recalculation. A negative withholding
value remains a refund, not a parsing failure.

## Saving a PDF

`ExportPayslip` obtains authorized HEAD metadata before opening the platform save
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

Domain tests cover scope changes, concurrent publication, pending cancellation,
late success/failure, lease expiry, collector disposal, failed/hanging cleanup and
save-dialog recovery. Data tests cover SELF transport, bounded DTOs, exact amounts,
conditional PDF integrity and encrypted storage with real SQLite/AES-GCM on JVM.
An application integration test resolves the generated bindings. Browser checks
run the portable domain/data tests as well as decimal formatting.

Presentation tests cover rapid year changes, duplicate requests, issued page
cursors, failed cleanup, authoritative denials, completed storage subscriptions
and route disposal. Assembled application tests exercise year navigation,
published amounts, unset payment status and PDF saving across a temporary
Activity stop. A separate case covers 200% text and leaving a pending file dialog.

The integrated workspace checks passed with 959 desktop tests and eighteen
targeted Chromium tests. Android dev assembly and the production Web build
passed. API 35 emulator acceptance used a published payslip from the development
API: annual filters, exact amounts, unset payment status, picker cancellation,
native PDF saving, offline list/detail restoration after background process death,
and explicit online recovery. Both native themes were inspected. The 11,099-byte
PDF matched the backend SHA-256. The validated APK was installed on the authorized
phone with install-only delivery.

The backend contract is documented in
[HRIS Platform payslips](https://github.com/fajarxfce/hris-platform/blob/main/docs/payroll-payslips.md).
