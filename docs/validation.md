# Validation

## Overtime command foundation

Root checks passed with 868 desktop tests, 85 Workforce domain and 22 data Chromium
tests, and Android dev assembly. The focused domain/data run passed 129 tests.
Forty-three new cases cover competing confirmation, actual/withdrawal exclusion,
late cancellation, ownership/permission changes, expiry, response loss, immutable
replay, current policy, receipt validation and reconciliation before cleanup.

Delivery tests exercise manual/worker serialization, the deadline while waiting
for another sender, finite batch/cursor progress and retry limits. Real SQLite
tests reopen all three command types under a replacement session for the same
account, reject copied ciphertext and stale outcome revisions, preserve lost-key
evidence, and verify cancellation/account replacement during pending encryption.
HTTP tests cover exact routes, bodies, versions, idempotency keys, localizable
problems and bounded receipts. Native forms and real development API command
acceptance remain separate; no new phone installation is claimed here.

## Overtime planning preparation

Root checks passed with 825 desktop tests and Android dev assembly. Workforce
passed 56 domain and 30 data desktop tests; Chromium passed the 56 domain and
17 common data cases. Twenty new tests cover current owned-employment discovery,
read-only grants, company/session replacement, cancelled late results, finite
acquisition and review expiry, protocol bounds and localizable denial. Time-policy
cases cover overnight shifts, whole-minute limits, breaks, DST day boundaries
and actual-time limits.

The scrolled-detail regression was also corrected after CI exposed an assumption
about a row at the viewport's lower edge. It verifies visible failure feedback,
the retained scroll position and reachable activity after the viewport shrinks.
Root checks and Android assembly passed again after that correction. No additional
phone installation is claimed for the preparation boundary.

## Overtime history and request details

Root checks passed with 805 desktop tests, all 21 Workforce presentation Chromium
tests and Android dev assembly. The preceding read-boundary increment also passed
66 desktop and 53 Chromium domain/data cases. New MVI coverage exercises
late cancellation, company replacement, lease expiry, retained denial after failed
cleanup, completed storage subscriptions, history paging and offline filters.
Five layout cases and the assembled HTTP/cache/navigation flow passed. Layouts
include Indonesian dark appearance at 200% text and stable header/filter targets.

API 35 emulator acceptance used the fictional employee's real development API
requests. It verified status/month filtering, request activity and the distinct
work date and execution date following a night shift. An app-UID/API-port firewall
rule tested refresh failure, encrypted list/detail restoration after process
restart offline, and explicit online recovery. The rule was removed afterward.
The first device probe exposed refresh feedback hidden above the anchored list;
fixed feedback placement and scrolled-list regressions passed before the final
acceptance. Screenshots were inspected.

After advance notice, the validated APK was installed on the authorized Android 16
phone at 04:02 on 2026-10-13 with `--install-only`. Installation and package update
metadata were verified without opening the app. This increment delivers employee
reads; native planning, actual-time submission and withdrawal remain in progress.

## Company selection and operation feedback

Root checks passed with 747 desktop tests, five app Chromium tests and Android
dev assembly. The new assembled regression holds company acquisition pending,
checks disabled duplicate inputs, returns a connection failure and opens a second
company after retry. Layout checks cover one target per company, visible pending
feedback and fixed actions at 200% text size. Existing logout, company isolation
and retained-tab regressions also passed.

The API 35 emulator opened the picker from Account, retained it through appearance
and font changes, and returned to Home after selection against the development
API. Light and dark/150% screenshots were inspected; system preferences were
restored. No frame-time or memory benchmark was performed.

## Background process restoration

The installed application's crash log identified an SDK back-stack restoration
failure during bootstrap. The same failure was reproduced on the API 35 emulator
by backgrounding HRIS, terminating its process and reopening its retained task.
With the scoped saver, Leave restored in a new process without a new HRIS crash;
the restored page screenshot was inspected. One initial UI probe read a transient
incomplete accessibility dump; a bounded retry verified the completed page.

Root checks passed with 743 desktop tests, including four new restoration tests
and the three existing navigation ownership regressions. Five app Chromium tests
and Android dev assembly passed. The tests cover changed company, revoked session,
repeated recreation while bootstrap is unresolved, retained owners and disposal.
These observations do not measure memory, frame time or battery use.

## Published documents and saved copies

