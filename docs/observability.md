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

## Failure outcome across suspension

`measureOperation` explicitly assigns `Failed` when a non-cancellation exception
escapes its block, then rethrows the same exception. Cancellation keeps its separate
outcome. The original Starter Apple network test exposed a null failure sample;
its assertion remains unchanged. A suspended dispatcher-hop regression supplements
that test, and collaboration CI now runs observability on desktop, browser and Apple.

[Evidence](measurements/native-failure-outcome-2026-11-07.json) retains the original
failed Apple run and the accepted Mobile local checks. Verification on the changed
Apple source remains pending. This diagnostic correction does not establish the
cause or resolution of physical-phone message delays.

## Delivery stage exception outcomes

The delivery stage tracer explicitly assigns Failed before rethrowing a suspended exception,
as measureOperation already does. Actual Apple control 43443248470 exposed a missing outcome;
local focused/full verification is accepted while the corrected Apple control remains pending.
See [delivery outcome evidence](collaboration-delivery-failure-outcome.md).
