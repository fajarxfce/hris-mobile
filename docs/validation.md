# Validation

The workspace-admission and inbox implementation passed these Linux checks:

```sh
xvfb-run -a ./gradlew :check \
  :core:workspace:domain:wasmJsBrowserTest \
  :features:inbox:domain:wasmJsBrowserTest \
  :apps:android:assembleDevDebug
```

The root check includes architecture validation for 52 modules, Kotlin formatting,
static analysis and JVM tests. The assembled application passed 21 Compose UI
tests, three application-MVI tests and one link-parser test. Workspace domain
tests passed on JVM and in Chromium (12 each), with five SQLite/encryption tests.
Inbox domain tests passed on JVM and in Chromium (30 each); inbox data
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

The workspace APK was used for offline startup and foreground revalidation;
the preceding command/inbox APKs were used for the earlier observations. The proxy was stopped
and the normal dev API connection restored afterward. No frame-time, heap or
battery measurements were taken. Native MFA, external SSO, push delivery, and iOS
device behavior still need their own physical checks.

## Integration boundaries

Runtime composition uses the HRIS API, not the starter demo transport. Compose
tests use wire-contract fixtures. The platform's separate PostgreSQL/API/worker
integration covers native authentication, recipient isolation, command replay and
withdrawal. Those checks complement device tests; none establishes production
capacity. Web bearer credentials remain ephemeral, so page-close workspace
restoration is unavailable there. Inherited starter notification/transfer
modules are not evidence that those HRIS workflows have been integrated.
