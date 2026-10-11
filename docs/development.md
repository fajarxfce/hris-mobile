# Development

## Convention plugins

- `starter.kmp`: common Kotlin targets and test dependencies.
- `starter.android.library`: Android KMP library target and SDK configuration.
- `starter.compose`: Compose compiler, runtime, foundation, UI, and resources.
- `starter.serialization`: Kotlin serialization compiler plugin.
- `starter.di`: Koin annotation processing in commonMain.
- `starter.android.application`: Android runner configuration with built-in Kotlin.
- `starter.quality`: shared Kotlin formatting tasks.
- `starter.web.toolchain`: use Node 22 LTS and resolve Binaryen through the settings repository.
- `starter.proto`: Wire protobuf generation for KMP.
- `starter.database`: Room, schema export, and platform KSP processors.
- `starter.environment`: generated environment selection for shared targets.
- `starter.android.flavors`: dev/staging/prod Android variants.

Keep dependency versions in the root version catalog. App version/name are in
`gradle.properties`. New modules declare their own dependencies using `libs` and
`projects` accessors, then register their path in settings.

The Android application convention enables core library desugaring for the API 24
minimum. This packages supported Java APIs, including `java.time` used by
`kotlinx-datetime`, for Android versions that do not provide them. Keep the
desugaring dependency in the version catalog and configure it once in the runner
convention; individual features must not duplicate it. A JDK/desktop test alone
does not validate the declared Android minimum.

## MVI screens

Each screen keeps its `State`, sealed `Event`, and ViewModel together. Extend
`MviViewModel<State, Event, Effect>`, register handlers in `init`, and expose only
`onEvent` to the page. Use `Nothing` when a screen has no transient effects.

```kotlin
init {
    on<LoginEvent.EmailChanged>(::onEmailChanged)
    on<LoginEvent.SignInRequested>(::onSignInRequested)
}

private fun onEmailChanged(event: LoginEvent.EmailChanged) {
    updateState { it.copy(email = event.value, failure = null) }
}
```

The route collects state with lifecycle awareness and installs `CollectEffects` once
when needed. A handler chooses the concurrency policy for its operation. Keep loading,
errors, and form values in state; use effects for transient UI actions.

## Backend integration

The application uses HRIS Platform's `/api/v1/` native authentication and company
endpoints. Dev defaults to loopback HTTP through ADB reverse; staging and production
require the configured HTTPS origin. See [native identity](native-identity.md) and
[environments](environments.md). The inherited demo module is not installed in the
runtime graph.

HTTP APIs, DTOs and raw datasource adapters stay in their consuming data module.
Use cases coordinate current session, workspace and company-policy admission.
Authenticated requests carry their owning session; a late response cannot replace
another account's content. Credentials use the platform secure store, and passwords
are never persisted in preferences.

## Editors and platforms

Open the root directory in Android Studio or IntelliJ IDEA with Kotlin Multiplatform
support. Gradle tasks are also included for VS Code and Zed. Android debugging is available
through Android Studio; desktop debugging uses the IDE's Gradle/JVM configuration.
Web development uses the URL printed by the Wasm development server.

Android dev APK: `apps/android/build/outputs/apk/dev/debug/android-dev-debug.apk`.
Web output: `apps/shared/build/dist/wasmJs/productionExecutable`.
Desktop distributions: `apps/shared/build/compose/binaries/main`.

## Headless Android emulator

Routine VPS testing uses a dedicated AVD; physical phones receive completed-feature
APK updates separately. Notify the device owner before starting each installation.
Always select the target serial when multiple devices are
connected. An APK update preserves application data but may stop the application's
existing process. `--install-only` never brings it to the foreground.

Create the AVD once with Android SDK command-line tools on `PATH`:

```sh
emulator -accel-check
sdkmanager 'system-images;android-35;google_apis;x86_64'
avdmanager create avd --name hris-mobile-dev \
  --package 'system-images;android-35;google_apis;x86_64' --device pixel_4a
```

Start it in its own terminal; keep the console and ADB ports local to the host:

```sh
emulator -avd hris-mobile-dev -port 5560 -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader -memory 2048 -cores 2
```

Wait for `adb -s emulator-5560 shell getprop sys.boot_completed` to return `1`, then:

```sh
adb -s emulator-5560 reverse tcp:8088 tcp:8088
python3 tool/run_android.py emulator-5560
```

The runner builds with `assemble`, then uses `adb -s` for installation and launch.
It refuses an ambiguous or disconnected target. To install an already validated
APK on a phone, replace `<phone-serial>` with its actual serial:

```sh
python3 tool/run_android.py <phone-serial> --skip-build --install-only
```

The default dev API uses the device's loopback address. WireGuard provides device
reachability but does not recreate ADB forwarding. After restarting or reconnecting
ADB, restore `adb -s <phone-serial> reverse tcp:8088 tcp:8088` before opening this
dev build. Deployed builds use their configured HTTPS origin.

Stop an emulator when it is no longer needed with
`adb -s emulator-5560 emu kill`; its app data is retained. Never use `-wipe-data`
for an ordinary restart. Emulator checks cover UI, lifecycle and API integration;
physical sensors, manufacturer behavior and performance still need device checks.

## Tests

`:check` runs architectural checks and all module desktop tests, including the UI smoke
test. Those tests cover common Kotlin behavior; they do not substitute for Android/iOS
device testing. On headless Linux use Xvfb. UI captures are written under
`apps/shared/build/reports/screenshots`.

Run `./gradlew clean` for a full rebuild. It is intentionally separate from ordinary
build/run tasks so incremental compilation remains useful.

The Android runner's device-selection checks run with
`python3 -m unittest discover -s tool -p 'test_*.py'`.
