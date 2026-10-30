# HRIS Mobile

Employee application for [HRIS Platform](https://github.com/fajarxfce/hris-platform),
built with Compose Multiplatform, community [Compose Fluent](https://github.com/compose-fluent/compose-fluent-ui),
Clean Architecture, and MVI.

Generated from [Compose Fluent Starter](https://github.com/fajarxfce/compose-fluent-starter).
The repositories have independent histories. Reusable foundation fixes also return
to the starter through [reviewed updates](docs/template-updates.md).

Agent continuation: [current checkpoint and next steps](docs/agent-handoff.md).

## Current integration

The application uses the HRIS native API for Firebase email/password and Google
exchange, native password sign-in, authenticator setup and verification, recovery
codes, token rotation, active-session management, company selection, and current
company access. [Firebase authentication](docs/firebase-auth.md) discovers the
installed/backend providers before enabling a login method. Employees open their own personal profile, employment terms and
organization assignments from encrypted local records. The owned-profile change feed
updates coherent snapshots; employment selection works offline without an age limit. The employee inbox synchronizes encrypted company
correspondence, retains admitted content when a refresh fails, and queues explicit
read/acknowledgement actions with durable idempotent retries. The encrypted workspace
and previously acquired views reopen offline without waiting for access renewal.
Stale data remains readable; live credentials and command preparation are checked
separately. See the [local-first model and remaining work](docs/local-first.md).
Company policies gate maintenance,
minimum builds and disabled modules, and pause inbox delivery before transport.
The work calendar and attendance history query account-owned encrypted records,
updated through the change feed. Acquired months and weekly schedule rules remain
available offline across process restarts and reauthentication. Attendance supports clock-in,
clock-out, foreground location evidence, encrypted offline commands and idempotent
delivery. Attendance opens monthly metrics and daily history with a date filter;
clock-in/out and pending-command recovery stay on Home. Pending review stays
distinct from accepted time. Workforce availability
is independent of the personal-profile module. Home places the employee's name
and company above today's clock-in/out card and service grid. The four tabs are
Home, Inbox, Employees and Account. Inbox shows the account's total unread count.
Employees synchronizes an account-owned encrypted coworker directory. Name/number
search, paging and department filters query acquired records locally, including
after restart or reauthentication. Search opens a dedicated page and preserves
the browsing list on return.
All services opens a separate page; company
switching stays in Account. Back returns from an inner page to its tab, then to
Home before leaving the application. Inner pages keep their back and refresh
actions above the scrolling content; Settings groups language, security and
[device push-notification controls](docs/native-push.md).
Account also opens [Data and sync](docs/data-sync.md), showing the last complete
sync per data group with individual or combined refresh. Draft submission and
command delivery stay in their owning workflows.
Account also opens [Pending actions](docs/pending-actions.md) for local drafts,
retained commands and document transfers, with direct links to continue each workflow.
Supported [HRIS deep links](docs/deep-links.md) open those pages, Inbox or a scoped
message after login/company selection and keep old intents out of replacement accounts.
Screens support English and Indonesian. System
appearance and reduced-motion preferences are respected.

Overtime shows the employee's requests with month and status filters. Request
details include planned and actual time, approval progress, the reviewed schedule
and paged activity. Account-owned encrypted records receive incremental changes;
month, status and page filters query local storage. Acquired requests and activity
remain available across process restarts and reauthentication. Employees can review the company work schedule, select
local dates and times, and submit an overtime plan. Planning, actual-time and
withdrawal input autosaves to separate encrypted drafts and reopens offline
without an age limit. Actual-time and withdrawal drafts retain the original
request version for review.
Reviewed input survives interrupted delivery and process restart; retry keeps the
original operation. An uncertain submission stays retained before a different
action can be prepared.

Leave shows annual balances and request history with status filters, account-owned
employment selection and encrypted records updated through the change feed.
Tracked carry awards show their included balance, original validity date and
posted expiry/transfer/deduction amounts. Recorded totals remain readable offline;
request review checks eligibility for the selected dates.
Year, status and page changes query the local database. Acquired history and balances
survive reauthentication for the same account and remain readable offline. Each request opens its
submitted dates, duration, reason, current status and paged activity history;
each acquired request and activity page remains available offline without an age limit.
Background synchronization fills older activity in resumable batches. Withdrawal,
cancellation and withdrawal of a pending cancellation retain encrypted commands
and recover the original request after interrupted delivery. Employees can select
leave dates and portions, review the server calculation, and submit with durable
recovery after interrupted delivery. Unfinished dates, type, portion, reason and
selected evidence autosave to a separate encrypted working copy and reopen locally
without an age limit. Up to three personal documents can be selected
from the employee's published evidence catalog, including for types requiring an
attachment. Documents opens the published catalog with category tabs; its folder
action opens files on the current device. It supports encrypted local import of PDF/JPG/PNG files and
confirmed local removal, scoped to the selected account and company. Employees can
confirm an employment, title, classification and description in the form opened
after file selection, before a resumable upload. Supported servers also accept
document category, number, issuing country and printed issue/validity dates;
confirmed facts remain attached to the encrypted original upload during retries.
A first confirmed rejection can be corrected without importing the file again;
uncertain requests retain their original operation.
Progress, server inspection, explicit retry and cancellation use the retained
encrypted upload intent. Published document metadata synchronizes into account-owned
encrypted records. Employment, category and page filters work locally, including
after restart and reauthentication, without an age limit. Documents can be downloaded
into encrypted storage, paused and explicitly resumed after current content authorization.
Completed revisions can be saved through the platform file dialog after fresh
server authorization; the exported copy is then managed outside the application.

The runtime uses the HRIS authentication transport. The employee workflows described
here have explicit implementation and acceptance contracts; provider/device and
deployment acceptance are tracked separately in [validation](docs/validation.md). See [native identity](docs/native-identity.md), [company workspaces](docs/workspace.md), [employee profiles](docs/people.md), [inbox and synchronization](docs/inbox.md), [company availability](docs/company-policy.md),
[attendance and work calendars](docs/workforce.md), [overtime](docs/overtime.md), the [leave integration](docs/leave.md), and the platform's
[remaining delivery work](https://github.com/fajarxfce/hris-platform/blob/feat/firebase-auth/docs/completion.md).

The [employee payslip pages](docs/payroll.md) query account-owned annual history and
full details from the local replica. Incremental synchronization retains published
assessment revisions and independent payment balances in encrypted records.
Previously acquired records remain readable while their format is upgraded. Employees
can browse retained revisions offline; missing revisions are acquired individually.
Payment balances remain independent of the selected statement. Employees can
save the displayed revision as an English or Indonesian PDF through the platform file dialog after fresh
server authorization. Every read remains limited to their own payslips.

The [expense pages](docs/expenses.md) show employee claims with month/status
filters, current details, submitted receipt metadata, approval history and
payment progress. Employees can create or edit drafts, select dated categories,
cost centers and published receipts, submit claims, withdraw pending submissions,
cancel drafts, and recover interrupted delivery. Account-owned encrypted records
receive incremental claim and reimbursement updates. Employment, month, status,
page and acquired activity selections query local storage across process restarts
and reauthentication. Unsubmitted draft fields and unfinished items autosave to
separate encrypted working copies and reopen locally. Choosing new catalog entries
and saving to the server still require current online review. Saved commands retain
their original body and operation ID until their outcome is reconciled.

## Development

Requires JDK 21 and Android SDK 36. Start HRIS Platform using its development
instructions, including its `compose.wireguard.yml` overlay on the development
VPS. Connect the phone to WireGuard, then run:

```sh
./gradlew :apps:android:assembleDevDebug
python3 tool/run_android.py emulator-5560 --skip-build
```

Use the serial from `adb devices` for a different target. The runner installs on
that device only. See [headless emulator setup](docs/development.md#headless-android-emulator)
for VPS development and updating a connected phone without opening the application.

This application's `gradle.properties` selects the private dev API at
`http://10.77.77.1:8088/api/v1/`. It works independently of ADB forwarding.
Only dev permits HTTP for this VPN address and loopback. For a local backend,
build with `-PapiBaseUrl.dev=http://127.0.0.1:8088/api/v1/` and add
`--reverse-port 8088` when installing that APK. See
[development](docs/development.md#headless-android-emulator) for both paths.
Configure a deployed API using Gradle properties, for example:

```sh
./gradlew :apps:android:assembleStagingDebug -PapiBaseUrl.staging=https://hris.example.com/api/v1/
```

Staging and production require HTTPS; their default `.invalid` addresses are
configuration placeholders. Server credentials and signing keys never belong
in Gradle properties or source code.

```sh
xvfb-run -a ./gradlew :check              # Headless Linux
./gradlew :apps:shared:run                # Desktop development host
./gradlew :apps:shared:wasmJsBrowserDistribution
```

Open `apps/ios/HrisMobile.xcodeproj` on macOS for iOS. Apple builds and distribution
require Xcode and the appropriate signing configuration. Browser deployment also
needs an explicit same-origin authentication/CORS setup; native desktop version
policy is not configured yet. Android/iOS send the server's native version headers.

## Structure

- `apps/android`, `apps/ios`, `apps/shared`: platform entry points and composition.
- `core/identity`: native credentials, MFA, account contracts, and session policy.
- `core/workspace`: current company selection and scope ownership.
- `core/location`: foreground location, permission and Settings I/O contracts.
- `core/documents`: shared [HRIS evidence contracts and catalog](docs/documents.md).
- `features/auth`, `features/workspace`, `features/home`, `features/people`, `features/inbox`, `features/workforce`, `features/leave`, `features/documents`, `features/payroll`, `features/expenses`: feature-owned MVI screens.
- `core/designsystem`: shared `AppXxx` Fluent components and restrained transitions.
- `build-logic`: Gradle convention plugins; versions are in `gradle/libs.versions.toml`.

See [architecture](docs/architecture.md), [mobile experience](docs/experience.md),
[environments](docs/environments.md), [quality checks](docs/quality.md), and
[distribution](docs/distribution.md). Other inherited infrastructure modules remain
available for the subsequent HRIS integrations.
