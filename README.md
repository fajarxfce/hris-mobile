# HRIS Mobile

Employee application for [HRIS Platform](https://github.com/fajarxfce/hris-platform),
built with Compose Multiplatform, community [Compose Fluent](https://github.com/compose-fluent/compose-fluent-ui),
Clean Architecture, and MVI.

Generated from [Compose Fluent Starter](https://github.com/fajarxfce/compose-fluent-starter).
The repositories have independent histories. Reusable foundation fixes also return
to the starter through [reviewed updates](docs/template-updates.md).

## Current integration

The application uses the HRIS native API for password sign-in, authenticator setup
and verification, recovery codes, token rotation, company selection, and current
company access. Employees can open their own personal profile, employment terms,
and organization assignments. Screens support English and Indonesian. System
appearance and reduced-motion preferences are respected.

The runtime contains no demo authentication transport. Transactional employee workflows and
encrypted offline synchronization are still being connected; a generated module
is not a completed HRIS workflow. See [native identity](docs/native-identity.md), [employee profiles](docs/people.md), and
the platform's [remaining delivery work](https://github.com/fajarxfce/hris-platform/blob/main/docs/completion.md).

## Development

Requires JDK 21 and Android SDK 36. Start HRIS Platform using its development
instructions, then run:

```sh
./gradlew :apps:android:assembleDevDebug
adb reverse tcp:8088 tcp:8088
./gradlew :apps:android:installDevDebug
```

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
- `features/auth`, `features/workspace`, `features/home`, `features/people`: feature-owned MVI screens.
- `core/designsystem`: shared `AppXxx` Fluent components and restrained transitions.
- `build-logic`: Gradle convention plugins; versions are in `gradle/libs.versions.toml`.

See [architecture](docs/architecture.md), [mobile experience](docs/experience.md),
[environments](docs/environments.md), [quality checks](docs/quality.md), and
[distribution](docs/distribution.md). Other inherited infrastructure modules remain
available for the subsequent HRIS integrations.
