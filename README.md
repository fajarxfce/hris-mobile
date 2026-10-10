# HRIS Mobile

Employee application for [HRIS Platform](https://github.com/fajarxfce/hris-platform), built with Compose Multiplatform, community [Compose Fluent](https://github.com/compose-fluent/compose-fluent-ui), clean architecture, and MVI.

Generated from [Compose Fluent Starter](https://github.com/fajarxfce/compose-fluent-starter). The enterprise foundation is imported from commit [`5aa8ee4`](https://github.com/fajarxfce/compose-fluent-starter/commit/5aa8ee40f4da5d002f4463bb395b7ea70c55ffe0). This repository has an independent history; the template license is retained.

Backend integration is in progress. The inherited demo described below is a template fixture, not an HRIS service.

## Run

Requires JDK 21. Checks require Python 3; web builds require Node.js 22 LTS.
Android builds also require Android SDK 36.

```sh
./gradlew :apps:shared:run                       # Desktop
./gradlew :apps:android:installDevDebug          # Connected Android device
./gradlew :apps:shared:wasmJsBrowserDevelopmentRun # Web
```

Open `apps/ios/FluentStarter.xcodeproj` on macOS for iOS. Select a simulator or configure your signing team for a physical device.

Demo account: **demo@example.com / Demo123!**. The default transport is an in-process Ktor MockEngine; no authentication requests leave the device. Onboarding preferences persist. Sessions restore from secure storage on mobile/desktop; Web sessions remain in memory. Dashboard content is sample data, cached locally. Saved activities use a transactional outbox and persist with the active session.

Light and dark appearance follow the system theme automatically. Dashboard navigation
adapts to the window width; see [accessibility and adaptive layout](docs/accessibility.md).

## Workspace

```text
apps/android                 Android runner
apps/ios                     iOS runner
apps/shared                  Composition, navigation, desktop/iOS/web entry points
apps/demo                    Demo HTTP transport
core/common                  Result and failure types
core/securestorage           Android Keystore, iOS Keychain, desktop credential manager
core/availability            domain / data; minimum version and maintenance policy
core/security                domain / data; permissions, biometric app lock and access policy
core/observability           Structured diagnostics and mobile Crashlytics
core/settings                domain / data; reactive language preference
core/localization            English/Indonesian resources and formatting
core/presentation            MviViewModel and lifecycle-aware effect collection
core/network                 Ktor client factory and HTTP failure boundary
core/storage                 Storage error boundaries
core/datastore               Reactive protobuf preferences and migration
core/database                Room / IndexedDB, dashboard cache, inbox, outbox and file chunks
core/transfers               domain / data; resumable transfer queue and HTTP boundary
core/sync                    domain / data; task contracts and scheduling boundary
core/worker                  WorkManager and foreground execution
core/notifications           domain / data; local delivery and FCM adapters
core/featureflags            domain / data; Remote Config, defaults and cached overrides
core/designsystem            Fluent theme and AppXxx composables
core/identity/domain         User, repository contract, authentication use cases
core/identity/data           API, datasource contracts, DTOs, repository, session
features/onboarding          domain / data / presentation
features/security/presentation  Biometric app lock and settings
features/availability/presentation  Update and maintenance gate
features/auth/presentation   Validated sign-in form and ViewModel
features/files/presentation  Session-scoped file queue
features/settings/presentation Language selection
features/notifications/presentation  Persistent inbox and notification controls
features/dashboard           domain / data / presentation
build-logic                  Gradle convention plugins
```

Dependency versions are centralized in `gradle/libs.versions.toml`. Internal dependencies use type-safe accessors such as `implementation(projects.core.common)`. Koin annotations generate registrations during normal Gradle builds.

See [SSO](docs/sso.md), [app lock](docs/app-lock.md), [app availability](docs/availability.md) and [access control](docs/access-control.md), [sessions and HTTP](docs/runtime.md), [observability](docs/observability.md), [performance](docs/performance.md), and [localization](docs/localization.md).

See [internal distribution](docs/distribution.md) for signing and manual Firebase/TestFlight workflows.
See [architecture](docs/architecture.md), [development](docs/development.md), [quality gates](docs/quality.md), and [validation](docs/validation.md).
See [file transfers](docs/file-transfers.md) for bounded imports, resumable uploads/downloads and backend contracts.
See [local-first sync and workers](docs/sync.md) for execution, retry and backend integration.
See [feature flags](docs/feature-flags.md) for Firebase Remote Config, offline defaults, and dev/staging overrides.
See [local storage](docs/storage.md) and [build environments](docs/environments.md) for platform setup. See [notifications and deep links](docs/notifications.md) for Firebase configuration.

## Check and build

```sh
./gradlew :check
./gradlew :apps:android:assembleDevDebug
./gradlew :apps:shared:wasmJsBrowserDistribution
./gradlew :apps:shared:packageDistributionForCurrentOS
```

Use `xvfb-run -a ./gradlew :check` on headless Linux. Native installers require the corresponding operating system. Apple builds require Xcode.

Compose Fluent remains experimental. Its API and accessibility coverage require evaluation for each target. The session and refresh foundations require a real backend contract for production use; the included transport and tokens are demo fixtures. External SSO requires registered public clients and the documented backend code exchange.
