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

Keep dependency versions in the root version catalog. App version/name are in
`gradle.properties`. New modules declare their own dependencies using `libs` and
`projects` accessors, then register their path in settings.

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

The demo transport implements `POST /auth/login` and `GET /dashboard`. HTTP APIs and DTOs
live in the consuming data module. Replace the engine and base URL in app composition
when integrating a server. Each client factory invocation accepts its own engine and base
URL, so multiple independently configured/named clients can coexist.

Do not point the existing demo sign-in contract at a real backend and assume it is secure:
add a backend-specific session response, secure credential port, authenticated client,
expiration/refresh policy, and matching tests. Never persist passwords in preferences.

## Editors and platforms

Open the root directory in Android Studio or IntelliJ IDEA with Kotlin Multiplatform
support. Gradle tasks are also included for VS Code and Zed. Android debugging is available
through Android Studio; desktop debugging uses the IDE's Gradle/JVM configuration.
Web development uses the URL printed by the Wasm development server.

Android APK: `apps/android/build/outputs/apk/debug/android-debug.apk`.
Web output: `apps/shared/build/dist/wasmJs/productionExecutable`.
Desktop distributions: `apps/shared/build/compose/binaries/main`.

## Tests

`:check` runs architectural checks and all module desktop tests, including the UI smoke
test. Those tests cover common Kotlin behavior; they do not substitute for Android/iOS
device testing. On headless Linux use Xvfb. UI captures are written under
`apps/shared/build/reports/screenshots`.

Run `./gradlew clean` for a full rebuild. It is intentionally separate from ordinary
build/run tasks so incremental compilation remains useful.