Complete root checks passed with 739 desktop/JVM tests. Chromium passed 59 document
domain tests, 24 document data tests, two localization tests and five app wiring
tests. Android dev assembly and the production Web distribution passed; Web
retains its three existing bundler warnings.

Three assembled UI regressions cover catalog filters, committed download progress,
cancel/resume offsets, export buffer ownership, denied resource access through
failed retry, and a pending save dialog across Activity stop and route disposal.
The downloaded record opens its own route instead of the upload form.

On the API 35 emulator, the real development catalog returned an uploaded PDF.
Authenticated download completed, native Save dismissal recovered, and a second
Save wrote a 607-byte PDF whose SHA-256 matched the original. The encrypted
download and correct detail route restored after process restart. The catalog
and saved-copy screens were inspected. This validation did not drive the phone
UI or measure runtime memory/frame timings.

## Authenticated export admission

Targeted document checks passed with 59 domain and 24 data desktop tests, plus
59 domain Chromium tests. Export regressions cover bounded plaintext copying,
picker dismissal, late cancellation, account replacement, access loss after
selection and during writing, final resource denial, preserved technical failures,
zero progress and finite deadlines. Catalog checks cover read-only employment
acquisition, changed-company results and lease expiry without network polling.

## Owned file output

Complete root checks passed with 719 desktop/JVM tests. The file-I/O suite passed
21 Chromium tests, localization passed two, and app wiring passed five. Android
dev assembly and production Web distribution passed; Web retains its three
existing bundler warnings. Native and browser regressions cover bounded writes,
partial writes, zero progress, picker cancellation, late results, disposal and
read/write failure classification. Apple native filesystem regressions and the
unsigned iOS simulator application build subsequently passed in GitHub Actions,
alongside Linux and Windows checks, for both HRIS Mobile and Compose Fluent Starter.

At this foundation checkpoint, employee export and native save-dialog acceptance
were separate integration work.

## Authenticated download core

Root checks passed with 711 desktop/JVM tests. Chromium passed all 42 document
domain tests and 24 data tests, and Android dev assembly passed. New coverage
checks current catalog/content admission, concurrent preparation, fresh local
identities after removal, exact authenticated ranges, interrupted block writes,
resume offsets, checksum failure, stale completion, late cancellation, changed
company access and finite transfer deadlines. The same storage cases run against
SQLite and IndexedDB. Downloaded revisions cannot enter the import/upload flow.

This checkpoint validated the shared transfer and storage contracts before
published-document browsing, OS export and real API download acceptance. It did
not include a new device installation or memory benchmark.

## Bounded binary transport

Root checks passed with 691 desktop/JVM tests, all 34 network and five transfer
Chromium tests, and Android dev assembly. Range coverage verifies retained session
and conditional headers, bounded strong validators, offset overflow protection,
exact extents, conflicting headers, compression rejection, truncated/oversized
streams, cancellation and body-free HEAD acquisition. API problem codes survive
binary rejection through the same bounded error reader used by JSON requests.
The existing transfer adapter now uses this shared reader. At that checkpoint,
HRIS download storage and its feature UI were separate integration work.

## Document upload presentation

Root checks passed with 684 desktop/JVM tests. The sync data and application
Chromium suites passed seven and five tests respectively. Android dev assembly
and the production Web build passed. Four assembled UI regressions cover form
validation, confirmed metadata and publication, pending acquisition cancellation,
retained input after detachment, and denied access through failed and successful
revalidation. English and Indonesian captures were inspected.

The full UI flow exposed a scheduler registration cycle that the earlier upload
core tests did not resolve. Deferred platform acquisition and a generated-DI
regression now cover tasks which schedule subsequent work. The expanded document
contract also required updating the Leave evidence fixture for clean CI compilation.
The shared scheduler correction was ported to the starter with its own checks.

The API 35 emulator uploaded a 607-byte PDF through the real development API and
reached server `READY` after content inspection. The encrypted local record and
completion status restored after process restart. Phone ADB connection timed out,
so this feature update has not yet been installed on the physical device.

## Resumable document upload core

Complete root checks passed with 679 desktop/JVM tests. The document domain and
data suites also passed 46 Chromium tests. Coverage includes durable confirmation,
duplicate admission, response loss, cancelled chunk transport, changed account
access, late receipts, bounded automatic attempts, validation recovery and
preserved cancellation intent. SQLite and IndexedDB checks verify encrypted
checkpoints, immutable input, competing revisions and bounded authenticated chunks.

