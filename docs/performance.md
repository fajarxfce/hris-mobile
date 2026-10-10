# Performance and resource ownership

`measureOperation` records elapsed monotonic time and a bounded operation name. It
finishes its native sample in `finally`, including cancellation and late results.
Telemetry errors cannot replace the application's result. Operations carry their
trace context in the coroutine; concurrent operations do not share a global active
request or retain a screen/controller.

Built-in measurements cover application bootstrap (session restore and onboarding
read), each sync task, and HTTP calls. Bootstrap is not a first-frame or cold-start
benchmark. `FileUpload` and `FileDownload` are available for transfer execution.

## Correlation

The HTTP adapter emits W3C `traceparent` only for the exact configured API scheme,
host and port. Child requests share an enclosing operation's trace ID and get their
own span ID. It removes `baggage` and `tracestate`, and never copies correlation to
another origin. OIDC discovery and signed file URLs use separately configured
clients. The starter disables automatic redirects.

Structured diagnostics include trace/span IDs, duration, operation outcome and HTTP
status. They exclude URLs, request/response bodies, headers, user IDs and free-text
labels. Configure the backend to accept `traceparent` and attach that correlation
to its own logs/traces. Web API CORS must allow this header. This is correlation and
operation timing; it is not a complete OpenTelemetry exporter or distributed trace
storage service.

## Firebase Performance

Android and iOS hosts connect manual traces to Firebase Performance using the
existing flavor configuration. Collection stays disabled in debug; release hosts
enable it when Firebase is configured. Adjust that host policy for your product's
consent requirements. Firebase applies its own SDK collection and sampling rules.

In Firebase Console, open **Performance** for the Android/iOS app and inspect custom
traces named `starter_AppBootstrap`, `starter_Sync` and `starter_HttpRequest`. The
only custom attribute is `outcome`. IDs used in diagnostic correlation are not
attached as high-cardinality Firebase attributes. No additional service-account
key belongs in the app.

The Android performance instrumentation Gradle plugin is intentionally absent.
iOS disables automatic instrumentation before Firebase configuration and in
Info.plist. Manual timing avoids automatic collection of signed resource URLs.
Desktop/Web emit structured diagnostics; a host can install another `PerformanceSink`.
A build or a fake-sink test does not prove delivery to the Firebase console; verify
it using a configured release build and the matching Firebase app.

## UI and lifecycle

The root language provider observes language only. Settings access refresh and
role observation belong to its route. Equal language values do not publish another
state, and disposing its ViewModel cancels the storage subscription. The permission
cache has a fixed capacity of eight session-keyed records.

Native browser and biometric resources have operation-scoped ownership. Cancelling
SSO closes callback listeners, timers and the desktop loopback socket. Host teardown
cancels pending authentication. App lock uses a single expiry timer, coalesces
interaction renewals and emits only visible status changes.

Use Android Studio Memory Profiler/LeakCanary and Compose recomposition inspection,
Perfetto for Android frame/startup traces, Instruments on iOS, and browser performance
and heap tools on Web. Profile a release-like build on the target device through
repeated navigation, account changes, background/resume, and large-font layouts.
The starter's lifecycle tests establish resource contracts; they do not establish
an app-wide absence of leaks, a frame-rate target, or a measured speedup.
