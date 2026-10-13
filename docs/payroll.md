# Employee payslips

`features/payroll/domain` owns employee read admission, acquisition, observation
and explicit PDF export. `features/payroll/data` maps the API and maintains
encrypted read snapshots. The application installs its generated data module;
native pages and navigation are the next integration step.

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

The route must own export until disposal. A temporary Activity stop for the
Android system save dialog must not cancel the operation. Leaving the route,
changing company, or disposing its ViewModel cancels pending export work.

## Validation

Domain tests cover scope changes, concurrent publication, pending cancellation,
late success/failure, lease expiry, collector disposal, failed/hanging cleanup and
save-dialog recovery. Data tests cover SELF transport, bounded DTOs, exact amounts,
conditional PDF integrity and encrypted storage with real SQLite/AES-GCM on JVM.
An application integration test resolves the generated bindings. Browser checks
run the portable domain/data tests as well as decimal formatting.

The backend contract is documented in
[HRIS Platform payslips](https://github.com/fajarxfce/hris-platform/blob/main/docs/payroll-payslips.md).