The isolated browser suite loads the same IANA timezone data as the application;
without it, valid employment-directory responses failed timezone validation. The
Wasm lockfile was regenerated with Gradle and reviewed. This checkpoint does not
include a new APK or device upload acceptance; those accompany the feature UI.

## Retained Home and Employees tabs

Complete root checks passed with 631 desktop/JVM tests, four build-endpoint tests
and seven Android runner tests. Android dev assembly passed. Four new assembled
UI cases cover repeated tab changes without new HTTP requests, retained search
and scroll, manual refresh, cancellation with loaded rows, hidden access changes,
expired-cache recovery and observation cleanup. The initial probes reproduced
Home returning to loading and the missing hidden read observer before the fix.

The full flow suite initially switched its transport offline before a returning
Leave overview had finished refreshing. That setup now waits for the overview
before exercising offline detail recovery; the complete suite passed afterward.

The API 35 emulator completed three Home/Account/Employees round trips with Wi-Fi
and mobile data disabled and the API confirmed unreachable. Attendance values
and the employee query remained visible. Both connections were restored and
the Home/directory captures were inspected. The same APK was installed on the
authorized phone using `--install-only`; phone UI was not driven. Runtime heap
use and frame timings were not measured.

## Private VPN development connection

Root checks passed with 627 desktop/JVM tests, four build-endpoint tests and seven
Android runner tests. Android dev assembly passed. Endpoint coverage rejects public
and link-local HTTP, credentials, queries/fragments and HTTP in staging/production.
Runner coverage verifies selected-device forwarding, failure before installation,
invalid ports, bounded ADB commands and installation without launching the app.

The API 35 emulator signed in, selected the fixture company and restored Home after
process restart with an empty ADB reverse table. The Home capture was inspected.
The same APK was installed on the authorized phone with `--install-only`, and its
obsolete API reverse mapping was removed. No phone UI was driven. The owner
confirmed successful sign-in to Home using the new connection. The earlier
loopback connection was also restored and checked before this update.

The development API now uses the private WireGuard interface. Changing from the
old loopback origin requires sign-in again; retained commands/files keep their
original origin binding. This configuration does not migrate their ownership.

## Durable file blocks and checkpoints

Complete root checks passed with 627 desktop/JVM tests. All 23 database Chromium
tests passed, including 11 new cases shared with SQLite. Coverage verifies atomic
block/progress writes, immutable completed content, checkpoint contention, stale
deletion, account replacement, bounded reservations/pages, cancellation, independent
connections, restart and migration with retained commands. Android dev assembly and
the production Web build passed. Web retains three existing bundler warnings.

The validated APK, including the Leave evidence selector, was installed on the
authorized Android phone with `--install-only`. No application launch, screen
interaction or physical-device acceptance is claimed. The preceding selector
commit `c2a9d41` passed Linux, Windows and Apple CI. File acquisition and the HRIS
upload protocol remain separate work; this raw storage boundary accepts opaque
envelopes and does not itself encrypt content or deliver it.

## Leave document selection

Complete root checks passed with 616 desktop tests. All 52 Leave presentation
tests and both localization tests passed in Chromium. Android dev assembly and
the production Web build passed. New coverage verifies bounded selection,
original revision IDs during interrupted delivery, required-attachment removal,
closed-dialog callbacks, pending cancellation, read expiry and access gates
through failed refreshes. The assembled HTTP/SQLite application test closes a
picker with a request pending, verifies cancellation, then selects required
evidence and recovers one unchanged submission after response loss.

Controlled layouts verify fixed close controls, long document names, pagination
and attachment removal at 200% text. The screenshot was inspected. One new test
initially searched for a lazy-list item before scrolling it into composition;
the corrected test traverses the list and keeps the visibility/enabled assertions.
Web retains its three existing bundler warnings. Runtime performance was not
measured by these checks.

API 35 emulator acceptance selected an existing processed personal PDF and
submitted a one-day request under a policy requiring evidence. The development
API confirmed the original document/revision, size and digest, with one day
reserved and one remaining. Withdrawal restored two available days and zero
reserved while preserving the original attachment and withdrawal history. The
isolated allowance was then reversed and its policy retired through the API.
Light/dark appearance and 200% text were inspected; the emulator returned to Home
with normal text and light appearance. Later installation is recorded above;
there is no physical-phone interaction claim for this acceptance.

