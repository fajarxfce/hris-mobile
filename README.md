# Compose Fluent Starter

A modular Compose Multiplatform starter with community [Compose Fluent](https://github.com/compose-fluent/compose-fluent-ui), clean architecture, MVI, and an onboarding → sign-in → dashboard flow.

## Run

Requires JDK 21. Checks require Python 3; web builds require Node.js 22 LTS.
Android builds also require Android SDK 36.

```sh
./gradlew :apps:shared:run                       # Desktop
./gradlew :apps:android:installDebug             # Connected Android device
./gradlew :apps:shared:wasmJsBrowserDevelopmentRun # Web
```

Open `apps/ios/FluentStarter.xcodeproj` on macOS for iOS. Select a simulator or configure your signing team for a physical device.

Demo account: **demo@example.com / Demo123!**. The default transport is an in-process Ktor MockEngine; no authentication requests leave the device. Onboarding preferences persist. The demo session is memory-only and sign-in is required after a process restart. Dashboard content is sample data.

Light and dark appearance follow the system theme automatically.

## Workspace

```text
apps/android                 Android runner
apps/ios                     iOS runner
apps/shared                  Composition, navigation, desktop/iOS/web entry points
apps/demo                    Demo HTTP transport
core/common                  Result and failure types
core/presentation            MviViewModel and lifecycle-aware effect collection
core/network                 Ktor client factory and HTTP failure boundary
core/storage                 Platform preference drivers and storage boundary
core/designsystem            Fluent theme and AppXxx composables
core/identity/domain         User, repository contract, authentication use cases
core/identity/data           API, datasource contracts, DTOs, repository, session
features/onboarding          domain / data / presentation
features/auth/presentation   Sign-in screen and ViewModel
features/dashboard           domain / data / presentation
build-logic                  Gradle convention plugins
```

Dependency versions are centralized in `gradle/libs.versions.toml`. Internal dependencies use type-safe accessors such as `implementation(projects.core.common)`. Koin annotations generate registrations during normal Gradle builds.

See [architecture](docs/architecture.md), [development](docs/development.md), and [validation](docs/validation.md).

## Check and build

```sh
./gradlew :check
./gradlew :apps:android:assembleDebug
./gradlew :apps:shared:wasmJsBrowserDistribution
./gradlew :apps:shared:packageDistributionForCurrentOS
```

Use `xvfb-run -a ./gradlew :check` on headless Linux. Native installers require the corresponding operating system. Apple builds require Xcode.

Compose Fluent remains experimental. Its API and accessibility coverage require evaluation for each target. Production authentication, secure token storage, refresh tokens, and OAuth are integration work; this starter does not simulate them as production security.
