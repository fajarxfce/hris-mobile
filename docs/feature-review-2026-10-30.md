# Mobile and Starter feature review

The current employee workflows and applicable reusable foundation have been
reviewed against their contracts, registered owners, maintained tests and recorded
native acceptance. No additional application implementation or generic port was
established by this review. Platform's Workforce broad/browser/full-CI acceptance
and the combined development update remain active; this is not whole-workspace
completion.

Mobile application/test/build sources still match exact `d170810f`, which passed
all four jobs in [CI 41077194465](https://github.com/fajarxfce/hris-mobile/actions/runs/41077194465).
Downloaded reports contain 1,994 desktop, 1,676 browser, ten build-logic and 104
Apple simulator cases, with no failures/skips. Full Xcode, all three Android
variants, Web, Windows, app links and SBOM checks passed. This review reuses those
unchanged sources; it does not claim another test execution. The
[measurement index](measurements/feature-review-2026-10-30.json) retains contract
hashes, module report counts/hashes and source-tree continuity.

| Area | Reviewed contract and behavior evidence |
| --- | --- |
| Identity | [Native sessions](native-identity.md), [Firebase](firebase-auth.md), MFA, rotation, recovery and session management. Identity domain/data and login-owner tests cover cancellation, replacement and stale proof. Actual Android Firebase password and user-operated Google → HRIS → company/Home are recorded in the validation ledger. |
| Company access | [Workspace](workspace.md), [policy](company-policy.md), maintenance, minimum build and feature access. Local ownership stays independent of credential age; confirmed denial remains closed through failed acquisition or cleanup. Workspace admission and assembled navigation tests cover replacement and recovery. |
| Experience | [Navigation/motion](experience.md), [accessibility](accessibility.md), English/Indonesian and app lock. Application tests cover retained tabs, Back, delayed callbacks, large text and hidden/disposed owners. |
| Inbox | [Messages](inbox.md) and [read/ack commands](inbox-commands.md) retain encrypted facts, unread state and original operations. Admission, bootstrap, lost responses, bounded retry and actual Android offline recovery have matching evidence. Push does not imply read/ack. |
| People | [Directory/private profile](people.md) preserve account/company partitions, local filtering, current employee selection and coherent canonical references. Replica/storage and presentation tests remain in the accepted CI. |
| Attendance/calendar | [Workforce](workforce.md) keeps raw offline evidence distinct from accepted time, owns finite foreground location capture, and retains calendar/history across restart. Actual attendance response loss, closed-day revision and roster reset/reactivation evidence remain source-scoped in the ledger. |
| Overtime | [Planning and actions](overtime.md) retain separate working copies, original versions, request/activity replicas and immutable commands. Review/admission/delivery tests and actual Android plan-response-loss recovery are recorded. HR approval remains a Platform workflow. |
| Leave | [Balances, carry and requests](leave.md) include published evidence selection, retained history, local working copies and submit/withdraw/cancel recovery. Exact drafts and commands survive offline restart without becoming confirmed allocations. |
| Payroll | [Statements](payroll.md) preserve retained revisions and independent payment balances. Export authorizes the displayed immutable revision. Replica/migration/admission/PDF tests and actual retained-revision Android evidence remain valid for their recorded sources. |
| Expenses | [Claims](expenses.md) support draft/item autosave, dated reference review, submission/withdrawal/cancellation, receipts/history and reimbursements. Original-command recovery and actual Expense-save response loss are recorded. |
| Documents | [Files/catalog](documents.md) and [business metadata](document-business-metadata.md) include local import, resumable upload/download, explicit export, category/identity validity and immutable metadata changes. Latest Android acceptance covers offline process restarts, response loss, original retry and a later independent edit. |
| Data and sync | Nine registered record groups implement [data observations/acquisition](data-sync.md). Seven local observers across six feature areas implement [Pending actions](pending-actions.md), including separate document-file and metadata commands. Opening the summary sends no HTTP or business command. |
| Push/links | [Native push](native-push.md) and [deep links](deep-links.md) retain registration retirement, account/company scoping and bounded hint acquisition. Actual Android foreground/background FCM and notification navigation are accepted; Firebase configuration alone is not delivery evidence. |
| Storage/resources | [Architecture](architecture.md), [encrypted content](content-encryption.md), replicas and file adapters separate raw I/O, mapping and application policy. Tests cover interruption, stale publication, failed storage, finite queues/work and owned platform resources. |
| Distribution | [Packaging](distribution.md) and [template updates](template-updates.md) retain selected API/backend, real signing checks, association files, dependency verification and independent Starter acceptance. No production release is implied. |

## Applicable Starter foundation

657 tracked shared files are byte-identical. All 60 differing shared files were
classified: HRIS native-session/API contracts and fixtures, company feed dependencies,
application identity/branding, business localization and selected configuration.
The 419 Mobile-only files belong to HRIS documents, workspace, identity, change-feed
adapters and their fixtures, plus private-dev endpoint validation and a fail-closed
transfer host default. Starter keeps its demo transport and injectable file source.
A path under `core` does not make an HRIS business contract a generic template API.

All 3,747 common dependency artifact digests and the verification policy match;
Mobile adds 35 artifact entries for its dependencies. Both locales retain all 129
shared keys; six onboarding descriptions differ and 790 HRIS keys are added in
each locale. These are application differences, not an unported foundation fix.

Starter application/build/test trees still match `77063862`, which passed all three
jobs in [CI 40916187442](https://github.com/fajarxfce/compose-fluent-starter/actions/runs/40916187442),
including 84 Apple simulator cases and the full Xcode application. Its two manual
distribution workflows additionally contain the independently verified hidden
association-file upload correction. HRIS provider/session/business policy stays in
Mobile; the accepted generic provider, storage, file, UI and packaging ports remain
in [the port ledger](template-updates.md).

## Evidence limits

The recorded native workflows are actual prior runs, not fresh device exercises
from this documentation review. Physical iPhone testing was explicitly waived.
Signed Apple distribution/APNs and a public production deployment still require
the selected target configuration; passing simulator/Xcode does not claim device
delivery. The installed phone APK and private server were not changed here.

The outdated local-first continuation requesting FCM credentials and Google exchange
has been corrected to point to their existing acceptance. Finish Platform's active
Workforce gates and final combined development update before closing the full goal.

## Workspace closure

Platform `64eb0d14` closes all 61 initial requirements and detailed module reviews.
Final CI `41094285531` passed with complete log/artifact evidence, and the private
schema-103 API/worker update is verified. Mobile application/test/build sources
remain unchanged from accepted `d170810f`; no new APK or generic port was required.
[The current handoff](agent-handoff.md) records closure and the retained scope limits.