The preceding catalog commit `83cbd4d` passed Linux, Windows and Apple CI. Server
commit `8ed91d3b` passed its full backend, dashboard and live integration jobs.

## Owned document catalog

Complete root checks passed with 604 desktop tests. All 12 new document domain
and data tests also passed in Chromium. Coverage includes company/account changes
during pending work, cancellation, finite acquisition, expired policy recovery,
maintenance admission, scoped metadata, bounded pages and malformed file facts.
The Wasm lockfile adds only the new workspace packages. The local browser check
uses the installed Playwright Chromium executable; no Android build or device
interaction is claimed for this boundary.

## Leave creation interface

Complete root checks passed with 592 desktop tests, plus all 42 Leave presentation
tests in Chromium and two localization Chromium tests. Android dev and production
Web builds passed. New coverage verifies typed form events, superseded previews,
date/portion bounds, expiry, stopped owners, access gates through failed retries,
completed observers, retained response-loss recovery and canonical reconciliation.
An assembled HTTP/SQLite UI test commits a request, loses its response, reopens
the form offline and verifies an unchanged replay with one server-side creation.
Controlled layouts cover multiline reasons, fixed Back/close controls, disabled
dates, expired confirmation and active sending versus an uncertain outcome.

CI run `38188709062` exposed a layout-test assumption: finding a lazy-list section
heading does not ensure the following date has been composed. The test now scrolls
to the excluded date itself and asserts that it is displayed. Formatting and the
complete root checks passed after the correction. This changes test navigation
only; no additional device installation or platform build is claimed.

API 35 emulator acceptance used the development API. A three-date selection
excluded Saturday and charged two half-days as one day. A separate half-day
request was confirmed with its API connection blocked, restored after process
restart while still blocked, and delivered explicitly after connection recovery.
Its original reason remained intact and canonical pending status opened afterward.
The available balance changed from 12 to 11.5 days with 0.5 reserved. Withdrawal
returned it to 12 available and zero reserved; both test requests retain their
withdrawal history.
The native form and calendar were inspected in Indonesian, including dark mode
with 200% text; the close action and all dates remained reachable. The emulator
was returned to its normal light appearance and text scale afterward.
The initial forwarding-only interruption did not close existing HTTP connections;
the successful offline check used an emulator-only, app/port-scoped firewall rule,
removed afterward. Web retains three existing bundler warnings. No physical-phone
interaction or runtime performance benchmark is claimed by these checks.

## Inline loading

The optional compact loading layout passed root checks, Android dev assembly and
the production Web build. Its shared UI regression keeps both the label and an
adjacent action visible at 200% text in a bounded viewport. The default layout is
unchanged. Runtime performance was not measured.

## Controlled date picker

Root checks passed with 574 desktop tests, Android dev assembled and Web
compilation passed. The new shared UI regression covers caller-owned selection,
disabled dates/month navigation, full-date accessible labels and 200% text at
320 dp. Its screenshot was inspected after shortening the weekday labels.
No phone interaction or runtime performance measurement was performed.

## Durable leave submission

Complete root checks, 86 Leave domain/data Chromium tests, Android dev assembly
and the production Web build passed for the submission boundary. Twenty-nine new
tests cover preview-bound admission, immutable input during pending work, duplicate
confirmation, replacement scopes, cancellation, finite retries, lost responses,
receipt validation and canonical reconciliation. Real SQLite tests verify encrypted
process/session restoration, cross-partition key ownership, tampered envelopes,
outcome races and cancellation before publication. Web retains three existing
bundler warnings. The creation page is not connected by this increment.

