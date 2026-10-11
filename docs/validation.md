# Validation

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
