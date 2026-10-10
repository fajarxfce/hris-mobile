# Build environments

| Environment | Android/iOS identifier | URL scheme |
|---|---|---|
| dev | `dev.fajar.hris.mobile.dev` | `hrismobile-dev` |
| staging | `dev.fajar.hris.mobile.staging` | `hrismobile-staging` |
| prod | `dev.fajar.hris.mobile` | `hrismobile` |

Android flavors are configured by `starter.android.flavors`, independently of
`debug`/`release` build types. Application IDs isolate installed apps and storage.

```sh
./gradlew :apps:android:assembleDevDebug
./gradlew :apps:android:assembleStagingDebug
./gradlew :apps:android:assembleProdRelease
python3 tool/run_android.py --flavor dev
```

Release APKs are unsigned until signing environment variables are supplied.
See [internal distribution](distribution.md) for the convention plugin, secrets and
manual workflows. Do not commit signing keys or passwords. APK output is under
`apps/android/build/outputs/apk/<flavor>/<buildType>/`.

For desktop/Web, `starter.environment` generates the selected environment from
`-PappEnvironment=dev|staging|prod` (default `dev`):

```sh
./gradlew :apps:shared:run -PappEnvironment=staging
./gradlew :apps:shared:wasmJsBrowserDistribution -PappEnvironment=prod
```

Desktop storage directories and browser storage keys include the environment. Android
uses its flavor's BuildConfig value, independently of the shared default.
Every runtime uses the native HRIS backend. Dev defaults to
`http://127.0.0.1:8088/api/v1/`; Android can reach the VPS through
`adb reverse tcp:8088 tcp:8088`. Its network security configuration permits
cleartext only for `127.0.0.1` and `localhost`. Staging/prod require HTTPS and must
replace their `.invalid` configuration placeholders using
`-PapiBaseUrl.staging=...` or `-PapiBaseUrl.prod=...`. API URLs include a trailing
slash. A credential restored for another configured API audience is invalidated.

iOS has `HrisMobile-dev`, `HrisMobile-staging`, and `HrisMobile-prod` schemes,
each with Debug/Release configurations and separate bundle identifiers. The framework
build passes `HRIS_ENVIRONMENT` to Gradle. Existing Debug/Release configurations remain
aliases for dev/prod. Native release distribution still requires Apple signing.

Firebase configuration and verified HTTPS link association are environment-specific.
Never put server credentials in BuildConfig, generated Kotlin, or browser assets.
