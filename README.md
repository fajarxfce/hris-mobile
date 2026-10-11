# HRIS Mobile

Employee application for [HRIS Platform](https://github.com/fajarxfce/hris-platform),
built with Compose Multiplatform, community [Compose Fluent](https://github.com/compose-fluent/compose-fluent-ui),
Clean Architecture, and MVI.

Generated from [Compose Fluent Starter](https://github.com/fajarxfce/compose-fluent-starter).
The repositories have independent histories. Reusable foundation fixes also return
to the starter through [reviewed updates](docs/template-updates.md).

## Current integration

The application uses the HRIS native API for password sign-in, authenticator setup
and verification, recovery codes, token rotation, active-session management, company selection, and current
company access. Employees can open their own personal profile, employment terms,
and organization assignments. The employee inbox synchronizes encrypted company
correspondence, retains admitted content when a refresh fails, and queues explicit
read/acknowledgement actions with durable idempotent retries. A time-limited encrypted
workspace restores the selected company offline. Company policies gate maintenance,
minimum builds and disabled modules, and pause inbox delivery before transport.
The weekly work calendar shows assigned shifts, roster overrides and days off,
with a bounded encrypted snapshot for offline access. Attendance supports clock-in,
clock-out, foreground location evidence, encrypted offline commands and idempotent
delivery. Attendance opens monthly metrics and daily history with a date filter;
clock-in/out and pending-command recovery stay on Home. Pending review stays
distinct from accepted time. Workforce availability
is independent of the personal-profile module. Home places the employee's name
and company above today's clock-in/out card and service grid. The four tabs are
Home, Inbox, Employees and Account. Inbox shows the account's total unread count.
Employees provides a searchable, paged coworker
directory with assignment labels and department filters. All services opens a separate page; company
switching stays in Account. Back returns from an inner page to its tab, then to
Home before leaving the application. Inner pages keep their back and refresh
actions above the scrolling content; Settings groups language and security controls.
Screens support English and Indonesian. System
appearance and reduced-motion preferences are respected.

The runtime contains no demo authentication transport. Other transactional employee workflows
are still being connected; a generated module
is not a completed HRIS workflow. See [native identity](docs/native-identity.md), [company workspaces](docs/workspace.md), [employee profiles](docs/people.md), [inbox and synchronization](docs/inbox.md), [company availability](docs/company-policy.md),
[attendance and work calendars](docs/workforce.md), the [leave integration](docs/leave.md), and the platform's
[remaining delivery work](https://github.com/fajarxfce/hris-platform/blob/main/docs/completion.md).

## Development

Requires JDK 21 and Android SDK 36. Start HRIS Platform using its development
instructions, then run:

```sh
./gradlew :apps:android:assembleDevDebug
adb -s emulator-5560 reverse tcp:8088 tcp:8088
python3 tool/run_android.py emulator-5560 --skip-build
```

Use the serial from `adb devices` for a different target. The runner installs on
that device only. See [headless emulator setup](docs/development.md#headless-android-emulator)
for VPS development and updating a connected phone without opening the application.

The dev API defaults to `http://127.0.0.1:8088/api/v1/`. Only dev permits loopback
HTTP. Configure a deployed API using Gradle properties, for example:

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
- `features/auth`, `features/workspace`, `features/home`, `features/people`, `features/inbox`, `features/workforce`: feature-owned MVI screens.
- `features/leave`: scoped balance/history contracts and encrypted offline reads; UI integration is in progress.
- `core/designsystem`: shared `AppXxx` Fluent components and restrained transitions.
- `build-logic`: Gradle convention plugins; versions are in `gradle/libs.versions.toml`.

See [architecture](docs/architecture.md), [mobile experience](docs/experience.md),
[environments](docs/environments.md), [quality checks](docs/quality.md), and
[distribution](docs/distribution.md). Other inherited infrastructure modules remain
available for the subsequent HRIS integrations.
