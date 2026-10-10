# Observability

`core/observability` exposes a process-owned structured diagnostic sink. Platform
hosts install it before creating the application container. Desktop and Web use
JSON console output. Android and iOS also integrate Firebase Crashlytics.

Diagnostics contain a closed set of fields: operation area, event, exception
category, HTTP status, duration and operation correlation. They do not accept arbitrary messages, user
IDs, URLs, headers or payloads. HTTP failures are breadcrumbs; unexpected network
and storage exceptions can be non-fatal reports. Android retains stack frames on
a sanitized exception without its message or cause. The iOS bridge reports the
sanitized category; it does not reconstruct a Kotlin non-fatal stack.

SDK-captured fatal crashes are separate from this sanitization boundary. Never
put secrets in exception messages or custom Crashlytics logs/keys. Logging failure
must not turn a successful data operation into a failed one.

## Firebase

Use the existing flavor-specific `google-services.json` and `GoogleService-Info.plist`
files described in [notifications](notifications.md). In the Firebase console,
open **Crashlytics** for the registered Android/iOS app and complete its setup.
No new API secret is needed in application source.

Collection starts disabled in the manifests. Android release builds enable it;
debug builds keep it disabled. iOS applies the same policy with `DEBUG`. Change
this host policy if the product requires an explicit consent choice. The Android
Gradle plugin handles build IDs/mapping files; the iOS archive script uploads
dSYMs when configuration is present. Config-free CI and simulators skip upload.
A production archive requires the usual Apple signing configuration.

A successful build does not prove Firebase delivery. To verify a configured app,
exercise a controlled non-fatal report or a deliberate test crash on a test build,
restart it with connectivity, and inspect the matching Firebase app. No deliberate
crash is triggered by the starter UI or automated application tests.

See [performance](performance.md) for manual Firebase traces, first-party HTTP
correlation and lifecycle/recomposition boundaries.