The development API preview still excluded the selected Saturday, proposed a
half-day deduction and left the existing balance unchanged. The temporary probe
session was closed afterward. No phone interaction or performance measurement was
performed for this boundary. The preceding `b27a9c6` mobile CI passed on Linux,
Windows and Apple. Server revision confirmation is implemented separately in
[HRIS Platform](https://github.com/fajarxfce/hris-platform/commit/390e567).

## Leave preparation boundary

Root checks pass with 544 desktop tests; all 64 Leave domain/data tests pass in
Chromium. Android dev and production Web builds pass. Thirteen new regressions
cover catalog bounds and cursors, inactive choices, exact preview date partitions,
separate annual balances, malformed responses, permission and company changes,
replacement sessions, cancellation, finite deadlines and mutation of input while
acquisition is pending. The creation interface is not connected by this increment.

The preceding `2472964` mobile CI and `f63b94f` starter CI completed successfully
on Linux, Windows and Apple. The server preview is independently implemented and
verified in [HRIS Platform](https://github.com/fajarxfce/hris-platform/commit/b7182e7).
There is no new phone interaction or performance measurement for this boundary.

## Leave action interface

Root checks pass with 531 desktop tests; all 29 Leave presentation tests and both
localization tests pass in Chromium. Android dev and production Web builds pass.
The new MVI regressions cover duplicate confirmation, current-version admission,
cancelled and late work, permission recovery, process-independent command recovery,
and completed-source resubscription. An assembled UI test loses the response after
the server fixture commits, restores the original command, recovers its receipt
and withdraws the resulting pending cancellation. It asserts the original request
body and operation ID rather than treating a new submission as a retry.

Shared layout tests cover multiline editing at 200% text and the dialog's fixed
close action. The generic field and regression also pass in Compose Fluent Starter.
The earlier `b95bb03` CI run completed successfully on Linux, Windows and Apple,
including the Home publication correction and durable-command foundation.

Against the development API, the API 35 emulator withdrew a pending half-day
request and recovered its reservation: available balance changed from 12 to 12.5
days and reserved balance from 0.5 to zero. For a separate approved request,
cancellation was retained offline, restored after process termination, and sent
after reconnecting. Withdrawing that pending cancellation returned the request to
approved; consumed balance stayed at 0.5 days throughout both cancellation actions.
Light/dark appearance, 200% text, multiline editing and dialog dismissal were
inspected. Normal text size, light appearance and API forwarding were restored.
These transactions use fictional development data; new mobile request creation
and evidence upload remain separate work. No performance measurement is claimed.

After advance notice, the validated dev APK was installed on the connected
Android 16 phone at 05:26 on 2026-10-12 with `--install-only`. Package update time
and API forwarding were verified without launching or interacting with its UI.

## Durable leave command foundation

Root architecture, formatting, static analysis and all 519 desktop tests pass.
The 51 Leave domain/data tests also pass in Chromium, and Android dev assembles.
Twenty-seven new command tests cover admission, recovery, deadlines, concurrency,
encrypted persistence and transport validation. The Home publication regression
also passes in the complete suite. UI controls and device acceptance for these
transactions remain the next increment; no release was created.

## Home layout during cache publication

A controlled cache notification reproduced a 113 px upward menu shift after
attendance acquisition completed but before its snapshot reached the UI. Home
now retains its loading layout until the content arrives. The regression, both
calendar scenarios that failed in hosted CI, and Home lifecycle/location recovery
pass as four focused assembled UI tests. The test still activates the menu through
its pointer action. No frame-time or heap measurement is claimed.

At `eee789e`, Windows and Apple CI passed; Linux verification failed in the two
initial calendar navigation scenarios. That failed run is not counted as a pass.

## Leave request presentation

The root check passes with 491 desktop tests, and Leave presentation passes
20 Chromium tests. Android dev and production Web build successfully. New tests
cover route-owned subscriptions, pending-read cancellation, late denial, history
cursors and restoration, current metadata with earlier history, expired reads,
failed cleanup and retries, upstream storage-flow completion, and company
replacement. Assembled UI tests open real feature routes through generated DI,
exercise offline recovery and unavailable-request gates, and verify Indonesian
at 200% text with fixed navigation. Manual refresh probes wait for the visible
action to become enabled; transport completion alone did not establish UI readiness.

The API 35 emulator opened one fictional half-day request from the development
API. Submitted date/portion, duration, reason, current status and activity matched.
Force-stop/restart with the emulator's API forwarding removed restored the saved
request; reconnecting and refreshing removed the connection error. Light/dark
and 200% text were inspected, and normal text size, appearance and forwarding
were restored. This validates request reading, not mobile submission. No frame-time
or memory benchmark is claimed.

After advance notice, this APK was installed on the connected Android 16 phone
with `--install-only`; the package update and API forwarding were verified.
The phone application was not launched or driven for this delivery.

## Leave request read boundary

The root check passes with 478 desktop tests. Leave domain/data also passed
31 Chromium tests, and Android dev assembles. Seventeen new tests cover owner-only
request transport, current metadata with older history, malformed responses,
cancellation, late results, lease expiry, generation conflicts, independent cache
windows, encrypted restoration and corrupt/missing records. A 366-day fixture
with ten multibyte history entries verifies bounded chunking beyond a single
96,000-byte record. The existing nine presentation browser tests remain unchanged.

The development API accepted one fictional half-day request and returned it through
the owner-only detail route. Request-detail UI, transaction submission and physical
device acceptance are not claimed for this foundation change.

## Protected reads and leave balances

The 2026-10-12 update passed root checks with 461 desktop/JVM/UI tests, all nine
Leave presentation Chromium tests, and Android dev plus production Web builds.
The preceding Leave data/domain Chromium runs passed their 18 tests. Regression
tests reproduce failed cache cleanup, denied reads followed by failed retries,
expiry during pending acquisition, fresh publication, and stopped/disposed
observers. An access or MFA failure cannot be replaced by a later network/storage
failure to reveal the old snapshot.

The API 35 emulator read the real development calendar, navigated to the previous
week and back to the current week, returned to Home, and opened the fictional
12.5-day leave balance. The earlier Leave acceptance also covered offline process
restoration, online recovery, English/Indonesian, both themes and 200% text.
Request submission and physical iOS acceptance remain separate work.

The validated dev APK was installed on the authorized Android phone at 03:38 on
2026-10-12 with `--install-only`. Installation and package metadata were verified;
the phone UI was not launched or driven.

## Monthly attendance interface

The 2026-10-12 update passed root checks with 425 desktop/JVM/UI tests, the
localization Chromium suite, and Android dev and production Web builds. The
workforce domain Chromium suite also passed for the monthly acquisition/summary
implementation. Screen tests cover leap-year filters, future-month restrictions,
metrics, read-only day details, cancelled month requests, company replacement,
offline refresh, and expiry/MFA during pending acquisition. Cached observation
stops on a denied read and resumes after successful refresh. Tests cover both
arrival orders of cache and network results when restoring a saved month offline.

The API 35 emulator exercised the real API's pending attendance, monthly navigation,
day details, English/Indonesian, light/dark, and the month picker at 200% text.
The final APK restored September after an offline process restart and kept that
month on online refresh. Home retained an explicit capture without location after
restart; retry sent it and the next snapshot removed it from the device queue.
It remained pending verification with zero verified time. GPS did not produce a
valid location during this queue test, so this is not a physical-location check.

The final dev APK was installed on the authorized Android 16 phone at 02:13 on
2026-10-12 using `--install-only`. Installation and package metadata were verified;
its UI was not opened or driven. No release was created.

The reusable month picker and date formatting are also ported to the starter;
HRIS metrics, access policy, cache recovery and capture workflows remain here.

## Monthly attendance foundation

The acquisition and storage update passed root checks with 420 desktop/JVM/UI
tests, workforce domain Chromium tests and the Android dev build. Regression
coverage includes complete/leap-year months, invalid/future filters, separate
recent/history caches, dense monthly ciphertext, v1 cache recovery, cancellation
during encryption, key-buffer cleanup, company changes and stale results. Domain
tests distinguish confirmed absences, unrecorded/off days, queued captures,
pending review and ongoing shifts. This validates the data foundation; the new
Attendance screen is delivered separately.

## Inbox totals and navigation badges

The 2026-10-12 update passed the root checks with 410 desktop/JVM/UI tests,
the database and Inbox domain/data Chromium tests, and the Android dev build.
The full-count and adaptive-navigation UI tests were repeated to capture the
badge: 36 unread messages remain 36 with a 30-item page, then become 35 after an
explicit read. Tests also cover cancelled reads, company replacement, zero
counts, large text and full accessible count descriptions.

The updated APK was installed on the dedicated API 35 emulator. The real API
Inbox, Employees and Back-to-Home flow were checked; the existing emulator inbox
messages were already read. Positive badge counts were verified with isolated
API/UI fixtures. The phone was offline in ADB, so this update was not installed
there. No release was created.

## Earlier company and inbox integration

The company-policy, workspace-admission and inbox implementation passed these Linux checks:

```sh
xvfb-run -a ./gradlew :check \
  :core:workspace:domain:wasmJsBrowserTest \
  :core:workspace:data:wasmJsBrowserTest \
  :features:inbox:domain:wasmJsBrowserTest \
  :apps:android:assembleDevDebug
```

The root check includes architecture validation for 52 modules, Kotlin formatting,
static analysis and JVM tests. The assembled application passed 24 Compose UI
tests, three application-MVI tests and one link-parser test. Workspace domain
tests passed on JVM and in Chromium (21 each), with six SQLite/encryption tests on
JVM and three mapper tests on each JVM/browser target.
Inbox domain tests passed on JVM and in Chromium (37 each); inbox data
passed 13 JVM tests. Its two browser transport tests passed in the preceding
inbox-command run. Tests cover scope replacement, expiry,
pending cancellation, late outcomes, finite replay, conflicts, encrypted command
retention, response loss followed by login, and route disposal. Browser transport
tests verify the serialized command and original idempotency key.

Workspace-specific regressions cover lease renewal without a refresh loop or inbox
reset, MFA/session deadlines, late outcomes, foreground cancellation, concurrent
snapshot replacement and failed-revocation storage recovery. The shared atomic
snapshot primitive passed JVM and actual Chromium storage checks in both HRIS
Mobile and Compose Fluent Starter; both repository CI runs also passed.

The earlier protected-credential checks passed eight JVM and thirteen browser
tests, including concurrent record creation, reopening, tampering and cancellation.
The Android-compatible file-lock correction passed the root check and dev build
in both this repository and Compose Fluent Starter.

Gradle dependency verification is enabled using the committed SHA-256 metadata.
See [quality gates](quality.md) for updates and their trust boundary.

Screenshots from UI tests are under `apps/shared/build/reports/screenshots`.
The CI workflow additionally builds an unsigned iOS simulator application and
Windows classes. These are compilation checks, not physical Apple/Windows vault
tests or signing/distribution validation.

Company-policy regressions cover revision zero, platform build admission,
microsecond freshness deadlines, scheduled maintenance after a stale snapshot,
flag changes without cache or command loss, late denied access, scope replacement,
and a pending bootstrap failure followed by successful retry. Virtual-time tests
verify the finite observation deadlines; no clock change was made on the device.

## Android device observations

The dev application was installed on a Samsung Galaxy A72 running Android 16,
connected through WireGuard/ADB to the actual local HRIS API. A fictional employee
and published dev announcement were used. Checks covered:

- Onboarding, password sign-in, two company selections and current employee profile.
- Company-isolated inbox contents and a selectable plain-text message.
- Indonesian language persistence, dark appearance, background/foreground return
  and credential restoration after process termination.
- An admitted message remaining readable while a device-only proxy closed its
  network connections, followed by successful refresh after reconnection.
- Explicit read, acknowledgement-prompt cancellation, an offline acknowledgement
  retained after force-stop/restart, and successful retry/reconciliation with the
  real backend. The completed message showed `Dikonfirmasi` without pending actions.
- Force-stop/restart with the API connection still disabled, restoring the admitted
  company and readable inbox without another company selection. Reconnection and
  foreground revalidation retained the open detail.
- Replacing that company, then restarting offline into its empty inbox without
  exposing the first company's messages.
- Minimum-build and maintenance gates from actual company policy revisions,
  switching to an unaffected company, and explicit retry after maintenance removal.
- Disabling People and Communications while Inbox was open, hiding its content
  and the affected home actions. Another company retained both actions. Re-enabling
  the modules restored the message and its confirmed acknowledgement.
- An invalid authenticator code producing localized feedback without ending the
  verification flow, followed by successful verification and company selection.
- Authenticator enrollment with a separate fictional account, acknowledgement of
  ten recovery codes, company restoration after force-stop/restart, and a subsequent
  sign-in using one recovery code. Proofs were generated from fixture setup secrets;
  an external authenticator application was not part of this check.
- Cancelling another device's session-revocation confirmation, then confirming it.
  The disposable session remained valid after cancellation and its token received
  `401 native_session_invalid` after confirmation. The current device remained usable.

The company-policy APK was used for the availability checks, and the workspace APK
for offline startup and foreground revalidation. The preceding command/inbox APKs
were used for the earlier observations. The proxy was stopped, the normal dev API
connection restored, and the demo company's original policy values restored after
testing. The disposable session was revoked and the temporary MFA account was
signed out and its company grant disabled. The original employee account was restored afterward.
No frame-time, heap or battery measurements were taken. External SSO, push delivery,
and iOS device behavior still need their own physical checks.

## Android emulator observations

The existing company-policy dev APK was also installed on a dedicated headless
Android 15/API 35 x86_64 emulator using KVM and software graphics on Linux. Against
the local HRIS API it passed onboarding, fictional employee sign-in, company
selection, inbox reading, and acknowledged-message display. Removing only the
emulator's API tunnel and restarting the process restored the admitted company
and its readable saved message. The normal API tunnel was restored afterward.
These checks did not interact with the physical phone.

Both HRIS Mobile and Compose Fluent Starter passed their root checks and four
Android-runner tests covering target selection, an ambiguous/disconnected device,
missing artifacts, and updating without an application launch. No application
runtime code changed for this tooling update; the existing APK was used for the
emulator observations. See [development](development.md#headless-android-emulator)
for reproduction. No emulator performance or memory benchmark is claimed.

## Integration boundaries

Runtime composition uses the HRIS API, not the starter demo transport. Compose
tests use wire-contract fixtures. The platform's separate PostgreSQL/API/worker
integration covers native authentication, recipient isolation, command replay and
withdrawal. Those checks complement device tests; none establishes production
capacity. Web bearer credentials remain ephemeral, so page-close workspace
restoration is unavailable there. Inherited starter notification/transfer
modules are not evidence that those HRIS workflows have been integrated.

## Employee layout refinement

Home now separates company selection from employee actions; account preferences
and session controls use grouped rows. Calendar and inbox share compact headers,
and calendar time ranges have clearer emphasis than saved/source metadata.

Root architecture, formatting and distribution checks passed with all 351 desktop
tests, including retained offline content, navigation ownership and cancellation
coverage. A shared 360 dp layout regression checks 200% text, heading semantics,
icon targets, one action per tile and keyboard activation. Android dev and Web
distribution builds passed; Web retains its existing bundler warnings.

The API 35 emulator opened Home, Account, weekly calendars, the inbox and message
details against the real development API. Week navigation returned to the current
week. English/Indonesian, light/dark and 150% text captures were inspected; the
workspace survived appearance and text-size changes. An initial probe read the
previous week before its response arrived; waiting for the expected date verified
the completed navigation.

After advance notice, the same APK was installed on the connected Android 16 phone
with `--install-only`. Installation, package update time and the API forwarding
rule were checked without launching or driving its UI. These observations do not
establish frame-time, heap or battery performance. The reusable components and
regression were also validated in Compose Fluent Starter with 198 tests and
Android/Web builds.

## Leave layout and action placement

The Leave overview and request detail keep their available actions below the
scrolling content. Creation keeps submission below its calculation review.
Root checks passed with 749 desktop tests, five application Chromium tests and
Android dev assembly. Regressions cover fixed actions at large text sizes, disabled
actions during refresh/recovery, denied reads, and the original durable submission
after response loss.

The API 35 emulator opened balances, creation and existing request details against
the development API. Light appearance, dark appearance at 150% text, fixed request
actions and return navigation were inspected. The shared layout tests also cover
Indonesian at 200% text. No frame-time or memory measurements were taken.

## Document navigation and transfer actions

Root checks passed with 755 desktop tests, five application Chromium tests and
Android dev assembly. Coverage includes direct catalog navigation, interrupted
metadata acquisition when opening local files, native-picker stop/resume, late
results after route disposal, upload confirmation, retained denied access,
resumable ranges, authorized export and obsolete navigation-snapshot rejection.
Shared layout tests cover Indonesian at 200% text and fixed transfer actions.

The API 35 emulator opened the real published catalog, changed categories,
downloaded a document, opened and cancelled the native Save dialog, then imported
a PDF through the native picker directly into its upload form. Light appearance
and dark appearance at 150% text were inspected. The shared tab component also
passed the starter's root checks, 265 desktop tests, two application Chromium
tests and Android dev assembly. No performance or memory benchmark is claimed.

## Dedicated employee search

Root checks passed with 759 desktop tests, five application Chromium tests and
Android dev assembly. Search has its own navigation-owned ViewModel, query and
filter. Regressions cover pending cancellation on Back, discarded late results,
owner disposal, company replacement, retained browsing filters/pages/scroll,
explicit retry, and fixed search/Back controls with Indonesian text at 200%.

The API 35 emulator verified the browsing entry, automatic keyboard focus, real API
results, an empty result, clearing, return to the retained directory, and Back to
Home. Light appearance and dark appearance with 150% text were inspected; emulator
preferences were restored afterward. The shared controls also passed the starter's
267 desktop tests, two application Chromium tests and Android dev assembly.
These checks do not establish frame-time, memory or battery performance.
